#include "js8core/android/storage_android.hpp"

#include <algorithm>
#include <fstream>
#include <system_error>

namespace js8core::android {

FileStorage::FileStorage(std::filesystem::path storage_path)
    : storage_path_(std::move(storage_path)) {
  // Create storage directory if it doesn't exist
  std::error_code ec;
  std::filesystem::create_directories(storage_path_, ec);
  // Ignore errors - will fail on actual operations if path is invalid
}

bool FileStorage::is_valid_key(std::string_view key) const {
  // Prevent path traversal attacks and ensure valid filename
  if (key.empty() || key.size() > 255) {
    return false;
  }
  // Disallow path separators and other problematic characters
  return std::find_if(key.begin(), key.end(), [](char c) {
    return c == '/' || c == '\\' || c == '\0' || c == '<' || c == '>' ||
           c == ':' || c == '"' || c == '|' || c == '?' || c == '*';
  }) == key.end();
}

std::filesystem::path FileStorage::key_to_path(std::string_view key) const {
  return storage_path_ / std::string(key);
}

bool FileStorage::put(std::string_view key, std::span<const std::byte> value) {
  if (!is_valid_key(key)) {
    return false;
  }

  std::lock_guard<std::mutex> lock(mutex_);

  try {
    auto path = key_to_path(key);
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    if (!file) {
      return false;
    }

    file.write(reinterpret_cast<const char*>(value.data()),
               static_cast<std::streamsize>(value.size()));
    return file.good();
  } catch (...) {
    return false;
  }
}

bool FileStorage::get(std::string_view key, std::vector<std::byte>& out) const {
  if (!is_valid_key(key)) {
    return false;
  }

  std::lock_guard<std::mutex> lock(mutex_);

  try {
    auto path = key_to_path(key);
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) {
      return false;
    }

    auto size = file.tellg();
    if (size < 0) {
      return false;
    }

    out.resize(static_cast<std::size_t>(size));
    file.seekg(0, std::ios::beg);
    file.read(reinterpret_cast<char*>(out.data()), size);
    return file.good();
  } catch (...) {
    return false;
  }
}

bool FileStorage::erase(std::string_view key) {
  if (!is_valid_key(key)) {
    return false;
  }

  std::lock_guard<std::mutex> lock(mutex_);

  try {
    auto path = key_to_path(key);
    std::error_code ec;
    return std::filesystem::remove(path, ec);
  } catch (...) {
    return false;
  }
}

}  // namespace js8core::android
