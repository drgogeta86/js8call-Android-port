#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include "js8core/rig.hpp"

namespace js8core::android {

// Null rig control implementation (no rig connected)
// Always reports offline state
class NullRigControl : public RigControl {
public:
  NullRigControl() = default;
  ~NullRigControl() override = default;

  bool start(RigStateHandler on_state, RigErrorHandler on_error) override;
  void stop() override;
  void apply(RigState const& desired, unsigned sequence_number) override;
  void request_status(unsigned sequence_number) override;

private:
  RigStateHandler on_state_;
  RigErrorHandler on_error_;
  std::mutex mutex_;
};

// Network-based rig control (e.g., FlRig, rigctld over TCP)
// This is a placeholder for future implementation
class NetworkRigControl : public RigControl {
public:
  explicit NetworkRigControl(std::string host, uint16_t port);
  ~NetworkRigControl() override;

  bool start(RigStateHandler on_state, RigErrorHandler on_error) override;
  void stop() override;
  void apply(RigState const& desired, unsigned sequence_number) override;
  void request_status(unsigned sequence_number) override;

private:
  void worker_thread();

  std::string host_;
  uint16_t port_;
  std::atomic<bool> running_{false};
  std::thread worker_;
  RigStateHandler on_state_;
  RigErrorHandler on_error_;
  RigState current_state_;
  std::mutex mutex_;
};

// USB serial rig control (via Android USB Host API)
// This will require JNI integration with Android Java layer
// For now, this is a stub that reports an error
class UsbRigControl : public RigControl {
public:
  UsbRigControl() = default;
  ~UsbRigControl() override = default;

  bool start(RigStateHandler on_state, RigErrorHandler on_error) override;
  void stop() override;
  void apply(RigState const& desired, unsigned sequence_number) override;
  void request_status(unsigned sequence_number) override;

private:
  RigStateHandler on_state_;
  RigErrorHandler on_error_;
  std::mutex mutex_;
};

}  // namespace js8core::android
