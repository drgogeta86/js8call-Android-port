#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <thread>
#include "js8core/clock.hpp"

namespace js8core::android {

// Thread-based scheduler implementation for Android
// Uses a background thread to execute scheduled callbacks
class ThreadScheduler : public Scheduler {
public:
  ThreadScheduler();
  ~ThreadScheduler() override;

  // Disable copy/move
  ThreadScheduler(const ThreadScheduler&) = delete;
  ThreadScheduler& operator=(const ThreadScheduler&) = delete;

  SteadyTimePoint now() const override;
  TimerHandle call_after(SteadyDuration delay, std::function<void()> fn) override;
  TimerHandle call_every(SteadyDuration period, std::function<void()> fn) override;
  void cancel(TimerHandle handle) override;

private:
  struct TimerInfo {
    TimerHandle handle;
    SteadyTimePoint next_fire;
    SteadyDuration period;  // 0 for one-shot timers
    std::function<void()> callback;
    bool cancelled = false;
  };

  void worker_thread();

  std::atomic<bool> running_{true};
  std::atomic<TimerHandle> next_handle_{1};
  std::thread worker_;
  std::mutex mutex_;
  std::condition_variable cv_;
  std::map<TimerHandle, std::shared_ptr<TimerInfo>> timers_;
};

}  // namespace js8core::android
