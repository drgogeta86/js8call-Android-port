#include "js8core/android/rig_android.hpp"

#include <chrono>
#include <thread>

namespace js8core::android {

// ============================================================================
// NullRigControl
// ============================================================================

bool NullRigControl::start(RigStateHandler on_state, RigErrorHandler on_error) {
  std::lock_guard<std::mutex> lock(mutex_);
  on_state_ = std::move(on_state);
  on_error_ = std::move(on_error);

  // Report offline state immediately
  if (on_state_) {
    RigState state;
    state.online = false;
    on_state_(state);
  }

  return true;
}

void NullRigControl::stop() {
  std::lock_guard<std::mutex> lock(mutex_);
  on_state_ = nullptr;
  on_error_ = nullptr;
}

void NullRigControl::apply(RigState const& /*desired*/, unsigned /*sequence_number*/) {
  // No-op for null rig
}

void NullRigControl::request_status(unsigned /*sequence_number*/) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (on_state_) {
    RigState state;
    state.online = false;
    on_state_(state);
  }
}

// ============================================================================
// NetworkRigControl
// ============================================================================

NetworkRigControl::NetworkRigControl(std::string host, uint16_t port)
    : host_(std::move(host)), port_(port) {
  current_state_.online = false;
}

NetworkRigControl::~NetworkRigControl() {
  stop();
}

bool NetworkRigControl::start(RigStateHandler on_state, RigErrorHandler on_error) {
  std::lock_guard<std::mutex> lock(mutex_);

  if (running_) {
    return false;  // Already running
  }

  on_state_ = std::move(on_state);
  on_error_ = std::move(on_error);

  running_ = true;
  worker_ = std::thread([this] { worker_thread(); });

  return true;
}

void NetworkRigControl::stop() {
  running_ = false;
  if (worker_.joinable()) {
    worker_.join();
  }

  std::lock_guard<std::mutex> lock(mutex_);
  on_state_ = nullptr;
  on_error_ = nullptr;
}

void NetworkRigControl::apply(RigState const& desired, unsigned /*sequence_number*/) {
  std::lock_guard<std::mutex> lock(mutex_);
  // TODO: Implement network protocol to send commands to rig control software
  // For now, just update local state
  current_state_ = desired;
  current_state_.online = false;  // Not actually connected yet
}

void NetworkRigControl::request_status(unsigned /*sequence_number*/) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (on_state_) {
    on_state_(current_state_);
  }
}

void NetworkRigControl::worker_thread() {
  // TODO: Implement network connection to rig control software
  // This would involve:
  // 1. Connect to host_:port_
  // 2. Send/receive commands using protocol (FlRig XML-RPC, rigctld text, etc.)
  // 3. Parse responses and update current_state_
  // 4. Call on_state_ callback with updates

  while (running_) {
    std::this_thread::sleep_for(std::chrono::seconds(1));

    // For now, just report offline state periodically
    std::lock_guard<std::mutex> lock(mutex_);
    if (on_state_) {
      RigState state;
      state.online = false;
      on_state_(state);
    }
  }
}

// ============================================================================
// UsbRigControl
// ============================================================================

bool UsbRigControl::start(RigStateHandler on_state, RigErrorHandler on_error) {
  std::lock_guard<std::mutex> lock(mutex_);
  on_state_ = std::move(on_state);
  on_error_ = std::move(on_error);

  // USB serial requires JNI integration with Android USB Host API
  if (on_error_) {
    on_error_("USB rig control not yet implemented - requires JNI integration");
  }

  // Report offline state
  if (on_state_) {
    RigState state;
    state.online = false;
    on_state_(state);
  }

  return true;
}

void UsbRigControl::stop() {
  std::lock_guard<std::mutex> lock(mutex_);
  on_state_ = nullptr;
  on_error_ = nullptr;
}

void UsbRigControl::apply(RigState const& /*desired*/, unsigned /*sequence_number*/) {
  // No-op until USB implementation is complete
}

void UsbRigControl::request_status(unsigned /*sequence_number*/) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (on_state_) {
    RigState state;
    state.online = false;
    on_state_(state);
  }
}

}  // namespace js8core::android
