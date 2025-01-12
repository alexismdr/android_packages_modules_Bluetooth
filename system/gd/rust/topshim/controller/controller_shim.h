/*
 * Copyright (C) 2021 The Android Open Source Project
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
#ifndef GD_RUST_TOPSHIM_CONTROLLER_SHIM
#define GD_RUST_TOPSHIM_CONTROLLER_SHIM

#include <memory>

#include "hci/controller_interface.h"
#include "main/shim/entry.h"
#include "rust/cxx.h"
#include "types/raw_address.h"

namespace bluetooth {
namespace topshim {
namespace rust {

class ControllerIntf {
 public:
  ControllerIntf() : controller_(shim::GetController()) {}
  ~ControllerIntf();

  RawAddress read_local_addr() const;
  uint64_t get_ble_supported_states() const;

 private:
  const hci::ControllerInterface* controller_;
};

std::unique_ptr<ControllerIntf> GetControllerInterface();

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth

#endif  // GD_RUST_TOPSHIM_CONTROLLER_SHIM
