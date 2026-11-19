#include "detector.h"

#include "util.h"

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#if defined(__GNUC__)
// Keep cold validation/output code at the binary's global -Os setting.  Only
// the full-frame loops use -O2; this captures nearly all of the ARM speedup
// without paying the size cost of optimizing the complete translation unit.
#define OM1_DETECTOR_HOT __attribute__((hot, optimize("O2")))
#else
#define OM1_DETECTOR_HOT
#endif

namespace om1_stars {
namespace {

constexpr uint32_t kSlabRows = 32;
constexpr uint32_t kTileWidth = 32;
constexpr uint32_t kBackgroundBands = 2;
constexpr uint32_t kMaxTiles =
    (kMaxDetectorWidth + kTileWidth - 1u) / kTileWidth;
constexpr uint32_t kMaxRuns = (kMaxDetectorWidth + 1u) / 2u;
constexpr uint32_t kMaxComponentSlots = kMaxRuns * 2u;
// Each background band is at most 16 rows high and samples both axes at
// stride four.  Keeping the exact samples is far smaller than a full 16-bit
// histogram and makes the median independent of the input code range.
constexpr uint32_t kMaxTileBackgroundSamples =
    ((kTileWidth + 3u) / 4u) *
    (((kSlabRows / kBackgroundBands) + 3u) / 4u);
constexpr uint16_t kNoComponent = 0xffffu;
constexpr uint8_t kInputUnset = 0;
constexpr uint8_t kInputRawRows = 1;
constexpr uint8_t kInputLumaRows = 2;
static_assert(kMaxDetectorWidth < kNoComponent,
              "detector coordinates must fit in run records");
static_assert(kMaxComponentSlots < kNoComponent,
              "detector labels must fit in component records");

struct Component {
  // area counts threshold-exceeding 2x2 CFA cells.  flux is the integrated
  // positive, unfiltered residual above the streaming local background.  The
  // matched signal is used only to decide region membership.
  uint64_t flux;
  uint64_t weighted_x;
  uint64_t weighted_y;
  uint64_t weighted_xx;
  uint64_t weighted_yy;
  uint64_t weighted_xy;
  uint32_t area;
  uint32_t peak_neighbor_sum;
  uint16_t peak;
  uint16_t peak_residual;
  uint16_t peak_x;
  uint16_t peak_y;
  uint16_t peak_left;
  uint16_t peak_right;
  uint16_t peak_up;
  uint16_t peak_down;
  uint16_t left;
  uint16_t right;
  uint16_t top;
  uint16_t bottom;
  uint16_t flags;
  uint8_t peak_patch_valid;
};

struct Run {
  uint16_t left;
  uint16_t right;
  uint16_t component;
};

RawInfo g_info;
DetectorOptions g_options;
uint32_t g_expected_row;
uint32_t g_detector_width;
uint32_t g_detector_height;
uint32_t g_white_code;
uint32_t g_slab_start_y;
uint32_t g_slab_row_count;
uint32_t g_component_count;
uint32_t g_previous_run_count;
uint32_t g_best_count;
uint8_t g_run_bank;
uint8_t g_input_mode;
bool g_started;
bool g_failed;
bool g_have_pending_row;

// At most one raw-row pair and one 32-row luma slab are resident.  Saturation
// uses one byte per cell: this modest fixed-BSS cost lets row aggregation copy
// eight flags at once and avoids a branch/read-modify-write per detector cell.
uint32_t g_cell_sum[kMaxDetectorWidth];
uint8_t g_cell_saturated[kMaxDetectorWidth];
alignas(16) uint16_t g_luma[kSlabRows * kMaxDetectorWidth];
uint8_t g_saturation[kSlabRows * kMaxDetectorWidth];
// The matched filter changes only the signed background residual used for the
// detection mask.  Original luma remains available for photometry and the
// peak fit.  The pending row/halo bridges slab boundaries without a second
// raster pass or a full-frame allocation.  Explicit alignment keeps every
// fixed-stride row aligned for the AArch32 NEON loops.
alignas(16) int32_t g_horizontal[kSlabRows * kMaxDetectorWidth];
alignas(16) int32_t g_detection_row[kMaxDetectorWidth];
alignas(16) int32_t g_pending_horizontal_above[kMaxDetectorWidth];
alignas(16) int32_t g_pending_horizontal[kMaxDetectorWidth];
// Two horizontally interpolated sub-block medians describe a bilinear
// background surface for the current slab.  Evaluating that surface on demand
// avoids retaining a 32-row background plane.
alignas(16) uint16_t
    g_background_surface[kBackgroundBands][kMaxDetectorWidth];
int32_t g_background_y_center_q1[kBackgroundBands];
uint16_t g_pending_luma_above[kMaxDetectorWidth];
uint16_t g_pending_luma[kMaxDetectorWidth];
uint16_t g_pending_background_above[kMaxDetectorWidth];
uint16_t g_pending_background[kMaxDetectorWidth];
uint8_t g_pending_saturation[kMaxDetectorWidth];
// A band/tile contributes at most 32 samples.  Sorting those exact values is
// both smaller and cheaper than clearing and scanning a 65,536-bin histogram.
uint16_t g_tile_background_samples[kMaxTileBackgroundSamples];
uint16_t g_background[kBackgroundBands][kMaxTiles];
uint16_t g_background_centers[kMaxTiles];
uint32_t g_tile_count;
uint32_t g_detection_threshold;
uint32_t g_pending_detection_threshold;

Component g_components[kMaxComponentSlots];
uint16_t g_parent[kMaxComponentSlots];
uint16_t g_root_map[kMaxComponentSlots];
uint8_t g_root_active[kMaxComponentSlots];
Run g_runs[2][kMaxRuns];
Star g_best[kMaxStars];

bool detector_fail(Error *error, const char *message) {
  g_failed = true;
  return fail(error, message);
}

void copy_raw_info(RawInfo *destination, const RawInfo &source) {
  destination->width = source.width;
  destination->height = source.height;
  destination->crop_x = source.crop_x;
  destination->crop_y = source.crop_y;
  destination->crop_width = source.crop_width;
  destination->crop_height = source.crop_height;
  destination->strip_offset = source.strip_offset;
  destination->strip_bytes = source.strip_bytes;
  destination->bits_per_sample = source.bits_per_sample;
  destination->valid_bits = source.valid_bits;
  for (uint32_t i = 0; i < 4; ++i) destination->black[i] = source.black[i];
  for (uint32_t i = 0; i < 20; ++i) destination->codec[i] = source.codec[i];
  for (uint32_t i = 0; i < 4; ++i) destination->cfa[i] = source.cfa[i];
}

void copy_component(Component *destination, const Component &source) {
  destination->flux = source.flux;
  destination->weighted_x = source.weighted_x;
  destination->weighted_y = source.weighted_y;
  destination->weighted_xx = source.weighted_xx;
  destination->weighted_yy = source.weighted_yy;
  destination->weighted_xy = source.weighted_xy;
  destination->area = source.area;
  destination->peak_neighbor_sum = source.peak_neighbor_sum;
  destination->peak = source.peak;
  destination->peak_residual = source.peak_residual;
  destination->peak_x = source.peak_x;
  destination->peak_y = source.peak_y;
  destination->peak_left = source.peak_left;
  destination->peak_right = source.peak_right;
  destination->peak_up = source.peak_up;
  destination->peak_down = source.peak_down;
  destination->left = source.left;
  destination->right = source.right;
  destination->top = source.top;
  destination->bottom = source.bottom;
  destination->flags = source.flags;
  destination->peak_patch_valid = source.peak_patch_valid;
}

void copy_star(Star *destination, const Star &source) {
  destination->flux = source.flux;
  destination->area = source.area;
  destination->x_q8 = source.x_q8;
  destination->y_q8 = source.y_q8;
  destination->mean_q8 = source.mean_q8;
  destination->peak = source.peak;
  destination->bbox_left = source.bbox_left;
  destination->bbox_top = source.bbox_top;
  destination->bbox_width = source.bbox_width;
  destination->bbox_height = source.bbox_height;
  destination->flags = source.flags;
}

void swap_star(Star *left, Star *right) {
  Star saved;
  copy_star(&saved, *left);
  copy_star(left, *right);
  copy_star(right, saved);
}

bool star_better(const Star &left, const Star &right) {
  if (left.flux != right.flux) return left.flux > right.flux;
  if (left.area != right.area) return left.area > right.area;
  if (left.peak != right.peak) return left.peak > right.peak;
  if (left.y_q8 != right.y_q8) return left.y_q8 < right.y_q8;
  if (left.x_q8 != right.x_q8) return left.x_q8 < right.x_q8;
  if (left.bbox_top != right.bbox_top)
    return left.bbox_top < right.bbox_top;
  if (left.bbox_left != right.bbox_left)
    return left.bbox_left < right.bbox_left;
  if (left.bbox_height != right.bbox_height)
    return left.bbox_height < right.bbox_height;
  if (left.bbox_width != right.bbox_width)
    return left.bbox_width < right.bbox_width;
  return left.flags < right.flags;
}

// g_best is a worst-first heap.  That makes admission of a new top-256
// candidate O(log 256), while the final insertion sort is bounded and small.
void retain_star(const Star &candidate) {
  if (g_best_count < kMaxStars) {
    uint32_t child = g_best_count++;
    copy_star(&g_best[child], candidate);
    while (child != 0) {
      const uint32_t parent = (child - 1u) >> 1;
      if (!star_better(g_best[parent], g_best[child])) break;
      swap_star(&g_best[parent], &g_best[child]);
      child = parent;
    }
    return;
  }

  if (!star_better(candidate, g_best[0])) return;
  copy_star(&g_best[0], candidate);
  uint32_t parent = 0;
  for (;;) {
    const uint32_t left = parent * 2u + 1u;
    if (left >= g_best_count) break;
    const uint32_t right = left + 1u;
    uint32_t worse = left;
    if (right < g_best_count && star_better(g_best[left], g_best[right]))
      worse = right;
    if (!star_better(g_best[parent], g_best[worse])) break;
    swap_star(&g_best[parent], &g_best[worse]);
    parent = worse;
  }
}

// Return round(numerator / denominator), bounded by maximum, without using a
// 64-bit divide (and consequently without pulling an ARM compiler helper into
// the freestanding image).  Every current caller has a naturally small Q8
// quotient, so a binary search needs at most 24 comparisons.
uint32_t rounded_ratio(uint64_t numerator, uint32_t denominator,
                       uint32_t maximum) {
  if (denominator == 0) return 0;
  const uint64_t target = numerator + (denominator >> 1);
  uint32_t low = 0;
  uint32_t high = maximum;
  while (low < high) {
    const uint32_t middle = low + ((high - low) >> 1) + 1u;
    const uint64_t product =
        static_cast<uint64_t>(middle) * static_cast<uint64_t>(denominator);
    if (product <= target)
      low = middle;
    else
      high = middle - 1u;
  }
  return low;
}

uint64_t integer_sqrt(uint64_t value) {
  uint64_t result = 0;
  uint64_t bit = static_cast<uint64_t>(1) << 62;
  while (bit > value) bit >>= 2;
  while (bit != 0) {
    if (value >= result + bit) {
      value -= result + bit;
      result = (result >> 1) + bit;
    } else {
      result >>= 1;
    }
    bit >>= 2;
  }
  return result;
}

bool component_is_too_elongated(const Component &component) {
  if (g_options.max_elongation_q8 == 0 || component.area < 3u) return false;
  const uint32_t flux = static_cast<uint32_t>(component.flux);
  // Translate exact integer moments to the component's top-left before
  // converting them to Q4 covariance.  Centering absolute image coordinates
  // after division catastrophically amplifies sub-Q4 mean rounding at large
  // x/y positions; this algebraic translation is exact and location invariant.
  const uint32_t origin_x = component.left * 4u;
  const uint32_t origin_y = component.top * 4u;
  const uint64_t centered_x =
      component.weighted_x - static_cast<uint64_t>(origin_x) * flux;
  const uint64_t centered_y =
      component.weighted_y - static_cast<uint64_t>(origin_y) * flux;
  const uint64_t centered_xx =
      component.weighted_xx -
      2u * static_cast<uint64_t>(origin_x) * component.weighted_x +
      static_cast<uint64_t>(origin_x) * origin_x * flux;
  const uint64_t centered_yy =
      component.weighted_yy -
      2u * static_cast<uint64_t>(origin_y) * component.weighted_y +
      static_cast<uint64_t>(origin_y) * origin_y * flux;
  const uint64_t centered_xy =
      component.weighted_xy -
      static_cast<uint64_t>(origin_x) * component.weighted_y -
      static_cast<uint64_t>(origin_y) * component.weighted_x +
      static_cast<uint64_t>(origin_x) * origin_y * flux;
  const uint32_t mean_x_q4 =
      rounded_ratio(centered_x << 4, flux, UINT32_MAX);
  const uint32_t mean_y_q4 =
      rounded_ratio(centered_y << 4, flux, UINT32_MAX);
  const uint32_t exx_q4 =
      rounded_ratio(centered_xx << 4, flux, UINT32_MAX);
  const uint32_t eyy_q4 =
      rounded_ratio(centered_yy << 4, flux, UINT32_MAX);
  const uint32_t exy_q4 =
      rounded_ratio(centered_xy << 4, flux, UINT32_MAX);
  const uint64_t mean_xx_q4 =
      (static_cast<uint64_t>(mean_x_q4) * mean_x_q4 + 8u) >> 4;
  const uint64_t mean_yy_q4 =
      (static_cast<uint64_t>(mean_y_q4) * mean_y_q4 + 8u) >> 4;
  const uint64_t mean_xy_q4 =
      (static_cast<uint64_t>(mean_x_q4) * mean_y_q4 + 8u) >> 4;
  uint64_t cxx = exx_q4 > mean_xx_q4 ? exx_q4 - mean_xx_q4 : 0;
  uint64_t cyy = eyy_q4 > mean_yy_q4 ? eyy_q4 - mean_yy_q4 : 0;
  int64_t cxy = static_cast<int64_t>(exy_q4) -
                static_cast<int64_t>(mean_xy_q4);

  uint64_t largest = cxx > cyy ? cxx : cyy;
  const uint64_t abs_xy = cxy < 0 ? static_cast<uint64_t>(-cxy)
                                  : static_cast<uint64_t>(cxy);
  if (abs_xy > largest) largest = abs_xy;
  while (largest > (static_cast<uint64_t>(1) << 29)) {
    cxx >>= 1;
    cyy >>= 1;
    cxy >>= 1;
    largest >>= 1;
  }
  const int64_t difference = static_cast<int64_t>(cxx) -
                             static_cast<int64_t>(cyy);
  const uint64_t discriminant =
      static_cast<uint64_t>(difference * difference) +
      4u * static_cast<uint64_t>(cxy * cxy);
  const uint64_t root = integer_sqrt(discriminant);
  const uint64_t trace = cxx + cyy;
  if (root >= trace) return true;
  const uint64_t lambda_max_twice = trace + root;
  const uint64_t lambda_min_twice = trace - root;
  const uint64_t limit = g_options.max_elongation_q8;
  return lambda_max_twice * 65536u >
         lambda_min_twice * limit * limit;
}

bool component_is_too_sharp(const Component &component) {
  if (g_options.max_sharpness_q8 == 0 ||
      component.peak_patch_valid == 0 || component.peak_residual == 0)
    return false;
  const uint64_t scaled_peak = 8u * component.peak_residual;
  if (component.peak_neighbor_sum >= scaled_peak) return false;
  return (scaled_peak - component.peak_neighbor_sum) * 256u >
         scaled_peak * g_options.max_sharpness_q8;
}

int32_t quadratic_offset_q8(uint16_t before, uint16_t center,
                            uint16_t after) {
  const int32_t curvature = static_cast<int32_t>(before) -
                            2 * static_cast<int32_t>(center) + after;
  if (curvature >= 0) return 256;
  const int32_t numerator =
      (static_cast<int32_t>(before) - static_cast<int32_t>(after)) * 128;
  return numerator / curvature;
}

void refine_star_position(const Component &component, Star *star) {
  if (star == nullptr || component.area < 5u ||
      component.peak_patch_valid == 0 ||
      (component.flags & kStarSaturated) != 0)
    return;
  const int32_t dx = quadratic_offset_q8(
      component.peak_left, component.peak_residual, component.peak_right);
  const int32_t dy = quadratic_offset_q8(
      component.peak_up, component.peak_residual, component.peak_down);
  if (dx < -128 || dx > 128 || dy < -128 || dy > 128) return;
  const uint32_t half_x =
      component.peak_x * 2u + 1u < g_info.crop_width
          ? component.peak_x * 4u + 1u
          : component.peak_x * 4u;
  const uint32_t half_y =
      component.peak_y * 2u + 1u < g_info.crop_height
          ? component.peak_y * 4u + 1u
          : component.peak_y * 4u;
  const int32_t peak_x_q8 = static_cast<int32_t>(half_x << 7) + dx * 2;
  const int32_t peak_y_q8 = static_cast<int32_t>(half_y << 7) + dy * 2;
  const int32_t com_x_q8 = static_cast<int32_t>(star->x_q8);
  const int32_t com_y_q8 = static_cast<int32_t>(star->y_q8);
  const int32_t difference_x = peak_x_q8 - com_x_q8;
  const int32_t difference_y = peak_y_q8 - com_y_q8;
  if (difference_x < -256 || difference_x > 256 ||
      difference_y < -256 || difference_y > 256 || peak_x_q8 < 0 ||
      peak_y_q8 < 0)
    return;
  star->x_q8 = static_cast<uint32_t>(peak_x_q8);
  star->y_q8 = static_cast<uint32_t>(peak_y_q8);
}

void finalize_component(const Component &component) {
  if (component.area < g_options.min_area ||
      component.area > g_options.max_area || component.flux == 0)
    return;
  if (g_options.border_margin != 0 &&
      (component.left < g_options.border_margin ||
       component.top < g_options.border_margin ||
       component.right + g_options.border_margin >= g_detector_width ||
       component.bottom + g_options.border_margin >= g_detector_height))
    return;
  if (component_is_too_elongated(component) ||
      component_is_too_sharp(component))
    return;

  // g_best[0] is the worst retained result.  Once the heap is full, a
  // strictly dimmer completed component cannot win any later tie-breaker.
  // Keep equal-flux components because area, peak, and position still decide
  // their ordering.  This avoids centroid divisions and peak refinement for
  // crowded-frame tail candidates.
  if (g_best_count == kMaxStars && component.flux < g_best[0].flux) return;

  Star star;
  star.flux = component.flux;
  star.area = component.area;
  // An admitted component contains at most UINT16_MAX cells and every luma
  // contribution is at most UINT16_MAX, so its flux is strictly below 2^32.
  const uint32_t flux32 = static_cast<uint32_t>(component.flux);
  star.x_q8 = rounded_ratio(component.weighted_x << 7,
                            flux32,
                            (g_info.crop_width - 1u) << 8);
  star.y_q8 = rounded_ratio(component.weighted_y << 7,
                            flux32,
                            (g_info.crop_height - 1u) << 8);
  star.mean_q8 = rounded_ratio(
      component.flux << 8, component.area, static_cast<uint32_t>(65535u << 8));
  star.peak = component.peak;
  star.bbox_left = component.left * 2u;
  star.bbox_top = component.top * 2u;
  uint32_t right = (component.right + 1u) * 2u;
  uint32_t bottom = (component.bottom + 1u) * 2u;
  if (right > g_info.crop_width) right = g_info.crop_width;
  if (bottom > g_info.crop_height) bottom = g_info.crop_height;
  star.bbox_width = right - star.bbox_left;
  star.bbox_height = bottom - star.bbox_top;
  star.flags = component.flags;
  refine_star_position(component, &star);
  retain_star(star);
}

uint16_t find_root(uint16_t component) {
  uint16_t root = component;
  while (g_parent[root] != root) root = g_parent[root];
  while (g_parent[component] != component) {
    const uint16_t next = g_parent[component];
    g_parent[component] = root;
    component = next;
  }
  return root;
}

void merge_component_values(Component *destination, const Component &source) {
  if (destination->area <= g_options.max_area &&
      source.area <= g_options.max_area &&
      source.area <= g_options.max_area - destination->area) {
    destination->flux += source.flux;
    destination->weighted_x += source.weighted_x;
    destination->weighted_y += source.weighted_y;
    destination->weighted_xx += source.weighted_xx;
    destination->weighted_yy += source.weighted_yy;
    destination->weighted_xy += source.weighted_xy;
    destination->area += source.area;
    if (source.peak_residual > destination->peak_residual ||
        (source.peak_residual == destination->peak_residual &&
         (source.peak_y < destination->peak_y ||
          (source.peak_y == destination->peak_y &&
           source.peak_x < destination->peak_x)))) {
      destination->peak = source.peak;
      destination->peak_residual = source.peak_residual;
      destination->peak_neighbor_sum = source.peak_neighbor_sum;
      destination->peak_x = source.peak_x;
      destination->peak_y = source.peak_y;
      destination->peak_left = source.peak_left;
      destination->peak_right = source.peak_right;
      destination->peak_up = source.peak_up;
      destination->peak_down = source.peak_down;
      destination->peak_patch_valid = source.peak_patch_valid;
    }
  } else {
    // The component can never be admitted again.  Retain only this sentinel
    // and its union-find connectivity; all photometry and geometry are dead.
    destination->area = g_options.max_area + 1u;
    return;
  }
  if (source.left < destination->left) destination->left = source.left;
  if (source.right > destination->right) destination->right = source.right;
  if (source.top < destination->top) destination->top = source.top;
  if (source.bottom > destination->bottom)
    destination->bottom = source.bottom;
  destination->flags = static_cast<uint16_t>(destination->flags | source.flags);
}

uint16_t unite(uint16_t left, uint16_t right) {
  left = find_root(left);
  right = find_root(right);
  if (left == right) return left;
  if (right < left) {
    const uint16_t saved = left;
    left = right;
    right = saved;
  }
  merge_component_values(&g_components[left], g_components[right]);
  g_parent[right] = left;
  return left;
}

OM1_DETECTOR_HOT uint16_t sampled_median(uint32_t count) {
  for (uint32_t i = 1; i < count; ++i) {
    const uint16_t value = g_tile_background_samples[i];
    uint32_t position = i;
    while (position != 0u &&
           value < g_tile_background_samples[position - 1u]) {
      g_tile_background_samples[position] =
          g_tile_background_samples[position - 1u];
      --position;
    }
    g_tile_background_samples[position] = value;
  }
  const uint32_t lower = (count - 1u) >> 1;
  const uint32_t upper = count >> 1;
  return static_cast<uint16_t>(
      (static_cast<uint32_t>(g_tile_background_samples[lower]) +
       g_tile_background_samples[upper] + 1u) >> 1);
}

inline __attribute__((always_inline)) int32_t rounded_signed_ratio(
    int32_t numerator, uint32_t denominator) {
  if (denominator == 0) return 0;
  const bool negative = numerator < 0;
  const uint32_t magnitude = negative
                                 ? static_cast<uint32_t>(-numerator)
                                 : static_cast<uint32_t>(numerator);
  const uint32_t quotient =
      denominator == 32u
          ? (magnitude + 16u) >> 5
          : (magnitude + (denominator >> 1)) / denominator;
  return negative ? -static_cast<int32_t>(quotient)
                  : static_cast<int32_t>(quotient);
}

inline __attribute__((always_inline)) uint16_t interpolate_background(
    uint16_t first, uint16_t second, int32_t offset, uint32_t span) {
  const int32_t slope = static_cast<int32_t>(second) - first;
  int32_t value = static_cast<int32_t>(first) +
                  rounded_signed_ratio(slope * offset, span);
  if (value < 0) value = 0;
  if (value > 65535) value = 65535;
  return static_cast<uint16_t>(value);
}

inline __attribute__((always_inline)) uint16_t background_at_y(uint32_t y,
                                                               uint32_t x) {
  const int32_t span = g_background_y_center_q1[1] -
                       g_background_y_center_q1[0];
  if (span == 0) return g_background_surface[0][x];
  const int32_t offset = static_cast<int32_t>(y * 2u) -
                         g_background_y_center_q1[0];
  return interpolate_background(g_background_surface[0][x],
                                g_background_surface[1][x], offset,
                                static_cast<uint32_t>(span));
}

OM1_DETECTOR_HOT void estimate_background() {
  g_tile_count =
      (g_detector_width + kTileWidth - 1u) / kTileWidth;

  const uint32_t split = (g_slab_row_count + 1u) >> 1;
  uint32_t band_first[kBackgroundBands] = {0u, split};
  uint32_t band_last[kBackgroundBands] = {split, g_slab_row_count};
  if (g_slab_row_count == 1u) {
    band_first[1] = 0u;
    band_last[1] = 1u;
  }
  uint32_t sample_first[kBackgroundBands];
  for (uint32_t band = 0; band < kBackgroundBands; ++band) {
    const uint32_t length = band_last[band] - band_first[band];
    const uint32_t inset = length > 1u ? (length - 1u) / 2u : 0u;
    sample_first[band] = band_first[band] + (inset < 2u ? inset : 2u);
    const uint32_t sample_last = sample_first[band] +
        ((band_last[band] - 1u - sample_first[band]) / 4u) * 4u;
    // The median of a linear ramp lies midway between the central samples.
    // Retaining that actual sample center, rather than the nominal half-band
    // center, keeps interpolation exact despite the sparse stride-four grid.
    g_background_y_center_q1[band] =
        static_cast<int32_t>(sample_first[band] + sample_last);
  }

  for (uint32_t tile = 0; tile < g_tile_count; ++tile) {
    const uint32_t first_x = tile * kTileWidth;
    uint32_t last_x = first_x + kTileWidth;
    if (last_x > g_detector_width) last_x = g_detector_width;

    for (uint32_t band = 0; band < kBackgroundBands; ++band) {
      uint32_t count = 0;
      // Phase-staggered stride sampling follows Tetra's fast extractor: the
      // median remains robust to stars while background work is a small
      // fraction of the full raster sweep.
      for (uint32_t y = sample_first[band]; y < band_last[band]; y += 4u) {
        const uint16_t *row = &g_luma[y * kMaxDetectorWidth];
        const uint32_t sample_row = (g_slab_start_y + y) >> 2;
        uint32_t x = first_x + ((tile + sample_row) & 3u);
        for (; x < last_x; x += 4u) {
          g_tile_background_samples[count++] = row[x];
        }
      }
      if (count == 0) {
        uint32_t sample_y =
            static_cast<uint32_t>((g_background_y_center_q1[band] + 1) >> 1);
        if (sample_y >= band_last[band]) sample_y = band_last[band] - 1u;
        g_tile_background_samples[0] =
            g_luma[sample_y * kMaxDetectorWidth + first_x];
        count = 1;
      }
      const uint16_t background = sampled_median(count);
      g_background[band][tile] = background;
    }
    g_background_centers[tile] =
        static_cast<uint16_t>((first_x + last_x - 1u) >> 1);
  }

  for (uint32_t band = 0; band < kBackgroundBands; ++band) {
    for (uint32_t x = 0; x < g_detector_width; ++x) {
      if (g_tile_count == 1) {
        g_background_surface[band][x] = g_background[band][0];
        continue;
      }
      uint32_t left = 0;
      const uint32_t last = g_tile_count - 1u;
      if (x >= g_background_centers[last]) {
        left = last - 1u;
      } else if (x > g_background_centers[0]) {
        left = (x - g_background_centers[0]) / kTileWidth;
        if (left + 1u >= g_tile_count) left = g_tile_count - 2u;
        while (left + 1u < g_tile_count &&
               x > g_background_centers[left + 1u])
          ++left;
        while (left != 0 && x < g_background_centers[left]) --left;
      }
      const uint32_t right = left + 1u;
      const uint32_t span = g_background_centers[right] -
                            g_background_centers[left];
      const int32_t offset = static_cast<int32_t>(x) -
                             g_background_centers[left];
      g_background_surface[band][x] = interpolate_background(
          g_background[band][left], g_background[band][right], offset, span);
    }
  }
}

#if defined(__ARM_NEON)
inline __attribute__((always_inline)) int32x4_t rounded_signed_divide_32(
    int32x4_t value) {
  const int32x4_t zero = vdupq_n_s32(0);
  uint32x4_t magnitude = vreinterpretq_u32_s32(vabsq_s32(value));
  magnitude = vshrq_n_u32(vaddq_u32(magnitude, vdupq_n_u32(16u)), 5);
  const int32x4_t positive = vreinterpretq_s32_u32(magnitude);
  return vbslq_s32(vcltq_s32(value, zero), vnegq_s32(positive), positive);
}
#endif

OM1_DETECTOR_HOT void build_unfiltered_residual() {
#if defined(__ARM_NEON)
  const int32_t span = g_background_y_center_q1[1] -
                       g_background_y_center_q1[0];
#endif
  for (uint32_t y = 0; y < g_slab_row_count; ++y) {
    const uint16_t *luma = &g_luma[y * kMaxDetectorWidth];
    int32_t *residual = &g_horizontal[y * kMaxDetectorWidth];
    uint32_t x = 0;
#if defined(__ARM_NEON)
    if (span == 0) {
      for (; x + 8u <= g_detector_width; x += 8u) {
        const uint16x8_t samples = vld1q_u16(&luma[x]);
        const uint16x8_t background =
            vld1q_u16(&g_background_surface[0][x]);
        vst1q_s32(&residual[x],
                  vsubq_s32(
                      vreinterpretq_s32_u32(vmovl_u16(vget_low_u16(samples))),
                      vreinterpretq_s32_u32(
                          vmovl_u16(vget_low_u16(background)))));
        vst1q_s32(
            &residual[x + 4u],
            vsubq_s32(
                vreinterpretq_s32_u32(vmovl_u16(vget_high_u16(samples))),
                vreinterpretq_s32_u32(
                    vmovl_u16(vget_high_u16(background)))));
      }
    } else if (span == 32) {
      const int32_t offset = static_cast<int32_t>(y * 2u) -
                             g_background_y_center_q1[0];
      const int32x4_t zero = vdupq_n_s32(0);
      const int32x4_t maximum = vdupq_n_s32(65535);
      for (; x + 8u <= g_detector_width; x += 8u) {
        const uint16x8_t samples = vld1q_u16(&luma[x]);
        const uint16x8_t first =
            vld1q_u16(&g_background_surface[0][x]);
        const uint16x8_t second =
            vld1q_u16(&g_background_surface[1][x]);

        const int32x4_t first_low =
            vreinterpretq_s32_u32(vmovl_u16(vget_low_u16(first)));
        const int32x4_t second_low =
            vreinterpretq_s32_u32(vmovl_u16(vget_low_u16(second)));
        int32x4_t background_low = vaddq_s32(
            first_low, rounded_signed_divide_32(vmulq_n_s32(
                           vsubq_s32(second_low, first_low), offset)));
        background_low =
            vmaxq_s32(zero, vminq_s32(background_low, maximum));
        vst1q_s32(
            &residual[x],
            vsubq_s32(
                vreinterpretq_s32_u32(vmovl_u16(vget_low_u16(samples))),
                background_low));

        const int32x4_t first_high =
            vreinterpretq_s32_u32(vmovl_u16(vget_high_u16(first)));
        const int32x4_t second_high =
            vreinterpretq_s32_u32(vmovl_u16(vget_high_u16(second)));
        int32x4_t background_high = vaddq_s32(
            first_high, rounded_signed_divide_32(vmulq_n_s32(
                            vsubq_s32(second_high, first_high), offset)));
        background_high =
            vmaxq_s32(zero, vminq_s32(background_high, maximum));
        vst1q_s32(
            &residual[x + 4u],
            vsubq_s32(
                vreinterpretq_s32_u32(vmovl_u16(vget_high_u16(samples))),
                background_high));
      }
    }
#endif
    for (; x < g_detector_width; ++x)
      residual[x] = static_cast<int32_t>(luma[x]) - background_at_y(y, x);
  }
}

// The unfiltered background residual is needed by both the robust noise
// estimator and the matched-filter detector.
// Estimate noise from that plane instead of reconstructing the same bilinear
// background at every sparse sigma sample.  The selected pixels, clipping,
// integer squares, traversal order, and rounding are unchanged.
OM1_DETECTOR_HOT uint32_t estimate_noise_sigma_from_residual() {
  uint32_t clip = 65535u;
  uint32_t sigma = 1u;
  for (uint32_t iteration = 0; iteration < 3u; ++iteration) {
    uint64_t sum_squares = 0;
    uint32_t count = 0;
#if defined(__ARM_NEON)
    uint64x2_t vector_sum = vdupq_n_u64(0);
    uint32x4_t vector_count = vdupq_n_u32(0);
    const int32x4_t zero = vdupq_n_s32(0);
    const uint32x4_t clip_vector = vdupq_n_u32(clip);
#endif
    for (uint32_t y = 0; y < g_slab_row_count; ++y) {
      const int32_t *residual = &g_horizontal[y * kMaxDetectorWidth];
      uint32_t phase = (g_slab_start_y + y) & 3u;
      while (phase >= g_detector_width) phase -= g_detector_width;
      uint32_t x = phase;
#if defined(__ARM_NEON)
      for (; x + 16u <= g_detector_width; x += 16u) {
        const int32x4_t sampled = vld4q_s32(&residual[x]).val[0];
        uint32x4_t deviation =
            vreinterpretq_u32_s32(vnegq_s32(sampled));
        const uint32x4_t valid =
            vandq_u32(vcleq_s32(sampled, zero),
                      vcleq_u32(deviation, clip_vector));
        deviation = vandq_u32(deviation, valid);
        vector_sum = vaddq_u64(
            vector_sum, vpaddlq_u32(vmulq_u32(deviation, deviation)));
        vector_count =
            vaddq_u32(vector_count, vshrq_n_u32(valid, 31));
      }
#endif
      for (; x < g_detector_width; x += 4u) {
        if (residual[x] > 0) continue;
        const uint32_t deviation = static_cast<uint32_t>(-residual[x]);
        if (deviation > clip) continue;
        sum_squares += static_cast<uint64_t>(deviation) * deviation;
        ++count;
      }
    }
#if defined(__ARM_NEON)
    sum_squares += vgetq_lane_u64(vector_sum, 0) +
                   vgetq_lane_u64(vector_sum, 1);
    count += vgetq_lane_u32(vector_count, 0) +
             vgetq_lane_u32(vector_count, 1) +
             vgetq_lane_u32(vector_count, 2) +
             vgetq_lane_u32(vector_count, 3);
#endif
    if (count == 0) return 1u;
    sigma = static_cast<uint32_t>(
        integer_sqrt((sum_squares + (count >> 1)) / count));
    if (sigma == 0) sigma = 1u;
    clip = sigma > 21845u ? 65535u : sigma * 3u;
  }
  return sigma;
}

int32_t rounded_quarter(int64_t value) {
  return static_cast<int32_t>((value >= 0 ? value + 2 : value - 2) / 4);
}

#if defined(__ARM_NEON)
inline __attribute__((always_inline)) int32x4_t rounded_quarter_neon(
    int32x4_t value) {
  const int32x4_t zero = vdupq_n_s32(0);
  uint32x4_t magnitude = vreinterpretq_u32_s32(vabsq_s32(value));
  magnitude = vshrq_n_u32(vaddq_u32(magnitude, vdupq_n_u32(2u)), 2);
  const int32x4_t positive = vreinterpretq_s32_u32(magnitude);
  return vbslq_s32(vcltq_s32(value, zero), vnegq_s32(positive), positive);
}
#endif

OM1_DETECTOR_HOT void filter_horizontal_row(int32_t *residual) {
  uint32_t x = 0;
  int32_t left = residual[0];
#if defined(__ARM_NEON)
  int32x4_t previous = vdupq_n_s32(left);
  for (; x + 4u < g_detector_width; x += 4u) {
    const int32x4_t center = vld1q_s32(&residual[x]);
    const int32x4_t before = vextq_s32(previous, center, 3);
    const int32x4_t after = vld1q_s32(&residual[x + 1u]);
    const int32x4_t sum =
        vaddq_s32(vaddq_s32(before, after), vshlq_n_s32(center, 1));
    vst1q_s32(&residual[x], rounded_quarter_neon(sum));
    previous = center;
  }
  if (x != 0) left = vgetq_lane_s32(previous, 3);
#endif
  int32_t center = residual[x];
  for (; x < g_detector_width; ++x) {
    const int32_t right =
        x + 1u < g_detector_width ? residual[x + 1u] : center;
    residual[x] = rounded_quarter(
        static_cast<int64_t>(left) + 2 * center + right);
    left = center;
    center = right;
  }
}

OM1_DETECTOR_HOT void prepare_detection_signal() {
  estimate_background();
  build_unfiltered_residual();

  const uint32_t sigma = estimate_noise_sigma_from_residual();
  uint64_t threshold = static_cast<uint64_t>(sigma) * g_options.sigma_q8;
  threshold = (threshold * 3u + 1024u) >> 11;
  if (threshold == 0) threshold = 1;
  if (threshold > INT32_MAX) threshold = INT32_MAX;
  g_detection_threshold = static_cast<uint32_t>(threshold);

  for (uint32_t y = 0; y < g_slab_row_count; ++y)
    filter_horizontal_row(&g_horizontal[y * kMaxDetectorWidth]);
}

struct MeasurementRow {
  const uint16_t *luma;
  const uint16_t *background;
  uint16_t slab_y;
};

uint16_t positive_residual(const MeasurementRow &row, uint32_t x) {
  if (row.luma == nullptr) return 0;
  const uint16_t background = row.background != nullptr
                                  ? row.background[x]
                                  : background_at_y(row.slab_y, x);
  return row.luma[x] > background
             ? static_cast<uint16_t>(row.luma[x] - background)
             : 0;
}

void add_component_pixel(Component *component, uint32_t x, uint32_t y,
                         const MeasurementRow &above,
                         const MeasurementRow &current,
                         const MeasurementRow &below, bool saturated) {
  // An oversized component cannot shrink.  Keep its connectivity sentinel but
  // stop every residual lookup, moment multiply, peak patch, and bounds update.
  if (component->area >= g_options.max_area) {
    component->area = g_options.max_area + 1u;
    return;
  }

  const uint32_t half_x =
      x * 2u + 1u < g_info.crop_width ? x * 4u + 1u : x * 4u;
  const uint32_t half_y =
      y * 2u + 1u < g_info.crop_height ? y * 4u + 1u : y * 4u;
  const uint16_t contribution = positive_residual(current, x);
  component->flux += contribution;
  component->weighted_x += static_cast<uint64_t>(contribution) * half_x;
  component->weighted_y += static_cast<uint64_t>(contribution) * half_y;
  component->weighted_xx +=
      static_cast<uint64_t>(contribution) * half_x * half_x;
  component->weighted_yy +=
      static_cast<uint64_t>(contribution) * half_y * half_y;
  component->weighted_xy +=
      static_cast<uint64_t>(contribution) * half_x * half_y;
  ++component->area;
  if (contribution > component->peak_residual) {
    component->peak = current.luma[x];
    component->peak_residual = contribution;
    component->peak_x = static_cast<uint16_t>(x);
    component->peak_y = static_cast<uint16_t>(y);
    component->peak_patch_valid =
        static_cast<uint8_t>(x != 0 && x + 1u < g_detector_width &&
                             above.luma != nullptr && below.luma != nullptr);
    if (component->peak_patch_valid != 0) {
      component->peak_left = positive_residual(current, x - 1u);
      component->peak_right = positive_residual(current, x + 1u);
      component->peak_up = positive_residual(above, x);
      component->peak_down = positive_residual(below, x);
      component->peak_neighbor_sum =
          positive_residual(above, x - 1u) + component->peak_up +
          positive_residual(above, x + 1u) + component->peak_left +
          component->peak_right + positive_residual(below, x - 1u) +
          component->peak_down + positive_residual(below, x + 1u);
    }
  }
  if (x < component->left) component->left = static_cast<uint16_t>(x);
  if (x > component->right) component->right = static_cast<uint16_t>(x);
  if (y < component->top) component->top = static_cast<uint16_t>(y);
  if (y > component->bottom) component->bottom = static_cast<uint16_t>(y);
  if (saturated)
    component->flags =
        static_cast<uint16_t>(component->flags | kStarSaturated);
  if (x == 0 || x + 1u == g_detector_width || y == 0 ||
      y + 1u == g_detector_height)
    component->flags =
        static_cast<uint16_t>(component->flags | kStarTouchesBorder);
}

void begin_run_component(Component *component, uint32_t x, uint32_t y,
                         const MeasurementRow &above,
                         const MeasurementRow &current,
                         const MeasurementRow &below, bool saturated) {
  component->flux = 0;
  component->weighted_x = 0;
  component->weighted_y = 0;
  component->weighted_xx = 0;
  component->weighted_yy = 0;
  component->weighted_xy = 0;
  component->area = 0;
  component->peak_neighbor_sum = 0;
  component->peak = 0;
  component->peak_residual = 0;
  component->peak_x = static_cast<uint16_t>(x);
  component->peak_y = static_cast<uint16_t>(y);
  component->peak_left = 0;
  component->peak_right = 0;
  component->peak_up = 0;
  component->peak_down = 0;
  component->left = static_cast<uint16_t>(x);
  component->right = static_cast<uint16_t>(x);
  component->top = static_cast<uint16_t>(y);
  component->bottom = static_cast<uint16_t>(y);
  component->flags = 0;
  component->peak_patch_valid = 0;
  add_component_pixel(component, x, y, above, current, below, saturated);
}

OM1_DETECTOR_HOT uint32_t find_foreground(const int32_t *signal,
                                           uint32_t x) {
#if defined(__ARM_NEON)
  const int32x4_t threshold =
      vdupq_n_s32(static_cast<int32_t>(g_detection_threshold));
  while (x + 8u <= g_detector_width) {
    const uint32x4_t foreground0 =
        vcgtq_s32(vld1q_s32(&signal[x]), threshold);
    const uint32x4_t foreground1 =
        vcgtq_s32(vld1q_s32(&signal[x + 4u]), threshold);
    const uint32x4_t foreground =
        vorrq_u32(foreground0, foreground1);
    uint32x2_t any =
        vorr_u32(vget_low_u32(foreground), vget_high_u32(foreground));
    any = vpmax_u32(any, any);
    if (vget_lane_u32(any, 0) != 0) break;
    x += 8u;
  }
#endif
  while (x < g_detector_width &&
         signal[x] <= static_cast<int32_t>(g_detection_threshold))
    ++x;
  return x;
}

OM1_DETECTOR_HOT bool label_row(uint32_t image_y, const int32_t *signal,
                                const MeasurementRow &above,
                                const MeasurementRow &measurement,
                                const MeasurementRow &below,
                                const uint8_t *saturation, Error *error) {
  Run *previous = g_runs[g_run_bank];
  Run *current_runs = g_runs[g_run_bank ^ 1u];

  for (uint32_t i = 0; i < g_component_count; ++i) g_parent[i] = i;
  uint32_t slot_count = g_component_count;
  uint32_t current_count = 0;
  uint32_t previous_cursor = 0;
  uint32_t x = 0;

  for (;;) {
    x = find_foreground(signal, x);
    if (x == g_detector_width) break;
    if (current_count >= kMaxRuns)
      return detector_fail(error, "detector row-run capacity exceeded");

    const uint32_t run_left = x;
    Component aggregate;
    begin_run_component(&aggregate, x, image_y, above, measurement, below,
                        saturation[x] != 0);
    ++x;
    while (x < g_detector_width &&
           signal[x] > static_cast<int32_t>(g_detection_threshold)) {
      add_component_pixel(&aggregate, x, image_y, above, measurement, below,
                          saturation[x] != 0);
      ++x;
    }
    const uint32_t run_right = x - 1u;

    while (previous_cursor < g_previous_run_count &&
           static_cast<uint32_t>(previous[previous_cursor].right) + 1u <
               run_left)
      ++previous_cursor;
    uint32_t overlap = previous_cursor;
    uint16_t component = kNoComponent;
    while (overlap < g_previous_run_count &&
           static_cast<uint32_t>(previous[overlap].left) <= run_right + 1u) {
      const uint16_t root = find_root(previous[overlap].component);
      component = component == kNoComponent ? root : unite(component, root);
      ++overlap;
    }

    if (component == kNoComponent) {
      if (slot_count >= kMaxComponentSlots)
        return detector_fail(error, "detector component capacity exceeded");
      component = static_cast<uint16_t>(slot_count++);
      g_parent[component] = component;
      copy_component(&g_components[component], aggregate);
    } else {
      component = find_root(component);
      merge_component_values(&g_components[component], aggregate);
    }
    current_runs[current_count].left = static_cast<uint16_t>(run_left);
    current_runs[current_count].right = static_cast<uint16_t>(run_right);
    current_runs[current_count].component = component;
    ++current_count;
  }

  for (uint32_t i = 0; i < slot_count; ++i) g_root_active[i] = 0;
  for (uint32_t i = 0; i < current_count; ++i) {
    const uint16_t root = find_root(current_runs[i].component);
    current_runs[i].component = root;
    g_root_active[root] = 1;
  }
  // Union-retire frontier: after this row, a root with no current-row run can
  // never be reached by a later 8-connected pixel.  Finalize it immediately,
  // then compact only live roots.  Consequently component/link storage is
  // bounded by the maximum run count of two adjacent rows, not image area.
  for (uint32_t i = 0; i < slot_count; ++i) {
    if (g_parent[i] == i && g_root_active[i] == 0)
      finalize_component(g_components[i]);
  }

  uint32_t compact_count = 0;
  for (uint32_t i = 0; i < slot_count; ++i) {
    if (g_parent[i] != i || g_root_active[i] == 0) continue;
    g_root_map[i] = static_cast<uint16_t>(compact_count);
    if (compact_count != i)
      copy_component(&g_components[compact_count], g_components[i]);
    ++compact_count;
  }
  for (uint32_t i = 0; i < current_count; ++i)
    current_runs[i].component = g_root_map[current_runs[i].component];

  g_component_count = compact_count;
  g_previous_run_count = current_count;
  g_run_bank ^= 1u;
  return true;
}

void build_detection_row(const int32_t *above, const int32_t *center,
                         const int32_t *below) {
  uint32_t x = 0;
#if defined(__ARM_NEON)
  for (; x + 4u <= g_detector_width; x += 4u) {
    const int32x4_t middle = vld1q_s32(&center[x]);
    const int32x4_t sum =
        vaddq_s32(vaddq_s32(vld1q_s32(&above[x]),
                            vld1q_s32(&below[x])),
                  vshlq_n_s32(middle, 1));
    vst1q_s32(&g_detection_row[x], rounded_quarter_neon(sum));
  }
#endif
  for (; x < g_detector_width; ++x) {
    g_detection_row[x] = rounded_quarter(
        static_cast<int64_t>(above[x]) + 2 * center[x] + below[x]);
  }
}

OM1_DETECTOR_HOT bool process_slab(Error *error) {
  if (g_slab_row_count == 0) return true;
  prepare_detection_signal();
  const bool final_slab =
      g_slab_start_y + g_slab_row_count == g_detector_height;

  if (g_have_pending_row) {
    const int32_t *below = &g_horizontal[0];
    build_detection_row(g_pending_horizontal_above, g_pending_horizontal,
                        below);
    const MeasurementRow above = {g_pending_luma_above,
                                  g_pending_background_above, 0u};
    const MeasurementRow current = {g_pending_luma, g_pending_background, 0u};
    const MeasurementRow next = {&g_luma[0], nullptr, 0u};
    const uint32_t saved_threshold = g_detection_threshold;
    g_detection_threshold = g_pending_detection_threshold;
    if (!label_row(g_slab_start_y - 1u, g_detection_row, above, current, next,
                   g_pending_saturation, error))
      return false;
    g_detection_threshold = saved_threshold;
  }

  const uint32_t rows_to_process =
      g_slab_row_count == 0 ? 0 : g_slab_row_count - 1u;
  for (uint32_t y = 0; y < rows_to_process; ++y) {
    const int32_t *center = &g_horizontal[y * kMaxDetectorWidth];
    const int32_t *above =
        y != 0 ? center - kMaxDetectorWidth
               : (g_have_pending_row ? g_pending_horizontal : center);
    const int32_t *below = center + kMaxDetectorWidth;
    build_detection_row(above, center, below);
    const uint16_t *luma = &g_luma[y * kMaxDetectorWidth];
    MeasurementRow before;
    if (g_slab_start_y + y == 0) {
      before = MeasurementRow{nullptr, nullptr, 0u};
    } else if (y == 0) {
      before = MeasurementRow{g_pending_luma, g_pending_background, 0u};
    } else {
      before = MeasurementRow{luma - kMaxDetectorWidth, nullptr,
                              static_cast<uint16_t>(y - 1u)};
    }
    const MeasurementRow current = {luma, nullptr,
                                    static_cast<uint16_t>(y)};
    const MeasurementRow next = {luma + kMaxDetectorWidth, nullptr,
                                 static_cast<uint16_t>(y + 1u)};
    if (!label_row(g_slab_start_y + y, g_detection_row, before, current, next,
                   &g_saturation[y * kMaxDetectorWidth], error))
      return false;
  }

  const uint32_t last = g_slab_row_count - 1u;
  const uint32_t above = last == 0 ? last : last - 1u;
  for (uint32_t x = 0; x < g_detector_width; ++x) {
    const int32_t previous_horizontal = g_pending_horizontal[x];
    const uint16_t previous_luma = g_pending_luma[x];
    const uint16_t previous_background = g_pending_background[x];
    g_pending_horizontal_above[x] = last == 0 && g_have_pending_row
                                        ? previous_horizontal
                                        : g_horizontal[above *
                                                           kMaxDetectorWidth +
                                                       x];
    g_pending_horizontal[x] = g_horizontal[last * kMaxDetectorWidth + x];
    g_pending_luma_above[x] = last == 0 && g_have_pending_row
                                  ? previous_luma
                                  : g_luma[above * kMaxDetectorWidth + x];
    g_pending_luma[x] = g_luma[last * kMaxDetectorWidth + x];
    g_pending_background_above[x] = last == 0 && g_have_pending_row
                                        ? previous_background
                                        : background_at_y(above, x);
    g_pending_background[x] = background_at_y(last, x);
    g_pending_saturation[x] = g_saturation[last * kMaxDetectorWidth + x];
  }
  g_pending_detection_threshold = g_detection_threshold;
  g_have_pending_row = true;

  g_slab_start_y += g_slab_row_count;
  g_slab_row_count = 0;
  if (final_slab) {
    build_detection_row(g_pending_horizontal_above, g_pending_horizontal,
                        g_pending_horizontal);
    const MeasurementRow before = {g_pending_luma_above,
                                   g_pending_background_above, 0u};
    const MeasurementRow current = {g_pending_luma, g_pending_background, 0u};
    const MeasurementRow no_next = {nullptr, nullptr, 0u};
    const uint32_t saved_threshold = g_detection_threshold;
    g_detection_threshold = g_pending_detection_threshold;
    if (!label_row(g_detector_height - 1u, g_detection_row, before, current,
                   no_next, g_pending_saturation, error))
      return false;
    g_detection_threshold = saved_threshold;
    g_have_pending_row = false;
  }
  return true;
}

bool commit_luma_row(Error *error) {
  ++g_slab_row_count;
  if (g_slab_row_count == kSlabRows ||
      g_slab_start_y + g_slab_row_count == g_detector_height)
    return process_slab(error);
  return true;
}

OM1_DETECTOR_HOT bool append_luma_row(
    const uint32_t *sums, const uint8_t *cell_saturated,
    uint32_t source_row_count, Error *error) {
  const uint32_t slab_y = g_slab_row_count;
  uint16_t *destination = &g_luma[slab_y * kMaxDetectorWidth];
  uint8_t *saturation = &g_saturation[slab_y * kMaxDetectorWidth];

  uint32_t x = 0;
#if defined(__ARM_NEON)
  if (source_row_count == 2u) {
    const uint32_t full_columns = g_info.crop_width >> 1;
    const uint16x8_t white = vdupq_n_u16(
        static_cast<uint16_t>(g_white_code));
    for (; x + 8u <= full_columns; x += 8u) {
      const uint16x4_t rounded0 =
          vrshrn_n_u32(vld1q_u32(&sums[x]), 2);
      const uint16x4_t rounded1 =
          vrshrn_n_u32(vld1q_u32(&sums[x + 4u]), 2);
      vst1q_u16(&destination[x],
                vminq_u16(vcombine_u16(rounded0, rounded1), white));
      vst1_u8(&saturation[x], vld1_u8(&cell_saturated[x]));
    }
  }
#endif
  for (; x < g_detector_width; ++x) {
    const uint32_t source_column_count =
        x * 2u + 1u < g_info.crop_width ? 2u : 1u;
    const uint32_t count = source_row_count * source_column_count;
    const uint32_t sum = sums[x];
    uint32_t value;
    if (count == 4u)
      value = (sum + 2u) >> 2;
    else if (count == 2u)
      value = (sum + 1u) >> 1;
    else
      value = sum;
    if (value > g_white_code) value = g_white_code;
    destination[x] = static_cast<uint16_t>(value);
    saturation[x] = cell_saturated[x];
  }
  return commit_luma_row(error);
}

bool validate_begin(const RawInfo &info, const DetectorOptions &options,
                    Error *error) {
  if (info.width == 0 || info.height == 0 || info.width > kMaxRawWidth ||
      info.height > kMaxRawHeight)
    return fail(error, "detector raw dimensions are unsupported");
  if (info.crop_width == 0 || info.crop_height == 0 ||
      info.crop_x > info.width || info.crop_y > info.height ||
      info.crop_width > info.width - info.crop_x ||
      info.crop_height > info.height - info.crop_y)
    return fail(error, "detector active crop is invalid");
  if (info.bits_per_sample == 0 || info.bits_per_sample > 16 ||
      info.valid_bits == 0 || info.valid_bits > info.bits_per_sample)
    return fail(error, "detector sample precision is invalid");
  const uint32_t white = info.valid_bits == 16
                             ? 65535u
                             : (1u << info.valid_bits) - 1u;
  for (uint32_t i = 0; i < 4; ++i) {
    if (info.black[i] > white)
      return fail(error, "detector black level exceeds sample range");
  }
  if (options.sigma_q8 == 0 || options.sigma_q8 > 16384u)
    return fail(error, "detector sigma is outside 1/256..64 range");
  if (options.min_area == 0 || options.max_area < options.min_area)
    return fail(error, "detector component area range is invalid");
  if (options.max_elongation_q8 != 0 &&
      options.max_elongation_q8 < 256u)
    return fail(error, "detector elongation limit is below one");
  if (options.max_sharpness_q8 > 256u)
    return fail(error, "detector sharpness limit exceeds one");
  const uint32_t detector_width = (info.crop_width + 1u) >> 1;
  const uint32_t detector_height = (info.crop_height + 1u) >> 1;
  if (options.border_margin >= detector_width ||
      options.border_margin >= detector_height)
    return fail(error, "detector border margin is too large");
  return true;
}

uint32_t corrected_sample(uint16_t sample, uint16_t black) {
  uint32_t bounded = sample;
  if (bounded > g_white_code) bounded = g_white_code;
  return bounded > black ? bounded - black : 0;
}

}  // namespace

bool detector_begin(const RawInfo &info, const DetectorOptions &options,
                    Error *error) {
  clear_error(error);
  g_started = false;
  g_failed = false;
  if (!validate_begin(info, options, error)) return false;

  copy_raw_info(&g_info, info);
  g_options.sigma_q8 = options.sigma_q8;
  g_options.min_area = options.min_area;
  g_options.max_area = options.max_area;
  g_options.max_elongation_q8 = options.max_elongation_q8;
  g_options.max_sharpness_q8 = options.max_sharpness_q8;
  g_options.border_margin = options.border_margin;
  g_expected_row = 0;
  g_detector_width = (info.crop_width + 1u) >> 1;
  g_detector_height = (info.crop_height + 1u) >> 1;
  g_white_code = info.valid_bits == 16
                     ? 65535u
                     : (1u << info.valid_bits) - 1u;
  g_slab_start_y = 0;
  g_slab_row_count = 0;
  g_component_count = 0;
  g_previous_run_count = 0;
  g_best_count = 0;
  g_run_bank = 0;
  g_input_mode = kInputUnset;
  g_have_pending_row = false;
  g_started = true;
  return true;
}

OM1_DETECTOR_HOT bool detector_consume_row(void *context, uint32_t row,
                                          const uint16_t *samples,
                                          uint32_t width, Error *error) {
  (void)context;
  if (!g_started) return fail(error, "detector has not been started");
  if (g_failed) return fail(error, "detector is in a failed state");
  if (samples == nullptr)
    return detector_fail(error, "detector received a null raw row");
  if (g_input_mode == kInputUnset) g_input_mode = kInputRawRows;
  if (g_input_mode != kInputRawRows)
    return detector_fail(error, "detector input row modes were mixed");
  if (row != g_expected_row || row >= g_info.height)
    return detector_fail(error, "detector raw rows are not in raster order");
  if (width != g_info.width)
    return detector_fail(error, "detector raw row width changed");

  ++g_expected_row;
  if (row < g_info.crop_y || row - g_info.crop_y >= g_info.crop_height)
    return true;

  const uint32_t active_y = row - g_info.crop_y;
  const uint32_t first = g_info.crop_x;
  const uint32_t full_cells = g_info.crop_width >> 1;
  const uint32_t first_phase = ((row & 1u) << 1) | (first & 1u);
  const uint16_t black_left = g_info.black[first_phase];
  const uint16_t black_right = g_info.black[first_phase ^ 1u];
  const bool first_cell_row = (active_y & 1u) == 0u;
  const uint16_t *source = &samples[first];
  uint32_t cell_x = 0;
#if defined(__ARM_NEON)
  const uint16x4x2_t black_zip =
      vzip_u16(vdup_n_u16(black_left), vdup_n_u16(black_right));
  const uint16x8_t black = vcombine_u16(black_zip.val[0], black_zip.val[1]);
  const uint16x8_t white = vdupq_n_u16(static_cast<uint16_t>(g_white_code));
  for (; cell_x + 8u <= full_cells; cell_x += 8u) {
    const uint16x8_t raw0 = vld1q_u16(&source[cell_x * 2u]);
    const uint16x8_t raw1 = vld1q_u16(&source[cell_x * 2u + 8u]);
    const uint16x8_t corrected0 = vqsubq_u16(vminq_u16(raw0, white), black);
    const uint16x8_t corrected1 = vqsubq_u16(vminq_u16(raw1, white), black);
    const uint32x4_t sums0 = vpaddlq_u16(corrected0);
    const uint32x4_t sums1 = vpaddlq_u16(corrected1);
    if (first_cell_row) {
      vst1q_u32(&g_cell_sum[cell_x], sums0);
      vst1q_u32(&g_cell_sum[cell_x + 4u], sums1);
    } else {
      vst1q_u32(&g_cell_sum[cell_x],
                vaddq_u32(vld1q_u32(&g_cell_sum[cell_x]), sums0));
      vst1q_u32(&g_cell_sum[cell_x + 4u],
                vaddq_u32(vld1q_u32(&g_cell_sum[cell_x + 4u]), sums1));
    }

    const uint16x8_t clipped0 = vcgeq_u16(raw0, white);
    const uint16x8_t clipped1 = vcgeq_u16(raw1, white);
    const uint16x4_t clipped_pairs0 =
        vpmax_u16(vget_low_u16(clipped0), vget_high_u16(clipped0));
    const uint16x4_t clipped_pairs1 =
        vpmax_u16(vget_low_u16(clipped1), vget_high_u16(clipped1));
    uint8x8_t clipped_pairs =
        vmovn_u16(vcombine_u16(clipped_pairs0, clipped_pairs1));
    if (!first_cell_row)
      clipped_pairs =
          vorr_u8(clipped_pairs, vld1_u8(&g_cell_saturated[cell_x]));
    vst1_u8(&g_cell_saturated[cell_x], clipped_pairs);
  }
#endif
  for (; cell_x < full_cells; ++cell_x) {
    const uint16_t left = source[cell_x * 2u];
    const uint16_t right = source[cell_x * 2u + 1u];
    const uint32_t sum = corrected_sample(left, black_left) +
                         corrected_sample(right, black_right);
    const uint8_t saturated =
        (left >= g_white_code || right >= g_white_code) ? 1u : 0u;
    if (first_cell_row) {
      g_cell_sum[cell_x] = sum;
      g_cell_saturated[cell_x] = saturated;
    } else {
      g_cell_sum[cell_x] += sum;
      g_cell_saturated[cell_x] = static_cast<uint8_t>(
          g_cell_saturated[cell_x] | saturated);
    }
  }
  if ((g_info.crop_width & 1u) != 0u) {
    const uint32_t cell_x = full_cells;
    const uint16_t sample = source[full_cells * 2u];
    const uint32_t sum = corrected_sample(sample, black_left);
    const uint8_t saturated = sample >= g_white_code ? 1u : 0u;
    if (first_cell_row) {
      g_cell_sum[cell_x] = sum;
      g_cell_saturated[cell_x] = saturated;
    } else {
      g_cell_sum[cell_x] += sum;
      g_cell_saturated[cell_x] = static_cast<uint8_t>(
          g_cell_saturated[cell_x] | saturated);
    }
  }

  if ((active_y & 1u) != 0u || active_y + 1u == g_info.crop_height) {
    const uint32_t source_row_count = (active_y & 1u) != 0u ? 2u : 1u;
    if (!append_luma_row(g_cell_sum, g_cell_saturated, source_row_count,
                         error))
      return false;
  }
  return true;
}

OM1_DETECTOR_HOT bool detector_consume_luma_row(
    void *context, uint32_t row, const uint32_t *sums,
    const uint8_t *saturated, uint32_t width, Error *error) {
  (void)context;
  if (!g_started) return fail(error, "detector has not been started");
  if (g_failed) return fail(error, "detector is in a failed state");
  if (sums == nullptr || saturated == nullptr)
    return detector_fail(error, "detector received a null luma row");
  if (g_input_mode == kInputUnset) g_input_mode = kInputLumaRows;
  if (g_input_mode != kInputLumaRows)
    return detector_fail(error, "detector input row modes were mixed");
  if (row != g_expected_row || row >= g_detector_height)
    return detector_fail(error, "detector luma rows are not in raster order");
  if (width != g_detector_width)
    return detector_fail(error, "detector luma row width changed");

  ++g_expected_row;
  const uint32_t source_row_count =
      row * 2u + 1u < g_info.crop_height ? 2u : 1u;
  return append_luma_row(sums, saturated, source_row_count, error);
}

bool detector_finish(Star out[kMaxStars], uint32_t *count, Error *error) {
  if (!g_started) return fail(error, "detector has not been started");
  if (g_failed) return fail(error, "detector is in a failed state");
  if (out == nullptr || count == nullptr)
    return detector_fail(error, "detector output pointer is null");
  if (g_input_mode == kInputUnset)
    return detector_fail(error, "detector received no image rows");
  const uint32_t expected_rows = g_input_mode == kInputRawRows
                                     ? g_info.height
                                     : g_detector_height;
  if (g_expected_row != expected_rows)
    return detector_fail(error, "detector did not receive every image row");
  if (!process_slab(error)) return false;

  for (uint32_t i = 0; i < g_component_count; ++i)
    finalize_component(g_components[i]);
  g_component_count = 0;
  g_previous_run_count = 0;

  // Convert the bounded admission heap into the public descending order.
  for (uint32_t i = 1; i < g_best_count; ++i) {
    uint32_t position = i;
    while (position != 0 &&
           star_better(g_best[position], g_best[position - 1u])) {
      swap_star(&g_best[position], &g_best[position - 1u]);
      --position;
    }
  }
  for (uint32_t i = 0; i < g_best_count; ++i) copy_star(&out[i], g_best[i]);
  *count = g_best_count;
  g_started = false;
  clear_error(error);
  return true;
}

}  // namespace om1_stars
