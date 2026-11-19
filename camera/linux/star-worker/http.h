#ifndef OM1_STARS_HTTP_H
#define OM1_STARS_HTTP_H

#include "orf.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {

// lighttpd/isc_file responses are small, but an explicit cap prevents a bad
// local peer from turning header collection into an unbounded operation.
constexpr uint32_t kHttpHeaderCapacity = 8u * 1024u;
constexpr uint32_t kHttpMaximumBodyBytes = 128u * 1024u * 1024u;
constexpr uint32_t kHttpMaximumPathBytes = 512u;
constexpr int kHttpProgressTimeoutMilliseconds = 10 * 1000;

// Fixed storage for one forward-only loopback HTTP response.  Zero
// initialization is a valid closed state; no constructor or heap allocation
// is required.  Keep this object alive until its associated InputStream has
// reached EOF or close_http_input() has been called.
struct HttpInputStream {
  // The descriptor is stored as fd + 1 so an all-zero object is safely closed.
  uint32_t socket_plus_one;
  uint32_t content_length;
  uint32_t bytes_delivered;
  uint16_t buffered_begin;
  uint16_t buffered_end;
  uint8_t buffer[kHttpHeaderCapacity];
};

// Open a GET for a canonical camera media path such as
// /DCIM/108OMSYS/P8153896.ORF through lighttpd/isc_file on 127.0.0.1:80.
// The path is deliberately restricted to safe, non-traversing /DCIM/*.ORF
// names.  A successful call initializes input with the exact Content-Length.
bool open_http_input(HttpInputStream *http, const char *path,
                     InputStream *input, Error *error);

// Close an unfinished response.  Completed responses close automatically.
// Calling this on an all-zero or already-closed object is harmless.
void close_http_input(HttpInputStream *http);

}  // namespace om1_stars

#endif
