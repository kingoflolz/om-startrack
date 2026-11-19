#include "util.h"

#include <errno.h>
#include <unistd.h>

namespace om1_stars {

namespace {

void copy_error(Error *error, const char *message) {
  if (error == nullptr) return;
  size_t i = 0;
  if (message != nullptr) {
    while (message[i] != '\0' && i + 1 < sizeof(error->text)) {
      error->text[i] = message[i];
      ++i;
    }
  }
  error->text[i] = '\0';
}

int hex_value(char value) {
  if (value >= '0' && value <= '9') return value - '0';
  if (value >= 'a' && value <= 'f') return value - 'a' + 10;
  if (value >= 'A' && value <= 'F') return value - 'A' + 10;
  return -1;
}

}  // namespace

void clear_error(Error *error) {
  if (error != nullptr) error->text[0] = '\0';
}

bool fail(Error *error, const char *message) {
  copy_error(error, message);
  return false;
}

bool fail_u64(Error *error, const char *message, uint64_t value) {
  if (error == nullptr) return false;
  TextBuffer output(error->text, sizeof(error->text));
  output.append(message);
  output.append_u64(value);
  return false;
}

size_t string_length(const char *text) {
  size_t result = 0;
  if (text != nullptr) {
    while (text[result] != '\0') ++result;
  }
  return result;
}

bool string_equal(const char *left, const char *right) {
  if (left == nullptr || right == nullptr) return left == right;
  while (*left == *right && *left != '\0') {
    ++left;
    ++right;
  }
  return *left == *right;
}

bool string_ends_with_ascii_case(const char *text, const char *suffix) {
  const size_t text_size = string_length(text);
  const size_t suffix_size = string_length(suffix);
  if (suffix_size > text_size) return false;
  for (size_t i = 0; i < suffix_size; ++i) {
    char a = text[text_size - suffix_size + i];
    char b = suffix[i];
    if (a >= 'A' && a <= 'Z') a = static_cast<char>(a - 'A' + 'a');
    if (b >= 'A' && b <= 'Z') b = static_cast<char>(b - 'A' + 'a');
    if (a != b) return false;
  }
  return true;
}

bool parse_u32(const char *text, uint32_t *value) {
  if (text == nullptr || value == nullptr || *text == '\0') return false;
  uint64_t result = 0;
  while (*text != '\0') {
    if (*text < '0' || *text > '9') return false;
    result = result * 10u + static_cast<unsigned>(*text - '0');
    if (result > UINT32_MAX) return false;
    ++text;
  }
  *value = static_cast<uint32_t>(result);
  return true;
}

bool parse_ipv4(const char *text, uint32_t *network_order_address) {
  if (text == nullptr || network_order_address == nullptr) return false;
  uint32_t octets[4] = {};
  for (unsigned i = 0; i < 4; ++i) {
    if (*text < '0' || *text > '9') return false;
    unsigned digits = 0;
    while (*text >= '0' && *text <= '9') {
      octets[i] = octets[i] * 10u + static_cast<unsigned>(*text - '0');
      if (octets[i] > 255u || ++digits > 3u) return false;
      ++text;
    }
    if (i != 3) {
      if (*text != '.') return false;
      ++text;
    }
  }
  if (*text != '\0') return false;
  *network_order_address = (octets[0] << 24) | (octets[1] << 16) |
                           (octets[2] << 8) | octets[3];
  return true;
}

bool parse_guid_tail(const char *text, uint8_t result[6]) {
  if (text == nullptr || result == nullptr) return false;
  unsigned nibble = 0;
  uint8_t byte = 0;
  unsigned output = 0;
  while (*text != '\0') {
    if (*text == ':' || *text == '-') {
      ++text;
      continue;
    }
    const int digit = hex_value(*text++);
    if (digit < 0 || output >= 6) return false;
    if ((nibble & 1u) == 0u) {
      byte = static_cast<uint8_t>(digit << 4);
    } else {
      result[output++] = static_cast<uint8_t>(byte | digit);
    }
    ++nibble;
  }
  return nibble == 12 && output == 6;
}

bool write_all(int fd, const void *data, size_t size) {
  const uint8_t *cursor = static_cast<const uint8_t *>(data);
  while (size != 0) {
    const ssize_t written = ::write(fd, cursor, size);
    if (written < 0) {
      if (errno == EINTR) continue;
      return false;
    }
    if (written == 0) return false;
    cursor += static_cast<size_t>(written);
    size -= static_cast<size_t>(written);
  }
  return true;
}

TextBuffer::TextBuffer(char *storage, size_t capacity)
    : storage_(storage), capacity_(capacity), size_(0), ok_(capacity != 0) {
  if (capacity_ != 0) storage_[0] = '\0';
}

void TextBuffer::reset() {
  size_ = 0;
  ok_ = capacity_ != 0;
  if (capacity_ != 0) storage_[0] = '\0';
}

bool TextBuffer::append_char(char value) {
  if (!ok_ || size_ + 1 >= capacity_) {
    ok_ = false;
    return false;
  }
  storage_[size_++] = value;
  storage_[size_] = '\0';
  return true;
}

bool TextBuffer::append(const char *text) {
  if (text == nullptr) return append("(null)");
  while (*text != '\0') {
    if (!append_char(*text++)) return false;
  }
  return true;
}

bool TextBuffer::append_u64(uint64_t value) {
  char digits[24];
  unsigned count = 0;
  do {
    digits[count++] = static_cast<char>('0' + value % 10u);
    value /= 10u;
  } while (value != 0);
  while (count != 0) {
    if (!append_char(digits[--count])) return false;
  }
  return true;
}

bool TextBuffer::append_i64(int64_t value) {
  if (value < 0) {
    if (!append_char('-')) return false;
    const uint64_t magnitude = static_cast<uint64_t>(-(value + 1)) + 1u;
    return append_u64(magnitude);
  }
  return append_u64(static_cast<uint64_t>(value));
}

bool TextBuffer::append_hex(uint64_t value, unsigned digits) {
  static constexpr char kHex[] = "0123456789abcdef";
  if (digits > 16) return false;
  for (unsigned i = digits; i != 0; --i) {
    const unsigned shift = (i - 1u) * 4u;
    if (!append_char(kHex[(value >> shift) & 15u])) return false;
  }
  return true;
}

bool TextBuffer::append_q8(uint32_t value, unsigned fractional_digits) {
  if (!append_u64(value >> 8)) return false;
  if (fractional_digits == 0) return true;
  if (!append_char('.')) return false;
  uint32_t fraction = value & 255u;
  for (unsigned i = 0; i < fractional_digits; ++i) {
    fraction *= 10u;
    if (!append_char(static_cast<char>('0' + (fraction >> 8)))) return false;
    fraction &= 255u;
  }
  return true;
}

}  // namespace om1_stars
