#pragma once

#include <QLoggingCategory>

#include "js8core/logger.hpp"

namespace js8core::qt {

class LoggerAdapter : public js8core::Logger {
public:
  explicit LoggerAdapter(QLoggingCategory& category);
  void log(LogLevel level, std::string_view message) override;

private:
  QLoggingCategory& category_;
};

}  // namespace js8core::qt
