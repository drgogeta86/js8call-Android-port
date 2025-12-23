#include "js8core/qt/rig_adapter.hpp"

#include <QDebug>
#include <QLoggingCategory>
#include <utility>

#include "Transceiver.hpp"

Q_DECLARE_LOGGING_CATEGORY(js8core_logger_adapter)

namespace js8core::qt {

RigAdapter::RigAdapter(Transceiver* rig) : rig_(rig) {}

void RigAdapter::set_transceiver(Transceiver* rig) {
  rig_ = rig;
}

RigState RigAdapter::to_core_state(Transceiver::TransceiverState const& state) const {
  RigState out;
  out.online = state.online();
  out.rx_frequency = state.frequency();
  out.tx_frequency = state.tx_frequency();
  out.mode = static_cast<Mode>(state.mode());
  out.split = state.split() ? Split::On : Split::Off;
  out.ptt = state.ptt();
  return out;
}

Transceiver::TransceiverState RigAdapter::to_qt_state(RigState const& state) const {
  Transceiver::TransceiverState out;
  out.online(state.online);
  out.frequency(state.rx_frequency);
  out.tx_frequency(state.tx_frequency);
  out.mode(static_cast<Transceiver::MODE>(state.mode));
  out.split(state.split == Split::On);
  out.ptt(state.ptt);
  return out;
}

bool RigAdapter::start(RigStateHandler on_state, RigErrorHandler on_error) {
  on_state_ = std::move(on_state);
  on_error_ = std::move(on_error);

  if (!rig_) {
    if (on_error_) on_error_("RigAdapter: no Transceiver set");
    return false;
  }

  connect(rig_, &Transceiver::update, this,
          [this](Transceiver::TransceiverState const& state, unsigned) {
            if (on_state_) on_state_(to_core_state(state));
          });

  connect(rig_, &Transceiver::failure, this,
          [this](QString const& reason) {
            if (on_error_) on_error_(reason.toStdString());
          });

  connect(rig_, &Transceiver::finished, this,
          [this]() {
            if (on_error_) on_error_("RigAdapter: transceiver finished");
          });

  // Kick off rig start; sequence number 0 for now.
  QMetaObject::invokeMethod(rig_, [this]() { rig_->start(0); }, Qt::QueuedConnection);

  return true;
}

void RigAdapter::stop() {
  if (rig_) {
    QMetaObject::invokeMethod(rig_, &Transceiver::stop, Qt::QueuedConnection);
  }
}

void RigAdapter::apply(RigState const& desired, unsigned sequence_number) {
  if (!rig_) return;
  auto qt_state = to_qt_state(desired);
  QMetaObject::invokeMethod(rig_,
                            [this, qt_state, sequence_number]() { rig_->set(qt_state, sequence_number); },
                            Qt::QueuedConnection);
}

void RigAdapter::request_status(unsigned sequence_number) {
  Q_UNUSED(sequence_number);
  // Transceiver does not expose an explicit poll; rely on periodic updates.
}

}  // namespace js8core::qt
