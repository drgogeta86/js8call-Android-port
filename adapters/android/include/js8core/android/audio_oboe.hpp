#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>
#include "js8core/audio.hpp"

#ifdef __ANDROID__
#include <oboe/Oboe.h>
#endif

namespace js8core::android {

#ifdef __ANDROID__

// Oboe-based audio input implementation for Android
class OboeAudioInput : public AudioInput, public oboe::AudioStreamDataCallback {
public:
  OboeAudioInput();
  ~OboeAudioInput() override;

  // AudioInput interface
  bool start(AudioStreamParams const& params,
             AudioInputHandler on_frames,
             AudioErrorHandler on_error) override;
  void stop() override;

  // Oboe callback
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                        void* audio_data,
                                        int32_t num_frames) override;

private:
  std::shared_ptr<oboe::AudioStream> stream_;
  AudioStreamParams params_;
  AudioInputHandler on_frames_;
  AudioErrorHandler on_error_;
  std::mutex mutex_;

  // Resampling state (device may force 48kHz; engine expects 12kHz)
  int actual_sample_rate_ = 0;
  int decimation_factor_ = 1;
  std::vector<float> fir_taps_;
  std::vector<int16_t> fir_buffer_;
  int fir_pos_ = 0;
};

// Oboe-based audio output implementation for Android
class OboeAudioOutput : public AudioOutput, public oboe::AudioStreamDataCallback {
public:
  OboeAudioOutput();
  ~OboeAudioOutput() override;

  void set_device_id(int device_id);

  // AudioOutput interface
  bool start(AudioStreamParams const& params,
             AudioOutputFill fill,
             AudioErrorHandler on_error) override;
  void stop() override;

  // Oboe callback
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                        void* audio_data,
                                        int32_t num_frames) override;

private:
  std::shared_ptr<oboe::AudioStream> stream_;
  AudioStreamParams params_;
  AudioOutputFill fill_;
  AudioErrorHandler on_error_;
  std::mutex mutex_;
  int device_id_ = 0;
};

#else

// Stub implementations for non-Android builds
class OboeAudioInput : public AudioInput {
public:
  bool start(AudioStreamParams const&, AudioInputHandler, AudioErrorHandler) override {
    return false;
  }
  void stop() override {}
};

class OboeAudioOutput : public AudioOutput {
public:
  void set_device_id(int) {}
  bool start(AudioStreamParams const&, AudioOutputFill, AudioErrorHandler) override {
    return false;
  }
  void stop() override {}
};

#endif  // __ANDROID__

}  // namespace js8core::android
