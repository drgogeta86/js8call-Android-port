# JS8Call backend refactor (Android-first, frontend-agnostic)

## Goals
- Isolate DSP/protocol into a reusable `libjs8core` (no Qt) to support Android and other frontends.
- Keep feature parity with desktop; allow multiple concurrent engine instances; remove global decode state.
- Make platform concerns pluggable (audio, rig control, storage, logging, networking).
- Provide thin adapters: Qt for the current UI, Android/Oboe for mobile, future CLI/headless.

## Current coupling risks (survey)
- Qt-owned app lifecycle and settings in `main.cpp`, `MainWindow`, `QSettings`, `QLockFile`.
- DSP and decoder thread tied to Qt (`JS8.hpp` `QObject`, `QThread/QSemaphore`, `QDebug`, `commons.h` globals).
- Audio path is Qt-specific (`QAudioSource/QAudioSink`, `AudioDevice` inherits `QIODevice`, fixed 48k assumptions).
- Rig control abstractions are Qt signal/slot based (`Transceiver`, Hamlib/OmniRig/Commander adapters).
- Messaging/network stack uses Qt JSON and `QUdpSocket`; data models use Qt containers/QString/QByteArray.
- Global mutable state in `dec_data`, `specData`, and FFTW mutex shared across UI and decoder.

## Target architecture
- `libjs8core` (pure C++17/20): DSP, framing, encoding/decoding, message schema, timers, scheduling.
- `platform interfaces` (no Qt): audio in/out, rig control, clock/timer, storage/config, logger, network I/O.
- `adapters`:
  - `qt-adapter`: wraps interfaces with signals/slots and uses `QAudio*`, `QUdpSocket`, `QSettings`, `QLoggingCategory`.
  - `android-adapter`: Oboe/AAudio for audio, JNI/C API bridge, Android logging, SharedPreferences/files, native/BSD sockets.
  - Future CLI/headless adapter for testing/automation.
- `frontends`: existing Qt UI, Android UI, CLI tools.

## Core interfaces (std types only)
- `AudioInput`/`AudioOutput`: configure sample rate/format, start/stop, callback-based read/write of `std::span<int16_t>`; channel selection; explicit latency/buffer sizing.
- `RigControl`: set/get frequency, split, mode, PTT; async state updates via callbacks; error/failure notifications.
- `Clock/Timer`: wall clock now, monotonic ticks, scheduled callbacks for period boundaries.
- `Storage`: key/value + small blob API with namespacing; sync and async variants.
- `Logger`: leveled logging hook (`trace/debug/info/warn/error`) that core calls; platform chooses sink.
- `NetworkSession`: UDP/TCP send/recv abstractions for MessageClient/Server; may be optional for headless use.
- `Js8Engine`: owns decoder/encoder contexts, accepts audio frames, emits decode events, produces TX waveforms, manages submodes/config, lifecycle (init/start/stop/reset).
- Data models: std types (`std::string`, `std::vector<uint8_t|float>`, `std::chrono`, `enum class`). JSON serialization via a small portable lib or hand-rolled serializer; keep existing schema.

## Threading & state
- Core uses std::thread/condition_variable; no Qt. Engine owns a context (replaces `dec_data/specData` globals) to allow multiple instances and reentrancy.
- Callbacks are user-provided executors to avoid hard-coding threading (frontends decide which thread to marshal to).
- FFTW/Eigen stay in core; guard FFTW planning with internal mutex but avoid global `fftw_mutex` exposure.

## Proposed layout
- `core/`:
  - `dsp/`: `JS8.cpp`, `JS8Submode.cpp`, `varicode.cpp`, `Modes.cpp`, `Flatten.cpp`, `Detector.cpp`, filters, utilities.
  - `protocol/`: framing, CRC, Costas arrays, message schema, submodes, metadata.
  - `engine/`: `Js8Engine`, scheduler, decode/encode orchestration, context structs (replacing `dec_data/specData`).
  - `platform/`: pure interfaces (`audio.hpp`, `rig.hpp`, `storage.hpp`, `logger.hpp`, `network.hpp`, `clock.hpp`).
  - `support/`: config/feature flags, error types, type aliases.
- `adapters/qt/`: wrappers for audio (bridging `AudioDevice`, `SoundInput/Output`), rig (Hamlib/Commander/OmniRig), storage (`QSettings`), network (`QUdpSocket`), logging.
- `adapters/android/`: Oboe audio bridge, rig bridge (Hamlib NDK or custom CAT), storage, network, logging, JNI/C API surface.
- `frontends/`: Qt app (current UI wired to qt-adapter), Android UI, CLI test harness.
- `tests/`: headless golden-vector encode/decode, fixtures, CLI smoke tests.

## Build/packaging targets (CMake)
- `libjs8core` (static): core/dsp/protocol/engine/platform interfaces; no Qt.
- `js8-qt-adapter` (static): wraps interfaces with Qt implementations.
- `js8-android-adapter` (static/so): NDK/Oboe bindings + C API/JNI bridge.
- `js8call-qt` (exe): existing UI relinked against `libjs8core` + `js8-qt-adapter`.
- `js8core-cli` (exe): headless test harness to feed audio fixtures and emit decodes.
- Android toolchain config to build `libjs8core` and JNI-friendly shim; produce `.aar`/`.so` for app module.

## Migration path
- First, carve out `libjs8core` headers/interfaces and move DSP/protocol code behind them (no behavior change).
- Add Qt adapter layer to keep current UI working; incrementally swap MainWindow interactions to adapters.
- Replace `dec_data`/`specData` with per-engine context; remove globals.
- Port MessageClient/Server to std JSON + network interface; keep Qt wrapper.
- Swap audio path to interface-driven pipeline (resampling assumptions explicit).
- Add CLI/headless tests to validate parity; then build Android adapter and JNI surface.
