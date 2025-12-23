#pragma once

#include <functional>
#include <memory>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

#include "js8core/audio.hpp"
#include "js8core/clock.hpp"
#include "js8core/protocol/varicode.hpp"
#include "js8core/logger.hpp"
#include "js8core/network.hpp"
#include "js8core/rig.hpp"
#include "js8core/storage.hpp"
#include "js8core/types.hpp"
#include "js8core/protocol/varicode.hpp"

namespace js8core {

namespace events {

struct DecodeStarted {
  int submodes = 0;
};

struct SyncStart {
  int position = 0;
  int size = 0;
};

struct SyncState {
  enum class Kind { Candidate, Decoded } kind = Kind::Candidate;
  int mode = 0;
  float frequency = 0.0f;
  float dt = 0.0f;
  union {
    int candidate;
    float decoded;
  } sync {0};
};

struct Decoded {
  int utc = 0;
  int snr = 0;
  float xdt = 0.0f;
  float frequency = 0.0f;
  std::string data;
  int type = 0;
  float quality = 0.0f;
  int mode = 0;
};

struct DecodeFinished {
  std::size_t decoded = 0;
};

struct Spectrum {
  std::vector<float> bins;
  float bin_hz = 0.0f;
  float power_db = 0.0f;
  float peak_db = 0.0f;
};

using Variant = std::variant<DecodeStarted, SyncStart, SyncState, Decoded, DecodeFinished, Spectrum>;

}  // namespace events

struct EngineConfig {
  int sample_rate_hz = 0;
  int submodes = 0;
};

struct EngineCallbacks {
  std::function<void(events::Variant const&)> on_event;
  std::function<void(std::string_view message)> on_error;
  std::function<void(LogLevel level, std::string_view message)> on_log;
};

struct EngineDependencies {
  AudioInput* audio_in = nullptr;
  AudioOutput* audio_out = nullptr;
  RigControl* rig = nullptr;
  Scheduler* scheduler = nullptr;
  Storage* storage = nullptr;
  Logger* logger = nullptr;
  UdpChannel* udp = nullptr;
};

class Js8Engine {
public:
  virtual ~Js8Engine() = default;

  virtual bool start() = 0;
  virtual void stop() = 0;

  virtual bool submit_capture(AudioInputBuffer const& buffer) = 0;
};

std::unique_ptr<Js8Engine> make_engine(EngineConfig const& config,
                                       EngineCallbacks callbacks,
                                       EngineDependencies deps);

}  // namespace js8core
