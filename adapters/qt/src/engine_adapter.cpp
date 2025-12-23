#include "js8core/qt/engine_adapter.hpp"

#include <memory>

#include "js8core/engine.hpp"
#include "js8core/protocol/varicode.hpp"
#include "js8core/qt/varicode_adapter.hpp"

namespace js8core::qt {

EngineBundle make_engine_bundle(Transceiver* rig, EngineCallbacks callbacks, int sample_rate_hz) {
  VaricodeAdapter::register_backend();

  EngineBundle bundle;
  bundle.audio_in = std::make_unique<AudioInputAdapter>();
  bundle.audio_out = std::make_unique<AudioOutputAdapter>();
  if (rig) {
    bundle.rig = std::make_unique<RigAdapter>(rig);
  }
  bundle.udp = std::make_unique<UdpChannelAdapter>();

  EngineConfig cfg;
  cfg.sample_rate_hz = sample_rate_hz;

  EngineDependencies deps;
  deps.audio_in = bundle.audio_in.get();
  deps.audio_out = bundle.audio_out.get();
  deps.rig = bundle.rig ? bundle.rig.get() : nullptr;
  deps.udp = bundle.udp.get();

  bundle.engine = make_engine(cfg, std::move(callbacks), deps);
  return bundle;
}

}  // namespace js8core::qt
