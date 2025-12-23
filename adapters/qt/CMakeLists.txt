add_library(js8core-qt-adapter STATIC EXCLUDE_FROM_ALL
  src/logger_adapter.cpp
  src/audio_adapter.cpp
  src/rig_adapter.cpp
  src/network_adapter.cpp
  src/varicode_adapter.cpp
  src/engine_adapter.cpp
  include/js8core/qt/audio_adapter.hpp
  include/js8core/qt/rig_adapter.hpp
  include/js8core/qt/network_adapter.hpp
  include/js8core/qt/logger_adapter.hpp
  include/js8core/qt/varicode_adapter.hpp
  include/js8core/qt/engine_adapter.hpp
)

add_library(js8core::qt-adapter ALIAS js8core-qt-adapter)

target_compile_features(js8core-qt-adapter PUBLIC cxx_std_20)

target_include_directories(js8core-qt-adapter
  PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<BUILD_INTERFACE:${CMAKE_SOURCE_DIR}>
    $<BUILD_INTERFACE:${CMAKE_SOURCE_DIR}/core/include>
)

target_link_libraries(js8core-qt-adapter
  PUBLIC
    Qt::Core
    Qt::Network
    Qt::Multimedia
    Qt::Gui
)
