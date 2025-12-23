# FFTW3 for Android

FFTW3 (Fastest Fourier Transform in the West) is required for JS8Call's DSP operations.

## Option 1: Build from Source (Recommended)

Run the build script from the `android` directory:

```bash
export ANDROID_NDK_ROOT=/path/to/android-ndk
cd android
./build-fftw3.sh
```

This will:
- Download FFTW3 3.3.10
- Build static libraries for arm64-v8a, armeabi-v7a, x86_64, x86
- Install to `android/libs/fftw3/{ABI}/`

Expected directory structure after build:
```
android/libs/fftw3/
├── arm64-v8a/
│   ├── include/
│   │   └── fftw3.h
│   └── lib/
│       └── libfftw3f.a
├── armeabi-v7a/
│   ├── include/
│   │   └── fftw3.h
│   └── lib/
│       └── libfftw3f.a
├── x86_64/
│   └── ...
└── x86/
    └── ...
```

## Option 2: Download Prebuilt Libraries

If you have prebuilt FFTW3 libraries, place them in the structure above.

You can find prebuilt Android libraries at:
- https://github.com/Const-me/fftw-android-cmake (unofficial)
- Or build yourself using the provided script

## Option 3: Use System Package (Not Recommended)

Some Android devices have FFTW3 in the system, but this is not reliable.

## Verification

After setup, verify the files are in place:

```bash
find android/libs/fftw3 -name "*.a"
```

You should see `libfftw3f.a` for each ABI.

## Build Configuration

The CMake configuration (android/CMakeLists.txt) will automatically:
1. Look for FFTW3 in `libs/fftw3/{ABI}/`
2. Create imported target `FFTW3::fftw3f`
3. Link it to the JS8 core library

## Requirements

- FFTW3 3.3.x (single-precision float)
- Built with `--enable-single` and `--enable-threads`
- Static library (`.a`) preferred for easier distribution

## Troubleshooting

### "FFTW3 not found" error

Make sure:
1. FFTW3 is built for the correct ABI
2. Files are in `android/libs/fftw3/{ABI}/lib/libfftw3f.a`
3. Header is in `android/libs/fftw3/{ABI}/include/fftw3.h`

### Build script fails

Check:
1. ANDROID_NDK_ROOT is set correctly
2. NDK version is compatible (r21+)
3. Build tools are installed (make, autoconf, etc.)

### Wrong architecture

The build script detects your host OS. If it fails, manually set:
```bash
TOOLCHAIN="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64"
# or
TOOLCHAIN="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/darwin-x86_64"
```

## License

FFTW3 is licensed under the GPL. This means:
- You can use it in your app
- If you distribute your app, you must comply with GPL terms
- Consider linking dynamically to avoid GPL requirements on your code

See http://www.fftw.org/doc/License-and-Copyright.html for details.
