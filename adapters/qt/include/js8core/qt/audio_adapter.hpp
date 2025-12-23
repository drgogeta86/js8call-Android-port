#pragma once

#include <memory>

#include <QObject>

#include "js8core/audio.hpp"

namespace js8core::qt {

struct AudioInputAdapterPrivate;
struct AudioOutputAdapterPrivate;
using AudioInputAdapterDeleter = void(*)(AudioInputAdapterPrivate*);
using AudioOutputAdapterDeleter = void(*)(AudioOutputAdapterPrivate*);

class AudioInputAdapter : public QObject, public js8core::AudioInput {
  Q_OBJECT
public:
  ~AudioInputAdapter() override;
  bool start(AudioStreamParams const& params,
             AudioInputHandler on_frames,
             AudioErrorHandler on_error) override;
  void stop() override;

private:
  std::unique_ptr<AudioInputAdapterPrivate, AudioInputAdapterDeleter> d_{nullptr, [](AudioInputAdapterPrivate*){}};
};

class AudioOutputAdapter : public QObject, public js8core::AudioOutput {
  Q_OBJECT
public:
  ~AudioOutputAdapter() override;
  bool start(AudioStreamParams const& params,
             AudioOutputFill fill,
             AudioErrorHandler on_error) override;
  void stop() override;

private:
  std::unique_ptr<AudioOutputAdapterPrivate, AudioOutputAdapterDeleter> d_{nullptr, [](AudioOutputAdapterPrivate*){}};
};

}  // namespace js8core::qt
