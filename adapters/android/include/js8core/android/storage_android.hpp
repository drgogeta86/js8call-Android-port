#pragma once

#include <filesystem>
#include <mutex>
#include <string>
#include "js8core/storage.hpp"

namespace js8core::android {

// File-based storage implementation for Android
// Stores key-value pairs as individual files in a directory
// Thread-safe for concurrent access
class FileStorage : public Storage {
public:
  // storage_path should typically be app-specific storage directory
  // e.g., /data/data/com.example.js8call/files/storage
  explicit FileStorage(std::filesystem::path storage_path);
  ~FileStorage() override = default;

  bool put(std::string_view key, std::span<const std::byte> value) override;
  bool get(std::string_view key, std::vector<std::byte>& out) const override;
  bool erase(std::string_view key) override;

private:
  std::filesystem::path storage_path_;
  mutable std::mutex mutex_;

  std::filesystem::path key_to_path(std::string_view key) const;
  bool is_valid_key(std::string_view key) const;
};

}  // namespace js8core::android
