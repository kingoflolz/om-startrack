#ifndef OM1_STARS_STATION_STATE_PARSER_H_
#define OM1_STARS_STATION_STATE_PARSER_H_

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {
namespace detail {

inline size_t station_state_string_length(const char *text) {
  size_t size = 0;
  while (text[size] != '\0') ++size;
  return size;
}

inline bool station_state_line_starts_with(const char *data, size_t begin,
                                           size_t end,
                                           const char *prefix) {
  const size_t prefix_size = station_state_string_length(prefix);
  if (end - begin < prefix_size) return false;
  for (size_t i = 0; i < prefix_size; ++i) {
    if (data[begin + i] != prefix[i]) return false;
  }
  return true;
}

// `tpc stamode info` renders each state as exactly eight decimal digits.  Keep
// this grammar separate from the canonical, unpadded decimal parser used for
// DCF diagnostics in camera.cpp.
inline bool parse_zero_padded_decimal8(const char *data, size_t end,
                                       size_t *cursor, uint32_t maximum,
                                       uint32_t *value) {
  size_t position = *cursor;
  if (position > end || end - position < 8u) return false;

  uint32_t result = 0;
  for (unsigned i = 0; i < 8; ++i) {
    const char character = data[position + i];
    if (character < '0' || character > '9') return false;
    const uint32_t digit = static_cast<uint32_t>(character - '0');
    if (result > (UINT32_MAX - digit) / 10u) return false;
    result = result * 10u + digit;
  }
  if (result > maximum) return false;

  *cursor = position + 8u;
  *value = result;
  return true;
}

inline bool parse_exact_prefixed_zero_padded_u32(
    const char *data, size_t size, const char *prefix, uint32_t maximum,
    uint32_t *value) {
  bool found = false;
  uint32_t parsed = 0;
  for (size_t begin = 0; begin < size;) {
    size_t end = begin;
    while (end < size && data[end] != '\n') ++end;
    size_t content_end = end;
    if (content_end != begin && data[content_end - 1u] == '\r') --content_end;
    if (station_state_line_starts_with(data, begin, content_end, prefix)) {
      size_t cursor = begin + station_state_string_length(prefix);
      uint32_t current = 0;
      if (found ||
          !parse_zero_padded_decimal8(data, content_end, &cursor, maximum,
                                      &current) ||
          cursor != content_end) {
        return false;
      }
      parsed = current;
      found = true;
    }
    begin = end < size ? end + 1u : size;
  }
  if (found && value != nullptr) *value = parsed;
  return found;
}

}  // namespace detail
}  // namespace om1_stars

#endif  // OM1_STARS_STATION_STATE_PARSER_H_
