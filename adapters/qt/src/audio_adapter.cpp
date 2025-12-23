#include "js8core/qt/audio_adapter.hpp"

#include <QAudioDevice>
#include <QAudioFormat>
#include <QAudioSink>
#include <QAudioSource>
#include <QLoggingCategory>
#include <QMediaDevices>
#include <QPointer>
#include <QScopedPointer>

#include <memory>
#include <span>
#include <vector>

Q_DECLARE_LOGGING_CATEGORY(js8core_logger_adapter)

namespace js8core::qt {
namespace {

void delete_input_private(AudioInputAdapterPrivate* p) { delete p; }
void delete_output_private(AudioOutputAdapterPrivate* p) { delete p; }

QAudioFormat to_qt_format(AudioFormat const& fmt) {
  QAudioFormat qfmt;
  qfmt.setSampleRate(fmt.sample_rate);
  qfmt.setChannelCount(fmt.channels);
  switch (fmt.sample_type) {
    case SampleType::Int16:
      qfmt.setSampleFormat(QAudioFormat::Int16);
      break;
    case SampleType::Float32:
      qfmt.setSampleFormat(QAudioFormat::Float);
      break;
    default:
      qfmt.setSampleFormat(QAudioFormat::Unknown);
      break;
  }
  return qfmt;
}

AudioFormat to_core_format(QAudioFormat const& qfmt) {
  AudioFormat fmt;
  fmt.sample_rate = qfmt.sampleRate();
  fmt.channels = qfmt.channelCount();
  fmt.sample_type = qfmt.sampleFormat() == QAudioFormat::Float ? SampleType::Float32 : SampleType::Int16;
  return fmt;
}

std::size_t bytes_per_frame(QAudioFormat const& fmt) {
  return fmt.bytesPerFrame();
}

class CallbackIODevice : public QIODevice {
public:
  CallbackIODevice(AudioOutputFill fill, AudioFormat fmt)
      : fill_(std::move(fill)), fmt_(fmt) {}

  qint64 readData(char* data, qint64 maxlen) override {
    if (!fill_) return 0;
    std::span<std::byte> span(reinterpret_cast<std::byte*>(data),
                              static_cast<std::size_t>(maxlen));
    AudioOutputBuffer buffer{span, fmt_, SteadyClock::now()};
    auto written = fill_(buffer);
    if (written > span.size()) written = span.size();
    return static_cast<qint64>(written);
  }

  qint64 writeData(const char*, qint64) override { return -1; }

private:
  AudioOutputFill fill_;
  AudioFormat fmt_;
};

}  // namespace

class AudioInputAdapterPrivate {
public:
  QScopedPointer<QAudioSource> source;
  QPointer<QIODevice> io_device;
  AudioInputHandler on_frames;
  AudioErrorHandler on_error;
  AudioFormat format;
  std::vector<std::byte> buffer;
  ~AudioInputAdapterPrivate();
};

AudioInputAdapterPrivate::~AudioInputAdapterPrivate() = default;

AudioInputAdapter::~AudioInputAdapter() = default;

bool AudioInputAdapter::start(AudioStreamParams const& params,
                              AudioInputHandler on_frames,
                              AudioErrorHandler on_error) {
  stop();

  d_.reset(new AudioInputAdapterPrivate());
  d_.get_deleter() = &delete_input_private;
  d_->on_frames = std::move(on_frames);
  d_->on_error = std::move(on_error);
  d_->format = params.format;

  QAudioDevice device = QMediaDevices::defaultAudioInput();
  QAudioFormat qfmt = to_qt_format(params.format);

  if (!qfmt.isValid() || !device.isFormatSupported(qfmt)) {
    if (d_->on_error) d_->on_error("AudioInputAdapter: unsupported audio format");
    d_.reset();
    return false;
  }

  d_->source.reset(new QAudioSource(device, qfmt));
  if (params.frames_per_buffer > 0) {
    d_->source->setBufferSize(static_cast<int>(params.frames_per_buffer * bytes_per_frame(qfmt)));
  }

  d_->io_device = d_->source->start();
  if (!d_->io_device) {
    if (d_->on_error) d_->on_error("AudioInputAdapter: failed to start audio input");
    d_.reset();
    return false;
  }

  d_->buffer.resize(static_cast<std::size_t>(d_->source->bufferSize()));

  connect(d_->source.data(), &QAudioSource::stateChanged, this, [this](QAudio::State state) {
    if (state == QAudio::StoppedState && d_ && d_->source && d_->source->error() != QAudio::NoError) {
      if (d_->on_error) d_->on_error("AudioInputAdapter: audio input stopped with error");
    }
  });

  connect(d_->io_device, &QIODevice::readyRead, this, [this, qfmt]() {
    if (!d_ || !d_->io_device) return;
    while (d_->io_device->bytesAvailable() >= static_cast<qint64>(bytes_per_frame(qfmt))) {
      qint64 avail = d_->io_device->bytesAvailable();
      if (avail > static_cast<qint64>(d_->buffer.size())) {
        d_->buffer.resize(static_cast<std::size_t>(avail));
      }
      qint64 read = d_->io_device->read(reinterpret_cast<char*>(d_->buffer.data()), avail);
  if (read > 0 && d_->on_frames) {
        std::span<const std::byte> span(d_->buffer.data(), static_cast<std::size_t>(read));
        AudioInputBuffer buf{span, to_core_format(qfmt), SteadyClock::now()};
        d_->on_frames(buf);
      } else if (read < 0 && d_->on_error) {
        d_->on_error("AudioInputAdapter: read error");
        break;
      }
    }
  });

  return true;
}

void AudioInputAdapter::stop() {
  if (!d_) return;
  if (d_->source) {
    d_->source->stop();
  }
  d_.reset();
}

class AudioOutputAdapterPrivate {
public:
  QScopedPointer<QAudioSink> sink;
  std::unique_ptr<CallbackIODevice> io_device;
  AudioErrorHandler on_error;
  AudioFormat format;
  ~AudioOutputAdapterPrivate();
};

AudioOutputAdapterPrivate::~AudioOutputAdapterPrivate() = default;

AudioOutputAdapter::~AudioOutputAdapter() = default;

bool AudioOutputAdapter::start(AudioStreamParams const& params,
                               AudioOutputFill fill,
                               AudioErrorHandler on_error) {
  stop();

  d_.reset(new AudioOutputAdapterPrivate());
  d_.get_deleter() = &delete_output_private;
  d_->on_error = std::move(on_error);
  d_->format = params.format;

  QAudioDevice device = QMediaDevices::defaultAudioOutput();
  QAudioFormat qfmt = to_qt_format(params.format);

  if (!qfmt.isValid() || !device.isFormatSupported(qfmt)) {
    if (d_->on_error) d_->on_error("AudioOutputAdapter: unsupported audio format");
    d_.reset();
    return false;
  }

  d_->sink.reset(new QAudioSink(device, qfmt));
  if (params.frames_per_buffer > 0) {
    d_->sink->setBufferSize(static_cast<int>(params.frames_per_buffer * bytes_per_frame(qfmt)));
  }

  d_->io_device = std::make_unique<CallbackIODevice>(std::move(fill), params.format);
  d_->io_device->open(QIODevice::ReadOnly);
  d_->sink->start(d_->io_device.get());

  connect(d_->sink.data(), &QAudioSink::stateChanged, this, [this]() {
    if (!d_ || !d_->sink) return;
    if (d_->sink->error() != QAudio::NoError && d_->on_error) {
      d_->on_error("AudioOutputAdapter: audio output error");
    }
  });

  return true;
}

void AudioOutputAdapter::stop() {
  if (!d_) return;
  if (d_->sink) {
    d_->sink->stop();
  }
  if (d_->io_device) {
    d_->io_device->close();
  }
  d_.reset();
}

}  // namespace js8core::qt
