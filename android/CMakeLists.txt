cmake_minimum_required(VERSION 3.22 FATAL_ERROR)

#------------------------------------------------------------------------------#
# JS8Call Android Build Configuration
#
# This CMake configuration builds the complete JS8Call stack for Android:
#   - libjs8core (platform-agnostic core)
#   - libjs8core-android-adapter (Android platform adapters)
#   - libjs8core-jni (JNI bridge for Java/Kotlin)
#
# Building:
#   From Android Studio: Configure in build.gradle
#   Standalone: Use Android NDK toolchain file
#------------------------------------------------------------------------------#

project(JS8Call-Android VERSION 2.5.0 LANGUAGES C CXX)

# Require C++20
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Android-specific settings
if(ANDROID)
  message(STATUS "Building for Android ${ANDROID_PLATFORM} (ABI: ${ANDROID_ABI})")

  # Enable exceptions and RTTI
  set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fexceptions -frtti")

  # Enable LTO for release builds
  if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON)
  endif()
else()
  message(WARNING "Not building for Android - some features may not work")
endif()

#------------------------------------------------------------------------------#
# Find dependencies
#------------------------------------------------------------------------------#

# FFTW3 (required)
# Option 1: Use prebuilt libraries (recommended)
# Option 2: Build from source (see fftw3-android directory)
if(ANDROID)
  # Look for prebuilt FFTW3 in libs directory
  set(FFTW3_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/libs/fftw3/${ANDROID_ABI}")
  set(FFTW3F_LIBRARY "${FFTW3_ROOT}/lib/libfftw3f.a")
  set(FFTW3_INCLUDE_DIR "${FFTW3_ROOT}/include")

  message(STATUS "FFTW3_ROOT: ${FFTW3_ROOT}")
  message(STATUS "FFTW3F_LIBRARY: ${FFTW3F_LIBRARY}")
  message(STATUS "FFTW3_INCLUDE_DIR: ${FFTW3_INCLUDE_DIR}")

  if(EXISTS "${FFTW3F_LIBRARY}" AND EXISTS "${FFTW3_INCLUDE_DIR}/fftw3.h")
    message(STATUS "Found FFTW3: ${FFTW3F_LIBRARY}")
    add_library(FFTW3::fftw3f STATIC IMPORTED)
    set_target_properties(FFTW3::fftw3f PROPERTIES
      IMPORTED_LOCATION "${FFTW3F_LIBRARY}"
      INTERFACE_INCLUDE_DIRECTORIES "${FFTW3_INCLUDE_DIR}"
    )
    set(FFTW3_LIBRARIES FFTW3::fftw3f)
    set(FFTW3_INCLUDE_DIRS "${FFTW3_INCLUDE_DIR}")
  else()
    message(FATAL_ERROR "FFTW3 not found at ${FFTW3_ROOT}. Please build or download prebuilt libraries to android/libs/fftw3/")
  endif()
else()
  find_package(FFTW3 REQUIRED COMPONENTS single)
endif()

# Oboe (required for Android)
if(ANDROID)
  find_package(oboe REQUIRED CONFIG)
  message(STATUS "Found Oboe: ${oboe_VERSION}")
endif()

# Boost (header-only parts)
if(ANDROID)
  # Header-only use: allow BOOST_INCLUDEDIR or BOOST_ROOT to locate headers.
  set(_BOOST_LOCAL_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/libs/boost")
  if(NOT Boost_INCLUDE_DIRS AND EXISTS "${_BOOST_LOCAL_INCLUDE}/boost/version.hpp")
    set(Boost_INCLUDE_DIRS "${_BOOST_LOCAL_INCLUDE}")
  endif()

  if(NOT Boost_INCLUDE_DIRS)
    find_path(Boost_INCLUDE_DIRS
      NAMES boost/version.hpp
      HINTS
        ${CMAKE_CURRENT_SOURCE_DIR}/libs/boost
        ENV BOOST_INCLUDEDIR
        ${BOOST_INCLUDEDIR}
      NO_CMAKE_FIND_ROOT_PATH
    )
  endif()

  if(NOT Boost_INCLUDE_DIRS)
    find_path(Boost_INCLUDE_DIRS
      NAMES boost/version.hpp
      HINTS
        ${CMAKE_CURRENT_SOURCE_DIR}/libs/boost
        ENV BOOST_ROOT
        ${BOOST_ROOT}
      PATH_SUFFIXES include
      NO_CMAKE_FIND_ROOT_PATH
    )
  endif()

  if(NOT Boost_INCLUDE_DIRS)
    message(FATAL_ERROR "Boost headers not found. Set BOOST_ROOT or BOOST_INCLUDEDIR.")
  endif()

  message(STATUS "Using Boost headers for Android from: ${Boost_INCLUDE_DIRS}")
  unset(_BOOST_LOCAL_INCLUDE)
else()
  set(Boost_USE_STATIC_LIBS ON)
  find_package(Boost 1.77 REQUIRED)
endif()

#------------------------------------------------------------------------------#
# Add subdirectories
#------------------------------------------------------------------------------#

# Core library (platform-agnostic)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../core ${CMAKE_CURRENT_BINARY_DIR}/core)

# Android adapters
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../adapters/android ${CMAKE_CURRENT_BINARY_DIR}/adapters/android)

# JNI bridge
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../adapters/android/jni ${CMAKE_CURRENT_BINARY_DIR}/jni)

#------------------------------------------------------------------------------#
# Installation
#------------------------------------------------------------------------------#

# Install libraries to jniLibs directory for Android Studio
if(ANDROID)
  install(TARGETS js8core-jni
    LIBRARY DESTINATION ${CMAKE_CURRENT_SOURCE_DIR}/js8core-lib/src/main/jniLibs/${ANDROID_ABI}
  )

  # Also install FFTW3 if it's a shared library
  if(EXISTS "${FFTW3_ROOT}/lib/libfftw3f.so")
    install(FILES "${FFTW3_ROOT}/lib/libfftw3f.so"
      DESTINATION ${CMAKE_CURRENT_SOURCE_DIR}/js8core-lib/src/main/jniLibs/${ANDROID_ABI}
    )
  endif()
endif()

#------------------------------------------------------------------------------#
# Summary
#------------------------------------------------------------------------------#

message(STATUS "")
message(STATUS "==================================================")
message(STATUS "JS8Call Android Build Configuration Summary")
message(STATUS "==================================================")
message(STATUS "  Version: ${PROJECT_VERSION}")
message(STATUS "  Build type: ${CMAKE_BUILD_TYPE}")
if(ANDROID)
  message(STATUS "  Android ABI: ${ANDROID_ABI}")
  message(STATUS "  Android Platform: ${ANDROID_PLATFORM}")
  message(STATUS "  Android STL: ${ANDROID_STL}")
endif()
message(STATUS "  C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "  FFTW3: ${FFTW3F_LIBRARY}")
if(ANDROID)
  message(STATUS "  Oboe: ${oboe_VERSION}")
endif()
message(STATUS "==================================================")
message(STATUS "")
