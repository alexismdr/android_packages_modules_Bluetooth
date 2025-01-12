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

#include "neighbor/page.h"

#include <bluetooth/log.h>

#include <memory>

#include "common/bind.h"
#include "hci/hci_layer.h"
#include "hci/hci_packets.h"
#include "module.h"
#include "neighbor/scan_parameters.h"
#include "os/handler.h"
#include "os/log.h"

namespace bluetooth {
namespace neighbor {

struct PageModule::impl {
  void SetScanActivity(ScanParameters params);
  ScanParameters GetScanActivity() const;

  void SetScanType(hci::PageScanType type);

  void SetTimeout(PageTimeout timeout);

  void Start();
  void Stop();

  impl(PageModule& page_module);

 private:
  PageModule& module_;

  ScanParameters scan_parameters_;
  hci::PageScanType scan_type_;
  PageTimeout timeout_;

  void OnCommandComplete(hci::CommandCompleteView status);

  hci::HciLayer* hci_layer_;
  os::Handler* handler_;
};

const ModuleFactory neighbor::PageModule::Factory = ModuleFactory([]() { return new neighbor::PageModule(); });

neighbor::PageModule::impl::impl(neighbor::PageModule& module) : module_(module) {}

void neighbor::PageModule::impl::OnCommandComplete(hci::CommandCompleteView view) {
  switch (view.GetCommandOpCode()) {
    case hci::OpCode::WRITE_PAGE_SCAN_ACTIVITY: {
      auto packet = hci::WritePageScanActivityCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
    } break;

    case hci::OpCode::READ_PAGE_SCAN_ACTIVITY: {
      auto packet = hci::ReadPageScanActivityCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
      scan_parameters_.interval = packet.GetPageScanInterval();
      scan_parameters_.window = packet.GetPageScanWindow();
    } break;

    case hci::OpCode::WRITE_PAGE_SCAN_TYPE: {
      auto packet = hci::WritePageScanTypeCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
    } break;

    case hci::OpCode::READ_PAGE_SCAN_TYPE: {
      auto packet = hci::ReadPageScanTypeCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
      scan_type_ = packet.GetPageScanType();
    } break;

    case hci::OpCode::WRITE_PAGE_TIMEOUT: {
      auto packet = hci::WritePageTimeoutCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
    } break;

    case hci::OpCode::READ_PAGE_TIMEOUT: {
      auto packet = hci::ReadPageTimeoutCompleteView::Create(view);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
      timeout_ = packet.GetPageTimeout();
    } break;

    default:
      log::error("Unhandled command {}", hci::OpCodeText(view.GetCommandOpCode()));
      break;
  }
}

void neighbor::PageModule::impl::Start() {
  hci_layer_ = module_.GetDependency<hci::HciLayer>();
  handler_ = module_.GetHandler();

  hci_layer_->EnqueueCommand(
      hci::ReadPageScanActivityBuilder::Create(), handler_->BindOnceOn(this, &impl::OnCommandComplete));

  hci_layer_->EnqueueCommand(
      hci::ReadPageScanTypeBuilder::Create(), handler_->BindOnceOn(this, &impl::OnCommandComplete));

  hci_layer_->EnqueueCommand(
      hci::ReadPageTimeoutBuilder::Create(), handler_->BindOnceOn(this, &impl::OnCommandComplete));
}

void neighbor::PageModule::impl::Stop() {
  log::info("Page scan interval:{} window:{}", scan_parameters_.interval, scan_parameters_.window);
  log::info("Page scan_type:{}", hci::PageScanTypeText(scan_type_));
}

void neighbor::PageModule::impl::SetScanActivity(ScanParameters params) {
  hci_layer_->EnqueueCommand(
      hci::WritePageScanActivityBuilder::Create(params.interval, params.window),
      handler_->BindOnceOn(this, &impl::OnCommandComplete));

  hci_layer_->EnqueueCommand(
      hci::ReadPageScanActivityBuilder::Create(), handler_->BindOnceOn(this, &impl::OnCommandComplete));
  log::info(
      "Set page scan activity interval:0x{:x}/{:.02f}ms window:0x{:x}/{:.02f}ms",
      params.interval,
      ScanIntervalTimeMs(params.interval),
      params.window,
      ScanWindowTimeMs(params.window));
}

ScanParameters neighbor::PageModule::impl::GetScanActivity() const {
  return scan_parameters_;
}

void neighbor::PageModule::impl::SetScanType(hci::PageScanType scan_type) {
  hci_layer_->EnqueueCommand(
      hci::WritePageScanTypeBuilder::Create(scan_type), handler_->BindOnceOn(this, &impl::OnCommandComplete));

  hci_layer_->EnqueueCommand(
      hci::ReadPageScanTypeBuilder::Create(), handler_->BindOnceOn(this, &impl::OnCommandComplete));
  log::info("Set page scan type:{}", hci::PageScanTypeText(scan_type));
}

void neighbor::PageModule::impl::SetTimeout(PageTimeout timeout) {
  hci_layer_->EnqueueCommand(
      hci::WritePageTimeoutBuilder::Create(timeout), handler_->BindOnceOn(this, &impl::OnCommandComplete));

  hci_layer_->EnqueueCommand(
      hci::ReadPageTimeoutBuilder::Create(), handler_->BindOnceOn(this, &impl::OnCommandComplete));
  log::info("Set page scan timeout:0x{:x}/{:.02f}ms", timeout, PageTimeoutMs(timeout));
}

/**
 * General API here
 */
neighbor::PageModule::PageModule() : pimpl_(std::make_unique<impl>(*this)) {}

neighbor::PageModule::~PageModule() {
  pimpl_.reset();
}

void neighbor::PageModule::SetScanActivity(ScanParameters params) {
  pimpl_->SetScanActivity(params);
}

ScanParameters neighbor::PageModule::GetScanActivity() const {
  return pimpl_->GetScanActivity();
}

void neighbor::PageModule::SetInterlacedScan() {
  pimpl_->SetScanType(hci::PageScanType::INTERLACED);
}

void neighbor::PageModule::SetStandardScan() {
  pimpl_->SetScanType(hci::PageScanType::STANDARD);
}

void neighbor::PageModule::SetTimeout(PageTimeout timeout) {
  pimpl_->SetTimeout(timeout);
}

/**
 * Module methods here
 */
void neighbor::PageModule::ListDependencies(ModuleList* list) const {
  list->add<hci::HciLayer>();
}

void neighbor::PageModule::Start() {
  pimpl_->Start();
}

void neighbor::PageModule::Stop() {
  pimpl_->Stop();
}

}  // namespace neighbor
}  // namespace bluetooth
