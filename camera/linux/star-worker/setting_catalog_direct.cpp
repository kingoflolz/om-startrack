#include "setting_catalog_direct.h"

#include <limits.h>
#include <stddef.h>
#include <stdint.h>

namespace om1_stars {
namespace direct_catalog {
namespace {

constexpr unsigned kExposureStepContextIndex = 9;
constexpr unsigned kIsoStepContextIndex = 10;

// Stock v1.9 aperture banks selected by property 35818 at runtime
// 0x606e2bf8/0x606e2c48/0x606e2c7e.  Bound projection relies on their exact
// order because the stock helpers return bank indices, not arbitrary values.
extern "C" int16_t const kApertureNative85[40];
extern "C" int16_t const kApertureNative128[27];
extern "C" int16_t const kApertureNative256[14];

// First dword of the stock normalized aperture pair for each native row,
// expressed as deci-f-number.  Runtime sources are
// 0x606ef694/0x606ef6e6/0x606ef71e, omitting their leading f/0.9 row.
extern "C" uint16_t const kApertureNominalDeci85[40];
extern "C" uint16_t const kApertureNominalDeci128[27];
extern "C" uint16_t const kApertureNominalDeci256[14];

// Fractional power table supplied from the input firmware.
extern "C" uint16_t const kAperturePow2Fraction[256];

static_assert(sizeof(kApertureNative85) / sizeof(kApertureNative85[0]) ==
                      sizeof(kApertureNominalDeci85) /
                          sizeof(kApertureNominalDeci85[0]) &&
                  sizeof(kApertureNative128) /
                          sizeof(kApertureNative128[0]) ==
                      sizeof(kApertureNominalDeci128) /
                          sizeof(kApertureNominalDeci128[0]) &&
                  sizeof(kApertureNative256) /
                          sizeof(kApertureNative256[0]) ==
                      sizeof(kApertureNominalDeci256) /
                          sizeof(kApertureNominalDeci256[0]),
              "stock aperture bank/normalizer tables diverged");

struct ApertureBank {
  const int16_t *native;
  const uint16_t *nominal_deci;
  uint16_t count;
  uint16_t upper_half_step;
};

bool select_aperture_bank(int32_t step, ApertureBank *bank) {
  if (bank == nullptr) return false;
  *bank = {};
  if (step == 85) {
    bank->native = kApertureNative85;
    bank->nominal_deci = kApertureNominalDeci85;
    bank->count = 40;
    bank->upper_half_step = 43;
    return true;
  }
  if (step == 128) {
    bank->native = kApertureNative128;
    bank->nominal_deci = kApertureNominalDeci128;
    bank->count = 27;
    bank->upper_half_step = 64;
    return true;
  }
  if (step == 256) {
    bank->native = kApertureNative256;
    bank->nominal_deci = kApertureNominalDeci256;
    bank->count = 14;
    bank->upper_half_step = 128;
    return true;
  }
  return false;
}

// Mirror stock 0x60590d6b -> 0x6012b186 -> 0x6057379f ->
// 0x605736c1 for the nonnegative, in-bank raw lens-minimum domain.  The
// result is the first dword of the stock normalized {numerator, 10} pair.
bool continuous_aperture_deci(int16_t raw, uint16_t *deci) {
  if (deci == nullptr) return false;
  *deci = 0;
  if (raw < 0) return false;
  const uint32_t half_raw = static_cast<uint16_t>(raw) / 2u;
  const uint32_t exponent = half_raw >> 8;
  // project_aperture_bounds() limits raw below the pinned final native row
  // (3328), so exponent is at most 6 and every shift remains inside uint32_t.
  // Keep the bound explicit so a future bank change fails closed.
  if (exponent > 6u) return false;
  const uint32_t fixed =
      (UINT32_C(65536) + kAperturePow2Fraction[half_raw & 0xffu])
      << exponent;
  const uint32_t quotient = fixed / UINT32_C(6553);
  const uint32_t remainder = fixed % UINT32_C(6553);
  uint32_t rounded = quotient * UINT32_C(6553);
  if (remainder >= UINT32_C(3275)) rounded += UINT32_C(6553);
  uint32_t scaled =
      ((rounded + UINT32_C(655)) & UINT32_C(0x00ffff00)) >> 8;
  if (scaled > UINT32_C(2560)) {
    scaled = (scaled + UINT32_C(128)) & UINT32_C(0x0000ff00);
  }
  const uint32_t normalized = scaled * UINT32_C(10) / UINT32_C(256);
  if (normalized > UINT16_MAX) return false;
  *deci = static_cast<uint16_t>(normalized);
  return true;
}

void clear_result(Result *result) {
  if (result == nullptr) return;
  for (unsigned setting = 0; setting < kSettingCount; ++setting) {
    result->value_count[setting] = 0;
    result->current_index[setting] = 0;
    for (unsigned index = 0; index < kMaxValues; ++index) {
      result->values[setting][index] = 0;
      result->native_index[setting][index] = 0;
    }
  }
  result->label_bytes = 0;
  for (unsigned byte = 0; byte < kLabelStorage; ++byte)
    result->labels[byte] = 0;
  result->aperture = {};
  result->mapping_certificate = 0;
  result->mutation_certificate = 0;
}

bool signed16(int32_t value) {
  return value >= INT16_MIN && value <= INT16_MAX;
}

int16_t derive_mode_helper(const int32_t context[kContextCount]) {
  const int32_t s41 = context[0];
  const int32_t s44 = context[1];
  const int32_t s109 = context[2];
  const int32_t s277 = context[3];
  const int32_t s26 = context[4];
  const int32_t s1444 = context[5];
  switch (s41) {
    case 1:
    case 5:
      return 53;
    case 2:
    case 6:
      return 54;
    case 3:
    case 7:
      return 55;
    case 4:
    case 8:
      return 56;
    default:
      if (s44 != 0) return 10;
      if (s109 == 2) return 49;
      if (s109 == 1) return s277 == 0 ? 42 : 51;
      switch (s26) {
        case 4:
          return 2;
        case 5:
          return 3;
        case 6:
          return s1444 == 1 ? 62 : 4;
        case 8:
          return 12;
        case 24:
        case 190:
        case 191:
        case 192:
          return 29;
        case 36:
          return 45;
        case 37:
          return 46;
        case 101:
          return 52;
        case 185:
        case 186:
        case 187:
        case 188:
        case 189:
          return 57;
        default:
          return 1;
      }
  }
}

void derive_sources(const int32_t context[kContextCount], int16_t helper,
                    uint16_t sources[kSettingCount]) {
  const bool alternate = helper == 10 || context[6] == 1;
  sources[kShutter] =
      alternate ? UINT16_C(1670)
                : (context[5] == 1 && context[7] == 6 ? UINT16_C(1445)
                                                       : UINT16_C(1669));
  sources[kAperture] = alternate ? UINT16_C(1668) : UINT16_C(1667);
  sources[kIso] = alternate ? UINT16_C(1672) : UINT16_C(1671);
}

uint16_t expected_native_count(unsigned setting, uint16_t source,
                               int32_t exposure_step, int32_t iso_step) {
  if (setting == kShutter) {
    if (source == 1445) return 3;
    if (source != 1669 && source != 1670) return 0;
    if (exposure_step == 85) return 69;
    if (exposure_step == 128) return 50;
    if (exposure_step == 256) return 29;
    return 0;
  }
  if (setting == kAperture) {
    if (source != 1667 && source != 1668) return 0;
    if (exposure_step == 85) return 40;
    if (exposure_step == 128) return 27;
    if (exposure_step == 256) return 14;
    return 0;
  }
  if (setting != kIso) return 0;
  if (source == 1671) {
    if (iso_step == 85) return 31;
    if (iso_step == 256) return 13;
  } else if (source == 1672) {
    if (iso_step == 85) return 20;
    if (iso_step == 256) return 8;
  }
  return 0;
}

bool labels_equal(const NativeEntry &left, const NativeEntry &right) {
  if (left.label_length != right.label_length) return false;
  for (uint8_t byte = 0; byte < left.label_length; ++byte) {
    if (left.label[byte] != right.label[byte]) return false;
  }
  return true;
}

bool validate_native_tables(const Snapshot &snapshot) {
  const int32_t exposure_step =
      snapshot.context_values[kExposureStepContextIndex];
  const int32_t iso_step = snapshot.context_values[kIsoStepContextIndex];
  for (unsigned setting = 0; setting < kSettingCount; ++setting) {
    const uint16_t count = snapshot.native_count[setting];
    if (count == 0 || count > kMaxValues ||
        count != expected_native_count(setting, snapshot.source_id[setting],
                                       exposure_step, iso_step)) {
      return false;
    }
    for (uint16_t index = 0; index < count; ++index) {
      const NativeEntry &entry = snapshot.native[setting][index];
      if (entry.label_length == 0 || entry.label_length > kLabelMax)
        return false;
      for (uint8_t byte = 0; byte < entry.label_length; ++byte) {
        const uint8_t value = static_cast<uint8_t>(entry.label[byte]);
        if (value < 0x21u || value > 0x7eu) return false;
      }
      for (uint16_t prior = 0; prior < index; ++prior) {
        if (snapshot.native[setting][prior].value == entry.value ||
            labels_equal(snapshot.native[setting][prior], entry)) {
          return false;
        }
      }
    }
  }
  return true;
}

int32_t effective_shutter_mode(const Snapshot &snapshot) {
  int32_t effective = snapshot.p49169;
  if (snapshot.ram8d0 != 3 && snapshot.p17665 == 1 &&
      snapshot.p37297_selector1 == 0) {
    if (snapshot.p49169 == 199) return 199;
    // The stock canonicalizer installs mode 3 before consulting the
    // availability helper record. An unavailable helper has a defined value;
    // it does not fall back to the raw property.
    effective = snapshot.ramde48 == 1 ? snapshot.helper_mode : 3;
  }
  return effective;
}

bool shutter_domain_supported(const Snapshot &snapshot, int32_t *lower,
                              int32_t *upper) {
  if (lower == nullptr || upper == nullptr ||
      (snapshot.source_id[kShutter] != 1669 &&
       snapshot.source_id[kShutter] != 1670) ||
      snapshot.context_values[kExposureStepContextIndex] != snapshot.p35818 ||
      snapshot.context_values[8] != 0 || snapshot.mode_helper == 62 ||
      snapshot.helper_index >= 3) {
    return false;
  }
  const int32_t mode = effective_shutter_mode(snapshot);
  const int32_t effective33697 =
      snapshot.ramde48 == 1 ? snapshot.p33697 : 0;
  if (mode < 3 || mode > 6 || snapshot.p26481 == 1 ||
      effective33697 == 1 || snapshot.p34225 == 5 ||
      snapshot.p21489 == 1 || snapshot.p7745 == 1) {
    return false;
  }

  int32_t lo = -1536;
  if (snapshot.p34225 == 3 && lo < 1194) lo = 1194;
  if (snapshot.p34225 == 4 && lo < 1024) lo = 1024;
  if ((snapshot.p59665 == 1 || snapshot.p59665 == 2) && lo < -512)
    lo = -512;
  int32_t hi = 3840;
  if ((snapshot.p61697 == 0 || snapshot.p61633 == 1) && hi > 3328)
    hi = 3328;
  *lower = lo;
  *upper = hi;
  return lo <= hi;
}

bool shutter_is_grid_member(int16_t value, int32_t exposure_step) {
  return value != INT16_MIN && value != INT16_MIN + 1 &&
         value != INT16_MAX && value != 1173 && value != 1429 &&
         (exposure_step == 85 || (value != 1194 && value != 1450));
}

bool iso_1671_legal(const Snapshot &snapshot, int16_t value) {
  const bool automatic = value == 0;
  const int32_t s24 = snapshot.context_values[7];
  const int32_t s1679 = snapshot.context_values[8];
  if (snapshot.iso598 == 1 || snapshot.iso598 == 2)
    return value == 1536;
  if ((s24 == 6 && snapshot.current1669 == INT16_MIN &&
       snapshot.iso434 == 0) ||
      (s24 == 6 && snapshot.current1669 == INT16_MAX &&
       snapshot.iso435 == 0)) {
    return !automatic;
  }
  if (s24 == 6 &&
      (snapshot.current1669 == INT16_MIN ||
       snapshot.current1669 == INT16_MAX ||
       snapshot.current1669 == INT16_MIN + 1)) {
    return !automatic && value <= 2816;
  }
  if (s24 == 6 && snapshot.iso239 == 1 && snapshot.iso754 == 0 &&
      s1679 == 0 && snapshot.iso1712 == 5) {
    return !automatic && value <= 3072;
  }
  if (s24 == 6 && snapshot.iso239 == 1 && snapshot.iso754 == 0 &&
      s1679 == 0) {
    return !automatic;
  }
  if (s24 == 6 && snapshot.iso239 == 1 && snapshot.iso754 == 0 &&
      s1679 == 1) {
    return !automatic && value <= 2048;
  }
  if (s24 == 6 && snapshot.iso239 == 1 && snapshot.iso754 == 1 &&
      snapshot.iso1678 == 0) {
    return !automatic && value <= 2304;
  }
  if (snapshot.iso754 == 1 && snapshot.iso1678 == 0)
    return automatic || value <= 2304;
  if (snapshot.iso754 == 0 && s1679 == 1)
    return automatic || value <= 2048;
  if (snapshot.iso1712 == 5) return automatic || value <= 3072;
  return true;
}

bool iso_1672_legal(const Snapshot &snapshot, int16_t value) {
  const bool automatic = value == 0;
  const int32_t s44 = snapshot.context_values[1];
  const int32_t s15209 = snapshot.context_values[6];
  const int32_t s24 = snapshot.context_values[7];
  if (s44 == 0 && s15209 == 1) return automatic;
  if (s44 == 1 && (s24 == 76 || s24 == 77 || s24 == 89))
    return automatic;
  if (s44 == 1 && s24 == 90 && snapshot.iso1325 == 1 &&
      (snapshot.iso1606 == 255 || snapshot.iso1606 == 253)) {
    if (snapshot.iso1673 == 0) return !automatic && value >= 1792;
    if (snapshot.iso1673 == 1) return automatic || value >= 1792;
  }
  if (s44 == 1 && s24 == 90 && snapshot.iso1673 == 0)
    return !automatic && value >= 1536;
  if (s44 == 1 && s24 == 90 && snapshot.iso1673 == 1) return true;
  return true;
}

bool entry_is_legal(const Snapshot &snapshot, unsigned setting,
                    int16_t value, int32_t shutter_lower,
                    int32_t shutter_upper) {
  if (setting == kAperture) return true;
  if (setting == kShutter) {
    if (snapshot.source_id[kShutter] == 1445) {
      // This source has no generic/postfilter table: B0B accepts all three
      // native sentinels.  Publication is deliberately bounded to the
      // independently proved ND0/helper-62 description path, where CGI also
      // retains all three rows regardless of the upper-limit global.
      return true;
    }
    const bool b0b_legal =
        value == shutter_lower || value == shutter_upper ||
        (value > shutter_lower && value < shutter_upper &&
         shutter_is_grid_member(
             value,
             snapshot.context_values[kExposureStepContextIndex]));
    // CGI applies the retained display ceiling after B0B.  Keep it separate
    // from the B0B upper boundary: a non-grid row equal to this ceiling must
    // not accidentally become legal merely because it is the visible limit.
    return b0b_legal && value <= snapshot.shutter_ceiling;
  }
  if (snapshot.source_id[kIso] == 1671)
    return iso_1671_legal(snapshot, value);
  return iso_1672_legal(snapshot, value);
}

void hash_byte(uint32_t *hash, uint8_t value) {
  *hash ^= value;
  *hash *= UINT32_C(16777619);
}

void hash_u16(uint32_t *hash, uint16_t value) {
  hash_byte(hash, static_cast<uint8_t>(value));
  hash_byte(hash, static_cast<uint8_t>(value >> 8));
}

void hash_u32(uint32_t *hash, uint32_t value) {
  hash_u16(hash, static_cast<uint16_t>(value));
  hash_u16(hash, static_cast<uint16_t>(value >> 16));
}

uint32_t mapping_certificate(const Result &result) {
  uint32_t hash = UINT32_C(2166136261);
  for (unsigned setting = 0; setting < kSettingCount; ++setting) {
    hash_u16(&hash, result.value_count[setting]);
    for (uint16_t index = 0; index < result.value_count[setting]; ++index)
      hash_u16(&hash, static_cast<uint16_t>(result.values[setting][index]));
  }
  hash_u16(&hash, result.label_bytes);
  for (uint16_t byte = 0; byte < result.label_bytes; ++byte)
    hash_byte(&hash, result.labels[byte]);
  return hash;
}

uint32_t mutation_certificate(const Snapshot &snapshot,
                              const Result &result) {
  uint32_t hash = UINT32_C(2166136261);
  for (unsigned setting = 0; setting < kSettingCount; ++setting)
    hash_u16(&hash, snapshot.source_id[setting]);
  for (unsigned index = 0; index < kContextCount; ++index)
    hash_u32(&hash, static_cast<uint32_t>(snapshot.context_values[index]));
  hash_u16(&hash, static_cast<uint16_t>(snapshot.mode_helper));
  for (unsigned setting = 0; setting < kSettingCount; ++setting) {
    hash_u32(&hash, static_cast<uint32_t>(snapshot.active[setting]));
    hash_u32(&hash, static_cast<uint32_t>(snapshot.selected[setting]));
    hash_u16(&hash, snapshot.native_count[setting]);
    for (uint16_t index = 0; index < snapshot.native_count[setting]; ++index) {
      const NativeEntry &entry = snapshot.native[setting][index];
      hash_u16(&hash, static_cast<uint16_t>(entry.value));
      hash_byte(&hash, entry.label_length);
      for (uint8_t byte = 0; byte < entry.label_length; ++byte)
        hash_byte(&hash, static_cast<uint8_t>(entry.label[byte]));
    }
  }
  const int32_t signed_fields[] = {
      snapshot.current1669, snapshot.iso598,  snapshot.iso434,
      snapshot.iso435,      snapshot.iso239,  snapshot.iso754,
      snapshot.iso1712,     snapshot.iso1678, snapshot.iso1325,
      snapshot.iso1606,     snapshot.iso1673};
  for (int32_t value : signed_fields)
    hash_u32(&hash, static_cast<uint32_t>(value));
  const uint8_t byte_fields[] = {
      snapshot.ram8d0,    snapshot.ramde48, snapshot.helper_index,
      snapshot.helper_mode, snapshot.p49169, snapshot.p17665,
      snapshot.p37297_selector1, snapshot.p26481, snapshot.p34225,
      snapshot.p59665, snapshot.p61697, snapshot.p61633,
      snapshot.p33697, snapshot.p21489, snapshot.p7745};
  for (uint8_t value : byte_fields) hash_byte(&hash, value);
  hash_u16(&hash, static_cast<uint16_t>(snapshot.p35818));
  hash_u16(&hash, static_cast<uint16_t>(snapshot.shutter_ceiling));
  hash_u16(&hash, static_cast<uint16_t>(snapshot.shutter_floor));
  for (uint8_t value : snapshot.aperture_lens_id) hash_byte(&hash, value);
  hash_byte(&hash, snapshot.aperture_lens_selector);
  hash_byte(&hash, snapshot.aperture_zoom_index);
  hash_byte(&hash, snapshot.aperture_row_count);
  hash_u16(&hash, snapshot.aperture_focal_mm);
  hash_u16(&hash, static_cast<uint16_t>(snapshot.aperture_min));
  hash_u16(&hash, static_cast<uint16_t>(snapshot.aperture_max));
  for (unsigned setting = 0; setting < kSettingCount; ++setting) {
    hash_u16(&hash, result.value_count[setting]);
    hash_u16(&hash, result.current_index[setting]);
    for (uint16_t index = 0; index < result.value_count[setting]; ++index) {
      hash_u16(&hash, static_cast<uint16_t>(result.values[setting][index]));
      hash_u16(&hash, result.native_index[setting][index]);
    }
  }
  hash_u16(&hash, result.label_bytes);
  for (uint16_t byte = 0; byte < result.label_bytes; ++byte)
    hash_byte(&hash, result.labels[byte]);
  return hash;
}

}  // namespace

void extract_catalog_context(const Snapshot *snapshot,
                             CatalogContext *context) {
  if (context == nullptr) return;
  *context = {};
  if (snapshot == nullptr) return;
  for (unsigned setting = 0; setting < kSettingCount; ++setting)
    context->source_id[setting] = snapshot->source_id[setting];
  for (unsigned index = 0; index < kContextCount; ++index)
    context->context_values[index] = snapshot->context_values[index];
  const int32_t signed_fields[] = {
      snapshot->current1669, snapshot->iso598,  snapshot->iso434,
      snapshot->iso435,      snapshot->iso239,  snapshot->iso754,
      snapshot->iso1712,     snapshot->iso1678, snapshot->iso1325,
      snapshot->iso1606,     snapshot->iso1673};
  for (unsigned index = 0; index < 11; ++index)
    context->signed_fields[index] = signed_fields[index];
  const uint8_t byte_fields[] = {
      snapshot->ram8d0,    snapshot->ramde48,
      snapshot->helper_index, snapshot->helper_mode,
      snapshot->p49169,    snapshot->p17665,
      snapshot->p37297_selector1, snapshot->p26481,
      snapshot->p34225,    snapshot->p59665,
      snapshot->p61697,    snapshot->p61633,
      snapshot->p33697,    snapshot->p21489,
      snapshot->p7745};
  for (unsigned index = 0; index < 15; ++index)
    context->byte_fields[index] = byte_fields[index];
  context->mode_helper = snapshot->mode_helper;
  context->p35818 = snapshot->p35818;
  context->shutter_ceiling = snapshot->shutter_ceiling;
  context->shutter_floor = snapshot->shutter_floor;
}

bool catalog_context_equal(const CatalogContext *left,
                           const CatalogContext *right) {
  if (left == nullptr || right == nullptr ||
      left->mode_helper != right->mode_helper ||
      left->p35818 != right->p35818 ||
      left->shutter_ceiling != right->shutter_ceiling ||
      left->shutter_floor != right->shutter_floor) {
    return false;
  }
  for (unsigned setting = 0; setting < kSettingCount; ++setting) {
    if (left->source_id[setting] != right->source_id[setting]) return false;
  }
  for (unsigned index = 0; index < kContextCount; ++index) {
    if (left->context_values[index] != right->context_values[index])
      return false;
  }
  for (unsigned index = 0; index < 11; ++index) {
    if (left->signed_fields[index] != right->signed_fields[index])
      return false;
  }
  for (unsigned index = 0; index < 15; ++index) {
    if (left->byte_fields[index] != right->byte_fields[index]) return false;
  }
  return true;
}

bool project_aperture_bounds(const Snapshot *snapshot,
                             ApertureProjection *projection) {
  if (projection == nullptr) return false;
  *projection = {};
  if (snapshot == nullptr || snapshot->aperture_min < 0 ||
      snapshot->aperture_min > snapshot->aperture_max) {
    return false;
  }

  ApertureBank bank = {};
  if (!select_aperture_bank(
          snapshot->context_values[kExposureStepContextIndex], &bank) ||
      snapshot->native_count[kAperture] != bank.count) {
    return false;
  }
  for (uint16_t index = 0; index < bank.count; ++index) {
    if (snapshot->native[kAperture][index].value != bank.native[index]) {
      return false;
    }
  }

  // Stock 0x602ced16 seeds the lower endpoint with the greatest bank value
  // not above the raw minimum.  Its caller reads the following row, so reject
  // a minimum at/above the last row rather than reproducing an out-of-bank
  // read in the independently bounded evaluator.
  if (snapshot->aperture_min < bank.native[0] ||
      snapshot->aperture_min >= bank.native[bank.count - 1u]) {
    return false;
  }
  uint16_t minimum_index = 0;
  while (minimum_index + 1u < bank.count &&
         bank.native[minimum_index + 1u] <= snapshot->aperture_min) {
    ++minimum_index;
  }
  if (minimum_index + 1u >= bank.count) return false;

  // In 0x602ceb38..0x602ceb6c, stock normalizes the continuous physical
  // minimum and the next nominal bank row against that same lower reference.
  // The next row clamps to the minimum once its nominal deci-f-number is not
  // greater, making the two output dwords equal and advancing the floor seed.
  uint16_t continuous_deci = 0;
  if (!continuous_aperture_deci(snapshot->aperture_min,
                                &continuous_deci)) {
    return false;
  }
  if (continuous_deci >= bank.nominal_deci[minimum_index + 1u]) {
    ++minimum_index;
  }

  // Stock 0x602cecca uses a fixed half of the selected exposure step (85 is
  // rounded up to 43).  It stops when raw_max <= native[i] + half, so an exact
  // midpoint remains on the lower row; values outside the bank clamp to an
  // endpoint.
  uint16_t maximum_index = 0;
  while (maximum_index + 1u < bank.count &&
         static_cast<int32_t>(snapshot->aperture_max) >
             static_cast<int32_t>(bank.native[maximum_index]) +
                 bank.upper_half_step) {
    ++maximum_index;
  }
  if (minimum_index > maximum_index) return false;

  projection->minimum_index = minimum_index;
  projection->maximum_index = maximum_index;
  projection->minimum_value = bank.native[minimum_index];
  projection->maximum_value = bank.native[maximum_index];
  return true;
}

bool target_is_legal(const Snapshot *snapshot, unsigned setting,
                     int16_t value) {
  if (snapshot == nullptr || setting >= kSettingCount ||
      snapshot->native_count[setting] == 0u ||
      snapshot->native_count[setting] > kMaxValues) {
    return false;
  }
  bool native = false;
  uint16_t native_index = 0;
  for (uint16_t index = 0; index < snapshot->native_count[setting]; ++index) {
    if (snapshot->native[setting][index].value != value) continue;
    if (native) return false;
    native = true;
    native_index = index;
  }
  if (!native) return false;
  if (setting == kAperture) {
    ApertureProjection projection = {};
    return project_aperture_bounds(snapshot, &projection) &&
           native_index >= projection.minimum_index &&
           native_index <= projection.maximum_index;
  }
  int32_t shutter_lower = INT16_MIN;
  int32_t shutter_upper = INT16_MAX;
  if (setting == kShutter && snapshot->source_id[kShutter] != 1445 &&
      !shutter_domain_supported(*snapshot, &shutter_lower, &shutter_upper)) {
    return false;
  }
  if (setting == kIso && snapshot->source_id[kIso] != 1671 &&
      snapshot->source_id[kIso] != 1672) {
    return false;
  }
  return entry_is_legal(*snapshot, setting, value, shutter_lower,
                        shutter_upper);
}

Status evaluate(const Snapshot *snapshot, Result *result) {
  clear_result(result);
  if (snapshot == nullptr || result == nullptr) return Status::kInvalid;

  for (unsigned index = 0; index < kContextCount; ++index) {
    if (!signed16(snapshot->context_values[index])) return Status::kInvalid;
  }
  for (unsigned setting = 0; setting < kSettingCount; ++setting) {
    if (!signed16(snapshot->active[setting]) ||
        !signed16(snapshot->selected[setting])) {
      return Status::kInvalid;
    }
    if (snapshot->active[setting] != snapshot->selected[setting]) {
      return Status::kUnsupported;
    }
  }
  if (!signed16(snapshot->current1669)) return Status::kInvalid;

  const int16_t helper = derive_mode_helper(snapshot->context_values);
  if (helper != snapshot->mode_helper) return Status::kUnsupported;
  uint16_t expected_sources[kSettingCount] = {};
  derive_sources(snapshot->context_values, helper, expected_sources);
  for (unsigned setting = 0; setting < kSettingCount; ++setting) {
    if (snapshot->source_id[setting] != expected_sources[setting])
      return Status::kUnsupported;
  }
  const int32_t exposure_step =
      snapshot->context_values[kExposureStepContextIndex];
  const int32_t iso_step = snapshot->context_values[kIsoStepContextIndex];
  if ((exposure_step != 85 && exposure_step != 128 &&
       exposure_step != 256) ||
      (iso_step != 85 && iso_step != 256)) {
    return Status::kUnsupported;
  }
  if (!validate_native_tables(*snapshot)) return Status::kInvalid;
  if (snapshot->context_values[kExposureStepContextIndex] !=
      snapshot->p35818) {
    return Status::kUnsupported;
  }
  bool lens_id_present = false;
  for (uint8_t byte : snapshot->aperture_lens_id)
    lens_id_present = lens_id_present || byte != 0u;
  if (!lens_id_present || snapshot->aperture_lens_selector > 1u ||
      snapshot->aperture_row_count == 0u ||
      snapshot->aperture_row_count > 32u ||
      snapshot->aperture_zoom_index >= snapshot->aperture_row_count ||
      snapshot->aperture_focal_mm == 0u || snapshot->aperture_min < 0 ||
      snapshot->aperture_min > snapshot->aperture_max) {
    return Status::kUnsupported;
  }
  ApertureProjection aperture_projection = {};
  if (!project_aperture_bounds(snapshot, &aperture_projection) ||
      !target_is_legal(
          snapshot, kAperture,
          static_cast<int16_t>(snapshot->active[kAperture]))) {
    return Status::kUnsupported;
  }

  int32_t shutter_lower = INT16_MIN;
  int32_t shutter_upper = INT16_MAX;
  if (snapshot->source_id[kShutter] != 1445) {
    if (!shutter_domain_supported(*snapshot, &shutter_lower,
                                  &shutter_upper)) {
      return Status::kUnsupported;
    }
  } else if (snapshot->context_values[8] != 0 ||
             snapshot->mode_helper != 62) {
    return Status::kUnsupported;
  }

  uint16_t label_cursor = 0;
  for (unsigned setting = 0; setting < kSettingCount; ++setting) {
    bool current_found = false;
    for (uint16_t native_index = 0;
         native_index < snapshot->native_count[setting]; ++native_index) {
      const NativeEntry &entry = snapshot->native[setting][native_index];
      if (!entry_is_legal(*snapshot, setting, entry.value, shutter_lower,
                          shutter_upper)) {
        continue;
      }
      const uint16_t output_index = result->value_count[setting];
      if (output_index >= kMaxValues ||
          static_cast<uint32_t>(label_cursor) + 1u + entry.label_length >
              kV19MaxLabelBytes) {
        clear_result(result);
        return Status::kInvalid;
      }
      result->values[setting][output_index] = entry.value;
      result->native_index[setting][output_index] = native_index;
      result->labels[label_cursor++] = entry.label_length;
      for (uint8_t byte = 0; byte < entry.label_length; ++byte)
        result->labels[label_cursor++] =
            static_cast<uint8_t>(entry.label[byte]);
      if (entry.value == snapshot->active[setting]) {
        if (current_found) {
          clear_result(result);
          return Status::kInvalid;
        }
        current_found = true;
        result->current_index[setting] = output_index;
      }
      result->value_count[setting] =
          static_cast<uint16_t>(output_index + 1u);
    }
    if (result->value_count[setting] == 0 || !current_found) {
      clear_result(result);
      return Status::kUnsupported;
    }
  }
  result->aperture = aperture_projection;
  result->label_bytes = label_cursor;
  result->mapping_certificate = mapping_certificate(*result);
  result->mutation_certificate = mutation_certificate(*snapshot, *result);
  return Status::kOk;
}

}  // namespace direct_catalog
}  // namespace om1_stars
