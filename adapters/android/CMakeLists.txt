cmake_minimum_required(VERSION 3.22 FATAL_ERROR)

project(js8core-android VERSION 1.0.0 LANGUAGES CXX)

# Require C++20
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Create the Android adapter library
add_library(js8core-android STATIC
  src/logger_android.cpp
  src/storage_android.cpp
  src/scheduler_android.cpp
  src/network_android.cpp
  src/audio_oboe.cpp
  src/rig_android.cpp
)

add_library(js8core::android-adapter ALIAS js8core-android)

target_compile_features(js8core-android PUBLIC cxx_std_20)

# Include directories
target_include_directories(js8core-android
  PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<INSTALL_INTERFACE:include>
  PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

# Link against js8core
target_link_libraries(js8core-android
  PUBLIC
    js8core::js8core
)

# Android-specific configuration
if(ANDROID)
  # Link against Android libraries
  target_link_libraries(js8core-android
    PUBLIC
      log          # For __android_log_write
      android      # For Android NDK APIs
  )

  # Find and link Oboe
  find_package(oboe REQUIRED CONFIG)
  target_link_libraries(js8core-android
    PUBLIC
      oboe::oboe
  )

  # Android compile definitions
  target_compile_definitions(js8core-android
    PUBLIC
      __ANDROID__
  )
endif()

# Compiler warnings
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
  target_compile_options(js8core-android PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Wno-unused-parameter
  )
endif()

# Export targets for installation (not needed for Android builds)
if(NOT ANDROID)
  install(TARGETS js8core-android
    EXPORT js8core-android-targets
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
    RUNTIME DESTINATION bin
    INCLUDES DESTINATION include
  )

  install(DIRECTORY include/
    DESTINATION include
  )

  install(EXPORT js8core-android-targets
    FILE js8core-android-targets.cmake
    NAMESPACE js8core::
    DESTINATION lib/cmake/js8core-android
  )
endif()
