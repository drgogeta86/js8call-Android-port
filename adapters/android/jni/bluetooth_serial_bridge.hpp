#pragma once

#include <cstddef>
#include <cstdint>

namespace js8core::android {

bool bt_serial_bridge_ready();
bool bt_serial_open(const char* address,
                    int port_index,
                    int baud_rate,
                    int data_bits,
                    int stop_bits,
                    int parity);
int bt_serial_read(std::uint8_t* buffer, std::size_t length, int timeout_ms);
int bt_serial_write(const std::uint8_t* buffer, std::size_t length, int timeout_ms);
int bt_serial_set_rts(bool enabled);
int bt_serial_set_dtr(bool enabled);
int bt_serial_flush();
void bt_serial_close();

}  // namespace js8core::android
