#pragma once

#include <memory>

#include <QObject>

#include "js8core/network.hpp"

namespace js8core::qt {

class UdpChannelAdapter : public QObject, public js8core::UdpChannel {
  Q_OBJECT
public:
  bool bind(Endpoint const& listen_on) override;
  bool send(Datagram const& datagram) override;
  void set_handlers(DatagramHandler on_receive, NetworkErrorHandler on_error) override;
  void close() override;

private:
  class Impl;
  friend void delete_impl(Impl*);
  using ImplDeleter = void(*)(Impl*);
  std::unique_ptr<Impl, ImplDeleter> impl_{nullptr, nullptr};
};

}  // namespace js8core::qt
