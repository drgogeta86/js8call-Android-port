# JS8Call JNI Bridge

This directory contains the JNI (Java Native Interface) bridge that connects the native JS8Call engine to Android Java/Kotlin applications.

## Architecture

```
Android App (Java/Kotlin)
       ↓
JS8Engine.kt (Kotlin wrapper)
       ↓
js8_jni_methods.cpp (JNI bindings)
       ↓
js8_engine_jni.cpp (C API wrapper)
       ↓
js8core::Js8Engine (C++ engine)
       ↓
Android adapters (Oboe, BSD sockets, etc.)
```

## Components

### Native Layer (C/C++)

#### `js8_engine_jni.h`
- C API header for the JNI bridge
- Opaque handle-based interface
- Engine lifecycle, audio submission, configuration

#### `js8_engine_jni.cpp`
- Implementation of C API wrapper
- Creates and manages all adapter instances
- Marshals C++ events to Java callbacks via JNI
- Manages global JavaVM reference

#### `js8_jni_methods.cpp`
- JNI method implementations
- Maps Kotlin/Java methods to C API functions
- Handles array conversions (e.g., ShortArray to int16_t*)

### Kotlin Layer

#### `kotlin/JS8Engine.kt`
- Main Kotlin API for the engine
- Type-safe wrapper around native methods
- Resource management (AutoCloseable)
- Callback interface for events

#### `kotlin/JS8AudioHelper.kt`
- Helper class for Android audio integration
- Uses AudioRecord to capture microphone input
- Feeds audio samples to the engine
- UI thread callback adapter

### Java Layer

#### `java/JS8CallbackHandler.java`
- Java interface for callbacks
- Alternative to Kotlin for Java-only projects

## Building

### Prerequisites

1. Android NDK r25 or later
2. CMake 3.22 or later
3. Oboe library (via prefab: `com.google.oboe:oboe:1.8.0`)

### Integration in Android Studio Project

1. **Add native library to app/build.gradle.kts:**

```kotlin
android {
    externalNativeBuild {
        cmake {
            path = file("path/to/adapters/android/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
}

dependencies {
    implementation("com.google.oboe:oboe:1.8.0")
}
```

2. **Copy Kotlin/Java source files to your app:**

```
app/src/main/java/com/js8call/core/
├── JS8Engine.kt
├── JS8AudioHelper.kt
└── JS8CallbackHandler.java (optional)
```

3. **Add permissions to AndroidManifest.xml:**

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

4. **Build your project:**

```bash
./gradlew assembleDebug
```

The native library `libjs8core-jni.so` will be automatically built and packaged into your APK.

## Usage

### Basic Example

```kotlin
import com.js8call.core.JS8Engine
import com.js8call.core.JS8AudioHelper

// Create callback handler
val callbackHandler = object : JS8Engine.CallbackHandler {
    override fun onDecoded(utc: Int, snr: Int, dt: Float, freq: Float,
                          text: String, type: Int, quality: Float, mode: Int) {
        Log.i("JS8", "Decoded: $text (SNR: $snr dB)")
    }

    override fun onSpectrum(bins: FloatArray, binHz: Float,
                           powerDb: Float, peakDb: Float) {
        // Update waterfall display
    }

    override fun onDecodeStarted(submodes: Int) {}
    override fun onDecodeFinished(count: Int) {}
    override fun onError(message: String) {
        Log.e("JS8", "Error: $message")
    }
    override fun onLog(level: Int, message: String) {}
}

// Create engine
val engine = JS8Engine.create(
    sampleRateHz = 12000,
    submodes = 0xFF,
    callbackHandler = callbackHandler
)

// Start engine
engine.start()

// Set up audio capture
val audioHelper = JS8AudioHelper(engine, sampleRate = 12000)
audioHelper.startCapture()

// ... app runs, audio is captured and decoded ...

// Clean up
audioHelper.stopCapture()
engine.stop()
engine.close()
```

### UI Thread Callbacks

Native callbacks are invoked on a background thread. To update UI elements, use the `UIThreadCallbackAdapter`:

```kotlin
import android.os.Handler
import android.os.Looper
import com.js8call.core.UIThreadCallbackAdapter

val uiHandler = Handler(Looper.getMainLooper())
val uiCallbackHandler = UIThreadCallbackAdapter(uiHandler, yourCallbackHandler)

val engine = JS8Engine.create(
    sampleRateHz = 12000,
    submodes = 0xFF,
    callbackHandler = uiCallbackHandler
)
```

### Manual Audio Submission

Instead of using `JS8AudioHelper`, you can manually submit audio:

```kotlin
val samples = ShortArray(4096)
// ... fill samples from audio source ...

engine.submitAudio(samples, System.nanoTime())
```

## API Reference

### JS8Engine

#### Methods

- `create(sampleRateHz, submodes, callbackHandler)` - Create engine instance
- `start()` - Start processing
- `stop()` - Stop processing
- `submitAudio(samples, timestampNs)` - Submit audio samples
- `setFrequency(frequencyHz)` - Set operating frequency
- `setSubmodes(submodes)` - Set enabled submodes
- `isRunning()` - Check if engine is running
- `close()` - Destroy engine (required, call in onDestroy)

#### CallbackHandler Interface

- `onDecoded(...)` - Called when a message is decoded
- `onSpectrum(...)` - Called with FFT data for waterfall
- `onDecodeStarted(submodes)` - Decode cycle started
- `onDecodeFinished(count)` - Decode cycle finished
- `onError(message)` - Error occurred
- `onLog(level, message)` - Log message

### JS8AudioHelper

#### Methods

- `constructor(engine, sampleRate)` - Create helper
- `startCapture()` - Start audio capture
- `stopCapture()` - Stop audio capture
- `close()` - Release resources

## Threading Model

- **Native callbacks**: Invoked on a native worker thread
- **Audio capture**: Runs on a dedicated audio thread
- **Engine processing**: Internal worker threads managed by adapters

**Important**: Do NOT call blocking operations in callbacks. Use Handler.post() or similar to marshal work to appropriate threads.

## Memory Management

The engine uses native memory. Always call `engine.close()` when done to prevent leaks:

```kotlin
class MyActivity : AppCompatActivity() {
    private var engine: JS8Engine? = null

    override fun onDestroy() {
        super.onDestroy()
        engine?.close()
        engine = null
    }
}
```

## Permissions

Required permissions in AndroidManifest.xml:

- `RECORD_AUDIO` - Required for microphone access (runtime permission on API 23+)
- `INTERNET` - Required for network features (PSK Reporter, APRS-IS)
- `ACCESS_NETWORK_STATE` - Optional, for network status

Request `RECORD_AUDIO` at runtime:

```kotlin
if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
    != PackageManager.PERMISSION_GRANTED) {
    ActivityCompat.requestPermissions(
        this,
        arrayOf(Manifest.permission.RECORD_AUDIO),
        REQUEST_CODE
    )
}
```

## Debugging

### Logcat

Native logs are tagged with "JS8Call":

```bash
adb logcat -s JS8Call:V
```

### GDB Debugging

Use Android Studio's native debugger or attach gdb:

```bash
adb shell gdbserver :5039 --attach $(pidof com.js8call.example)
adb forward tcp:5039 tcp:5039
arm-linux-androideabi-gdb
(gdb) target remote :5039
```

## Troubleshooting

### "Library not found" error
- Ensure Oboe dependency is added to build.gradle
- Check that CMake path is correct
- Verify NDK is installed

### "Permission denied" for microphone
- Request RECORD_AUDIO permission at runtime
- Check that permission is granted in app settings

### Crashes in native code
- Check logcat for native stack traces
- Enable AddressSanitizer in build.gradle for memory errors:
  ```kotlin
  android {
      defaultConfig {
          externalNativeBuild {
              cmake {
                  arguments += "-DANDROID_STL=c++_shared"
                  cppFlags += "-fsanitize=address"
              }
          }
      }
  }
  ```

### No decodes
- Verify audio is being captured (check logcat)
- Ensure sample rate is 12000 Hz
- Check that engine is started
- Verify audio source has actual signal

## Performance Considerations

- **Sample Rate**: 12 kHz is optimal for JS8. 48 kHz requires resampling (more CPU).
- **Buffer Size**: Larger buffers reduce CPU but increase latency
- **ABIs**: arm64-v8a is faster than armeabi-v7a
- **Release Builds**: Enable optimizations in CMake

## Next Steps

1. Implement waterfall display using spectrum data
2. Add TX support (generate audio, control PTT)
3. Implement rig control (USB serial or network)
4. Add log book and QSO management
5. Implement directed messaging UI

## License

Same as parent JS8Call project.
