#pragma once

#include <string>
#include "js8core/logger.hpp"

namespace js8core::android {

// Logger implementation that outputs to Android logcat
class AndroidLogger : public Logger {
public:
  explicit AndroidLogger(std::string tag = "JS8Call");
  ~AndroidLogger() override = default;

  void log(LogLevel level, std::string_view message) override;

private:
  std::string tag_;
};

}  // namespace js8core::android
