#pragma once

#include <QObject>
#include <QPointer>

#include "js8core/rig.hpp"
#include "Transceiver.hpp"

class Transceiver;

namespace js8core::qt {

class RigAdapter : public QObject, public js8core::RigControl {
  Q_OBJECT
public:
  explicit RigAdapter(Transceiver* rig = nullptr);

  void set_transceiver(Transceiver* rig);

  bool start(RigStateHandler on_state, RigErrorHandler on_error) override;
  void stop() override;
  void apply(RigState const& desired, unsigned sequence_number) override;
  void request_status(unsigned sequence_number) override;

private:
  RigState to_core_state(Transceiver::TransceiverState const& state) const;
  Transceiver::TransceiverState to_qt_state(RigState const& state) const;

  QPointer<Transceiver> rig_;
  RigStateHandler on_state_;
  RigErrorHandler on_error_;
};

}  // namespace js8core::qt
