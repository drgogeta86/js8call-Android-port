#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <thread>
#include "js8core/network.hpp"

namespace js8core::android {

// BSD socket-based UDP implementation for Android
// Uses a background thread for receiving datagrams
class BsdUdpChannel : public UdpChannel {
public:
  BsdUdpChannel();
  ~BsdUdpChannel() override;

  // Disable copy/move
  BsdUdpChannel(const BsdUdpChannel&) = delete;
  BsdUdpChannel& operator=(const BsdUdpChannel&) = delete;

  bool bind(Endpoint const& listen_on) override;
  bool send(Datagram const& datagram) override;
  void set_handlers(DatagramHandler on_receive, NetworkErrorHandler on_error) override;
  void close() override;

private:
  void receive_thread();

  int socket_fd_ = -1;
  std::atomic<bool> running_{false};
  std::thread receive_thread_;
  std::mutex handlers_mutex_;
  DatagramHandler on_receive_;
  NetworkErrorHandler on_error_;
};

}  // namespace js8core::android
