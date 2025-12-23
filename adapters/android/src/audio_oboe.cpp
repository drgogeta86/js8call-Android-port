#include "js8core/android/audio_oboe.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <android/log.h>

#ifdef __ANDROID__

namespace js8core::android {

namespace {
// Build a simple windowed-sinc low-pass FIR for integer decimation.
std::vector<float> make_decimation_fir(int input_rate, int target_rate) {
  // Match desktop detector FIR when downsampling 48k -> 12k.
  if (input_rate == 48000 && target_rate == 12000) {
    static const float kDesktopFIR[] = {
        0.000861074040f,  0.010051920210f,  0.010161983649f,  0.011363155076f,
        0.008706594219f,  0.002613872664f, -0.005202883094f, -0.011720748164f,
        -0.013752163325f, -0.009431602741f,  0.000539063909f,  0.012636767098f,
        0.021494659597f,  0.021951235065f,  0.011564169382f, -0.007656470131f,
        -0.028965787341f, -0.042637874109f, -0.039203309748f, -0.013153301537f,
         0.034320769178f,  0.094717832646f,  0.154224604789f,  0.197758325022f,
         0.213715139513f,  0.197758325022f,  0.154224604789f,  0.094717832646f,
         0.034320769178f, -0.013153301537f, -0.039203309748f, -0.042637874109f,
        -0.028965787341f, -0.007656470131f,  0.011564169382f,  0.021951235065f,
         0.021494659597f,  0.012636767098f,  0.000539063909f, -0.009431602741f,
        -0.013752163325f, -0.011720748164f, -0.005202883094f,  0.002613872664f,
         0.008706594219f,  0.011363155076f,  0.010161983649f,  0.010051920210f,
         0.000861074040f};
    return std::vector<float>(std::begin(kDesktopFIR), std::end(kDesktopFIR));
  }

  // Cutoff near the target Nyquist
  constexpr int kNumTaps = 32;
  constexpr double kPi = 3.14159265358979323846;
  const double cutoff = (0.5 * target_rate) / static_cast<double>(input_rate);

  std::vector<float> taps(kNumTaps);
  double sum = 0.0;

  for (int i = 0; i < kNumTaps; ++i) {
    const double n = i - (kNumTaps - 1) / 2.0;
    const double sinc = (n == 0.0)
                            ? 2.0 * cutoff
                            : std::sin(2.0 * kPi * cutoff * n) /
                                  (kPi * n);
    const double window =
        0.54 - 0.46 * std::cos(2.0 * kPi * i / (kNumTaps - 1));
    taps[i] = static_cast<float>(sinc * window);
    sum += taps[i];
  }

  // Normalize unity gain at DC
  if (sum != 0.0) {
    for (auto& t : taps) t = static_cast<float>(t / sum);
  }

  return taps;
}
}  // namespace

// ============================================================================
// OboeAudioInput
// ============================================================================

OboeAudioInput::OboeAudioInput() = default;

OboeAudioInput::~OboeAudioInput() {
  stop();
}

bool OboeAudioInput::start(AudioStreamParams const& params,
                           AudioInputHandler on_frames,
                           AudioErrorHandler on_error) {
  std::lock_guard<std::mutex> lock(mutex_);

  if (stream_) {
    return false;  // Already started
  }

  params_ = params;
  on_frames_ = std::move(on_frames);
  on_error_ = std::move(on_error);

  oboe::AudioStreamBuilder builder;
  builder.setDirection(oboe::Direction::Input)
      ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
      ->setSharingMode(oboe::SharingMode::Exclusive)
      ->setSampleRate(params.format.sample_rate)
      ->setChannelCount(params.format.channels)
      ->setDataCallback(this);

  // Set format
  if (params.format.sample_type == SampleType::Int16) {
    builder.setFormat(oboe::AudioFormat::I16);
  } else if (params.format.sample_type == SampleType::Float32) {
    builder.setFormat(oboe::AudioFormat::Float);
  }

  if (params.frames_per_buffer > 0) {
    builder.setFramesPerDataCallback(static_cast<int32_t>(params.frames_per_buffer));
  }

  oboe::Result result = builder.openStream(stream_);
  if (result != oboe::Result::OK) {
    if (on_error_) {
      on_error_(std::string("Failed to open input stream: ") +
                oboe::convertToText(result));
    }
    return false;
  }

  result = stream_->requestStart();
  if (result != oboe::Result::OK) {
    if (on_error_) {
      on_error_(std::string("Failed to start input stream: ") +
                oboe::convertToText(result));
    }
    stream_.reset();
    return false;
  }

  // Prepare decimator if device overrided the requested rate
  actual_sample_rate_ = stream_->getSampleRate();
  decimation_factor_ = 1;
  fir_taps_.clear();
  fir_buffer_.clear();

  if (actual_sample_rate_ > 0 && params_.format.sample_rate > 0 &&
      actual_sample_rate_ % params_.format.sample_rate == 0) {
    decimation_factor_ = actual_sample_rate_ / params_.format.sample_rate;

    if (decimation_factor_ > 1 &&
        params_.format.sample_type == SampleType::Int16) {
      fir_taps_ =
          make_decimation_fir(actual_sample_rate_, params_.format.sample_rate);
      fir_buffer_.assign(fir_taps_.size() * 2, 0);
      fir_pos_ = 0;
      __android_log_print(ANDROID_LOG_INFO, "JS8AudioInput",
                         "Decimating audio: device_rate=%d, target_rate=%d, "
                         "factor=%d, taps=%zu",
                         actual_sample_rate_, params_.format.sample_rate,
                         decimation_factor_, fir_taps_.size());
    }
  }

  return true;
}

void OboeAudioInput::stop() {
  std::lock_guard<std::mutex> lock(mutex_);

  if (stream_) {
    stream_->stop();
    stream_->close();
    stream_.reset();
  }
}

oboe::DataCallbackResult OboeAudioInput::onAudioReady(oboe::AudioStream* stream,
                                                       void* audio_data,
                                                       int32_t num_frames) {
  std::lock_guard<std::mutex> lock(mutex_);

  if (!on_frames_ || num_frames <= 0) {
    return oboe::DataCallbackResult::Continue;
  }

  AudioInputBuffer buffer;
  buffer.format = params_.format;
  buffer.captured_at = SteadyClock::now();

  const std::size_t bytes_per_sample =
      (params_.format.sample_type == SampleType::Int16) ? sizeof(int16_t)
                                                        : sizeof(float);
  const std::size_t buffer_size = static_cast<std::size_t>(num_frames) *
                                  params_.format.channels * bytes_per_sample;

  if (decimation_factor_ > 1 && params_.format.sample_type == SampleType::Int16 &&
      !fir_taps_.empty()) {
    auto* in = static_cast<const int16_t*>(audio_data);
    const int taps = static_cast<int>(fir_taps_.size());
    const int mirror = taps;  // mirror buffer to avoid bounds checks

    std::vector<int16_t> decimated;
    decimated.reserve(static_cast<std::size_t>(num_frames / decimation_factor_ + 1));

    for (int i = 0; i < num_frames; ++i) {
      const auto sample = in[i];
      fir_buffer_[fir_pos_] = sample;
      fir_buffer_[fir_pos_ + mirror] = sample;
      fir_pos_ = (fir_pos_ + 1) % mirror;

      if ((i % decimation_factor_) == decimation_factor_ - 1) {
        double acc = 0.0;
        int read_pos = (fir_pos_ - 1 + mirror) % mirror;
        for (int j = 0; j < taps; ++j) {
          int idx = (read_pos - j + mirror) % mirror;
          acc += static_cast<double>(fir_taps_[j]) *
                 static_cast<double>(fir_buffer_[idx]);
        }
        int value = static_cast<int>(std::lrint(acc));
        value = std::clamp(value,
                           static_cast<int>(std::numeric_limits<int16_t>::min()),
                           static_cast<int>(std::numeric_limits<int16_t>::max()));
        decimated.push_back(static_cast<int16_t>(value));
      }
    }

    buffer.data = std::span<const std::byte>(
        reinterpret_cast<const std::byte*>(decimated.data()),
        decimated.size() * sizeof(int16_t));

    try {
      on_frames_(buffer);
    } catch (...) {
      // Swallow exceptions to prevent stream termination
    }
  } else {
    // No resampling needed; pass through
    buffer.data = std::span<const std::byte>(
        static_cast<const std::byte*>(audio_data), buffer_size);
    try {
      on_frames_(buffer);
    } catch (...) {
      // Swallow exceptions to prevent stream termination
    }
  }

  return oboe::DataCallbackResult::Continue;
}

// ============================================================================
// OboeAudioOutput
// ============================================================================

OboeAudioOutput::OboeAudioOutput() = default;

OboeAudioOutput::~OboeAudioOutput() {
  stop();
}

bool OboeAudioOutput::start(AudioStreamParams const& params,
                            AudioOutputFill fill,
                            AudioErrorHandler on_error) {
  std::lock_guard<std::mutex> lock(mutex_);

  if (stream_) {
    return false;  // Already started
  }

  params_ = params;
  fill_ = std::move(fill);
  on_error_ = std::move(on_error);

  oboe::AudioStreamBuilder builder;
  builder.setDirection(oboe::Direction::Output)
      ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
      ->setSharingMode(oboe::SharingMode::Exclusive)
      ->setSampleRate(params.format.sample_rate)
      ->setChannelCount(params.format.channels)
      ->setDataCallback(this);

  // Set format
  if (params.format.sample_type == SampleType::Int16) {
    builder.setFormat(oboe::AudioFormat::I16);
  } else if (params.format.sample_type == SampleType::Float32) {
    builder.setFormat(oboe::AudioFormat::Float);
  }

  if (params.frames_per_buffer > 0) {
    builder.setFramesPerDataCallback(static_cast<int32_t>(params.frames_per_buffer));
  }

  oboe::Result result = builder.openStream(stream_);
  if (result != oboe::Result::OK) {
    if (on_error_) {
      on_error_(std::string("Failed to open output stream: ") +
                oboe::convertToText(result));
    }
    return false;
  }

  result = stream_->requestStart();
  if (result != oboe::Result::OK) {
    if (on_error_) {
      on_error_(std::string("Failed to start output stream: ") +
                oboe::convertToText(result));
    }
    stream_.reset();
    return false;
  }

  return true;
}

void OboeAudioOutput::stop() {
  std::lock_guard<std::mutex> lock(mutex_);

  if (stream_) {
    stream_->stop();
    stream_->close();
    stream_.reset();
  }
}

oboe::DataCallbackResult OboeAudioOutput::onAudioReady(oboe::AudioStream* stream,
                                                        void* audio_data,
                                                        int32_t num_frames) {
  std::lock_guard<std::mutex> lock(mutex_);

  if (!fill_ || num_frames <= 0) {
    // Fill with silence
    std::memset(audio_data, 0, static_cast<std::size_t>(num_frames) *
                                   params_.format.channels *
                                   ((params_.format.sample_type == SampleType::Int16)
                                        ? sizeof(int16_t)
                                        : sizeof(float)));
    return oboe::DataCallbackResult::Continue;
  }

  // Calculate buffer size in bytes
  std::size_t bytes_per_sample = (params_.format.sample_type == SampleType::Int16)
                                     ? sizeof(int16_t)
                                     : sizeof(float);
  std::size_t buffer_size = static_cast<std::size_t>(num_frames) *
                            params_.format.channels * bytes_per_sample;

  AudioOutputBuffer buffer;
  buffer.data = std::span<std::byte>(static_cast<std::byte*>(audio_data),
                                      buffer_size);
  buffer.format = params_.format;
  buffer.playback_at = SteadyClock::now();

  try {
    std::size_t filled = fill_(buffer);
    // Fill remainder with silence if fill returned less than buffer size
    if (filled < buffer_size) {
      std::memset(static_cast<std::byte*>(audio_data) + filled, 0, buffer_size - filled);
    }
  } catch (...) {
    // Fill with silence on exception
    std::memset(audio_data, 0, buffer_size);
  }

  return oboe::DataCallbackResult::Continue;
}

}  // namespace js8core::android

#endif  // __ANDROID__
