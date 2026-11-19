#ifndef OM1_STARS_SETTING_BATCH_PARSER_H_
#define OM1_STARS_SETTING_BATCH_PARSER_H_

#include <stddef.h>

namespace om1_stars {
namespace detail {

inline size_t setting_batch_string_length(const char *text) {
  size_t size = 0;
  while (text[size] != '\0') ++size;
  return size;
}

inline bool setting_batch_line_equal(const char *data, size_t begin,
                                     size_t end, const char *text) {
  const size_t text_size = setting_batch_string_length(text);
  if (end - begin != text_size) return false;
  for (size_t i = 0; i < text_size; ++i) {
    if (data[begin + i] != text[i]) return false;
  }
  return true;
}

inline size_t setting_batch_find(const char *data, size_t size, size_t begin,
                                 const char *needle) {
  const size_t needle_size = setting_batch_string_length(needle);
  if (needle_size == 0 || begin > size || needle_size > size - begin)
    return size;
  for (size_t i = begin; i <= size - needle_size; ++i) {
    size_t matched = 0;
    while (matched < needle_size && data[i + matched] == needle[matched])
      ++matched;
    if (matched == needle_size) return i;
  }
  return size;
}

// `echo TOKEN` has a synchronous command echo and an asynchronous bare TOKEN
// handler line. Only the latter followed by the final prompt is a FIFO fence.
inline bool setting_batch_barrier_complete(const char *data, size_t size,
                                           const char *token) {
  constexpr char kPrompt[] = "\nUSR> ";
  constexpr size_t kPromptSize = sizeof(kPrompt) - 1u;
  if (data == nullptr || token == nullptr || token[0] == '\0') return false;

  for (size_t begin = 0; begin < size;) {
    size_t end = begin;
    while (end < size && data[end] != '\n') ++end;
    size_t content_end = end;
    if (content_end != begin && data[content_end - 1u] == '\r') --content_end;
    const bool exact_token =
        setting_batch_line_equal(data, begin, content_end, token);
    const bool exact_token_with_handler_space =
        content_end > begin && data[content_end - 1u] == ' ' &&
        setting_batch_line_equal(data, begin, content_end - 1u, token);
    if (exact_token || exact_token_with_handler_space) {
      const size_t prompt = setting_batch_find(data, size, end, kPrompt);
      if (prompt == size) return false;
      for (size_t i = end; i < prompt; ++i) {
        if (data[i] != '\r' && data[i] != '\n') return false;
      }
      for (size_t i = prompt + kPromptSize; i < size; ++i) {
        if (data[i] != '\r' && data[i] != '\n') return false;
      }
      return true;
    }
    begin = end < size ? end + 1u : size;
  }
  return false;
}

}  // namespace detail
}  // namespace om1_stars

#endif  // OM1_STARS_SETTING_BATCH_PARSER_H_
