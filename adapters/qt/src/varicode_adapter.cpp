#include "js8core/qt/varicode_adapter.hpp"

#include <QString>
#include <QVector>

#include "varicode.h"

namespace {

QString to_qstr(std::string const& s) { return QString::fromStdString(s); }
std::string to_std(QString const& s) { return s.toStdString(); }

std::vector<bool> to_std(QVector<bool> const& v) {
  std::vector<bool> out;
  out.reserve(v.size());
  for (bool b : v) out.push_back(b);
  return out;
}

QVector<bool> to_qvec(std::vector<bool> const& v) {
  QVector<bool> out;
  out.reserve(static_cast<int>(v.size()));
  for (bool b : v) out.append(b);
  return out;
}

}  // namespace

namespace js8core::qt {

protocol::varicode::HuffTable VaricodeAdapter::default_huff_table() {
  auto qt = ::Varicode::defaultHuffTable();
  protocol::varicode::HuffTable out;
  for (auto it = qt.begin(); it != qt.end(); ++it) {
    out.emplace(it.key().toStdString(), it.value().toStdString());
  }
  return out;
}

protocol::varicode::HuffEncoded VaricodeAdapter::huff_encode(protocol::varicode::HuffTable const& table, std::string const& text) {
  QMap<QString, QString> qt;
  for (auto const& [k, v] : table) qt.insert(QString::fromStdString(k), QString::fromStdString(v));
  auto encoded = ::Varicode::huffEncode(qt, QString::fromStdString(text));
  protocol::varicode::HuffEncoded out;
  out.reserve(encoded.size());
  for (auto const& pair : encoded) {
    out.emplace_back(pair.first, to_std(pair.second));
  }
  return out;
}

std::string VaricodeAdapter::huff_decode(protocol::varicode::HuffTable const& table, std::vector<bool> const& bits) {
  QMap<QString, QString> qt;
  for (auto const& [k, v] : table) qt.insert(QString::fromStdString(k), QString::fromStdString(v));
  auto s = ::Varicode::huffDecode(qt, to_qvec(bits));
  return to_std(s);
}

std::unordered_set<std::string> VaricodeAdapter::huff_valid_chars(protocol::varicode::HuffTable const& table) {
  QMap<QString, QString> qt;
  for (auto const& [k, v] : table) qt.insert(QString::fromStdString(k), QString::fromStdString(v));
  auto set = ::Varicode::huffValidChars(qt);
  std::unordered_set<std::string> out;
  out.reserve(set.size());
  for (auto const& s : set) out.insert(s.toStdString());
  return out;
}

std::string VaricodeAdapter::escape(std::string const& text) { return to_std(::Varicode::escape(to_qstr(text))); }
std::string VaricodeAdapter::unescape(std::string const& text) { return to_std(::Varicode::unescape(to_qstr(text))); }
std::string VaricodeAdapter::rstrip(std::string const& text) { return to_std(::Varicode::rstrip(to_qstr(text))); }
std::string VaricodeAdapter::lstrip(std::string const& text) { return to_std(::Varicode::lstrip(to_qstr(text))); }

std::string VaricodeAdapter::checksum16(std::string const& input) { return to_std(::Varicode::checksum16(to_qstr(input))); }
bool VaricodeAdapter::checksum16_valid(std::string const& checksum, std::string const& input) { return ::Varicode::checksum16Valid(to_qstr(checksum), to_qstr(input)); }
std::string VaricodeAdapter::checksum32(std::string const& input) { return to_std(::Varicode::checksum32(to_qstr(input))); }
bool VaricodeAdapter::checksum32_valid(std::string const& checksum, std::string const& input) { return ::Varicode::checksum32Valid(to_qstr(checksum), to_qstr(input)); }

std::vector<bool> VaricodeAdapter::bytes_to_bits(char* bitvec, int n) { return to_std(::Varicode::bytesToBits(bitvec, n)); }
std::vector<bool> VaricodeAdapter::str_to_bits(std::string const& bitvec) { return to_std(::Varicode::strToBits(to_qstr(bitvec))); }
std::string VaricodeAdapter::bits_to_str(std::vector<bool> const& bitvec) { return to_std(::Varicode::bitsToStr(to_qvec(bitvec))); }

std::vector<bool> VaricodeAdapter::int_to_bits(std::uint64_t value, int expected) { return to_std(::Varicode::intToBits(value, expected)); }
std::uint64_t VaricodeAdapter::bits_to_int(std::vector<bool> value) { return ::Varicode::bitsToInt(to_qvec(value)); }
std::uint64_t VaricodeAdapter::bits_to_int(std::vector<bool>::const_iterator start, int n) {
  std::vector<bool> slice(start, start + n);
  return ::Varicode::bitsToInt(to_qvec(slice).constBegin(), n);
}
std::vector<bool> VaricodeAdapter::bits_list_to_bits(std::vector<std::vector<bool>>& list) {
  QList<QVector<bool>> qlist;
  qlist.reserve(static_cast<int>(list.size()));
  for (auto const& v : list) qlist.append(to_qvec(v));
  auto out = ::Varicode::bitsListToBits(qlist);
  return to_std(out);
}

std::uint8_t VaricodeAdapter::unpack5bits(std::string const& value) { return ::Varicode::unpack5bits(to_qstr(value)); }
std::string VaricodeAdapter::pack5bits(std::uint8_t packed) { return to_std(::Varicode::pack5bits(packed)); }
std::uint8_t VaricodeAdapter::unpack6bits(std::string const& value) { return ::Varicode::unpack6bits(to_qstr(value)); }
std::string VaricodeAdapter::pack6bits(std::uint8_t packed) { return to_std(::Varicode::pack6bits(packed)); }
std::uint16_t VaricodeAdapter::unpack16bits(std::string const& value) { return ::Varicode::unpack16bits(to_qstr(value)); }
std::string VaricodeAdapter::pack16bits(std::uint16_t packed) { return to_std(::Varicode::pack16bits(packed)); }
std::uint32_t VaricodeAdapter::unpack32bits(std::string const& value) { return ::Varicode::unpack32bits(to_qstr(value)); }
std::string VaricodeAdapter::pack32bits(std::uint32_t packed) { return to_std(::Varicode::pack32bits(packed)); }
std::uint64_t VaricodeAdapter::unpack64bits(std::string const& value) { return ::Varicode::unpack64bits(to_qstr(value)); }
std::string VaricodeAdapter::pack64bits(std::uint64_t packed) { return to_std(::Varicode::pack64bits(packed)); }
std::uint64_t VaricodeAdapter::unpack72bits(std::string const& value, std::uint8_t* pRem) { return ::Varicode::unpack72bits(to_qstr(value), reinterpret_cast<quint8*>(pRem)); }
std::string VaricodeAdapter::pack72bits(std::uint64_t value, std::uint8_t rem) { return to_std(::Varicode::pack72bits(value, rem)); }

std::uint32_t VaricodeAdapter::pack_alphanumeric22(std::string const& value, bool is_flag) { return ::Varicode::packAlphaNumeric22(to_qstr(value), is_flag); }
std::string VaricodeAdapter::unpack_alphanumeric22(std::uint32_t packed, bool* is_flag) { return to_std(::Varicode::unpackAlphaNumeric22(packed, is_flag)); }
std::uint64_t VaricodeAdapter::pack_alphanumeric50(std::string const& value) { return ::Varicode::packAlphaNumeric50(to_qstr(value)); }
std::string VaricodeAdapter::unpack_alphanumeric50(std::uint64_t packed) { return to_std(::Varicode::unpackAlphaNumeric50(packed)); }

std::uint32_t VaricodeAdapter::pack_callsign(std::string const& value, bool* p_portable) { return ::Varicode::packCallsign(to_qstr(value), reinterpret_cast<bool*>(p_portable)); }
std::string VaricodeAdapter::unpack_callsign(std::uint32_t value, bool portable) { return to_std(::Varicode::unpackCallsign(value, portable)); }

std::string VaricodeAdapter::deg2grid(float dlong, float dlat) { return to_std(::Varicode::deg2grid(dlong, dlat)); }
std::pair<float, float> VaricodeAdapter::grid2deg(std::string const& grid) {
  auto res = ::Varicode::grid2deg(to_qstr(grid));
  return {res.first, res.second};
}
std::uint16_t VaricodeAdapter::pack_grid(std::string const& value) { return ::Varicode::packGrid(to_qstr(value)); }
std::string VaricodeAdapter::unpack_grid(std::uint16_t value) { return to_std(::Varicode::unpackGrid(value)); }

std::uint8_t VaricodeAdapter::pack_num(std::string const& num, bool* ok) { return ::Varicode::packNum(to_qstr(num), reinterpret_cast<bool*>(ok)); }
std::uint8_t VaricodeAdapter::pack_pwr(std::string const& pwr, bool* ok) {
  return js8core::protocol::varicode::pack_pwr(pwr, ok);
}
std::uint8_t VaricodeAdapter::pack_cmd(std::uint8_t cmd, std::uint8_t num, bool* p_packed_num) { return ::Varicode::packCmd(cmd, num, reinterpret_cast<bool*>(p_packed_num)); }
std::uint8_t VaricodeAdapter::unpack_cmd(std::uint8_t value, std::uint8_t* p_num) { return ::Varicode::unpackCmd(value, reinterpret_cast<quint8*>(p_num)); }

bool VaricodeAdapter::is_snr_command(std::string const& cmd) { return ::Varicode::isSNRCommand(to_qstr(cmd)); }
bool VaricodeAdapter::is_command_allowed(std::string const& cmd) { return ::Varicode::isCommandAllowed(to_qstr(cmd)); }
bool VaricodeAdapter::is_command_buffered(std::string const& cmd) { return ::Varicode::isCommandBuffered(to_qstr(cmd)); }
int VaricodeAdapter::is_command_checksummed(std::string const& cmd) { return ::Varicode::isCommandChecksumed(to_qstr(cmd)); }
bool VaricodeAdapter::is_command_autoreply(std::string const& cmd) { return ::Varicode::isCommandAutoreply(to_qstr(cmd)); }
bool VaricodeAdapter::is_valid_callsign(std::string const& callsign, bool* p_is_compound) { return ::Varicode::isValidCallsign(to_qstr(callsign), reinterpret_cast<bool*>(p_is_compound)); }
bool VaricodeAdapter::is_compound_callsign(std::string const& callsign) { return ::Varicode::isCompoundCallsign(to_qstr(callsign)); }
bool VaricodeAdapter::is_group_allowed(std::string const& group) { return ::Varicode::isGroupAllowed(to_qstr(group)); }

std::string VaricodeAdapter::pack_heartbeat_message(std::string const& text, std::string const& callsign, int* n) { return to_std(::Varicode::packHeartbeatMessage(to_qstr(text), to_qstr(callsign), n)); }
std::vector<std::string> VaricodeAdapter::unpack_heartbeat_message(std::string const& text, std::uint8_t* pType, bool* isAlt, std::uint8_t* pBits3) {
  auto list = ::Varicode::unpackHeartbeatMessage(to_qstr(text), reinterpret_cast<quint8*>(pType), reinterpret_cast<bool*>(isAlt), reinterpret_cast<quint8*>(pBits3));
  std::vector<std::string> out;
  out.reserve(list.size());
  for (auto const& s : list) out.push_back(s.toStdString());
  return out;
}

std::string VaricodeAdapter::pack_compound_message(std::string const& text, int* n) { return to_std(::Varicode::packCompoundMessage(to_qstr(text), n)); }
std::vector<std::string> VaricodeAdapter::unpack_compound_message(std::string const& text, std::uint8_t* pType, std::uint16_t* pNum, std::uint8_t* pBits3) {
  auto list = ::Varicode::unpackCompoundMessage(to_qstr(text), reinterpret_cast<quint8*>(pType), reinterpret_cast<quint8*>(pBits3));
  std::vector<std::string> out;
  out.reserve(list.size());
  for (auto const& s : list) out.push_back(s.toStdString());
  return out;
}

std::string VaricodeAdapter::pack_compound_frame(std::string const& callsign, std::uint8_t type, std::uint16_t num, std::uint8_t bits3) {
  return to_std(::Varicode::packCompoundFrame(to_qstr(callsign), type, num, bits3));
}
std::vector<std::string> VaricodeAdapter::unpack_compound_frame(std::string const& text, std::uint8_t* pType, std::uint16_t* pNum, std::uint8_t* pBits3) {
  auto list = ::Varicode::unpackCompoundFrame(to_qstr(text), reinterpret_cast<quint8*>(pType), reinterpret_cast<quint16*>(pNum), reinterpret_cast<quint8*>(pBits3));
  std::vector<std::string> out;
  out.reserve(list.size());
  for (auto const& s : list) out.push_back(s.toStdString());
  return out;
}

std::string VaricodeAdapter::pack_directed_message(std::string const& text, std::string const& mycall, std::string* pTo, bool* pToCompound, std::string* pCmd, std::string* pNum, int* n) {
  QString qTo, qCmd, qNum;
  auto res = ::Varicode::packDirectedMessage(to_qstr(text), to_qstr(mycall), &qTo, reinterpret_cast<bool*>(pToCompound), &qCmd, &qNum, n);
  if (pTo) *pTo = qTo.toStdString();
  if (pCmd) *pCmd = qCmd.toStdString();
  if (pNum) *pNum = qNum.toStdString();
  return to_std(res);
}

std::vector<std::string> VaricodeAdapter::unpack_directed_message(std::string const& text, std::uint8_t* pType) {
  auto list = ::Varicode::unpackDirectedMessage(to_qstr(text), reinterpret_cast<quint8*>(pType));
  std::vector<std::string> out;
  out.reserve(list.size());
  for (auto const& s : list) out.push_back(s.toStdString());
  return out;
}

std::string VaricodeAdapter::pack_data_message(std::string const& text, int* n) { return to_std(::Varicode::packDataMessage(to_qstr(text), n)); }
std::string VaricodeAdapter::unpack_data_message(std::string const& text) { return to_std(::Varicode::unpackDataMessage(to_qstr(text))); }
std::string VaricodeAdapter::pack_fast_data_message(std::string const& text, int* n) { return to_std(::Varicode::packFastDataMessage(to_qstr(text), n)); }
std::string VaricodeAdapter::unpack_fast_data_message(std::string const& text) { return to_std(::Varicode::unpackFastDataMessage(to_qstr(text))); }

std::vector<std::pair<std::string, int>> VaricodeAdapter::build_message_frames(std::string const& mycall,
                                                                               std::string const& mygrid,
                                                                               std::string const& selectedCall,
                                                                               std::string const& text,
                                                                               bool forceIdentify,
                                                                               bool forceData,
                                                                               int submode,
                                                                               protocol::varicode::MessageInfo* pInfo) {
  ::Varicode::MessageInfo info{};
  auto frames = ::Varicode::buildMessageFrames(to_qstr(mycall),
                                               to_qstr(mygrid),
                                               to_qstr(selectedCall),
                                               to_qstr(text),
                                               forceIdentify,
                                               forceData,
                                               submode,
                                               pInfo ? &info : nullptr);
  if (pInfo) {
    pInfo->dir_to = info.dirTo.toStdString();
    pInfo->dir_cmd = info.dirCmd.toStdString();
    pInfo->dir_num = info.dirNum.toStdString();
  }
  std::vector<std::pair<std::string, int>> out;
  out.reserve(frames.size());
  for (auto const& pair : frames) {
    out.emplace_back(pair.first.toStdString(), pair.second);
  }
  return out;
}

protocol::varicode::Backend VaricodeAdapter::backend() {
  protocol::varicode::Backend b;
  b.pack_heartbeat_message = [](std::string const& text, std::string const& callsign, int* n) {
    return VaricodeAdapter::pack_heartbeat_message(text, callsign, n);
  };
  b.unpack_heartbeat_message = [](std::string const& text, std::uint8_t* pType, bool* isAlt, std::uint8_t* pBits3) {
    return VaricodeAdapter::unpack_heartbeat_message(text, pType, isAlt, pBits3);
  };
  b.pack_compound_message = [](std::string const& text, int* n) { return VaricodeAdapter::pack_compound_message(text, n); };
  b.unpack_compound_message = [](std::string const& text, std::uint8_t* pType, std::uint16_t* pNum, std::uint8_t* pBits3) {
    return VaricodeAdapter::unpack_compound_message(text, pType, pNum, pBits3);
  };
  b.pack_compound_frame = [](std::string const& callsign, std::uint8_t type, std::uint16_t num, std::uint8_t bits3) {
    return VaricodeAdapter::pack_compound_frame(callsign, type, num, bits3);
  };
  b.unpack_compound_frame = [](std::string const& text, std::uint8_t* pType, std::uint16_t* pNum, std::uint8_t* pBits3) {
    return VaricodeAdapter::unpack_compound_frame(text, pType, pNum, pBits3);
  };
  b.pack_directed_message = [](std::string const& text, std::string const& mycall, std::string* pTo, bool* pToCompound, std::string* pCmd, std::string* pNum, int* n) {
    return VaricodeAdapter::pack_directed_message(text, mycall, pTo, pToCompound, pCmd, pNum, n);
  };
  b.unpack_directed_message = [](std::string const& text, std::uint8_t* pType) {
    return VaricodeAdapter::unpack_directed_message(text, pType);
  };
  b.pack_data_message = [](std::string const& text, int* n) { return VaricodeAdapter::pack_data_message(text, n); };
  b.unpack_data_message = [](std::string const& text) { return VaricodeAdapter::unpack_data_message(text); };
  b.pack_fast_data_message = [](std::string const& text, int* n) { return VaricodeAdapter::pack_fast_data_message(text, n); };
  b.unpack_fast_data_message = [](std::string const& text) { return VaricodeAdapter::unpack_fast_data_message(text); };
  b.build_message_frames = [](std::string const& mycall,
                              std::string const& mygrid,
                              std::string const& selectedCall,
                              std::string const& text,
                              bool forceIdentify,
                              bool forceData,
                              int submode,
                              protocol::varicode::MessageInfo* pInfo) {
    return VaricodeAdapter::build_message_frames(mycall, mygrid, selectedCall, text, forceIdentify, forceData, submode, pInfo);
  };
  return b;
}

}  // namespace js8core::qt
