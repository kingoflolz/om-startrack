#ifndef OM1_STARS_UTIL_H
#define OM1_STARS_UTIL_H

#include "types.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {

void clear_error(Error *error);
bool fail(Error *error, const char *message);
bool fail_u64(Error *error, const char *message, uint64_t value);

size_t string_length(const char *text);
bool string_equal(const char *left, const char *right);
bool string_ends_with_ascii_case(const char *text, const char *suffix);
bool parse_u32(const char *text, uint32_t *value);
bool parse_ipv4(const char *text, uint32_t *network_order_address);
bool parse_guid_tail(const char *text, uint8_t result[6]);

bool write_all(int fd, const void *data, size_t size);

class TextBuffer {
 public:
  TextBuffer(char *storage, size_t capacity);

  bool append(const char *text);
  bool append_char(char value);
  bool append_u64(uint64_t value);
  bool append_i64(int64_t value);
  bool append_hex(uint64_t value, unsigned digits);
  bool append_q8(uint32_t value, unsigned fractional_digits = 2);
  const char *data() const { return storage_; }
  size_t size() const { return size_; }
  bool ok() const { return ok_; }
  void reset();

 private:
  char *storage_;
  size_t capacity_;
  size_t size_;
  bool ok_;
};

}  // namespace om1_stars

#endif
