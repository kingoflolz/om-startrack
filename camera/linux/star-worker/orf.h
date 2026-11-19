#ifndef OM1_STARS_ORF_H
#define OM1_STARS_ORF_H

#include "types.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {

// A file-sized, forward-only byte stream.  read() has read(2)-like return
// semantics: a positive byte count, zero at EOF, or a negative value after
// optionally setting error.  It must never return more than the requested
// size.  process_orf() starts at byte zero and never rewinds the stream.
struct InputStream {
  void *context;
  long (*read)(void *context, void *destination, size_t size, Error *error);
  uint32_t total_size;
};

// A row of black-corrected 2x2 active-crop cell sums.  source_width is
// ceil(crop_width / 2); the consumer can derive the 1, 2, or 4 source-sensel
// divisor at odd crop edges from RawInfo.  saturated has one byte per cell.
using ReducedRowConsumer = bool (*)(void *context, uint32_t row,
                                    const uint32_t *sums,
                                    const uint8_t *saturated,
                                    uint32_t width, Error *error);

// Parse and losslessly decode one ordinary, single-strip OM-1 ORF.  A bounded
// leading metadata window is retained; any remaining preview/metadata bytes
// before StripOffset are streamed past without a second read.  The compressed
// strip is then consumed once and each reconstructed row is delivered once.
// Storage is a single static instance, so calls must not overlap or recurse.
// stats may be null to omit expensive per-sample checksum diagnostics.
bool process_orf(InputStream *input, RowConsumer consumer,
                 void *consumer_context, RawInfo *raw_info,
                 DecodeStats *stats, Error *error);

// Decode the same single-pass stream, but fuse active-crop black correction
// and 2x2 Bayer reduction into the raw-row callback.  Only reduced rows are
// exposed to consumer; the predictive decoder still retains its two parity
// rows because the codec depends on row-2 samples.
bool process_orf_reduced(InputStream *input, ReducedRowConsumer consumer,
                         void *consumer_context, RawInfo *raw_info,
                         DecodeStats *stats, Error *error);

}  // namespace om1_stars

#endif
