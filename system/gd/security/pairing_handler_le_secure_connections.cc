/******************************************************************************
 *
 *  Copyright 2019 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include <bluetooth/log.h>

#include "crypto_toolbox/crypto_toolbox.h"
#include "hci/octets.h"
#include "os/rand.h"
#include "security/pairing_handler_le.h"

using bluetooth::os::GenerateRandom;

namespace bluetooth {
namespace security {
using hci::Octet16;

std::variant<PairingFailure, KeyExchangeResult> PairingHandlerLe::ExchangePublicKeys(const InitialInformations& i,
                                                                                     OobDataFlag remote_have_oob_data) {
  // Generate ECDH, or use one that was used for OOB data
  const auto [private_key, public_key] = (remote_have_oob_data == OobDataFlag::NOT_PRESENT || !i.my_oob_data)
                                             ? GenerateECDHKeyPair()
                                             : std::make_pair(i.my_oob_data->private_key, i.my_oob_data->public_key);

  log::info("Public key exchange start");
  std::unique_ptr<PairingPublicKeyBuilder> myPublicKey = PairingPublicKeyBuilder::Create(public_key.x, public_key.y);

  if (!ValidateECDHPoint(public_key)) {
    log::error("Can't validate my own public key!!!");
    return PairingFailure("Can't validate my own public key");
  }

  if (IAmCentral(i)) {
    // Send pairing public key
    log::info("Central sends out public key");
    SendL2capPacket(i, std::move(myPublicKey));
  }

  log::info("Waiting for Public key...");
  auto response = WaitPairingPublicKey();
  log::info("Received public key");
  if (std::holds_alternative<PairingFailure>(response)) {
    return std::get<PairingFailure>(response);
  }

  EcdhPublicKey remote_public_key;
  auto ppkv = std::get<PairingPublicKeyView>(response);
  remote_public_key.x = ppkv.GetPublicKeyX();
  remote_public_key.y = ppkv.GetPublicKeyY();
  log::info("Received Public key from remote");

  if (public_key.x == remote_public_key.x) {
    log::info("Remote and local public keys can't match");
    return PairingFailure("Remote and local public keys match");
  }

  // validate received public key
  if (!ValidateECDHPoint(remote_public_key)) {
    // TODO: Spec is unclear what should happend when the point is not on
    // the correct curve: A device that detects an invalid public key from
    // the peer at any point during the LE Secure Connections pairing
    // process shall not use the resulting LTK, if any.
    log::info("Can't validate remote public key");
    return PairingFailure("Can't validate remote public key");
  }

  if (!IAmCentral(i)) {
    log::info("Peripheral sends out public key");
    // Send pairing public key
    SendL2capPacket(i, std::move(myPublicKey));
  }

  log::info("Public key exchange finish");

  std::array<uint8_t, 32> dhkey = ComputeDHKey(private_key, remote_public_key);

  const EcdhPublicKey& PKa = IAmCentral(i) ? public_key : remote_public_key;
  const EcdhPublicKey& PKb = IAmCentral(i) ? remote_public_key : public_key;

  return KeyExchangeResult{PKa, PKb, dhkey};
}

Stage1ResultOrFailure PairingHandlerLe::DoSecureConnectionsStage1(const InitialInformations& i,
                                                                  const EcdhPublicKey& PKa, const EcdhPublicKey& PKb,
                                                                  const PairingRequestView& pairing_request,
                                                                  const PairingResponseView& pairing_response) {
  if (((pairing_request.GetAuthReq() & AuthReqMaskMitm) == 0) &&
      ((pairing_response.GetAuthReq() & AuthReqMaskMitm) == 0)) {
    // If both devices have not set MITM option, Just Works shall be used
    return SecureConnectionsJustWorks(i, PKa, PKb);
  }

  if (pairing_request.GetOobDataFlag() == OobDataFlag::PRESENT ||
      pairing_response.GetOobDataFlag() == OobDataFlag::PRESENT) {
    OobDataFlag remote_oob_flag = IAmCentral(i) ? pairing_response.GetOobDataFlag() : pairing_request.GetOobDataFlag();
    OobDataFlag my_oob_flag = IAmCentral(i) ? pairing_request.GetOobDataFlag() : pairing_response.GetOobDataFlag();
    return SecureConnectionsOutOfBand(i, PKa, PKb, my_oob_flag, remote_oob_flag);
  }

  const auto& iom = pairing_request.GetIoCapability();
  const auto& ios = pairing_response.GetIoCapability();

  if ((iom == IoCapability::KEYBOARD_DISPLAY || iom == IoCapability::DISPLAY_YES_NO) &&
      (ios == IoCapability::KEYBOARD_DISPLAY || ios == IoCapability::DISPLAY_YES_NO)) {
    return SecureConnectionsNumericComparison(i, PKa, PKb);
  }

  if (iom == IoCapability::NO_INPUT_NO_OUTPUT || ios == IoCapability::NO_INPUT_NO_OUTPUT) {
    return SecureConnectionsJustWorks(i, PKa, PKb);
  }

  if ((iom == IoCapability::DISPLAY_ONLY || iom == IoCapability::DISPLAY_YES_NO) &&
      (ios == IoCapability::DISPLAY_ONLY || ios == IoCapability::DISPLAY_YES_NO)) {
    return SecureConnectionsJustWorks(i, PKa, PKb);
  }

  IoCapability my_iocaps = IAmCentral(i) ? iom : ios;
  IoCapability remote_iocaps = IAmCentral(i) ? ios : iom;
  return SecureConnectionsPasskeyEntry(i, PKa, PKb, my_iocaps, remote_iocaps);
}

Stage2ResultOrFailure PairingHandlerLe::DoSecureConnectionsStage2(
    const InitialInformations& i,
    const EcdhPublicKey& /* PKa */,
    const EcdhPublicKey& /* PKb */,
    const PairingRequestView& pairing_request,
    const PairingResponseView& pairing_response,
    const Stage1Result stage1result,
    const std::array<uint8_t, 32>& dhkey) {
  log::info("Authentication stage 2 started");

  auto [Na, Nb, ra, rb] = stage1result;

  // 2.3.5.6.5 Authentication stage 2 long term key calculation
  uint8_t a[7];
  uint8_t b[7];

  if (IAmCentral(i)) {
    memcpy(a, i.my_connection_address.GetAddress().data(), hci::Address::kLength);
    a[6] = (uint8_t)i.my_connection_address.GetAddressType();
    memcpy(b, i.remote_connection_address.GetAddress().data(), hci::Address::kLength);
    b[6] = (uint8_t)i.remote_connection_address.GetAddressType();
  } else {
    memcpy(a, i.remote_connection_address.GetAddress().data(), hci::Address::kLength);
    a[6] = (uint8_t)i.remote_connection_address.GetAddressType();
    memcpy(b, i.my_connection_address.GetAddress().data(), hci::Address::kLength);
    b[6] = (uint8_t)i.my_connection_address.GetAddressType();
  }

  Octet16 ltk, mac_key;
  crypto_toolbox::f5((uint8_t*)dhkey.data(), Na, Nb, a, b, &mac_key, &ltk);

  // DHKey exchange and check

  std::array<uint8_t, 3> iocapA{static_cast<uint8_t>(pairing_request.GetIoCapability()),
                                static_cast<uint8_t>(pairing_request.GetOobDataFlag()), pairing_request.GetAuthReq()};
  std::array<uint8_t, 3> iocapB{static_cast<uint8_t>(pairing_response.GetIoCapability()),
                                static_cast<uint8_t>(pairing_response.GetOobDataFlag()), pairing_response.GetAuthReq()};

  // log::info("{} LTK = {}", (IAmCentral(i)), base::HexEncode(ltk.data(), ltk.size()));
  // log::info("{} MAC_KEY = {}", (IAmCentral(i)), base::HexEncode(mac_key.data(), mac_key.size()));
  // log::info("{} Na = {}", (IAmCentral(i)), base::HexEncode(Na.data(), Na.size()));
  // log::info("{} Nb = {}", (IAmCentral(i)), base::HexEncode(Nb.data(), Nb.size()));
  // log::info("{} ra = {}", (IAmCentral(i)), base::HexEncode(ra.data(), ra.size()));
  // log::info("{} rb = {}", (IAmCentral(i)), base::HexEncode(rb.data(), rb.size()));
  // log::info("{} iocapA = {}", (IAmCentral(i)), base::HexEncode(iocapA.data(), iocapA.size()));
  // log::info("{} iocapB = {}", (IAmCentral(i)), base::HexEncode(iocapB.data(), iocapB.size()));
  // log::info("{} a = {}", (IAmCentral(i)), base::HexEncode(a, 7));
  // log::info("{} b = {}", (IAmCentral(i)), base::HexEncode(b, 7));

  Octet16 Ea = crypto_toolbox::f6(mac_key, Na, Nb, rb, iocapA.data(), a, b);

  Octet16 Eb = crypto_toolbox::f6(mac_key, Nb, Na, ra, iocapB.data(), b, a);

  if (IAmCentral(i)) {
    // send Pairing DHKey Check
    SendL2capPacket(i, PairingDhKeyCheckBuilder::Create(Ea));

    auto response = WaitPairingDHKeyCheck();
    if (std::holds_alternative<PairingFailure>(response)) {
      return std::get<PairingFailure>(response);
    }

    if (std::get<PairingDhKeyCheckView>(response).GetDhKeyCheck() != Eb) {
      log::info("Ea != Eb, aborting!");
      SendL2capPacket(i, PairingFailedBuilder::Create(PairingFailedReason::DHKEY_CHECK_FAILED));
      return PairingFailure("Ea != Eb");
    }
  } else {
    auto response = WaitPairingDHKeyCheck();
    if (std::holds_alternative<PairingFailure>(response)) {
      return std::get<PairingFailure>(response);
    }

    if (std::get<PairingDhKeyCheckView>(response).GetDhKeyCheck() != Ea) {
      log::info("Ea != Eb, aborting!");
      SendL2capPacket(i, PairingFailedBuilder::Create(PairingFailedReason::DHKEY_CHECK_FAILED));
      return PairingFailure("Ea != Eb");
    }

    // send Pairing DHKey Check
    SendL2capPacket(i, PairingDhKeyCheckBuilder::Create(Eb));
  }

  log::info("Authentication stage 2 (DHKey checks) finished");
  return ltk;
}

Stage1ResultOrFailure PairingHandlerLe::SecureConnectionsOutOfBand(const InitialInformations& i,
                                                                   const EcdhPublicKey& Pka, const EcdhPublicKey& Pkb,
                                                                   OobDataFlag my_oob_flag,
                                                                   OobDataFlag remote_oob_flag) {
  log::info("Out Of Band start");

  Octet16 zeros{0};
  Octet16 localR = (remote_oob_flag == OobDataFlag::PRESENT && i.my_oob_data) ? i.my_oob_data->r : zeros;
  Octet16 remoteR;

  if (my_oob_flag == OobDataFlag::NOT_PRESENT || (my_oob_flag == OobDataFlag::PRESENT && !i.remote_oob_data)) {
    /* we have send the OOB data, but not received them. remote will check if
     * C value is correct */
    remoteR = zeros;
  } else {
    remoteR = i.remote_oob_data->le_sc_r;
    Octet16 remoteC = i.remote_oob_data->le_sc_c;

    Octet16 remoteC2;
    if (IAmCentral(i)) {
      remoteC2 = crypto_toolbox::f4((uint8_t*)Pkb.x.data(), (uint8_t*)Pkb.x.data(), remoteR, 0);
    } else {
      remoteC2 = crypto_toolbox::f4((uint8_t*)Pka.x.data(), (uint8_t*)Pka.x.data(), remoteR, 0);
    }

    if (remoteC2 != remoteC) {
      log::error("C_computed != C_from_remote, aborting!");
      return PairingFailure("C_computed != C_from_remote, aborting");
    }
  }

  Octet16 Na, Nb, ra, rb;
  if (IAmCentral(i)) {
    ra = localR;
    rb = remoteR;
    Na = GenerateRandom<16>();
    // Send Pairing Random
    SendL2capPacket(i, PairingRandomBuilder::Create(Na));

    log::info("Central waits for Nb");
    auto random = WaitPairingRandom();
    if (std::holds_alternative<PairingFailure>(random)) {
      return std::get<PairingFailure>(random);
    }
    Nb = std::get<PairingRandomView>(random).GetRandomValue();
  } else {
    ra = remoteR;
    rb = localR;
    Nb = GenerateRandom<16>();

    log::info("Peripheral waits for random");
    auto random = WaitPairingRandom();
    if (std::holds_alternative<PairingFailure>(random)) {
      return std::get<PairingFailure>(random);
    }
    Na = std::get<PairingRandomView>(random).GetRandomValue();

    SendL2capPacket(i, PairingRandomBuilder::Create(Nb));
  }

  return Stage1Result{Na, Nb, ra, rb};
}

Stage1ResultOrFailure PairingHandlerLe::SecureConnectionsPasskeyEntry(const InitialInformations& i,
                                                                      const EcdhPublicKey& PKa,
                                                                      const EcdhPublicKey& PKb, IoCapability my_iocaps,
                                                                      IoCapability remote_iocaps) {
  log::info("Passkey Entry start");
  Octet16 Na, Nb, ra{0}, rb{0};

  uint32_t passkey;

  if (my_iocaps == IoCapability::DISPLAY_ONLY || remote_iocaps == IoCapability::KEYBOARD_ONLY) {
    // I display
    passkey = GenerateRandom();
    passkey &= 0x0fffff; /* maximum 20 significant bytes */
    constexpr uint32_t PASSKEY_MAX = 999999;
    while (passkey > PASSKEY_MAX) passkey >>= 1;

    ConfirmationData data(i.remote_connection_address, i.remote_name, passkey);
    i.user_interface_handler->Post(common::BindOnce(&UI::DisplayPasskey, common::Unretained(i.user_interface), data));

  } else if (my_iocaps == IoCapability::KEYBOARD_ONLY || remote_iocaps == IoCapability::DISPLAY_ONLY) {
    ConfirmationData data(i.remote_connection_address, i.remote_name);
    i.user_interface_handler->Post(
        common::BindOnce(&UI::DisplayEnterPasskeyDialog, common::Unretained(i.user_interface), data));
    std::optional<PairingEvent> response = WaitUiPasskey();
    if (!response) return PairingFailure("Passkey did not arrive!");

    passkey = response->ui_value;

    /*TODO: shall we send "Keypress Notification" after each key ? This would
     * have impact on the SMP timeout*/

  } else {
    log::fatal("THIS SHOULD NEVER HAPPEN");
    return PairingFailure("FATAL!");
  }

  uint32_t bitmask = 0x01;
  for (int loop = 0; loop < 20; loop++, bitmask <<= 1) {
    log::info("Iteration no {}", loop);
    bool bit_set = ((bitmask & passkey) != 0);
    uint8_t ri = bit_set ? 0x81 : 0x80;

    Octet16 Cai, Cbi, Nai, Nbi;
    if (IAmCentral(i)) {
      Nai = GenerateRandom<16>();

      Cai = crypto_toolbox::f4((uint8_t*)PKa.x.data(), (uint8_t*)PKb.x.data(), Nai, ri);

      // Send Pairing Confirm
      log::info("Central sends Cai");
      SendL2capPacket(i, PairingConfirmBuilder::Create(Cai));

      log::info("Central waits for the Cbi");
      auto confirm = WaitPairingConfirm();
      if (std::holds_alternative<PairingFailure>(confirm)) {
        return std::get<PairingFailure>(confirm);
      }
      Cbi = std::get<PairingConfirmView>(confirm).GetConfirmValue();

      // Send Pairing Random
      SendL2capPacket(i, PairingRandomBuilder::Create(Nai));

      log::info("Central waits for Nbi");
      auto random = WaitPairingRandom();
      if (std::holds_alternative<PairingFailure>(random)) {
        return std::get<PairingFailure>(random);
      }
      Nbi = std::get<PairingRandomView>(random).GetRandomValue();

      Octet16 Cbi2 = crypto_toolbox::f4((uint8_t*)PKb.x.data(), (uint8_t*)PKa.x.data(), Nbi, ri);
      if (Cbi != Cbi2) {
        log::info("Cai != Cbi, aborting!");
        SendL2capPacket(i, PairingFailedBuilder::Create(PairingFailedReason::CONFIRM_VALUE_FAILED));
        return PairingFailure("Cai != Cbi");
      }
    } else {
      Nbi = GenerateRandom<16>();
      // Compute confirm
      Cbi = crypto_toolbox::f4((uint8_t*)PKb.x.data(), (uint8_t*)PKa.x.data(), Nbi, ri);

      log::info("Peripheral waits for the Cai");
      auto confirm = WaitPairingConfirm();
      if (std::holds_alternative<PairingFailure>(confirm)) {
        return std::get<PairingFailure>(confirm);
      }
      Cai = std::get<PairingConfirmView>(confirm).GetConfirmValue();

      // Send Pairing Confirm
      log::info("Peripheral sends confirmation");
      SendL2capPacket(i, PairingConfirmBuilder::Create(Cbi));

      log::info("Peripheral waits for random");
      auto random = WaitPairingRandom();
      if (std::holds_alternative<PairingFailure>(random)) {
        return std::get<PairingFailure>(random);
      }
      Nai = std::get<PairingRandomView>(random).GetRandomValue();

      Octet16 Cai2 = crypto_toolbox::f4((uint8_t*)PKa.x.data(), (uint8_t*)PKb.x.data(), Nai, ri);
      if (Cai != Cai2) {
        log::info("Cai != Cai2, aborting!");
        SendL2capPacket(i, PairingFailedBuilder::Create(PairingFailedReason::CONFIRM_VALUE_FAILED));
        return PairingFailure("Cai != Cai2");
      }

      // Send Pairing Random
      SendL2capPacket(i, PairingRandomBuilder::Create(Nbi));
    }

    if (loop == 19) {
      Na = Nai;
      Nb = Nbi;
    }
  }

  ra[0] = (uint8_t)(passkey);
  ra[1] = (uint8_t)(passkey >> 8);
  ra[2] = (uint8_t)(passkey >> 16);
  ra[3] = (uint8_t)(passkey >> 24);
  rb = ra;

  return Stage1Result{Na, Nb, ra, rb};
}

Stage1ResultOrFailure PairingHandlerLe::SecureConnectionsNumericComparison(const InitialInformations& i,
                                                                           const EcdhPublicKey& PKa,
                                                                           const EcdhPublicKey& PKb) {
  log::info("Numeric Comparison start");
  Stage1ResultOrFailure result = SecureConnectionsJustWorks(i, PKa, PKb);
  if (std::holds_alternative<PairingFailure>(result)) {
    return std::get<PairingFailure>(result);
  }

  const auto [Na, Nb, ra, rb] = std::get<Stage1Result>(result);

  uint32_t number_to_display = crypto_toolbox::g2((uint8_t*)PKa.x.data(), (uint8_t*)PKb.x.data(), Na, Nb);

  ConfirmationData data(i.remote_connection_address, i.remote_name, number_to_display);
  i.user_interface_handler->Post(
      common::BindOnce(&UI::DisplayConfirmValue, common::Unretained(i.user_interface), data));

  std::optional<PairingEvent> confirmyesno = WaitUiConfirmYesNo();
  if (!confirmyesno || confirmyesno->ui_value == 0) {
    log::info("Was expecting the user value confirm");
    return PairingFailure("Was expecting the user value confirm");
  }

  return result;
}

Stage1ResultOrFailure PairingHandlerLe::SecureConnectionsJustWorks(const InitialInformations& i,
                                                                   const EcdhPublicKey& PKa, const EcdhPublicKey& PKb) {
  Octet16 Cb, Na, Nb, ra, rb;

  ra = rb = {0};

  if (IAmCentral(i)) {
    Na = GenerateRandom<16>();
    log::info("Central waits for confirmation");
    auto confirm = WaitPairingConfirm();
    if (std::holds_alternative<PairingFailure>(confirm)) {
      return std::get<PairingFailure>(confirm);
    }
    Cb = std::get<PairingConfirmView>(confirm).GetConfirmValue();

    // Send Pairing Random
    SendL2capPacket(i, PairingRandomBuilder::Create(Na));

    log::info("Central waits for Random");
    auto random = WaitPairingRandom();
    if (std::holds_alternative<PairingFailure>(random)) {
      return std::get<PairingFailure>(random);
    }
    Nb = std::get<PairingRandomView>(random).GetRandomValue();

    // Compute Cb locally
    Octet16 Cb_local = crypto_toolbox::f4((uint8_t*)PKb.x.data(), (uint8_t*)PKa.x.data(), Nb, 0);

    if (Cb_local != Cb) {
      log::info("Cb_local != Cb, aborting!");
      SendL2capPacket(i, PairingFailedBuilder::Create(PairingFailedReason::CONFIRM_VALUE_FAILED));
      return PairingFailure("Cb_local != Cb");
    }
  } else {
    Nb = GenerateRandom<16>();
    // Compute confirm
    Cb = crypto_toolbox::f4((uint8_t*)PKb.x.data(), (uint8_t*)PKa.x.data(), Nb, 0);

    // Send Pairing Confirm
    log::info("Peripheral sends confirmation");
    SendL2capPacket(i, PairingConfirmBuilder::Create(Cb));

    log::info("Peripheral waits for random");
    auto random = WaitPairingRandom();
    if (std::holds_alternative<PairingFailure>(random)) {
      return std::get<PairingFailure>(random);
    }
    Na = std::get<PairingRandomView>(random).GetRandomValue();

    // Send Pairing Random
    SendL2capPacket(i, PairingRandomBuilder::Create(Nb));
  }

  return Stage1Result{Na, Nb, ra, rb};
}

}  // namespace security
}  // namespace bluetooth
