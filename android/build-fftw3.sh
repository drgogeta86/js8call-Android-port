#!/bin/bash
#
# Build FFTW3 for Android
#
# This script downloads and builds FFTW3 (single-precision) for Android
# across multiple architectures.
#
# Requirements:
#   - Android NDK (set ANDROID_NDK_ROOT environment variable)
#   - wget or curl
#   - Standard build tools (make, autoconf, etc.)
#
# Usage:
#   export ANDROID_NDK_ROOT=/path/to/ndk
#   ./build-fftw3.sh
#

set -e

# Configuration
FFTW_VERSION="3.3.10"
FFTW_URL="http://www.fftw.org/fftw-${FFTW_VERSION}.tar.gz"
MIN_SDK=26

# Check for NDK
if [ -z "$ANDROID_NDK_ROOT" ]; then
    echo "Error: ANDROID_NDK_ROOT not set"
    echo "Please set it to your Android NDK path:"
    echo "  export ANDROID_NDK_ROOT=/path/to/android-ndk"
    exit 1
fi

if [ ! -d "$ANDROID_NDK_ROOT" ]; then
    echo "Error: ANDROID_NDK_ROOT directory does not exist: $ANDROID_NDK_ROOT"
    exit 1
fi

# Directories
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BUILD_DIR="${SCRIPT_DIR}/fftw3-build"
OUTPUT_DIR="${SCRIPT_DIR}/libs/fftw3"
TOOLCHAIN_DIR="${BUILD_DIR}/toolchains"

echo "==================================="
echo "Building FFTW3 ${FFTW_VERSION} for Android"
echo "==================================="
echo "NDK: ${ANDROID_NDK_ROOT}"
echo "Output: ${OUTPUT_DIR}"
echo ""

# Download FFTW3 if not already present
FFTW_TAR="fftw-${FFTW_VERSION}.tar.gz"
if [ ! -f "${BUILD_DIR}/${FFTW_TAR}" ]; then
    echo "Downloading FFTW3..."
    mkdir -p "${BUILD_DIR}"
    if command -v wget >/dev/null 2>&1; then
        wget -O "${BUILD_DIR}/${FFTW_TAR}" "${FFTW_URL}"
    elif command -v curl >/dev/null 2>&1; then
        curl -L -o "${BUILD_DIR}/${FFTW_TAR}" "${FFTW_URL}"
    else
        echo "Error: wget or curl required"
        exit 1
    fi
fi

# Extract
if [ ! -d "${BUILD_DIR}/fftw-${FFTW_VERSION}" ]; then
    echo "Extracting FFTW3..."
    tar -xzf "${BUILD_DIR}/${FFTW_TAR}" -C "${BUILD_DIR}"
fi

# Build function for a single architecture
build_fftw3_arch() {
    local ARCH=$1
    local ANDROID_ABI=$2
    local HOST=$3

    echo ""
    echo "==================================="
    echo "Building for ${ANDROID_ABI}"
    echo "==================================="

    local INSTALL_DIR="${OUTPUT_DIR}/${ANDROID_ABI}"
    local SRC_DIR="${BUILD_DIR}/fftw-${FFTW_VERSION}"
    local ARCH_BUILD_DIR="${BUILD_DIR}/build-${ANDROID_ABI}"

    # Set up toolchain
    local TOOLCHAIN="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/darwin-x86_64"
    if [ ! -d "$TOOLCHAIN" ]; then
        TOOLCHAIN="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64"
    fi

    if [ ! -d "$TOOLCHAIN" ]; then
        echo "Error: Could not find NDK toolchain"
        exit 1
    fi

    local CC="${TOOLCHAIN}/bin/${HOST}${MIN_SDK}-clang"
    local CXX="${TOOLCHAIN}/bin/${HOST}${MIN_SDK}-clang++"
    local AR="${TOOLCHAIN}/bin/llvm-ar"
    local RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"

    # Clean and create build directory
    rm -rf "${ARCH_BUILD_DIR}"
    mkdir -p "${ARCH_BUILD_DIR}"
    cp -r "${SRC_DIR}"/* "${ARCH_BUILD_DIR}/"
    cd "${ARCH_BUILD_DIR}"

    # Configure
    echo "Configuring..."
    CC="$CC" \
    CXX="$CXX" \
    AR="$AR" \
    RANLIB="$RANLIB" \
    CFLAGS="-O3 -fPIC" \
    ./configure \
        --host="${HOST}" \
        --prefix="${INSTALL_DIR}" \
        --enable-single \
        --enable-static \
        --disable-shared \
        --disable-fortran \
        --enable-threads \
        --with-combined-threads \
        || { echo "Configure failed for ${ANDROID_ABI}"; exit 1; }

    # Build
    echo "Building..."
    make -j$(nproc) || { echo "Build failed for ${ANDROID_ABI}"; exit 1; }

    # Install
    echo "Installing..."
    make install || { echo "Install failed for ${ANDROID_ABI}"; exit 1; }

    echo "✓ Built ${ANDROID_ABI}"
}

# Build for each architecture
build_fftw3_arch "arm64" "arm64-v8a" "aarch64-linux-android"
build_fftw3_arch "arm" "armeabi-v7a" "armv7a-linux-androideabi"
build_fftw3_arch "x86_64" "x86_64" "x86_64-linux-android"
build_fftw3_arch "x86" "x86" "i686-linux-android"

echo ""
echo "==================================="
echo "Build complete!"
echo "==================================="
echo "FFTW3 libraries installed to: ${OUTPUT_DIR}"
echo ""
echo "Directory structure:"
find "${OUTPUT_DIR}" -name "*.a" -o -name "*.so"
echo ""
echo "To use in your project, the CMake will automatically find these libraries."
