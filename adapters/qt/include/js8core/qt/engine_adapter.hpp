#pragma once

#include <memory>

#include "js8core/engine.hpp"
#include "js8core/qt/audio_adapter.hpp"
#include "js8core/qt/network_adapter.hpp"
#include "js8core/qt/rig_adapter.hpp"
#include "js8core/qt/varicode_adapter.hpp"

namespace js8core::qt {

struct EngineBundle {
  std::unique_ptr<AudioInputAdapter> audio_in;
  std::unique_ptr<AudioOutputAdapter> audio_out;
  std::unique_ptr<RigAdapter> rig;
  std::unique_ptr<UdpChannelAdapter> udp;
  std::unique_ptr<Js8Engine> engine;
};

EngineBundle make_engine_bundle(Transceiver* rig, EngineCallbacks callbacks, int sample_rate_hz = 12000);

}  // namespace js8core::qt
