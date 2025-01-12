/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#define LOG_TAG "bt_gd_neigh"

#include "neighbor/inquiry.h"

#include <bluetooth/log.h>

#include <memory>

#include "common/bind.h"
#include "hci/hci_layer.h"
#include "hci/hci_packets.h"
#include "module.h"
#include "os/handler.h"
#include "os/log.h"

namespace bluetooth {
namespace neighbor {

static constexpr uint8_t kGeneralInquiryAccessCode = 0x33;
static constexpr uint8_t kLimitedInquiryAccessCode = 0x00;

struct InquiryModule::impl {
  void RegisterCallbacks(InquiryCallbacks inquiry_callbacks);
  void UnregisterCallbacks();

  void StartOneShotInquiry(bool limited, InquiryLength inquiry_length, NumResponses num_responses);
  void StopOneShotInquiry();

  void StartPeriodicInquiry(
      bool limited,
      InquiryLength inquiry_length,
      NumResponses num_responses,
      PeriodLength max_delay,
      PeriodLength min_delay);
  void StopPeriodicInquiry();

  void SetScanActivity(ScanParameters params);

  void SetScanType(hci::InquiryScanType scan_type);

  void SetInquiryMode(hci::InquiryMode mode);

  void Start();
  void Stop();

  bool HasCallbacks() const;

  impl(InquiryModule& inquiry_module);

 private:
  InquiryCallbacks inquiry_callbacks_;

  InquiryModule& module_;

  bool active_general_one_shot_{false};
  bool active_limited_one_shot_{false};
  bool active_general_periodic_{false};
  bool active_limited_periodic_{false};

  ScanParameters inquiry_scan_;
  hci::InquiryMode inquiry_mode_;
  hci::InquiryScanType inquiry_scan_type_;
  int8_t inquiry_response_tx_power_;

  bool IsInquiryActive() const;

  void EnqueueCommandComplete(std::unique_ptr<hci::CommandBuilder> command);
  void EnqueueCommandStatus(std::unique_ptr<hci::CommandBuilder> command);
  void OnCommandComplete(hci::CommandCompleteView view);
  void OnCommandStatus(hci::CommandStatusView status);

  void EnqueueCommandCompleteSync(std::unique_ptr<hci::CommandBuilder> command);
  void OnCommandCompleteSync(hci::CommandCompleteView view);

  void OnEvent(hci::EventView view);

  std::promise<void>* command_sync_{nullptr};

  hci::HciLayer* hci_layer_;
  os::Handler* handler_;
};

const ModuleFactory neighbor::InquiryModule::Factory = ModuleFactory([]() { return new neighbor::InquiryModule(); });

neighbor::InquiryModule::impl::impl(neighbor::InquiryModule& module) : module_(module) {}

void neighbor::InquiryModule::impl::OnCommandCompleteSync(hci::CommandCompleteView view) {
  OnCommandComplete(view);
  log::assert_that(command_sync_ != nullptr, "assert failed: command_sync_ != nullptr");
  command_sync_->set_value();
}

void neighbor::InquiryModule::impl::OnCommandComplete(hci::CommandCompleteView view) {
  switch (view.GetCommandOpCode()) {
    case hci::OpCode::INQUIRY_CANCEL: {
      auto packet = hci::InquiryCancelCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
    } break;

    case hci::OpCode::PERIODIC_INQUIRY_MODE: {
      auto packet = hci::PeriodicInquiryModeCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
    } break;

    case hci::OpCode::EXIT_PERIODIC_INQUIRY_MODE: {
      auto packet = hci::ExitPeriodicInquiryModeCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
    } break;

    case hci::OpCode::WRITE_INQUIRY_MODE: {
      auto packet = hci::WriteInquiryModeCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
    } break;

    case hci::OpCode::READ_INQUIRY_MODE: {
      auto packet = hci::ReadInquiryModeCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
      inquiry_mode_ = packet.GetInquiryMode();
    } break;

    case hci::OpCode::READ_INQUIRY_RESPONSE_TRANSMIT_POWER_LEVEL: {
      auto packet = hci::ReadInquiryResponseTransmitPowerLevelCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
      inquiry_response_tx_power_ = packet.GetTxPower();
    } break;

    case hci::OpCode::WRITE_INQUIRY_SCAN_ACTIVITY: {
      auto packet = hci::WriteInquiryScanActivityCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
    } break;

    case hci::OpCode::READ_INQUIRY_SCAN_ACTIVITY: {
      auto packet = hci::ReadInquiryScanActivityCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
      inquiry_scan_.interval = packet.GetInquiryScanInterval();
      inquiry_scan_.window = packet.GetInquiryScanWindow();
    } break;

    case hci::OpCode::WRITE_INQUIRY_SCAN_TYPE: {
      auto packet = hci::WriteInquiryScanTypeCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
    } break;

    case hci::OpCode::READ_INQUIRY_SCAN_TYPE: {
      auto packet = hci::ReadInquiryScanTypeCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
      inquiry_scan_type_ = packet.GetInquiryScanType();
    } break;

    default:
      log::warn("Unhandled command:{}", hci::OpCodeText(view.GetCommandOpCode()));
      break;
  }
}

void neighbor::InquiryModule::impl::OnCommandStatus(hci::CommandStatusView status) {
  log::assert_that(
      status.GetStatus() == hci::ErrorCode::SUCCESS,
      "assert failed: status.GetStatus() == hci::ErrorCode::SUCCESS");

  switch (status.GetCommandOpCode()) {
    case hci::OpCode::INQUIRY: {
      auto packet = hci::InquiryStatusView::Create(status);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      if (active_limited_one_shot_ || active_general_one_shot_) {
        log::info("Inquiry started lap: {}", active_limited_one_shot_ ? "Limited" : "General");
      }
    } break;

    default:
      log::warn("Unhandled command:{}", hci::OpCodeText(status.GetCommandOpCode()));
      break;
  }
}

void neighbor::InquiryModule::impl::OnEvent(hci::EventView view) {
  switch (view.GetEventCode()) {
    case hci::EventCode::INQUIRY_COMPLETE: {
      auto packet = hci::InquiryCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::info("inquiry complete");
      active_limited_one_shot_ = false;
      active_general_one_shot_ = false;
      inquiry_callbacks_.complete(packet.GetStatus());
    } break;

    case hci::EventCode::INQUIRY_RESULT: {
      auto packet = hci::InquiryResultView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::info(
          "Inquiry result size:{} num_responses:{}", packet.size(), packet.GetResponses().size());
      inquiry_callbacks_.result(packet);
    } break;

    case hci::EventCode::INQUIRY_RESULT_WITH_RSSI: {
      auto packet = hci::InquiryResultWithRssiView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::info("Inquiry result with rssi num_responses:{}", packet.GetResponses().size());
      inquiry_callbacks_.result_with_rssi(packet);
    } break;

    case hci::EventCode::EXTENDED_INQUIRY_RESULT: {
      auto packet = hci::ExtendedInquiryResultView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::info(
          "Extended inquiry result addr:{} repetition_mode:{} cod:{} clock_offset:{} rssi:{}",
          ADDRESS_TO_LOGGABLE_CSTR(packet.GetAddress()),
          hci::PageScanRepetitionModeText(packet.GetPageScanRepetitionMode()),
          packet.GetClassOfDevice().ToString(),
          packet.GetClockOffset(),
          packet.GetRssi());
      inquiry_callbacks_.extended_result(packet);
    } break;

    default:
      log::error("Unhandled event:{}", hci::EventCodeText(view.GetEventCode()));
      break;
  }
}

/**
 * impl
 */
void neighbor::InquiryModule::impl::RegisterCallbacks(InquiryCallbacks callbacks) {
  inquiry_callbacks_ = callbacks;

  hci_layer_->RegisterEventHandler(
      hci::EventCode::INQUIRY_RESULT, handler_->BindOn(this, &InquiryModule::impl::OnEvent));
  hci_layer_->RegisterEventHandler(
      hci::EventCode::INQUIRY_RESULT_WITH_RSSI, handler_->BindOn(this, &InquiryModule::impl::OnEvent));
  hci_layer_->RegisterEventHandler(
      hci::EventCode::EXTENDED_INQUIRY_RESULT, handler_->BindOn(this, &InquiryModule::impl::OnEvent));
  hci_layer_->RegisterEventHandler(
      hci::EventCode::INQUIRY_COMPLETE, handler_->BindOn(this, &InquiryModule::impl::OnEvent));
}

void neighbor::InquiryModule::impl::UnregisterCallbacks() {
  hci_layer_->UnregisterEventHandler(hci::EventCode::INQUIRY_COMPLETE);
  hci_layer_->UnregisterEventHandler(hci::EventCode::EXTENDED_INQUIRY_RESULT);
  hci_layer_->UnregisterEventHandler(hci::EventCode::INQUIRY_RESULT_WITH_RSSI);
  hci_layer_->UnregisterEventHandler(hci::EventCode::INQUIRY_RESULT);

  inquiry_callbacks_ = {nullptr, nullptr, nullptr, nullptr};
}

void neighbor::InquiryModule::impl::EnqueueCommandComplete(std::unique_ptr<hci::CommandBuilder> command) {
  hci_layer_->EnqueueCommand(std::move(command), handler_->BindOnceOn(this, &impl::OnCommandComplete));
}

void neighbor::InquiryModule::impl::EnqueueCommandStatus(std::unique_ptr<hci::CommandBuilder> command) {
  hci_layer_->EnqueueCommand(std::move(command), handler_->BindOnceOn(this, &impl::OnCommandStatus));
}

void neighbor::InquiryModule::impl::EnqueueCommandCompleteSync(std::unique_ptr<hci::CommandBuilder> command) {
  log::assert_that(command_sync_ == nullptr, "assert failed: command_sync_ == nullptr");
  command_sync_ = new std::promise<void>();
  auto command_received = command_sync_->get_future();
  hci_layer_->EnqueueCommand(std::move(command), handler_->BindOnceOn(this, &impl::OnCommandCompleteSync));
  command_received.wait();
  delete command_sync_;
  command_sync_ = nullptr;
}

void neighbor::InquiryModule::impl::StartOneShotInquiry(
    bool limited, InquiryLength inquiry_length, NumResponses num_responses) {
  log::assert_that(HasCallbacks(), "assert failed: HasCallbacks()");
  log::assert_that(!IsInquiryActive(), "assert failed: !IsInquiryActive()");
  hci::Lap lap;
  if (limited) {
    active_limited_one_shot_ = true;
    lap.lap_ = kLimitedInquiryAccessCode;
  } else {
    active_general_one_shot_ = true;
    lap.lap_ = kGeneralInquiryAccessCode;
  }
  EnqueueCommandStatus(hci::InquiryBuilder::Create(lap, inquiry_length, num_responses));
}

void neighbor::InquiryModule::impl::StopOneShotInquiry() {
  log::assert_that(
      active_general_one_shot_ || active_limited_one_shot_,
      "assert failed: active_general_one_shot_ || active_limited_one_shot_");
  active_general_one_shot_ = false;
  active_limited_one_shot_ = false;
  EnqueueCommandComplete(hci::InquiryCancelBuilder::Create());
}

void neighbor::InquiryModule::impl::StartPeriodicInquiry(
    bool limited,
    InquiryLength inquiry_length,
    NumResponses num_responses,
    PeriodLength max_delay,
    PeriodLength min_delay) {
  log::assert_that(HasCallbacks(), "assert failed: HasCallbacks()");
  log::assert_that(!IsInquiryActive(), "assert failed: !IsInquiryActive()");
  hci::Lap lap;
  if (limited) {
    active_limited_periodic_ = true;
    lap.lap_ = kLimitedInquiryAccessCode;
  } else {
    active_general_periodic_ = true;
    lap.lap_ = kGeneralInquiryAccessCode;
  }
  EnqueueCommandComplete(
      hci::PeriodicInquiryModeBuilder::Create(max_delay, min_delay, lap, inquiry_length, num_responses));
}

void neighbor::InquiryModule::impl::StopPeriodicInquiry() {
  log::assert_that(
      active_general_periodic_ || active_limited_periodic_,
      "assert failed: active_general_periodic_ || active_limited_periodic_");
  active_general_periodic_ = false;
  active_limited_periodic_ = false;
  EnqueueCommandComplete(hci::ExitPeriodicInquiryModeBuilder::Create());
}

bool neighbor::InquiryModule::impl::IsInquiryActive() const {
  return active_general_one_shot_ || active_limited_one_shot_ || active_limited_periodic_ || active_general_periodic_;
}

void neighbor::InquiryModule::impl::Start() {
  hci_layer_ = module_.GetDependency<hci::HciLayer>();
  handler_ = module_.GetHandler();

  EnqueueCommandComplete(hci::ReadInquiryResponseTransmitPowerLevelBuilder::Create());
  EnqueueCommandComplete(hci::ReadInquiryScanActivityBuilder::Create());
  EnqueueCommandComplete(hci::ReadInquiryScanTypeBuilder::Create());
  EnqueueCommandCompleteSync(hci::ReadInquiryModeBuilder::Create());

  log::info("Started inquiry module");
}

void neighbor::InquiryModule::impl::Stop() {
  log::info("Inquiry scan interval:{} window:{}", inquiry_scan_.interval, inquiry_scan_.window);
  log::info(
      "Inquiry mode:{} scan_type:{}",
      hci::InquiryModeText(inquiry_mode_),
      hci::InquiryScanTypeText(inquiry_scan_type_));
  log::info("Inquiry response tx power:{}", inquiry_response_tx_power_);
  log::info("Stopped inquiry module");
}

void neighbor::InquiryModule::impl::SetInquiryMode(hci::InquiryMode mode) {
  EnqueueCommandComplete(hci::WriteInquiryModeBuilder::Create(mode));
  inquiry_mode_ = mode;
  log::info("Set inquiry mode:{}", hci::InquiryModeText(mode));
}

void neighbor::InquiryModule::impl::SetScanActivity(ScanParameters params) {
  EnqueueCommandComplete(hci::WriteInquiryScanActivityBuilder::Create(params.interval, params.window));
  inquiry_scan_ = params;
  log::info(
      "Set scan activity interval:0x{:x}/{:.02f}ms window:0x{:x}/{:.02f}ms",
      params.interval,
      ScanIntervalTimeMs(params.interval),
      params.window,
      ScanWindowTimeMs(params.window));
}

void neighbor::InquiryModule::impl::SetScanType(hci::InquiryScanType scan_type) {
  EnqueueCommandComplete(hci::WriteInquiryScanTypeBuilder::Create(scan_type));
  log::info("Set scan type:{}", hci::InquiryScanTypeText(scan_type));
}

bool neighbor::InquiryModule::impl::HasCallbacks() const {
  return inquiry_callbacks_.result != nullptr && inquiry_callbacks_.result_with_rssi != nullptr &&
         inquiry_callbacks_.extended_result != nullptr && inquiry_callbacks_.complete != nullptr;
}

/**
 * General API here
 */
neighbor::InquiryModule::InquiryModule() : pimpl_(std::make_unique<impl>(*this)) {}

neighbor::InquiryModule::~InquiryModule() {
  pimpl_.reset();
}

void neighbor::InquiryModule::RegisterCallbacks(InquiryCallbacks callbacks) {
  pimpl_->RegisterCallbacks(callbacks);
}

void neighbor::InquiryModule::UnregisterCallbacks() {
  pimpl_->UnregisterCallbacks();
}

void neighbor::InquiryModule::StartGeneralInquiry(InquiryLength inquiry_length, NumResponses num_responses) {
  GetHandler()->Post(common::BindOnce(
      &neighbor::InquiryModule::impl::StartOneShotInquiry,
      common::Unretained(pimpl_.get()),
      false,
      inquiry_length,
      num_responses));
}

void neighbor::InquiryModule::StartLimitedInquiry(InquiryLength inquiry_length, NumResponses num_responses) {
  GetHandler()->Post(common::BindOnce(
      &neighbor::InquiryModule::impl::StartOneShotInquiry,
      common::Unretained(pimpl_.get()),
      true,
      inquiry_length,
      num_responses));
}

void neighbor::InquiryModule::StopInquiry() {
  GetHandler()->Post(
      common::BindOnce(&neighbor::InquiryModule::impl::StopOneShotInquiry, common::Unretained(pimpl_.get())));
}

void neighbor::InquiryModule::StartGeneralPeriodicInquiry(
    InquiryLength inquiry_length, NumResponses num_responses, PeriodLength max_delay, PeriodLength min_delay) {
  GetHandler()->Post(common::BindOnce(
      &neighbor::InquiryModule::impl::StartPeriodicInquiry,
      common::Unretained(pimpl_.get()),
      false,
      inquiry_length,
      num_responses,
      max_delay,
      min_delay));
}

void neighbor::InquiryModule::StartLimitedPeriodicInquiry(
    InquiryLength inquiry_length, NumResponses num_responses, PeriodLength max_delay, PeriodLength min_delay) {
  GetHandler()->Post(common::BindOnce(
      &neighbor::InquiryModule::impl::StartPeriodicInquiry,
      common::Unretained(pimpl_.get()),
      true,
      inquiry_length,
      num_responses,
      max_delay,
      min_delay));
}

void neighbor::InquiryModule::StopPeriodicInquiry() {
  GetHandler()->Post(
      common::BindOnce(&neighbor::InquiryModule::impl::StopPeriodicInquiry, common::Unretained(pimpl_.get())));
}

void neighbor::InquiryModule::SetScanActivity(ScanParameters params) {
  GetHandler()->Post(
      common::BindOnce(&neighbor::InquiryModule::impl::SetScanActivity, common::Unretained(pimpl_.get()), params));
}

void neighbor::InquiryModule::SetInterlacedScan() {
  GetHandler()->Post(common::BindOnce(
      &neighbor::InquiryModule::impl::SetScanType, common::Unretained(pimpl_.get()), hci::InquiryScanType::INTERLACED));
}

void neighbor::InquiryModule::SetStandardScan() {
  GetHandler()->Post(common::BindOnce(
      &neighbor::InquiryModule::impl::SetScanType, common::Unretained(pimpl_.get()), hci::InquiryScanType::STANDARD));
}

void neighbor::InquiryModule::SetStandardInquiryResultMode() {
  GetHandler()->Post(common::BindOnce(
      &neighbor::InquiryModule::impl::SetInquiryMode, common::Unretained(pimpl_.get()), hci::InquiryMode::STANDARD));
}

void neighbor::InquiryModule::SetInquiryWithRssiResultMode() {
  GetHandler()->Post(common::BindOnce(
      &neighbor::InquiryModule::impl::SetInquiryMode, common::Unretained(pimpl_.get()), hci::InquiryMode::RSSI));
}

void neighbor::InquiryModule::SetExtendedInquiryResultMode() {
  GetHandler()->Post(common::BindOnce(
      &neighbor::InquiryModule::impl::SetInquiryMode,
      common::Unretained(pimpl_.get()),
      hci::InquiryMode::RSSI_OR_EXTENDED));
}

/**
 * Module methods here
 */
void neighbor::InquiryModule::ListDependencies(ModuleList* list) const {
  list->add<hci::HciLayer>();
}

void neighbor::InquiryModule::Start() {
  pimpl_->Start();
}

void neighbor::InquiryModule::Stop() {
  pimpl_->Stop();
}

}  // namespace neighbor
}  // namespace bluetooth
