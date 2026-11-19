#ifndef OM1_STARS_TYPES_H
#define OM1_STARS_TYPES_H

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {

constexpr uint32_t kMaxRawWidth = 6000;
constexpr uint32_t kMaxRawHeight = 5000;
constexpr uint32_t kMaxDetectorWidth = (kMaxRawWidth + 1) / 2;
constexpr uint32_t kMaxStars = 256;

struct Error {
  char text[256];
};

struct RawInfo {
  uint32_t width;
  uint32_t height;
  uint32_t crop_x;
  uint32_t crop_y;
  uint32_t crop_width;
  uint32_t crop_height;
  uint32_t strip_offset;
  uint32_t strip_bytes;
  uint16_t bits_per_sample;
  uint16_t valid_bits;
  uint16_t black[4];
  // ImageProcessing codec tag values indexed by (tag - 0x0640).  The OM-1
  // schema has no tags 0x064a..0x064f, so codec[10]..codec[15] stay zero.
  uint16_t codec[20];
  uint8_t cfa[4];      // Exif 2x2 CFA values in raster order (0=R, 1=G, 2=B).
};

struct DecodeStats {
  uint64_t pixels_decoded;
  uint64_t strip_bytes_read;
  uint64_t sample_sum;
  uint64_t sample_fnv1a;
  uint16_t sample_min;
  uint16_t sample_max;
  uint32_t raw_passes;
};

struct DetectorOptions {
  uint16_t sigma_q8;  // Foreground threshold in robust-sigma units.
  uint16_t min_area;  // Number of foreground 2x2 CFA cells.
  uint16_t max_area;
  uint16_t max_elongation_q8;  // 0 disables the covariance-shape gate.
  uint16_t max_sharpness_q8;   // 0 disables the DAOFIND peak gate.
  uint16_t border_margin;      // Detector cells; 0 keeps edge components.
};

enum StarFlags : uint16_t {
  kStarSaturated = 1u << 0,
  kStarTouchesBorder = 1u << 1,
};

struct Star {
  uint64_t flux;
  uint32_t area;
  uint32_t x_q8;
  uint32_t y_q8;
  uint32_t mean_q8;
  uint32_t peak;
  uint32_t bbox_left;
  uint32_t bbox_top;
  uint32_t bbox_width;
  uint32_t bbox_height;
  uint16_t flags;
};

using RowConsumer = bool (*)(void *context, uint32_t row,
                             const uint16_t *samples, uint32_t width,
                             Error *error);

}  // namespace om1_stars

#endif
