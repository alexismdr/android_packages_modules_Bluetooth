/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "mock_codec_manager.h"

#include "broadcaster/broadcast_configuration_provider.h"

MockCodecManager* mock_codec_manager_pimpl_;
MockCodecManager* MockCodecManager::GetInstance() {
  bluetooth::le_audio::CodecManager::GetInstance();
  return mock_codec_manager_pimpl_;
}

namespace bluetooth::le_audio {

struct CodecManager::impl : public MockCodecManager {
 public:
  impl() = default;
  ~impl() = default;
};

CodecManager::CodecManager() {}

types::CodecLocation CodecManager::GetCodecLocation() const {
  if (!pimpl_) return types::CodecLocation::HOST;
  return pimpl_->GetCodecLocation();
}

bool CodecManager::IsDualBiDirSwbSupported(void) const {
  if (!pimpl_) {
    return false;
  }

  return pimpl_->IsDualBiDirSwbSupported();
}

void CodecManager::UpdateActiveAudioConfig(
    const types::BidirectionalPair<stream_parameters>& stream_params,
    types::BidirectionalPair<uint16_t> delays_ms,
    std::function<void(const ::bluetooth::le_audio::offload_config& config,
                       uint8_t direction)>
        update_receiver) {
  if (pimpl_)
    return pimpl_->UpdateActiveAudioConfig(stream_params, delays_ms,
                                           update_receiver);
}

std::unique_ptr<set_configurations::AudioSetConfiguration>
CodecManager::GetCodecConfig(
    types::LeAudioContextType ctx_type,
    std::function<const le_audio::set_configurations::AudioSetConfiguration*(
        le_audio::types::LeAudioContextType context_type,
        const le_audio::set_configurations::AudioSetConfigurations* confs)>
        non_vendor_config_matcher) {
  if (!pimpl_) return nullptr;
  return pimpl_->GetCodecConfig(ctx_type, non_vendor_config_matcher);
}

std::unique_ptr<::bluetooth::le_audio::broadcaster::BroadcastConfiguration>
CodecManager::GetBroadcastConfig(
    const std::vector<std::pair<bluetooth::le_audio::types::LeAudioContextType,
                                uint8_t>>& subgroup_quality,
    std::optional<const bluetooth::le_audio::types::PublishedAudioCapabilities*>
        pacs) const {
  if (!pimpl_)
    return std::make_unique<
        bluetooth::le_audio::broadcaster::BroadcastConfiguration>(
        bluetooth::le_audio::broadcaster::GetBroadcastConfig(subgroup_quality));
  return pimpl_->GetBroadcastConfig(subgroup_quality, pacs);
}

bool CodecManager::CheckCodecConfigIsBiDirSwb(
    const bluetooth::le_audio::set_configurations::AudioSetConfiguration&
        config) const {
  if (!pimpl_) return false;
  return pimpl_->CheckCodecConfigIsBiDirSwb(config);
}

std::vector<bluetooth::le_audio::btle_audio_codec_config_t>
CodecManager::GetLocalAudioOutputCodecCapa() {
  if (!pimpl_)
    return std::vector<bluetooth::le_audio::btle_audio_codec_config_t>{};
  return pimpl_->GetLocalAudioOutputCodecCapa();
}

std::vector<bluetooth::le_audio::btle_audio_codec_config_t>
CodecManager::GetLocalAudioInputCodecCapa() {
  if (!pimpl_)
    return std::vector<bluetooth::le_audio::btle_audio_codec_config_t>{};
  return pimpl_->GetLocalAudioInputCodecCapa();
}

void CodecManager::UpdateBroadcastConnHandle(
    const std::vector<uint16_t>& conn_handle,
    std::function<
        void(const ::bluetooth::le_audio::broadcast_offload_config& config)>
        update_receiver) {
  if (pimpl_)
    return pimpl_->UpdateBroadcastConnHandle(conn_handle, update_receiver);
}

void CodecManager::Start(
    const std::vector<bluetooth::le_audio::btle_audio_codec_config_t>&
        offloading_preference) {
  // It is needed here as CodecManager which is a singleton creates it, but in
  // this mock we want to destroy and recreate the mock on each test case.
  if (!pimpl_) {
    pimpl_ = std::make_unique<testing::NiceMock<impl>>();
  }

  mock_codec_manager_pimpl_ = pimpl_.get();
  pimpl_->Start();
}

void CodecManager::Stop() {
  // It is needed here as CodecManager which is a singleton creates it, but in
  // this mock we want to destroy and recreate the mock on each test case.
  if (pimpl_) {
    pimpl_->Stop();
    pimpl_.reset();
  }

  mock_codec_manager_pimpl_ = nullptr;
}

void CodecManager::UpdateCisConfiguration(
    const std::vector<struct types::cis>& cises,
    const stream_parameters& stream_params, uint8_t direction) {
  if (pimpl_)
    return pimpl_->UpdateCisConfiguration(cises, stream_params, direction);
}

void CodecManager::ClearCisConfiguration(uint8_t direction) {
  if (pimpl_) return pimpl_->ClearCisConfiguration(direction);
}

// CodecManager::~CodecManager() = default;

}  // namespace bluetooth::le_audio
