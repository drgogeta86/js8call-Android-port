#pragma once

#include "js8core/protocol/varicode.hpp"

namespace js8core::qt {

// Adapter that forwards to the existing Qt Varicode implementation.
struct VaricodeAdapter {
  static protocol::varicode::HuffTable default_huff_table();
  static protocol::varicode::HuffEncoded huff_encode(protocol::varicode::HuffTable const& table, std::string const& text);
  static std::string huff_decode(protocol::varicode::HuffTable const& table, std::vector<bool> const& bits);
  static std::unordered_set<std::string> huff_valid_chars(protocol::varicode::HuffTable const& table);

  static std::string escape(std::string const& text);
  static std::string unescape(std::string const& text);
  static std::string rstrip(std::string const& text);
  static std::string lstrip(std::string const& text);

  static std::string checksum16(std::string const& input);
  static bool checksum16_valid(std::string const& checksum, std::string const& input);
  static std::string checksum32(std::string const& input);
  static bool checksum32_valid(std::string const& checksum, std::string const& input);

  static std::vector<bool> bytes_to_bits(char* bitvec, int n);
  static std::vector<bool> str_to_bits(std::string const& bitvec);
  static std::string bits_to_str(std::vector<bool> const& bitvec);

  static std::vector<bool> int_to_bits(std::uint64_t value, int expected = 0);
  static std::uint64_t bits_to_int(std::vector<bool> value);
  static std::uint64_t bits_to_int(std::vector<bool>::const_iterator start, int n);
  static std::vector<bool> bits_list_to_bits(std::vector<std::vector<bool>>& list);

  static std::uint8_t unpack5bits(std::string const& value);
  static std::string pack5bits(std::uint8_t packed);
  static std::uint8_t unpack6bits(std::string const& value);
  static std::string pack6bits(std::uint8_t packed);
  static std::uint16_t unpack16bits(std::string const& value);
  static std::string pack16bits(std::uint16_t packed);
  static std::uint32_t unpack32bits(std::string const& value);
  static std::string pack32bits(std::uint32_t packed);
  static std::uint64_t unpack64bits(std::string const& value);
  static std::string pack64bits(std::uint64_t packed);
  static std::uint64_t unpack72bits(std::string const& value, std::uint8_t* pRem);
  static std::string pack72bits(std::uint64_t value, std::uint8_t rem);

  static std::uint32_t pack_alphanumeric22(std::string const& value, bool is_flag);
  static std::string unpack_alphanumeric22(std::uint32_t packed, bool* is_flag);
  static std::uint64_t pack_alphanumeric50(std::string const& value);
  static std::string unpack_alphanumeric50(std::uint64_t packed);

  static std::uint32_t pack_callsign(std::string const& value, bool* p_portable);
  static std::string unpack_callsign(std::uint32_t value, bool portable);

  static std::string deg2grid(float dlong, float dlat);
  static std::pair<float, float> grid2deg(std::string const& grid);
  static std::uint16_t pack_grid(std::string const& value);
  static std::string unpack_grid(std::uint16_t value);

  static std::uint8_t pack_num(std::string const& num, bool* ok);
  static std::uint8_t pack_pwr(std::string const& pwr, bool* ok);
  static std::uint8_t pack_cmd(std::uint8_t cmd, std::uint8_t num, bool* p_packed_num);
  static std::uint8_t unpack_cmd(std::uint8_t value, std::uint8_t* p_num);

  static bool is_snr_command(std::string const& cmd);
  static bool is_command_allowed(std::string const& cmd);
  static bool is_command_buffered(std::string const& cmd);
  static int is_command_checksummed(std::string const& cmd);
  static bool is_command_autoreply(std::string const& cmd);
  static bool is_valid_callsign(std::string const& callsign, bool* p_is_compound);
  static bool is_compound_callsign(std::string const& callsign);
  static bool is_group_allowed(std::string const& group);

  static std::string pack_heartbeat_message(std::string const& text, std::string const& callsign, int* n);
  static std::vector<std::string> unpack_heartbeat_message(std::string const& text, std::uint8_t* pType, bool* isAlt, std::uint8_t* pBits3);

  static std::string pack_compound_message(std::string const& text, int* n);
  static std::vector<std::string> unpack_compound_message(std::string const& text, std::uint8_t* pType, std::uint16_t* pNum, std::uint8_t* pBits3);

  static std::string pack_compound_frame(std::string const& callsign, std::uint8_t type, std::uint16_t num, std::uint8_t bits3);
  static std::vector<std::string> unpack_compound_frame(std::string const& text, std::uint8_t* pType, std::uint16_t* pNum, std::uint8_t* pBits3);

  static std::string pack_directed_message(std::string const& text, std::string const& mycall, std::string* pTo, bool* pToCompound, std::string* pCmd, std::string* pNum, int* n);
  static std::vector<std::string> unpack_directed_message(std::string const& text, std::uint8_t* pType);

  static std::string pack_data_message(std::string const& text, int* n);
  static std::string unpack_data_message(std::string const& text);

  static std::string pack_fast_data_message(std::string const& text, int* n);
  static std::string unpack_fast_data_message(std::string const& text);

  static std::vector<std::pair<std::string, int>> build_message_frames(std::string const& mycall,
                                                                       std::string const& mygrid,
                                                                       std::string const& selectedCall,
                                                                       std::string const& text,
                                                                       bool forceIdentify,
                                                                       bool forceData,
                                                                       int submode,
                                                                       protocol::varicode::MessageInfo* pInfo = nullptr);

  static protocol::varicode::Backend backend();
  static void register_backend() {
    protocol::varicode::set_backend(backend());
  }
};

}  // namespace js8core::qt
