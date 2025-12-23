#include "js8core/qt/logger_adapter.hpp"

#include <QString>

Q_LOGGING_CATEGORY(js8core_logger_adapter, "js8core.adapter.logger", QtWarningMsg)

namespace js8core::qt {

LoggerAdapter::LoggerAdapter(QLoggingCategory& category)
  : category_(category) {}

void LoggerAdapter::log(LogLevel level, std::string_view message) {
  auto qmsg = QString::fromUtf8(message.data(), static_cast<int>(message.size()));
  switch (level) {
    case LogLevel::Trace:
    case LogLevel::Debug:
      qCDebug(category_) << qmsg;
      break;
    case LogLevel::Info:
      qCInfo(category_) << qmsg;
      break;
    case LogLevel::Warn:
      qCWarning(category_) << qmsg;
      break;
    case LogLevel::Error:
      qCCritical(category_) << qmsg;
      break;
  }
}

}  // namespace js8core::qt
