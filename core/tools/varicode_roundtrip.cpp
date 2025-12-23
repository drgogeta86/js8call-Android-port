#include <iostream>
#include <string>
#include <vector>

#include "js8core/protocol/varicode.hpp"

int main() {
  std::vector<std::string> samples = {
      "CQ CQ CQ",
      "HB EM73",
      "KN4CRD: HELLO WORLD",
      "`@ALLCALL HB",
      "J1Y ACK 73",
      "TEST FASTDATA PAYLOAD"
  };

  for (auto const& s : samples) {
    std::uint8_t t = 0;
    std::uint16_t num = 0;
    std::uint8_t bits3 = 0;
    int n = 0;
    auto hb = js8core::protocol::varicode::pack_heartbeat_message(s, "KN4CRD", &n);
    if (!hb.empty()) {
      auto unpacked = js8core::protocol::varicode::unpack_heartbeat_message(hb, &t, nullptr, &bits3);
      std::cout << "HB roundtrip for '" << s << "': " << (unpacked.empty() ? "fail" : "ok") << "\n";
    }
    auto cmp = js8core::protocol::varicode::pack_compound_message("`" + s, &n);
    if (!cmp.empty()) {
      auto unpacked = js8core::protocol::varicode::unpack_compound_message(cmp, &t, &num, &bits3);
      std::cout << "Compound roundtrip for '" << s << "': " << (unpacked.empty() ? "fail" : "ok") << "\n";
    }
    auto fast = js8core::protocol::varicode::pack_fast_data_message(s, &n);
    auto fast_dec = js8core::protocol::varicode::unpack_fast_data_message(fast);
    std::cout << "Fast-data roundtrip for '" << s << "': " << (fast_dec == s ? "ok" : "fail") << "\n";
  }
  return 0;
}
