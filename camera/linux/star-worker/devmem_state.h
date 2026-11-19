#ifndef OM1_STARS_DEVMEM_STATE_H
#define OM1_STARS_DEVMEM_STATE_H

#include "types.h"
#include "setting_catalog_direct.h"

#include <stdint.h>

namespace om1_stars {

// Candidate OM-1 v1.9 state fields that are visible to Linux through the
// main-RTOS physical-memory aperture.  These mappings are deliberately
// read-only.  Their values must be shadowed against the corresponding stock
// U-shell diagnostics on a real camera before any caller treats them as an
// authoritative replacement for those diagnostics.
enum class DevMemStateField : uint8_t {
  kEventInitCookie,
  kBleHandlerState,
  kBleMode,
  kBlePassCheck,
  kBleConverterState,
  kShutterTriggerState,
};

struct DevMemStationState {
  uint16_t station;
  uint16_t registration;
  uint16_t priority;
  uint16_t connection;
  uint16_t picture_transfer;
};

constexpr uint32_t kDevMemTransferRecordCapacity = 3000;
constexpr uint32_t kDevMemShellCaptureCapacity = 16384;
constexpr uint32_t kDevMemSettingCatalogCount = 3;
constexpr uint32_t kDevMemSettingCatalogMaxValues = 128;
constexpr uint32_t kDevMemSettingLabelMax = 31;

struct DevMemSettingCatalogEntry {
  int16_t value;
  uint8_t label_length;
  char label[kDevMemSettingLabelMax];
};

constexpr uint32_t kDevMemLensNameStorage = 32;

// One read-only, focus-aware lens snapshot reconstructed directly from the
// selected v1.9 lens object.  Floating-point coefficients are kept as their
// exact IEEE-754 words until CameraController feeds them to the retained
// evaluator; this type never rounds or reformats camera calibration data.
struct DevMemLensSnapshot {
  uint8_t id[6];
  uint8_t name_length;
  char name[kDevMemLensNameStorage];
  uint16_t wide_mm;
  uint16_t tele_mm;
  uint8_t zoom_index;
  uint16_t focus_pulse;
  uint16_t autofocus_reference;
  uint32_t private_status;
  uint32_t calibration_f32_bits[8];
  uint16_t infinity_reference;
  uint8_t macro_is;
};

// The stock diagnostic capture is one lockless modulo-16-KiB byte ring.  The
// firmware publishes a complete printer chunk by advancing write only after
// copying its bytes.  Linux never changes either index: it keeps a private
// cursor while the authoritative 0x902A consumer remains available for
// qualification, recovery, and final release.
struct DevMemShellCaptureState {
  uint32_t pointer;
  uint16_t read;
  uint16_t write;
};

enum class DevMemShellCaptureReadStatus : uint8_t {
  kStable,
  kChangedDuringRead,
  kError,
};

// The exact 94BB list shape, reconstructed from the v1.9 DCF transfer table.
// DevMemState fills this only after two exact occupancy passes plus an exact
// second read of every occupied record's metadata-relevant bytes.
// CameraController still shadows the initial list against stock 94BB and
// fences a selected record with stock 94DD.
struct DevMemTransferIdList {
  uint32_t count;
  uint32_t ids[kDevMemTransferRecordCapacity];
};

struct DevMemTransferRecord {
  char leaf[13];
  uint64_t file_size;
};

// Stock v1.9 increments/decrements each descriptor's +16 occupied-record
// count only after mutating the corresponding record under its DCF mutex.
// The descriptor itself is written through a cached RTOS alias, however, so
// these counts are an optional early-wake hint only.  CameraController also
// runs bounded exact scans; a changed hint still requires a stable exact list
// and the selected record still requires the stock 94DD fence.
struct DevMemTransferCounts {
  uint32_t occupied[2];
};

// A full list read can legitimately overlap the firmware publishing or
// removing a record.  Keep that expected race distinct from a mapping/layout
// failure so the caller can retry it without trusting a partial snapshot.
enum class DevMemTransferListStatus : uint8_t {
  kStable,
  kChangedDuringRead,
  kError,
};

// Owns PROT_READ mappings made through an O_RDONLY /dev/mem descriptor.  No
// method in this type accepts a value to write and no mapping is writable.
class DevMemState {
 public:
  void initialize();

  bool open_read_only(Error *error);
  bool close(Error *error);

  bool read(DevMemStateField field, uint32_t *value, Error *error) const;
  bool read_station(DevMemStationState *state, Error *error) const;

  // Copy the immutable OM-1 v1.9 native value banks and their exact camera UI
  // labels directly from main-RTOS initialized RAM.  The selected sources and
  // two firmware step values choose one hash-pinned value array and formatter
  // table per setting.  All mappings are O_RDONLY/PROT_READ; descriptors,
  // arrays, formatter records, and strings are validated and bracketed before
  // any bytes become output.  This method intentionally does not decide live
  // legality: CameraController applies the separately reconstructed v1.9
  // legality functions before publishing a compact catalog.
  bool setting_catalog_tables_available() const;
  bool read_setting_catalog_tables(
      const uint16_t source_ids[kDevMemSettingCatalogCount],
      uint16_t exposure_step, uint16_t iso_step,
      DevMemSettingCatalogEntry entries[kDevMemSettingCatalogCount]
                                       [kDevMemSettingCatalogMaxValues],
      uint16_t counts[kDevMemSettingCatalogCount], Error *error) const;

  // Cold-certify the immutable v1.9 layout/tables and build one optimistic
  // direct-only exposure snapshot.  It never calls or falls back to
  // B02/B08/B09/B0B/HTTP.  Subsequent refresh calls copy the private native
  // cache and load every live field exactly once, with no DMB or A/B equality.
  bool read_direct_exposure_snapshot(direct_catalog::Snapshot *snapshot,
                                     Error *error,
                                     bool *transient = nullptr) const;
  bool refresh_direct_exposure_snapshot(
      direct_catalog::Snapshot *snapshot, Error *error,
      bool *transient = nullptr) const;

  // Hot watcher variant for a Snapshot previously populated by a cold or
  // cached strict read. Reuses its immutable native tables and refreshes only
  // dynamic providers/helper/lens-bound fields. A catalog-key change performs
  // one cold rebuild before returning.
  bool refresh_direct_exposure_state(
      direct_catalog::Snapshot *snapshot, Error *error,
      bool *transient = nullptr) const;

  // Mutation, convergence, and restore boundaries retain a compact A/B read
  // of only dynamic provider/helper bytes.  Immutable legality, native-bank,
  // and formatter data comes from the separately retained static cache.
  bool read_cached_direct_exposure_snapshot(
      direct_catalog::Snapshot *snapshot, Error *error,
      bool *transient = nullptr) const;

  // Best-effort optics for the periodic state path.  This never invokes the
  // U-shell.  Selector, slot, and each selected-object field are read exactly
  // once; failure leaves exposure state usable and is represented by absent
  // optics metadata at the worker boundary.
  bool read_direct_lens_snapshot(DevMemLensSnapshot *snapshot,
                                 Error *error,
                                 bool *transient = nullptr) const;

  // The shell-capture mapping is optional and dynamically follows only the
  // validated uncached pool pointer published by the stock allocator.  It is
  // always PROT_READ.  prepare_shell_capture() must be called after stock
  // 0x902B allocation and before direct reads.
  bool shell_capture_state_available() const;
  bool prepare_shell_capture(DevMemShellCaptureState *state, Error *error);
  bool unmap_shell_capture(Error *error);
  bool read_shell_capture_state(DevMemShellCaptureState *state,
                                Error *error) const;
  DevMemShellCaptureReadStatus read_shell_capture(
      uint16_t cursor, uint16_t firmware_read_checkpoint,
      uint8_t *destination, size_t capacity, size_t *size, uint16_t *next,
      Error *error) const;

  // These optional v1.9 mappings never make /dev/mem a protocol authority.
  // A mapping failure leaves the ordinary 94BB/94DD path available.
  bool transfer_records_available() const;
  bool read_transfer_counts(DevMemTransferCounts *counts,
                            Error *error) const;
  DevMemTransferListStatus read_stable_transfer_lists(
      DevMemTransferIdList lists[2], DevMemTransferCounts *counts,
      Error *error);
  bool read_transfer_record(uint32_t slot, uint32_t transfer_id,
                            DevMemTransferRecord *record,
                            Error *error) const;

  bool opened() const { return memory_fd_ >= 0; }

 private:
  bool read_direct_exposure_dynamic(direct_catalog::Snapshot *snapshot,
                                    Error *error, bool *transient,
                                    bool strict) const;
  bool certify_direct_exposure_static(Error *error) const;
  bool read_cold_direct_exposure_snapshot(
      direct_catalog::Snapshot *snapshot, Error *error,
      bool *transient, bool strict) const;

  int memory_fd_;
  void *event_page_;
  void *ble_page_;
  void *station_page_;
  void *trigger_page_;
  void *setting_provider_pointer_pages_;
  void *setting_catalog_common_values_pages_;
  void *setting_catalog_special_values_page_;
  void *setting_catalog_common_descriptors_page_;
  void *setting_catalog_special_descriptor_page_;
  void *setting_catalog_formatter_pages_;
  void *setting_catalog_provider_descriptors_pages_;
  void *setting_catalog_provider_buckets_pages_;
  void *setting_catalog_provider_value_pages_[3];
  void *setting_catalog_helper_state_page_;
  void *setting_catalog_helper_mode_page_;
  void *setting_catalog_dispatch_pages_;
  void *setting_catalog_postfilter_page_;
  void *setting_catalog_aperture_legality_page_;
  void *setting_catalog_shutter_legality_pages_;
  void *setting_catalog_iso_legality_pages_;
  void *setting_catalog_iso_registry_page_;
  mutable bool direct_exposure_static_certified_;
  void *lens_head_pages_[2];
  void *lens_calibration_pages_[2];
  void *lens_feature_pages_[2];
  void *lens_control_page_;
  void *lens_focus_page_;
  void *lens_commit_page_;
  void *lens_slots_page_;
  void *lens_connection_page_;
  mutable DevMemSettingCatalogEntry
      setting_catalog_snapshot_entries_[kDevMemSettingCatalogCount]
                                       [kDevMemSettingCatalogMaxValues];
  mutable uint16_t
      direct_exposure_native_counts_[kDevMemSettingCatalogCount];
  mutable uint16_t
      direct_exposure_native_sources_[kDevMemSettingCatalogCount];
  mutable int32_t direct_exposure_native_exposure_step_;
  mutable int32_t direct_exposure_native_iso_step_;
  mutable bool direct_exposure_native_cache_valid_;
  void *shell_capture_state_page_;
  void *shell_capture_ring_page_;
  size_t shell_capture_ring_mapping_length_;
  uint32_t shell_capture_pointer_;
  void *transfer_descriptor_page_;
  void *transfer_record_pages_[2];
  uint32_t transfer_snapshot_words_[2][kDevMemTransferRecordCapacity][5];
};

bool devmem_station_equal(const DevMemStationState &left,
                          const DevMemStationState &right);
bool devmem_transfer_record_equal(const DevMemTransferRecord &left,
                                  const DevMemTransferRecord &right);

}  // namespace om1_stars

#endif
