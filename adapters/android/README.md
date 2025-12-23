# JS8Call Android Adapters

This directory contains Android-specific implementations of the `libjs8core` platform interfaces.

## Overview

The Android adapters provide native implementations of all core platform interfaces:

- **Audio**: Oboe-based low-latency audio input/output
- **Storage**: File-based key-value storage
- **Logger**: Android logcat integration
- **Network**: BSD socket-based UDP networking
- **Scheduler**: Thread-based timer/scheduler
- **Rig Control**: Stub implementations (null, network, USB)

## Architecture

```
js8core (platform-agnostic core)
    ↓
android-adapter (this library)
    ↓
Android app (via JNI)
```

## Components

### Audio (Oboe)

- **OboeAudioInput**: Captures audio using Oboe library
- **OboeAudioOutput**: Plays audio using Oboe library
- Features:
  - Low-latency audio path
  - Supports Int16 and Float32 sample formats
  - Configurable sample rates and channel counts
  - Callback-based audio processing

### Storage

- **FileStorage**: Key-value storage using filesystem
- Features:
  - Thread-safe operations
  - Stores data in app-specific directory
  - Protection against path traversal attacks

### Logger

- **AndroidLogger**: Outputs to Android logcat
- Features:
  - Maps LogLevel to Android log priorities
  - Configurable log tag
  - Fallback to stderr for non-Android builds

### Network

- **BsdUdpChannel**: UDP networking using BSD sockets
- Features:
  - Background thread for receiving datagrams
  - IPv4 support
  - Address resolution using getaddrinfo

### Scheduler

- **ThreadScheduler**: Timer/scheduler using std::thread
- Features:
  - One-shot and repeating timers
  - Background worker thread
  - Cancellable timers

### Rig Control

- **NullRigControl**: No-op rig (always reports offline)
- **NetworkRigControl**: Network-based rig control (stub)
- **UsbRigControl**: USB serial rig control (stub, requires JNI)

## Building

### Prerequisites

- Android NDK r25 or later
- CMake 3.22 or later
- Oboe library (via prefab or source)

### Building for Android

```bash
# From Android Studio project, configure in build.gradle:
android {
    externalNativeBuild {
        cmake {
            path "../../adapters/android/CMakeLists.txt"
        }
    }
}

# Or build standalone:
mkdir build-android
cd build-android
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26
make
```

### Building for Desktop (Testing)

```bash
# The adapters can be built on desktop for testing
# (audio and some Android-specific features will be stubs)
mkdir build
cd build
cmake ..
make
```

## Usage Example

```cpp
#include <js8core/engine.hpp>
#include <js8core/android/audio_oboe.hpp>
#include <js8core/android/logger_android.hpp>
#include <js8core/android/storage_android.hpp>
#include <js8core/android/scheduler_android.hpp>
#include <js8core/android/network_android.hpp>
#include <js8core/android/rig_android.hpp>

using namespace js8core;
using namespace js8core::android;

// Create adapters
auto logger = std::make_unique<AndroidLogger>("JS8Call");
auto storage = std::make_unique<FileStorage>("/data/data/app/files/storage");
auto scheduler = std::make_unique<ThreadScheduler>();
auto audio_in = std::make_unique<OboeAudioInput>();
auto audio_out = std::make_unique<OboeAudioOutput>();
auto udp = std::make_unique<BsdUdpChannel>();
auto rig = std::make_unique<NullRigControl>();

// Configure engine
EngineConfig config;
config.sample_rate_hz = 12000;
config.submodes = /* ... */;

EngineCallbacks callbacks;
callbacks.on_event = [](events::Variant const& event) {
    // Handle decode events, spectrum updates, etc.
};
callbacks.on_error = [](std::string_view msg) {
    // Handle errors
};
callbacks.on_log = [](LogLevel level, std::string_view msg) {
    // Handle log messages
};

EngineDependencies deps;
deps.audio_in = audio_in.get();
deps.audio_out = audio_out.get();
deps.rig = rig.get();
deps.scheduler = scheduler.get();
deps.storage = storage.get();
deps.logger = logger.get();
deps.udp = udp.get();

// Create and start engine
auto engine = make_engine(config, std::move(callbacks), deps);
engine->start();

// Engine is now running and will call callbacks
```

## Next Steps

To complete the Android port:

1. **JNI Bridge** (Phase 2):
   - Create C API wrapper around engine
   - Implement JNI bindings for Java/Kotlin
   - Handle event marshaling to Java callbacks

2. **Android App** (Phase 4):
   - Create Android UI using Jetpack Compose or XML Views
   - Implement waterfall display
   - Add message list and composition UI

3. **USB Rig Control**:
   - Implement JNI bridge to Android USB Host API
   - Add CAT protocol support
   - Handle USB permissions and lifecycle

4. **Permissions & Lifecycle** (Phase 5):
   - Handle Android runtime permissions
   - Implement foreground service for background operation
   - Manage audio focus and interruptions

## Dependencies

- **libjs8core**: Core JS8 protocol and DSP library
- **Oboe**: Google's high-performance audio library for Android
- **Android NDK**: Native development kit for C++ on Android

## License

Same as parent JS8Call project.
