#include "js8core/android/scheduler_android.hpp"

#include <algorithm>

namespace js8core::android {

ThreadScheduler::ThreadScheduler() {
  worker_ = std::thread([this] { worker_thread(); });
}

ThreadScheduler::~ThreadScheduler() {
  running_ = false;
  cv_.notify_all();
  if (worker_.joinable()) {
    worker_.join();
  }
}

SteadyTimePoint ThreadScheduler::now() const {
  return SteadyClock::now();
}

TimerHandle ThreadScheduler::call_after(SteadyDuration delay, std::function<void()> fn) {
  auto handle = next_handle_++;
  auto timer = std::make_shared<TimerInfo>();
  timer->handle = handle;
  timer->next_fire = now() + delay;
  timer->period = SteadyDuration::zero();
  timer->callback = std::move(fn);

  {
    std::lock_guard<std::mutex> lock(mutex_);
    timers_[handle] = timer;
  }

  cv_.notify_one();
  return handle;
}

TimerHandle ThreadScheduler::call_every(SteadyDuration period, std::function<void()> fn) {
  auto handle = next_handle_++;
  auto timer = std::make_shared<TimerInfo>();
  timer->handle = handle;
  timer->next_fire = now() + period;
  timer->period = period;
  timer->callback = std::move(fn);

  {
    std::lock_guard<std::mutex> lock(mutex_);
    timers_[handle] = timer;
  }

  cv_.notify_one();
  return handle;
}

void ThreadScheduler::cancel(TimerHandle handle) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = timers_.find(handle);
  if (it != timers_.end()) {
    it->second->cancelled = true;
    timers_.erase(it);
  }
}

void ThreadScheduler::worker_thread() {
  while (running_) {
    std::unique_lock<std::mutex> lock(mutex_);

    // Find the next timer to fire
    std::shared_ptr<TimerInfo> next_timer;
    SteadyTimePoint earliest = SteadyTimePoint::max();

    for (auto& [handle, timer] : timers_) {
      if (!timer->cancelled && timer->next_fire < earliest) {
        earliest = timer->next_fire;
        next_timer = timer;
      }
    }

    if (!next_timer) {
      // No timers, wait indefinitely
      cv_.wait(lock, [this] { return !running_ || !timers_.empty(); });
      continue;
    }

    auto now_time = now();
    if (next_timer->next_fire <= now_time) {
      // Timer ready to fire
      auto callback = next_timer->callback;
      bool is_repeating = next_timer->period > SteadyDuration::zero();
      TimerHandle handle = next_timer->handle;

      if (is_repeating) {
        // Reschedule repeating timer
        next_timer->next_fire = now_time + next_timer->period;
      } else {
        // Remove one-shot timer
        timers_.erase(handle);
      }

      // Execute callback without holding lock
      lock.unlock();
      if (callback) {
        try {
          callback();
        } catch (...) {
          // Swallow exceptions to prevent worker thread termination
        }
      }
    } else {
      // Wait until next timer is ready
      cv_.wait_until(lock, next_timer->next_fire,
                     [this, &next_timer] {
                       return !running_ || next_timer->cancelled;
                     });
    }
  }
}

}  // namespace js8core::android
