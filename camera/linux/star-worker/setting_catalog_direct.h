#ifndef OM1_STARS_SETTING_CATALOG_DIRECT_H
#define OM1_STARS_SETTING_CATALOG_DIRECT_H

#include <stdint.h>

namespace om1_stars {
namespace direct_catalog {

constexpr uint32_t kSettingCount = 3;
constexpr uint32_t kContextCount = 11;
constexpr uint32_t kMaxValues = 128;
constexpr uint32_t kLabelMax = 31;
constexpr uint32_t kLabelStorage = 1234;
constexpr uint32_t kV19MaxLabelBytes = 584;

enum Setting : uint8_t {
  kShutter = 0,
  kAperture = 1,
  kIso = 2,
};

struct NativeEntry {
  int16_t value;
  uint8_t label_length;
  char label[kLabelMax];
};

// One already-bracketed, immutable input to the v1.9 legality evaluator.
// DevMemState owns address/layout qualification and must prove that every
// dynamic field and native table below was byte-identical in its A/B reads.
// The evaluator has no camera, shell, filesystem, or memory-mapping fallback.
struct Snapshot {
  uint16_t source_id[kSettingCount];
  int32_t context_values[kContextCount];
  int16_t mode_helper;
  int32_t active[kSettingCount];
  int32_t selected[kSettingCount];
  uint16_t native_count[kSettingCount];
  NativeEntry native[kSettingCount][kMaxValues];

  // Source-1671 ISO rules read setting 1669 even when the selected shutter
  // property is another source.
  int32_t current1669;
  int32_t iso598;
  int32_t iso434;
  int32_t iso435;
  int32_t iso239;
  int32_t iso754;
  int32_t iso1712;
  int32_t iso1678;
  int32_t iso1325;
  int32_t iso1606;
  int32_t iso1673;

  uint8_t ram8d0;
  uint8_t ramde48;
  uint8_t helper_index;
  uint8_t helper_mode;
  uint8_t p49169;
  uint8_t p17665;
  uint8_t p37297_selector1;
  uint8_t p26481;
  uint8_t p34225;
  uint8_t p59665;
  uint8_t p61697;
  uint8_t p61633;
  uint8_t p33697;
  uint8_t p21489;
  uint8_t p7745;
  int16_t p35818;
  int16_t shutter_ceiling;
  // Stock retains this adjacent value with the CGI ceiling.  CGI does not use
  // it for enumeration, but the coherent snapshot brackets and certificates
  // it as provenance for the retained pair.
  int16_t shutter_floor;

  // The stock aperture legality path is lens- and zoom-row-specific.  These
  // fields come from the same selected, guarded lens object used by the
  // CHARACTER_ZM diagnostic.  L_APmin/L_APmax are raw continuous APEX-like
  // limits for the current L_ZMENC row, not necessarily native dial values.
  // Strict readers bracket this context together with every exposure provider
  // so a lens swap or zoom transition cannot authorize a setting from a mixed
  // snapshot.  project_aperture_bounds() converts this raw pair into the
  // selected camera-authored aperture bank without overwriting the evidence.
  uint8_t aperture_lens_id[6];
  uint8_t aperture_lens_selector;
  uint8_t aperture_zoom_index;
  uint8_t aperture_row_count;
  uint16_t aperture_focal_mm;
  int16_t aperture_min;
  int16_t aperture_max;
};

struct ApertureProjection {
  uint16_t minimum_index;
  uint16_t maximum_index;
  int16_t minimum_value;
  int16_t maximum_value;
};

struct Result {
  uint16_t value_count[kSettingCount];
  uint16_t current_index[kSettingCount];
  int16_t values[kSettingCount][kMaxValues];
  uint16_t native_index[kSettingCount][kMaxValues];
  uint16_t label_bytes;
  uint8_t labels[kLabelStorage];
  // Derived STATE/mutation context.  It is excluded from mapping identity;
  // the exact raw pair that produced it remains in Snapshot and is bound by
  // mutation_certificate.
  ApertureProjection aperture;
  // Mapping identity hashes only public ordinal semantics: per-setting counts,
  // ordered raw values, and ordered labels.  Lens/zoom aperture limits remain
  // STATE/mutation context, so a focal transition does not churn the stable
  // full catalog generation when its bytes are unchanged.
  uint32_t mapping_certificate;
  // Mutation identity additionally binds every semantic snapshot field,
  // native table/slot, the coherent current tuple, and its compact indices.
  // Neither hash substitutes for exact A/B validation.
  uint32_t mutation_certificate;
};

// Exact dynamic inputs that can change the bytes of the public value banks.
// Current setting indices and lens/zoom aperture bounds are deliberately
// excluded because they belong to STATE, not the stable catalog.
struct CatalogContext {
  uint16_t source_id[kSettingCount];
  int32_t context_values[kContextCount];
  int32_t signed_fields[11];
  uint8_t byte_fields[15];
  int16_t mode_helper;
  int16_t p35818;
  int16_t shutter_ceiling;
  int16_t shutter_floor;
};

enum class Status : uint8_t {
  kOk = 0,
  // A coherent camera state lies outside the bounded, reverse-proved v1.9
  // legality domain or its current value is not presently legal.
  kUnsupported = 1,
  // The supposedly qualified snapshot is structurally malformed.
  kInvalid = 2,
};

Status evaluate(const Snapshot *snapshot, Result *result);

void extract_catalog_context(const Snapshot *snapshot,
                             CatalogContext *context);
bool catalog_context_equal(const CatalogContext *left,
                           const CatalogContext *right);

// Mirror the stock v1.9 lens-bound conversion into inclusive native-bank
// indices.  The lower endpoint is a floor seed with the stock continuous-vs-
// nominal aperture normalization bump; the upper endpoint is the stock
// fixed-half-step nearest selector with midpoint ties to the lower row.
// Failure leaves projection zero and is fail-closed for mutation callers.
bool project_aperture_bounds(const Snapshot *snapshot,
                             ApertureProjection *projection);

// Validate one native target against the live legality context of an already
// well-formed snapshot.  Publication deliberately keeps the complete
// camera-authored aperture bank stable; mutation callers use this additional
// predicate to enforce the selected lens/zoom row's projected inclusive
// native-bank interval.
bool target_is_legal(const Snapshot *snapshot, unsigned setting,
                     int16_t value);

}  // namespace direct_catalog
}  // namespace om1_stars

#endif
