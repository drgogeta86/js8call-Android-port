#include "js8core/qt/network_adapter.hpp"

#include <QByteArray>
#include <QDebug>
#include <QHostAddress>
#include <QLoggingCategory>
#include <QNetworkDatagram>
#include <QUdpSocket>

#include <cstring>
#include <memory>
#include <vector>

Q_DECLARE_LOGGING_CATEGORY(js8core_logger_adapter)

namespace js8core::qt {

class UdpChannelAdapter::Impl {
public:
  std::unique_ptr<QUdpSocket> socket;
  DatagramHandler on_receive;
  NetworkErrorHandler on_error;
};

void delete_impl(UdpChannelAdapter::Impl* p) { delete p; }

bool UdpChannelAdapter::bind(Endpoint const& listen_on) {
  close();
  impl_.reset(new Impl());
  impl_.get_deleter() = &delete_impl;

  impl_->socket = std::make_unique<QUdpSocket>(this);
  QHostAddress addr;
  if (listen_on.host.empty()) {
    addr = QHostAddress::AnyIPv4;
  } else {
    addr = QHostAddress(QString::fromStdString(listen_on.host));
  }

  if (!impl_->socket->bind(addr, listen_on.port)) {
    if (impl_->on_error) {
      impl_->on_error(impl_->socket->errorString().toStdString());
    }
    impl_.reset();
    return false;
  }

  connect(impl_->socket.get(), &QUdpSocket::readyRead, this, [this]() {
    if (!impl_ || !impl_->socket) return;
    while (impl_->socket->hasPendingDatagrams()) {
      QNetworkDatagram datagram = impl_->socket->receiveDatagram();
      if (!datagram.isValid()) continue;
      if (impl_->on_receive) {
        Endpoint from{datagram.senderAddress().toString().toStdString(),
                      static_cast<std::uint16_t>(datagram.senderPort())};
        auto payload = datagram.data();
        std::vector<std::byte> buf(static_cast<std::size_t>(payload.size()));
        std::memcpy(buf.data(), payload.data(), payload.size());
        impl_->on_receive(from, std::span<const std::byte>(buf.data(), buf.size()));
      }
    }
  });

  connect(impl_->socket.get(), &QUdpSocket::errorOccurred, this, [this](QUdpSocket::SocketError) {
    if (impl_ && impl_->on_error && impl_->socket) {
      impl_->on_error(impl_->socket->errorString().toStdString());
    }
  });

  return true;
}

bool UdpChannelAdapter::send(Datagram const& datagram) {
  if (!impl_ || !impl_->socket) return false;
  QHostAddress addr(QString::fromStdString(datagram.destination.host));
  auto bytes = QByteArray(reinterpret_cast<const char*>(datagram.payload.data()),
                          static_cast<int>(datagram.payload.size()));
  auto written = impl_->socket->writeDatagram(bytes, addr, datagram.destination.port);
  return written == bytes.size();
}

void UdpChannelAdapter::set_handlers(DatagramHandler on_receive, NetworkErrorHandler on_error) {
  if (!impl_) {
    impl_.reset(new Impl());
    impl_.get_deleter() = &delete_impl;
  }
  impl_->on_receive = std::move(on_receive);
  impl_->on_error = std::move(on_error);
}

void UdpChannelAdapter::close() {
  if (impl_ && impl_->socket) {
    impl_->socket->close();
  }
  impl_.reset();
}

}  // namespace js8core::qt
