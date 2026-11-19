#include "http.h"

#include "syscalls.h"
#include "util.h"

namespace om1_stars {

namespace {

constexpr char kRequestPrefix[] = "GET ";
constexpr char kRequestSuffix[] =
    " HTTP/1.0\r\n"
    "Host: 127.0.0.1\r\n"
    "Accept-Encoding: identity\r\n"
    "Connection: close\r\n\r\n";

int socket_fd(const HttpInputStream *http) {
  return static_cast<int>(http->socket_plus_one - 1u);
}

long read_failure(HttpInputStream *http, Error *error, const char *message) {
  close_http_input(http);
  fail(error, message);
  return -1;
}

long read_failure_u64(HttpInputStream *http, Error *error,
                      const char *message, uint64_t value) {
  close_http_input(http);
  fail_u64(error, message, value);
  return -1;
}

bool starts_with(const char *text, const char *prefix) {
  while (*prefix != '\0') {
    if (*text++ != *prefix++) return false;
  }
  return true;
}

char ascii_lower(char value) {
  if (value >= 'A' && value <= 'Z') {
    return static_cast<char>(value - 'A' + 'a');
  }
  return value;
}

bool equal_ascii_case(const uint8_t *text, size_t size,
                      const char *expected) {
  size_t index = 0;
  while (expected[index] != '\0') {
    if (index >= size ||
        ascii_lower(static_cast<char>(text[index])) !=
            ascii_lower(expected[index])) {
      return false;
    }
    ++index;
  }
  return index == size;
}

bool safe_path_character(char value) {
  return (value >= 'A' && value <= 'Z') ||
         (value >= 'a' && value <= 'z') ||
         (value >= '0' && value <= '9') || value == '/' || value == '.' ||
         value == '_' || value == '-';
}

bool valid_path(const char *path, size_t *path_size, Error *error) {
  if (path == nullptr || path_size == nullptr ||
      !starts_with(path, "/DCIM/")) {
    return fail(error, "HTTP path must start with /DCIM/");
  }

  size_t size = 0;
  size_t segment_begin = 1;
  for (;;) {
    const char value = path[size];
    if (value == '\0') break;
    if (size >= kHttpMaximumPathBytes) {
      return fail(error, "HTTP path exceeds fixed limit");
    }
    if (!safe_path_character(value)) {
      return fail(error, "HTTP path contains an unsafe character");
    }
    if (value == '/') {
      if (size == 0) {
        ++size;
        continue;
      }
      const size_t segment_size = size - segment_begin;
      if (segment_size == 0 ||
          (segment_size == 1 && path[segment_begin] == '.') ||
          (segment_size == 2 && path[segment_begin] == '.' &&
           path[segment_begin + 1] == '.')) {
        return fail(error, "HTTP path has an unsafe segment");
      }
      segment_begin = size + 1;
    }
    ++size;
  }

  const size_t final_size = size - segment_begin;
  if (final_size == 0 ||
      (final_size == 1 && path[segment_begin] == '.') ||
      (final_size == 2 && path[segment_begin] == '.' &&
       path[segment_begin + 1] == '.')) {
    return fail(error, "HTTP path has an unsafe final segment");
  }
  if (!string_ends_with_ascii_case(path, ".ORF")) {
    return fail(error, "HTTP path must name an ORF file");
  }
  *path_size = size;
  return true;
}

bool wait_ready(int fd, int16_t wanted, Error *error) {
  for (;;) {
    os::PollFd descriptor = {fd, wanted, 0};
    const long result =
        os::poll(&descriptor, 1, kHttpProgressTimeoutMilliseconds);
    if (result == -os::kErrorInterrupted) continue;
    if (os::failed(result)) {
      return fail_u64(error, "HTTP poll failed with errno ",
                      static_cast<uint64_t>(os::error_number(result)));
    }
    if (result == 0) return fail(error, "HTTP I/O progress timed out");
    if ((descriptor.revents & wanted) != 0) return true;
    if ((descriptor.revents & os::kPollInvalid) != 0) {
      return fail(error, "HTTP poll reported an invalid socket");
    }
    if ((descriptor.revents & os::kPollError) != 0) {
      return fail(error, "HTTP poll reported a socket error");
    }
    if ((descriptor.revents & os::kPollHangup) != 0) {
      return fail(error, "HTTP peer hung up before I/O progress");
    }
    return fail(error, "HTTP poll returned no requested event");
  }
}

bool send_all(int fd, const void *source, size_t size, Error *error) {
  const uint8_t *cursor = static_cast<const uint8_t *>(source);
  while (size != 0) {
    if (!wait_ready(fd, os::kPollOut, error)) return false;
    const long result = os::send(fd, cursor, size, os::kMessageNoSignal);
    if (result == -os::kErrorInterrupted) continue;
    if (os::failed(result)) {
      return fail_u64(error, "HTTP send failed with errno ",
                      static_cast<uint64_t>(os::error_number(result)));
    }
    if (result == 0) return fail(error, "HTTP send made no progress");
    cursor += static_cast<size_t>(result);
    size -= static_cast<size_t>(result);
  }
  return true;
}

bool is_header_name_character(uint8_t value) {
  if ((value >= 'A' && value <= 'Z') ||
      (value >= 'a' && value <= 'z') ||
      (value >= '0' && value <= '9')) {
    return true;
  }
  switch (value) {
    case '!':
    case '#':
    case '$':
    case '%':
    case '&':
    case '\'':
    case '*':
    case '+':
    case '-':
    case '.':
    case '^':
    case '_':
    case '`':
    case '|':
    case '~':
      return true;
    default:
      return false;
  }
}

bool valid_header_value(const uint8_t *value, size_t size) {
  for (size_t index = 0; index < size; ++index) {
    if (value[index] != '\t' &&
        (value[index] < 0x20u || value[index] > 0x7eu)) {
      return false;
    }
  }
  return true;
}

bool parse_content_length(const uint8_t *value, size_t size,
                          uint32_t *result, Error *error) {
  size_t begin = 0;
  size_t end = size;
  while (begin != end && (value[begin] == ' ' || value[begin] == '\t')) {
    ++begin;
  }
  while (end != begin && (value[end - 1] == ' ' || value[end - 1] == '\t')) {
    --end;
  }
  if (begin == end) return fail(error, "empty HTTP Content-Length");

  uint32_t parsed = 0;
  for (size_t index = begin; index < end; ++index) {
    if (value[index] < '0' || value[index] > '9') {
      return fail(error, "invalid HTTP Content-Length");
    }
    const uint32_t digit = static_cast<uint32_t>(value[index] - '0');
    if (parsed > (kHttpMaximumBodyBytes - digit) / 10u) {
      return fail(error, "HTTP Content-Length exceeds fixed limit");
    }
    parsed = parsed * 10u + digit;
  }
  *result = parsed;
  return true;
}

bool find_header_end(const uint8_t *data, size_t size, size_t *header_end) {
  if (size < 4) return false;
  for (size_t index = 0; index + 4 <= size; ++index) {
    if (data[index] == '\r' && data[index + 1] == '\n' &&
        data[index + 2] == '\r' && data[index + 3] == '\n') {
      *header_end = index + 4;
      return true;
    }
  }
  return false;
}

bool find_crlf(const uint8_t *data, size_t begin, size_t end,
               size_t *line_end) {
  for (size_t index = begin; index + 1 < end; ++index) {
    if (data[index] == '\r' && data[index + 1] == '\n') {
      *line_end = index;
      return true;
    }
  }
  return false;
}

bool valid_status_line(const uint8_t *data, size_t size, Error *error) {
  static constexpr char kHttp10[] = "HTTP/1.0 200";
  static constexpr char kHttp11[] = "HTTP/1.1 200";
  const size_t prefix_size = sizeof(kHttp10) - 1u;
  if (size < prefix_size ||
      (!starts_with(reinterpret_cast<const char *>(data), kHttp10) &&
       !starts_with(reinterpret_cast<const char *>(data), kHttp11))) {
    return fail(error, "HTTP response status is not 200");
  }
  if (size != prefix_size && data[prefix_size] != ' ') {
    return fail(error, "malformed HTTP status line");
  }
  for (size_t index = prefix_size; index < size; ++index) {
    if (data[index] < 0x20u || data[index] > 0x7eu) {
      return fail(error, "malformed HTTP status line");
    }
  }
  return true;
}

bool parse_headers(HttpInputStream *http, size_t received, size_t header_end,
                   Error *error) {
  size_t line_end = 0;
  if (!find_crlf(http->buffer, 0, header_end, &line_end) ||
      !valid_status_line(http->buffer, line_end, error)) {
    return false;
  }

  bool have_content_length = false;
  uint32_t content_length = 0;
  size_t line_begin = line_end + 2;
  while (line_begin + 2 <= header_end) {
    if (http->buffer[line_begin] == '\r' &&
        http->buffer[line_begin + 1] == '\n') {
      break;
    }
    if (!find_crlf(http->buffer, line_begin, header_end, &line_end)) {
      return fail(error, "malformed HTTP header line ending");
    }
    if (http->buffer[line_begin] == ' ' ||
        http->buffer[line_begin] == '\t') {
      return fail(error, "folded HTTP headers are not accepted");
    }

    size_t colon = line_begin;
    while (colon != line_end && http->buffer[colon] != ':') {
      if (!is_header_name_character(http->buffer[colon])) {
        return fail(error, "invalid HTTP header name");
      }
      ++colon;
    }
    if (colon == line_begin || colon == line_end) {
      return fail(error, "malformed HTTP header");
    }
    const uint8_t *const value = http->buffer + colon + 1;
    const size_t value_size = line_end - colon - 1;
    if (!valid_header_value(value, value_size)) {
      return fail(error, "invalid HTTP header value");
    }

    const uint8_t *const name = http->buffer + line_begin;
    const size_t name_size = colon - line_begin;
    if (equal_ascii_case(name, name_size, "Content-Length")) {
      uint32_t parsed = 0;
      if (!parse_content_length(value, value_size, &parsed, error)) {
        return false;
      }
      if (have_content_length && parsed != content_length) {
        return fail(error, "conflicting HTTP Content-Length headers");
      }
      content_length = parsed;
      have_content_length = true;
    } else if (equal_ascii_case(name, name_size, "Transfer-Encoding")) {
      return fail(error, "HTTP Transfer-Encoding is not accepted");
    } else if (equal_ascii_case(name, name_size, "Content-Encoding")) {
      return fail(error, "HTTP Content-Encoding is not accepted");
    }
    line_begin = line_end + 2;
  }

  if (!have_content_length) {
    return fail(error, "HTTP response has no Content-Length");
  }
  const size_t buffered_body = received - header_end;
  if (buffered_body > content_length) {
    return fail(error, "HTTP response exceeds its Content-Length");
  }
  http->content_length = content_length;
  http->bytes_delivered = 0;
  http->buffered_begin = static_cast<uint16_t>(header_end);
  http->buffered_end = static_cast<uint16_t>(received);
  return true;
}

long http_read(void *context, void *destination, size_t size, Error *error) {
  HttpInputStream *const http = static_cast<HttpInputStream *>(context);
  if (http == nullptr || destination == nullptr) {
    if (size == 0) return 0;
    return read_failure(http, error, "invalid HTTP read arguments");
  }
  if (size == 0 || http->bytes_delivered == http->content_length) return 0;
  if (http->socket_plus_one == 0) {
    return read_failure(http, error, "HTTP input is closed before EOF");
  }

  const uint32_t remaining = http->content_length - http->bytes_delivered;
  if (size > remaining) size = remaining;
  uint8_t *output = static_cast<uint8_t *>(destination);
  size_t copied = 0;

  if (http->buffered_begin != http->buffered_end) {
    size_t available = static_cast<size_t>(http->buffered_end -
                                           http->buffered_begin);
    if (available > size) available = size;
    for (size_t index = 0; index < available; ++index) {
      output[index] = http->buffer[http->buffered_begin + index];
    }
    http->buffered_begin =
        static_cast<uint16_t>(http->buffered_begin + available);
    copied = available;
  }

  while (copied != size) {
    if (!wait_ready(socket_fd(http), os::kPollIn, error)) {
      close_http_input(http);
      return -1;
    }
    const long result = os::recv(socket_fd(http), output + copied,
                                 size - copied, 0);
    if (result == -os::kErrorInterrupted) continue;
    if (os::failed(result)) {
      return read_failure_u64(http, error, "HTTP receive failed with errno ",
                              static_cast<uint64_t>(
                                  os::error_number(result)));
    }
    if (result == 0) {
      return read_failure(http, error,
                          "HTTP body ended before Content-Length");
    }
    copied += static_cast<size_t>(result);
  }

  http->bytes_delivered += static_cast<uint32_t>(copied);
  if (http->bytes_delivered == http->content_length) close_http_input(http);
  return static_cast<long>(copied);
}

}  // namespace

void close_http_input(HttpInputStream *http) {
  if (http == nullptr || http->socket_plus_one == 0) return;
  (void)os::close(socket_fd(http));
  http->socket_plus_one = 0;
}

bool open_http_input(HttpInputStream *http, const char *path,
                     InputStream *input, Error *error) {
  if (http == nullptr || input == nullptr) {
    return fail(error, "invalid HTTP input arguments");
  }
  clear_error(error);
  close_http_input(http);
  http->content_length = 0;
  http->bytes_delivered = 0;
  http->buffered_begin = 0;
  http->buffered_end = 0;
  input->context = nullptr;
  input->read = nullptr;
  input->total_size = 0;

  size_t path_size = 0;
  if (!valid_path(path, &path_size, error)) return false;

  const long socket_result =
      os::socket(os::kAddressFamilyInet, os::kSocketStream, 0);
  if (os::failed(socket_result)) {
    return fail_u64(error, "HTTP socket failed with errno ",
                    static_cast<uint64_t>(os::error_number(socket_result)));
  }
  http->socket_plus_one = static_cast<uint32_t>(socket_result) + 1u;

  const os::SockAddrIn address = {
      static_cast<uint16_t>(os::kAddressFamilyInet),
      os::host_to_network16(80),
      os::host_to_network32(UINT32_C(0x7f000001)),
      {},
  };
  const long connect_result =
      os::connect(socket_fd(http), &address, sizeof(address));
  if (os::failed(connect_result)) {
    close_http_input(http);
    return fail_u64(error, "HTTP connect failed with errno ",
                    static_cast<uint64_t>(os::error_number(connect_result)));
  }

  if (!send_all(socket_fd(http), kRequestPrefix,
                sizeof(kRequestPrefix) - 1u, error) ||
      !send_all(socket_fd(http), path, path_size, error) ||
      !send_all(socket_fd(http), kRequestSuffix,
                sizeof(kRequestSuffix) - 1u, error)) {
    close_http_input(http);
    return false;
  }

  size_t received = 0;
  size_t header_end = 0;
  while (!find_header_end(http->buffer, received, &header_end)) {
    if (received == sizeof(http->buffer)) {
      close_http_input(http);
      return fail(error, "HTTP response headers exceed fixed limit");
    }
    if (!wait_ready(socket_fd(http), os::kPollIn, error)) {
      close_http_input(http);
      return false;
    }
    const long result =
        os::recv(socket_fd(http), http->buffer + received,
                 sizeof(http->buffer) - received, 0);
    if (result == -os::kErrorInterrupted) continue;
    if (os::failed(result)) {
      close_http_input(http);
      return fail_u64(error, "HTTP receive failed with errno ",
                      static_cast<uint64_t>(os::error_number(result)));
    }
    if (result == 0) {
      close_http_input(http);
      return fail(error, "HTTP response ended before its headers");
    }
    received += static_cast<size_t>(result);
  }

  if (!parse_headers(http, received, header_end, error)) {
    close_http_input(http);
    return false;
  }
  input->context = http;
  input->read = http_read;
  input->total_size = http->content_length;
  if (http->content_length == 0) close_http_input(http);
  return true;
}

}  // namespace om1_stars
