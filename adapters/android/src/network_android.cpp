#include "js8core/android/network_android.hpp"

#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <array>

namespace js8core::android {

BsdUdpChannel::BsdUdpChannel() = default;

BsdUdpChannel::~BsdUdpChannel() {
  close();
}

bool BsdUdpChannel::bind(Endpoint const& listen_on) {
  if (socket_fd_ >= 0) {
    // Already bound
    return false;
  }

  // Create socket
  socket_fd_ = socket(AF_INET, SOCK_DGRAM, 0);
  if (socket_fd_ < 0) {
    if (on_error_) {
      on_error_("Failed to create socket");
    }
    return false;
  }

  // Set socket to reuse address
  int reuse = 1;
  setsockopt(socket_fd_, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

  // Bind to address
  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(listen_on.port);

  if (listen_on.host.empty() || listen_on.host == "0.0.0.0") {
    addr.sin_addr.s_addr = INADDR_ANY;
  } else {
    if (inet_pton(AF_INET, listen_on.host.c_str(), &addr.sin_addr) != 1) {
      ::close(socket_fd_);
      socket_fd_ = -1;
      if (on_error_) {
        on_error_("Invalid bind address");
      }
      return false;
    }
  }

  if (::bind(socket_fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
    ::close(socket_fd_);
    socket_fd_ = -1;
    if (on_error_) {
      on_error_(std::string("Bind failed: ") + strerror(errno));
    }
    return false;
  }

  // Start receive thread
  running_ = true;
  receive_thread_ = std::thread([this] { receive_thread(); });

  return true;
}

bool BsdUdpChannel::send(Datagram const& datagram) {
  if (socket_fd_ < 0) {
    return false;
  }

  // Resolve destination address
  addrinfo hints{};
  hints.ai_family = AF_INET;
  hints.ai_socktype = SOCK_DGRAM;

  addrinfo* result = nullptr;
  std::string port_str = std::to_string(datagram.destination.port);
  int err = getaddrinfo(datagram.destination.host.c_str(), port_str.c_str(),
                        &hints, &result);
  if (err != 0) {
    if (on_error_) {
      on_error_(std::string("Failed to resolve address: ") + gai_strerror(err));
    }
    return false;
  }

  bool success = false;
  if (result && result->ai_addr) {
    ssize_t sent = sendto(socket_fd_,
                          datagram.payload.data(),
                          datagram.payload.size(),
                          0,
                          result->ai_addr,
                          result->ai_addrlen);
    success = (sent == static_cast<ssize_t>(datagram.payload.size()));

    if (!success && on_error_) {
      on_error_(std::string("Send failed: ") + strerror(errno));
    }
  }

  freeaddrinfo(result);
  return success;
}

void BsdUdpChannel::set_handlers(DatagramHandler on_receive,
                                  NetworkErrorHandler on_error) {
  std::lock_guard<std::mutex> lock(handlers_mutex_);
  on_receive_ = std::move(on_receive);
  on_error_ = std::move(on_error);
}

void BsdUdpChannel::close() {
  if (socket_fd_ < 0) {
    return;
  }

  running_ = false;

  // Close socket to unblock receive thread
  int fd = socket_fd_;
  socket_fd_ = -1;
  ::close(fd);

  if (receive_thread_.joinable()) {
    receive_thread_.join();
  }
}

void BsdUdpChannel::receive_thread() {
  std::array<std::byte, 65536> buffer;

  while (running_) {
    sockaddr_in from_addr{};
    socklen_t from_len = sizeof(from_addr);

    ssize_t received = recvfrom(socket_fd_,
                                buffer.data(),
                                buffer.size(),
                                0,
                                reinterpret_cast<sockaddr*>(&from_addr),
                                &from_len);

    if (received < 0) {
      if (!running_) {
        // Socket closed, exit gracefully
        break;
      }
      std::lock_guard<std::mutex> lock(handlers_mutex_);
      if (on_error_) {
        on_error_(std::string("Receive failed: ") + strerror(errno));
      }
      continue;
    }

    // Convert address to string
    std::array<char, INET_ADDRSTRLEN> addr_str;
    inet_ntop(AF_INET, &from_addr.sin_addr, addr_str.data(), addr_str.size());

    Endpoint from;
    from.host = addr_str.data();
    from.port = ntohs(from_addr.sin_port);

    // Call handler
    std::lock_guard<std::mutex> lock(handlers_mutex_);
    if (on_receive_) {
      try {
        std::span<const std::byte> payload(buffer.data(),
                                           static_cast<std::size_t>(received));
        on_receive_(from, payload);
      } catch (...) {
        // Swallow exceptions from handler
      }
    }
  }
}

}  // namespace js8core::android
