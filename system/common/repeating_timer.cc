/*
 * Copyright 2018 The Android Open Source Project
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

#include "repeating_timer.h"

#include <base/functional/callback.h>
#include <bluetooth/log.h>

#include "message_loop_thread.h"
#include "time_util.h"

namespace bluetooth {

namespace common {

constexpr std::chrono::microseconds kMinimumPeriod =
    std::chrono::microseconds(1);

// This runs on user thread
RepeatingTimer::~RepeatingTimer() {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  if (message_loop_thread_ != nullptr && message_loop_thread_->IsRunning()) {
    CancelAndWait();
  }
}

// This runs on user thread
bool RepeatingTimer::SchedulePeriodic(
    const base::WeakPtr<MessageLoopThread>& thread,
    const base::Location& from_here, base::RepeatingClosure task,
    std::chrono::microseconds period) {
  if (period < kMinimumPeriod) {
    log::error("period must be at least {}", kMinimumPeriod.count());
    return false;
  }

  uint64_t time_now_us = time_get_os_boottime_us();
  uint64_t time_next_task_us = time_now_us + period.count();
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  if (thread == nullptr) {
    log::error("thread must be non-null");
    return false;
  }
  CancelAndWait();
  expected_time_next_task_us_ = time_next_task_us;
  task_ = std::move(task);
  task_wrapper_.Reset(
      base::Bind(&RepeatingTimer::RunTask, base::Unretained(this)));
  message_loop_thread_ = thread;
  period_ = period;
  uint64_t time_until_next_us = time_next_task_us - time_get_os_boottime_us();
  if (!thread->DoInThreadDelayed(
          from_here, task_wrapper_.callback(),
          std::chrono::microseconds(time_until_next_us))) {
    log::error("failed to post task to message loop for thread {}, from {}",
               *thread, from_here.ToString());
    expected_time_next_task_us_ = 0;
    task_wrapper_.Cancel();
    message_loop_thread_ = nullptr;
    period_ = {};
    return false;
  }
  return true;
}

// This runs on user thread
void RepeatingTimer::Cancel() {
  std::promise<void> promise;
  CancelHelper(std::move(promise));
}

// This runs on user thread
void RepeatingTimer::CancelAndWait() {
  std::promise<void> promise;
  auto future = promise.get_future();
  CancelHelper(std::move(promise));
  future.wait();
}

// This runs on user thread
void RepeatingTimer::CancelHelper(std::promise<void> promise) {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  MessageLoopThread* scheduled_thread = message_loop_thread_.get();
  if (scheduled_thread == nullptr) {
    promise.set_value();
    return;
  }
  if (scheduled_thread->GetThreadId() == base::PlatformThread::CurrentId()) {
    CancelClosure(std::move(promise));
    return;
  }
  scheduled_thread->DoInThread(
      FROM_HERE, base::BindOnce(&RepeatingTimer::CancelClosure,
                                base::Unretained(this), std::move(promise)));
}

// This runs on message loop thread
void RepeatingTimer::CancelClosure(std::promise<void> promise) {
  message_loop_thread_ = nullptr;
  task_wrapper_.Cancel();
#if BASE_VER < 927031
  task_ = {};
#else
  task_ = base::NullCallback();
#endif
  period_ = std::chrono::microseconds(0);
  expected_time_next_task_us_ = 0;
  promise.set_value();
}

// This runs on user thread
bool RepeatingTimer::IsScheduled() const {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  return message_loop_thread_ != nullptr && message_loop_thread_->IsRunning();
}

// This runs on message loop thread
void RepeatingTimer::RunTask() {
  if (message_loop_thread_ == nullptr || !message_loop_thread_->IsRunning()) {
    log::error("message_loop_thread_ is null or is not running");
    return;
  }
  log::assert_that(
      message_loop_thread_->GetThreadId() == base::PlatformThread::CurrentId(),
      "task must run on message loop thread");

  int64_t period_us = period_.count();
  expected_time_next_task_us_ += period_us;
  uint64_t time_now_us = time_get_os_boottime_us();
  int64_t remaining_time_us = expected_time_next_task_us_ - time_now_us;
  if (remaining_time_us < 0) {
    // if remaining_time_us is negative, schedule the task to the nearest
    // multiple of period
    remaining_time_us = (remaining_time_us % period_us + period_us) % period_us;
  }
  message_loop_thread_->DoInThreadDelayed(
      FROM_HERE, task_wrapper_.callback(),
      std::chrono::microseconds(remaining_time_us));

  uint64_t time_before_task_us = time_get_os_boottime_us();
  task_.Run();
  uint64_t time_after_task_us = time_get_os_boottime_us();
  auto task_time_us =
      static_cast<int64_t>(time_after_task_us - time_before_task_us);
  if (task_time_us > period_.count()) {
    log::error(
        "Periodic task execution took {} microseconds, longer than interval {} "
        "microseconds",
        task_time_us, period_.count());
  }
}

}  // namespace common

}  // namespace bluetooth
