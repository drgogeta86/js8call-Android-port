#include "js8core/android/logger_android.hpp"

#ifdef __ANDROID__
#include <android/log.h>
#else
// Fallback for non-Android builds (testing on desktop)
#include <iostream>
#endif

namespace js8core::android {

AndroidLogger::AndroidLogger(std::string tag)
    : tag_(std::move(tag)) {}

void AndroidLogger::log(LogLevel level, std::string_view message) {
#ifdef __ANDROID__
  android_LogPriority priority;
  switch (level) {
    case LogLevel::Trace:
      priority = ANDROID_LOG_VERBOSE;
      break;
    case LogLevel::Debug:
      priority = ANDROID_LOG_DEBUG;
      break;
    case LogLevel::Info:
      priority = ANDROID_LOG_INFO;
      break;
    case LogLevel::Warn:
      priority = ANDROID_LOG_WARN;
      break;
    case LogLevel::Error:
      priority = ANDROID_LOG_ERROR;
      break;
    default:
      priority = ANDROID_LOG_INFO;
      break;
  }

  // __android_log_write expects null-terminated string
  // Convert string_view to string to ensure null termination
  std::string msg_str(message);
  __android_log_write(priority, tag_.c_str(), msg_str.c_str());
#else
  // Fallback for desktop testing
  const char* level_str = "INFO";
  switch (level) {
    case LogLevel::Trace:   level_str = "TRACE"; break;
    case LogLevel::Debug:   level_str = "DEBUG"; break;
    case LogLevel::Info:    level_str = "INFO"; break;
    case LogLevel::Warn:    level_str = "WARN"; break;
    case LogLevel::Error:   level_str = "ERROR"; break;
  }
  std::cerr << "[" << tag_ << "] " << level_str << ": " << message << "\n";
#endif
}

}  // namespace js8core::android
