#ifndef OM1_STARS_CAMERA_H
#define OM1_STARS_CAMERA_H

#include "devmem_state.h"
#include "ptp.h"
#include "setting_write_retry.h"
#include "types.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {

constexpr uint8_t kDefaultPtpGuidTail[6] = {0x08, 0x00, 0x27,
                                            0xc3, 0xff, 0x56};
constexpr uint32_t kSettingCatalogCount = 3;
constexpr uint32_t kSettingCatalogMaxValues = 128;
constexpr uint32_t kSettingCatalogContextCount = 11;
constexpr uint32_t kSettingCatalogLabelStorage = 1234;
constexpr uint32_t kSettingCatalogLabelMax = 31;

struct CaptureInfo {
  uint32_t slot;         // Camera slot number, 1 or 2.
  uint32_t transfer_id;  // New vendor transfer object identifier from 94BB.
  char leaf[13];         // Eight-character DCF basename, '.', "ORF", NUL.
  uint64_t file_size;
  bool metadata_valid;
};

struct LensMetadata {
  uint32_t focal_length_f32_bits;
  uint8_t lens_name_length;
  char lens_name[64];
  bool focal_length_valid;
  bool lens_name_valid;
};

struct SettingCatalog {
  uint16_t value_count[kSettingCatalogCount];
  uint16_t current_index[kSettingCatalogCount];
  uint16_t source_id[kSettingCatalogCount];
  uint16_t exposure_step;
  int16_t values[kSettingCatalogCount][kSettingCatalogMaxValues];
  int16_t context_values[kSettingCatalogContextCount];
  int16_t mode_helper;
  // Selected lens/zoom-row limits projected into exact native aperture-bank
  // values.  The raw continuous CHARACTER_ZM halfwords remain private in the
  // certified direct snapshot.  Mutation paths enforce this inclusive range
  // and the companion resolves both values to exact labels.
  int16_t aperture_min;
  int16_t aperture_max;
  uint16_t label_bytes;
  uint8_t labels[kSettingCatalogLabelStorage];
};

// Guarded, on-camera control of the OM-1 v1.9 PTP/IP U-shell bridge.  The
// object contains all userspace protocol storage; place it in static startup
// storage rather than on the small process stack.  open() creates only the
// fixed sockets and /dev/mem mappings needed by the startup control phase.
class CameraController {
 public:
  void initialize();

  // Opens paired loopback PTP/IP, verifies/maps the v1.9 /dev/mem anchors,
  // enables the inner gate, and primes one diagnostic capture allocation.
  bool open(const uint8_t guid_tail[6], Error *error);

  // Save coherent active/selected settings from the read-only v1.9 direct
  // backend.  Each B01 is preceded by a fresh complete snapshot proving the
  // exact owned intermediate tuple and target legality; convergence is then
  // polled through the same backend without any shell-read fallback.
  bool apply_capture_settings(const int16_t targets[3], Error *error,
                              bool *catalog_rejected = nullptr);

  // Persist one exact native setting-table tuple while the worker is idle.
  // The operation snapshots and proves all three current values, validates
  // every requested intermediate state, and publishes success only after a
  // complete active/controller readback.  A successful call updates the
  // authoritative idle snapshot instead of scheduling restoration.
  bool set_persistent_settings(const int16_t targets[3], Error *error,
                               bool *catalog_rejected = nullptr,
                               bool *safe_rejected = nullptr);

  // Perform a fresh authoritative active+controller read at a safe idle
  // boundary and update the cached snapshot.  This observes physical-dial
  // changes and also recovers a certificate after a fully rolled-back SET.
  bool refresh_persistent_settings(int16_t values[3], Error *error,
                                   bool *rejected = nullptr);

  // Return the coherent tuple established at startup, retained after local
  // rollover, or committed by a successful set_persistent_settings().
  bool persistent_settings(int16_t values[3]) const;

  // Read the complete currently visible shutter/aperture/ISO catalogs without
  // mutation.  One v1.9 direct snapshot validates the native banks, formatter
  // labels, reconstructed legality dependencies, and coherent current tuple;
  // no exposure-setting shell or HTTP enumeration fallback exists.
  // `rejected` distinguishes a coherent but unsupported/stale catalog
  // context from transport/framing uncertainty.  Only the former may be
  // reported as a nonterminal CATALOG rejection.
  bool read_setting_catalog(SettingCatalog *catalog, bool *rejected,
                            Error *error);

  // Optimistic 10-Hz watcher. Immutable tables remain in the caller's session
  // cache; the ordinary path updates only current indices, aperture bounds,
  // and optics. A mapping-context change rebuilds the catalog once and sets
  // catalog_changed. A transient live sample leaves prior publication and
  // mutation evidence untouched.
  bool refresh_setting_state_hot(SettingCatalog *catalog,
                                 LensMetadata *metadata,
                                 bool *catalog_changed, bool *transient,
                                 Error *error);

  // Direct-memory lens metadata for optimistic catalog/state publication.
  // Capture RESULT uses the same evaluator with a strict A/B snapshot through
  // read_post_capture_lens_metadata().
  bool read_direct_lens_metadata(LensMetadata *metadata, Error *error);

  // Read the same metadata once at the explicit post-shot boundary established
  // by a successful take_persistent_photo(), after release/transcript and
  // setting restoration but before 94DE is opened.  Production uses only the
  // qualified read-only lens mapping; unavailable optics remain absent.
  bool read_post_capture_lens_metadata(LensMetadata *metadata, Error *error);

  // Prepare a warm capture generation while leaving the user's exposure
  // settings untouched.  This proves coherent settings, idle
  // SDK/converter/trigger state, and one stable two-slot 94BB baseline, then
  // retains the sole optimized shell-SDK shutter path for the worker lifetime.
  bool arm_persistent_capture(Error *error);

  // Run the fixed sleep-state query/rearm attempt through the retained
  // U-shell. A successful prompt reports recovered=false. If the outcome is
  // ambiguous, fence it exactly once without replaying it and report
  // recovered=true; failure to establish that fence is terminal to the
  // worker. No caller-provided U-shell text is accepted by this API.
  bool keep_awake(bool *recovered, Error *error);

  // Consume one armed exact-256 generation after apply_capture_settings().
  // Every persistent START emits the four distinct stock SDK-event edges as
  // asynchronous U-shell posts and validates their converter/TrigState
  // transitions through qualified read-only mappings. A required preposition
  // consumes its staged mailbox only after halfpress, waits for MAIN and the
  // fixed experimental allowance, and aborts with only first-stage-off on a
  // missing, cancelled, or timed-out stage. Cache the exact new
  // 94DD ORF metadata and restore the per-job originals before returning.
  bool take_persistent_photo(uint32_t timeout_ms, CaptureInfo *capture,
                             Error *error, bool preposition_required = false);

  // Snapshot both stock untransferred-record lists, post the exact
  // first/second-stage edges emitted by the stock blebridge action-5/action-6
  // converter entirely inside the camera, then identify the one new .ORF
  // record through 94BB/94DD.  M58 is deliberately not used: on v1.9 it is
  // the interval-shooting-only one-shot path.
  bool take_photo(uint32_t timeout_ms, CaptureInfo *capture, Error *error);

  // Resolve the 94BB transfer identifier with vendor operation 94DD.  This
  // yields the DCF leaf and exact byte size without starting PC-station mode
  // or guessing a directory.
  bool read_transfer_metadata(CaptureInfo *capture, Error *error);

  // Open the exact 94DD-described ORF as a forward-only zero-buffer PTP/IP
  // stream.  Settings must already have been restored: this leases the sole
  // command socket until EOF, and an abort necessarily tears that socket down.
  bool open_transfer_input(const CaptureInfo &capture, uint32_t timeout_ms,
                           InputStream *input, Error *error);

  // Persistent-worker variant: take_persistent_photo() has already restored
  // the per-job originals, so no setting, station, or duplicate-94DD round
  // trip is required before beginning the exact 94DE stream.
  bool open_persistent_transfer_input(const CaptureInfo &capture,
                                      uint32_t timeout_ms, InputStream *input,
                                      Error *error);

  // Strict rollover after a successful 94DE stream reaches EOF.  It proves
  // transfer cleanup, shutter idle, restored settings, a stable post-cleanup
  // transfer baseline, and the retained-shell boundary before another capture
  // generation can be armed.
  bool rollover_persistent_capture(Error *error);

  // Abort a stream returned by open_transfer_input().  This is terminal for
  // the PTP command connection; close() still releases remaining resources.
  // An incomplete stream returns false even when socket cancellation succeeds,
  // because pic_trans_state can no longer be observed and cleanup is unproved.
  bool cancel_transfer_input(Error *error);

  // Compare-before-restore in strict reverse application order.  Conflicting
  // external values are never overwritten.
  bool restore_settings(Error *error);

  // Terminal teardown finishes any shell-SDK shutter recovery proof, restores
  // settings only after shutter state is safe, releases diagnostic capture,
  // sends CloseSession, compare-restores the gate, and closes fds.
  // Call restore_settings() explicitly first if a failed restoration must be
  // retried while the PTP session is available.
  bool close(Error *error);

  bool settings_pending_restore() const {
    return changed_mask_ != 0 || deferred_controller_proof_pending_ ||
           shutter_settings_restore_blocked_;
  }
  bool gate_pending_restore() const { return gate_changed_; }
  // True means 94DE may have entered pic_trans_state=1 but an exact return to
  // the no-PC A tuple has not been observed.  It is intentionally sticky.
  bool picture_transfer_cleanup_unverified() const {
    return picture_transfer_cleanup_unverified_;
  }
  bool shutter_release_pending() const {
    return shutter_release_pending_ || shutter_recovery_required_;
  }
 private:
  enum class DirectSetResult : uint8_t {
    kSuccess,
    kTerminalUnresponsive,
    kTerminalNoSuccess,
    kTransportAmbiguous,
  };

  static constexpr uint32_t kTransferRecordCapacity =
      kDevMemTransferRecordCapacity;
  static constexpr size_t kTransferListDataCapacity =
      4u + static_cast<size_t>(kTransferRecordCapacity) * 4u;

  using TransferIdList = DevMemTransferIdList;

  bool map_and_open_gate(Error *error);
  bool restore_and_unmap_gate(Error *error);
  bool allocate_and_prime_shell(Error *error);
  bool post_shell(const uint8_t *command, size_t command_size, uint32_t flags,
                  Error *error);
  bool read_shell_capture(bool keep, uint32_t timeout_ms, Error *error);
  bool read_shell_capture_ptp(bool keep, uint32_t timeout_ms, Error *error);
  bool read_direct_shell_capture(uint8_t *destination, size_t capacity,
                                 size_t *size, uint16_t *cursor,
                                 uint16_t firmware_read_checkpoint,
                                 Error *error);
  bool qualify_direct_shell_capture(bool checkpoint_generation,
                                    Error *error);
  bool wait_for_shell_barrier_ptp(const char *token, uint64_t deadline,
                                  Error *error);
  void demote_direct_shell_capture(bool recovery_pending);
  bool wait_for_prompt(uint32_t timeout_ms, Error *error);
  bool drain_persistent_sdk_output(uint32_t timeout_ms, Error *error);
  bool resynchronize_shell(Error *error);
  bool shell_command(const char *command, Error *error);
  bool read_station_state(uint32_t *station, uint32_t *registration,
                          uint32_t *priority, uint32_t *connection,
                          uint32_t *picture_transfer, Error *error);
  bool require_station_baseline(Error *error);
  bool wait_for_station_baseline(uint32_t timeout_ms, Error *error);
  bool read_shell_hex(DevMemStateField field, const char *command,
                      uint32_t maximum, uint32_t *value, Error *error);
  bool read_shutter_trigger_state(uint32_t *value, Error *error);
  bool wait_for_shutter_trigger_state(uint32_t previous, uint32_t expected,
                                      uint32_t timeout_ms, Error *error);
  bool require_shutter_preflight(Error *error);
  bool post_shutter_edge(const char *command, uint32_t previous,
                         uint32_t expected, Error *error);
  bool wait_for_persistent_sdk_edge(uint32_t previous, uint32_t expected,
                                    Error *error);
  bool post_persistent_sdk_edge(const char *command, uint32_t previous,
                                uint32_t expected, Error *error);
  bool run_shell_sdk_shutter_cycle(Error *error, bool preposition_required);
  volatile uint8_t *astro_preposition_mailbox() const;
  bool consume_staged_astro_preposition(Error *error);
  void release_astro_preposition_ownership();
  bool refresh_persistent_transfer_slot_hint(Error *error);
  void qualify_direct_transfer_records();
  bool discover_persistent_capture(uint32_t timeout_ms, CaptureInfo *capture,
                                   Error *error);
  bool discover_direct_persistent_capture(uint64_t deadline,
                                          CaptureInfo *capture,
                                          bool *fallback, Error *error);
  bool discover_ptp_persistent_capture(uint64_t deadline,
                                       CaptureInfo *capture, Error *error);
  bool release_shutter(Error *error);
  bool read_untransferred_list(uint32_t slot, uint32_t timeout_ms,
                               TransferIdList *list, Error *error);
  bool read_transfer_snapshot(uint32_t operation_timeout_ms,
                              TransferIdList lists[2], Error *error);
  bool read_transfer_record_metadata(uint32_t slot, uint32_t transfer_id,
                                     uint32_t timeout_ms, char leaf[13],
                                     uint64_t *file_size, Error *error);
  bool sleep_transfer_poll(uint32_t delay_ms, Error *error);
  static bool transfer_id_is_canonical(uint32_t slot, uint32_t transfer_id);
  static bool transfer_lists_equal(const TransferIdList &left,
                                   const TransferIdList &right);
  static bool transfer_list_contains(const TransferIdList &list,
                                     uint32_t transfer_id);
  bool read_active(unsigned setting_index, int32_t *value, Error *error);
  bool read_controller(unsigned setting_index, int32_t *value, Error *error);
  bool read_pair(unsigned setting_index, int32_t *active, int32_t *controller,
                 Error *error);
  bool read_active_batch(int32_t values[3], Error *error);
  bool read_controller_batch(int32_t values[3], Error *error);
  bool read_setting_snapshot(int32_t active[3], int32_t controller[3],
                             Error *error);
  bool read_stock_setting_snapshot(int32_t active[3], int32_t controller[3],
                                   Error *error);
  bool read_direct_exposure_state(direct_catalog::Snapshot *snapshot,
                                  direct_catalog::Result *result,
                                  Error *error,
                                  direct_catalog::Status *status = nullptr);
  static bool direct_catalog_mapping_equal(
      const direct_catalog::Result &left,
      const direct_catalog::Result &right);
  static bool direct_catalog_contains(
      const direct_catalog::Result &result, unsigned setting_index,
      int32_t value, uint16_t *compact_index = nullptr);
  bool export_direct_setting_catalog(const direct_catalog::Snapshot &snapshot,
                                     const direct_catalog::Result &result,
                                     SettingCatalog *catalog, Error *error);
  void commit_direct_setting_evidence(
      const direct_catalog::Snapshot &snapshot,
      const direct_catalog::Result &result);
  bool validate_setting_catalog_targets(const int16_t targets[3],
                                        bool *rejected, Error *error);
  bool validate_catalog_restore_value(unsigned setting_index, int32_t value,
                                      Error *error);
  bool validate_pinned_setting_write(unsigned setting_index, int32_t value,
                                     bool restoring, Error *error);
  bool read_direct_lens_metadata_impl(LensMetadata *metadata, bool stable,
                                      Error *error);
  void invalidate_setting_catalog_certificate();
  bool targets_are_legal_batch(Error *error);
  bool write_and_observe_setting(unsigned setting_index, int32_t value,
                                 bool restoring, Error *error);
  bool read_setting_write_snapshot_class(
      const int32_t prewrite[3], const int32_t applied[3],
      SettingWriteSnapshotClass *snapshot_class, Error *error);
  bool wait_for_settings_batch(const int32_t expected[3],
                               uint32_t timeout_ms, Error *error);
  bool require_settings_batch(const int32_t expected[3], Error *error);
  bool target_is_legal(unsigned setting_index, Error *error);
  DirectSetResult direct_set(unsigned setting_index, int32_t value,
                             unsigned attempt, Error *error);
  bool wait_for_setting(unsigned setting_index, int32_t expected,
                        uint32_t timeout_ms, Error *error);
  bool require_setting(unsigned setting_index, int32_t expected, Error *error);
  bool apply_capture_settings_internal(const int16_t targets[3],
                                       bool require_idle_match,
                                       bool *catalog_rejected,
                                       bool *safe_rejected, Error *error);
  bool complete_deferred_controller_proof(Error *error);
  bool rollback_after_failure(const Error &primary, Error *error);

  DevMemState devmem_state_;
  PtpIpClient ptp_;
  PtpInputState transfer_input_;
  int memory_fd_;
  void *anchor_page_;
  void *mode_page_;
  void *gate_page_;
  void *astro_preposition_page_;
  bool astro_preposition_owned_;
  uint16_t gate_original_;
  bool gate_changed_;
  bool capture_allocated_;
  bool shell_ready_;
  bool picture_transfer_cleanup_unverified_;
  bool shutter_release_pending_;
  bool shutter_recovery_required_;
  bool shutter_settings_restore_blocked_;
  bool persistent_sdk_output_pending_;
  uint8_t persistent_sdk_expected_prompts_;
  bool shell_resync_required_;
  bool direct_shell_capture_qualified_;
  bool direct_shell_capture_recovery_pending_;
  uint16_t direct_shell_capture_cursor_;
  uint16_t direct_shell_capture_firmware_read_checkpoint_;
  uint32_t direct_shell_capture_checkpoint_bytes_;
  uint32_t shell_resync_generation_;
  bool persistent_shell_sdk_ready_;
  bool settings_applied_;
  bool persistent_armed_;
  bool persistent_settings_snapshot_valid_;
  bool setting_catalog_evidence_valid_;
  bool setting_catalog_mutation_grant_valid_;
  bool setting_write_attempted_;
  bool setting_prewrite_rejection_safe_;
  bool post_capture_lens_window_;
  bool deferred_controller_proof_pending_;
  bool settings_set_batch_performed_;
  bool direct_transfer_records_qualified_;
  uint8_t persistent_transfer_slot_hint_;
  uint8_t saved_mask_;
  uint8_t changed_mask_;
  int32_t original_settings_[3];
  int32_t capture_targets_[3];
  int32_t persistent_settings_snapshot_[3];
  int32_t setting_catalog_validated_current_[3];
  uint16_t selected_setting_controller_ids_[3];
  // Static storage is intentional: the complete direct snapshot is much too
  // large for the camera process' small thread stack.
  direct_catalog::Snapshot direct_catalog_snapshot_scratch_;
  direct_catalog::Result direct_catalog_result_scratch_;
  direct_catalog::Snapshot direct_catalog_snapshot_certificate_;
  direct_catalog::Result direct_catalog_result_certificate_;
  direct_catalog::CatalogContext direct_catalog_public_context_;
  bool direct_catalog_public_context_valid_;
  TransferIdList transfer_before_[2];
  TransferIdList transfer_after_[2];
  TransferIdList transfer_verify_[2];
  DevMemTransferCounts transfer_counts_;
  uint8_t transfer_list_data_[kTransferListDataCapacity];
  size_t shell_output_size_;
  char shell_output_[16385];
  char direct_shell_shadow_[kDevMemShellCaptureCapacity + 1u];
};

}  // namespace om1_stars

#endif
