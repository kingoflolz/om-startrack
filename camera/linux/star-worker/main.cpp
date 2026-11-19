#include "camera.h"
#include "detector.h"
#include "orf.h"
#include "star_worker_protocol.h"
#include "syscalls.h"
#include "types.h"
#include "util.h"
#include "worker_command.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {
namespace {

constexpr uint32_t kCaptureTimeoutMilliseconds = 30u * 1000u;
constexpr uint32_t kTransferTimeoutMilliseconds = 10u * 1000u;
constexpr int kStateObservationMilliseconds = 100;

static_assert(kMaxStars == STAR_WORKER_MAX_PEAKS,
              "worker result capacity must match detector capacity");
static_assert(kSettingCatalogCount == STAR_WORKER_SETTING_COUNT &&
                  kSettingCatalogMaxValues == 128u &&
                  kSettingCatalogLabelMax == STAR_WORKER_SETTING_LABEL_MAX,
              "camera and worker setting capacities must match");

CameraController g_camera;
Star g_output_stars[kMaxStars];
SettingCatalog g_setting_catalog;
LensMetadata g_lens_metadata;
uint8_t g_frame[STAR_WORKER_MAX_FRAME_SIZE];
uint8_t g_command_payload[STAR_WORKER_COMMAND_PAYLOAD_MAX];
uint8_t g_catalog_cache[STAR_WORKER_CATALOG_PAYLOAD_MAX];
uint8_t g_state_cache[STAR_WORKER_STATE_VALUE_MAX];
uint16_t g_catalog_cache_size;
uint16_t g_state_cache_size;
bool g_catalog_cache_valid;
bool g_state_cache_valid;

struct DetectionBridge {
  const RawInfo *raw_info;
  const DetectorOptions *options;
  bool started;
};

struct LabelView {
  const uint8_t *value;
  uint8_t length;
};

enum FrameRead {
  kFrameReadOk,
  kFrameReadEof,
  kFrameReadTruncated,
  kFrameReadFailed,
};

enum SnapshotRead {
  kSnapshotOk,
  kSnapshotRejected,
  kSnapshotTransient,
  kSnapshotFailed,
};

void store_le16(uint8_t *destination, uint16_t value) {
  destination[0] = static_cast<uint8_t>(value);
  destination[1] = static_cast<uint8_t>(value >> 8);
}

void store_le32(uint8_t *destination, uint32_t value) {
  destination[0] = static_cast<uint8_t>(value);
  destination[1] = static_cast<uint8_t>(value >> 8);
  destination[2] = static_cast<uint8_t>(value >> 16);
  destination[3] = static_cast<uint8_t>(value >> 24);
}

void copy_bytes(uint8_t *destination, const uint8_t *source, size_t size) {
  for (size_t i = 0; i < size; ++i) destination[i] = source[i];
}

bool bytes_equal(const uint8_t *left, const uint8_t *right, size_t size) {
  for (size_t i = 0; i < size; ++i) {
    if (left[i] != right[i]) return false;
  }
  return true;
}

void print_error(const char *prefix, const Error &error) {
  char storage[384];
  TextBuffer line(storage, sizeof(storage));
  line.append("om1-stars: ");
  line.append(prefix);
  if (error.text[0] != '\0') {
    line.append(": ");
    line.append(error.text);
  }
  line.append_char('\n');
  if (line.ok()) (void)write_all(2, line.data(), line.size());
}

void print_pending_camera_state() {
  Error state = {};
  if (g_camera.shutter_release_pending()) {
    fail(&state, "camera shutter-release event remains pending");
    print_error("manual recovery required", state);
  }
  if (g_camera.settings_pending_restore()) {
    fail(&state, "camera exposure settings remain unrestored");
    print_error("manual recovery required", state);
  }
  if (g_camera.picture_transfer_cleanup_unverified()) {
    fail(&state, "camera picture-transfer state cleanup is unverified");
    print_error("manual recovery required", state);
  }
  if (g_camera.gate_pending_restore()) {
    fail(&state, "PTP inner gate remains unrestored");
    print_error("manual recovery required", state);
  }
}

bool worker_camera_state_clean() {
  return !g_camera.shutter_release_pending() &&
         !g_camera.settings_pending_restore() &&
         !g_camera.picture_transfer_cleanup_unverified() &&
         !g_camera.gate_pending_restore();
}

bool publish_frame(uint8_t type, uint16_t payload_size) {
  if (payload_size > STAR_WORKER_MAX_PAYLOAD_SIZE) return false;
  store_le32(g_frame, STAR_WORKER_MAGIC);
  store_le16(g_frame + 4u, payload_size);
  g_frame[6] = STAR_WORKER_VERSION;
  g_frame[7] = type;
  const size_t frame_size = STAR_WORKER_FRAME_HEADER_SIZE + payload_size;
  for (;;) {
    const long count = os::write(1, g_frame, frame_size);
    if (count == static_cast<long>(frame_size)) return true;
    if (os::failed(count) &&
        os::error_number(count) == os::kErrorInterrupted) {
      continue;
    }
    return false;
  }
}

bool publish_failed(uint8_t safety) {
  g_frame[STAR_WORKER_FRAME_HEADER_SIZE] = safety;
  return publish_frame(STAR_WORKER_EVENT_FAILED,
                       STAR_WORKER_FAILED_PAYLOAD_SIZE);
}

bool publish_keep_awake_result(uint8_t result) {
  g_frame[STAR_WORKER_FRAME_HEADER_SIZE] = result;
  return publish_frame(STAR_WORKER_EVENT_KEEP_AWAKE_RESULT,
                       STAR_WORKER_KEEP_AWAKE_RESULT_PAYLOAD_SIZE);
}

int close_worker_without_peer() {
  Error cleanup = {};
  const bool closed = g_camera.close(&cleanup);
  const bool clean = closed && worker_camera_state_clean();
  if (!clean) {
    if (closed && cleanup.text[0] == '\0')
      fail(&cleanup, "camera cleanup left a pending state obligation");
    print_error("worker camera cleanup after command EOF failed", cleanup);
    print_pending_camera_state();
  }
  return clean ? 0 : 6;
}

int stop_after_publish_failure() {
  (void)close_worker_without_peer();
  return 6;
}

int terminate_worker_failure(bool capture_or_settings_mutation_possible,
                             const char *stage, const Error &primary) {
  print_error(stage, primary);
  Error cleanup = {};
  const bool closed = g_camera.close(&cleanup);
  const bool clean = closed && worker_camera_state_clean();
  if (!clean) {
    if (closed && cleanup.text[0] == '\0')
      fail(&cleanup, "camera cleanup left a pending state obligation");
    print_error("worker camera cleanup also failed", cleanup);
    print_pending_camera_state();
  }
  const uint8_t safety =
      !capture_or_settings_mutation_possible
          ? STAR_WORKER_FAILURE_NO_MUTATION
          : (clean ? STAR_WORKER_FAILURE_RESTORED
                   : STAR_WORKER_FAILURE_UNCERTAIN);
  (void)publish_failed(safety);
  return 6;
}

FrameRead read_exact(void *destination, size_t size, bool frame_start) {
  size_t received = 0;
  auto *bytes = static_cast<uint8_t *>(destination);
  while (received != size) {
    const long count = os::read(0, bytes + received, size - received);
    if (count > 0) {
      received += static_cast<size_t>(count);
      continue;
    }
    if (count == 0) {
      return received == 0 && frame_start ? kFrameReadEof
                                          : kFrameReadTruncated;
    }
    if (os::failed(count) &&
        os::error_number(count) == os::kErrorInterrupted) {
      continue;
    }
    return kFrameReadFailed;
  }
  return kFrameReadOk;
}

FrameRead read_command(star_worker_frame_header *header) {
  const FrameRead header_read =
      read_exact(header, sizeof(*header), true);
  if (header_read != kFrameReadOk) return header_read;
  if (header->magic != STAR_WORKER_MAGIC ||
      header->version != STAR_WORKER_VERSION ||
      !star_worker_command_payload_size_valid(header->type,
                                               header->payload_size)) {
    return kFrameReadFailed;
  }
  if (header->type == STAR_WORKER_COMMAND_KEEP_AWAKE) {
    return kFrameReadOk;
  }
  return read_exact(g_command_payload, header->payload_size, false);
}

bool catalog_label_at(const SettingCatalog &catalog, unsigned wanted_setting,
                      uint16_t wanted_index, LabelView *view) {
  if (wanted_setting >= STAR_WORKER_SETTING_COUNT || view == nullptr ||
      wanted_index >= catalog.value_count[wanted_setting] ||
      catalog.label_bytes == 0 ||
      catalog.label_bytes > kSettingCatalogLabelStorage) {
    return false;
  }
  uint16_t offset = 0;
  for (unsigned setting = 0; setting < STAR_WORKER_SETTING_COUNT; ++setting) {
    if (catalog.value_count[setting] == 0 ||
        catalog.value_count[setting] > kSettingCatalogMaxValues) {
      return false;
    }
    for (uint16_t index = 0; index < catalog.value_count[setting]; ++index) {
      if (offset >= catalog.label_bytes) return false;
      const uint8_t length = catalog.labels[offset++];
      if (length == 0 || length > STAR_WORKER_SETTING_LABEL_MAX ||
          static_cast<uint32_t>(offset) + length > catalog.label_bytes) {
        return false;
      }
      const uint8_t *const value = catalog.labels + offset;
      for (uint8_t byte = 0; byte < length; ++byte) {
        if (value[byte] < 0x21u || value[byte] > 0x7eu) return false;
      }
      if (setting == wanted_setting && index == wanted_index) {
        view->value = value;
        view->length = length;
        return true;
      }
      offset = static_cast<uint16_t>(offset + length);
    }
  }
  return false;
}

bool catalog_label_for_native(const SettingCatalog &catalog, unsigned setting,
                              int16_t native, LabelView *view) {
  if (setting >= STAR_WORKER_SETTING_COUNT || view == nullptr) return false;
  unsigned matches = 0;
  for (uint16_t index = 0; index < catalog.value_count[setting]; ++index) {
    if (catalog.values[setting][index] != native) continue;
    if (!catalog_label_at(catalog, setting, index, view)) return false;
    ++matches;
  }
  return matches == 1;
}

bool labels_equal(const LabelView &catalog_label, const uint8_t *requested,
                  uint8_t requested_length) {
  return catalog_label.length == requested_length &&
         bytes_equal(catalog_label.value, requested, requested_length);
}

bool resolve_label_tuple(const SettingCatalog &catalog,
                         const LabelTuple &labels, int16_t native[3]) {
  for (unsigned setting = 0; setting < STAR_WORKER_SETTING_COUNT; ++setting) {
    unsigned matches = 0;
    for (uint16_t index = 0; index < catalog.value_count[setting]; ++index) {
      LabelView candidate = {};
      if (!catalog_label_at(catalog, setting, index, &candidate)) return false;
      if (labels_equal(candidate, labels.value[setting],
                       labels.length[setting])) {
        native[setting] = catalog.values[setting][index];
        ++matches;
      }
    }
    if (matches != 1) return false;
  }
  return native[1] >= catalog.aperture_min &&
         native[1] <= catalog.aperture_max;
}

bool encode_public_catalog(const SettingCatalog &catalog, uint8_t *output,
                           uint16_t *output_size) {
  if (output == nullptr || output_size == nullptr ||
      catalog.label_bytes == 0 ||
      catalog.label_bytes > kSettingCatalogLabelStorage) {
    return false;
  }
  uint16_t output_offset = 6;
  uint16_t label_offset = 0;
  for (unsigned setting = 0; setting < STAR_WORKER_SETTING_COUNT; ++setting) {
    const uint16_t count = catalog.value_count[setting];
    if (count == 0 || count > kSettingCatalogMaxValues ||
        count > UINT8_MAX ||
        output_offset >= STAR_WORKER_CATALOG_PAYLOAD_MAX) {
      return false;
    }
    const uint16_t value_begin = output_offset;
    output[output_offset++] = static_cast<uint8_t>(count);
    for (uint16_t index = 0; index < count; ++index) {
      if (label_offset >= catalog.label_bytes) return false;
      const uint8_t length = catalog.labels[label_offset++];
      if (length == 0 || length > STAR_WORKER_SETTING_LABEL_MAX ||
          static_cast<uint32_t>(label_offset) + length >
              catalog.label_bytes ||
          static_cast<uint32_t>(output_offset) + 1u + length >
              STAR_WORKER_CATALOG_PAYLOAD_MAX) {
        return false;
      }
      output[output_offset++] = length;
      for (uint8_t byte = 0; byte < length; ++byte) {
        const uint8_t value = catalog.labels[label_offset++];
        if (value < 0x21u || value > 0x7eu) return false;
        output[output_offset++] = value;
      }
    }
    const uint16_t value_size =
        static_cast<uint16_t>(output_offset - value_begin);
    if (value_size > STAR_WORKER_CATALOG_VALUE_MAX) return false;
    store_le16(output + setting * 2u, value_size);
  }
  if (label_offset != catalog.label_bytes ||
      output_offset > STAR_WORKER_CATALOG_PAYLOAD_MAX) {
    return false;
  }
  *output_size = output_offset;
  return true;
}

bool focal_bits_are_valid(uint32_t bits) {
  return bits >= UINT32_C(0x3dcccccd) && bits <= UINT32_C(0x44ffff00);
}

bool encode_public_state(const SettingCatalog &catalog,
                         const LensMetadata &lens, uint8_t *output,
                         uint16_t *output_size) {
  if (output == nullptr || output_size == nullptr) return false;
  LabelView current[STAR_WORKER_SETTING_COUNT] = {};
  for (unsigned setting = 0; setting < STAR_WORKER_SETTING_COUNT; ++setting) {
    if (catalog.value_count[setting] == 0 ||
        catalog.current_index[setting] >= catalog.value_count[setting] ||
        !catalog_label_at(catalog, setting, catalog.current_index[setting],
                          &current[setting])) {
      return false;
    }
    store_le16(output + setting * 2u,
               static_cast<uint16_t>(
                   catalog.values[setting][catalog.current_index[setting]]));
  }
  LabelView aperture_min = {};
  LabelView aperture_max = {};
  if (!catalog_label_for_native(catalog, 1, catalog.aperture_min,
                                &aperture_min) ||
      !catalog_label_for_native(catalog, 1, catalog.aperture_max,
                                &aperture_max)) {
    return false;
  }
  const int16_t current_aperture =
      catalog.values[1][catalog.current_index[1]];
  if (catalog.aperture_min > current_aperture ||
      current_aperture > catalog.aperture_max) {
    return false;
  }

  uint32_t focal = 0;
  if (lens.focal_length_valid) {
    if (!focal_bits_are_valid(lens.focal_length_f32_bits)) return false;
    focal = lens.focal_length_f32_bits;
  }
  store_le32(output + 6u, focal);
  uint16_t offset = 10;
  for (unsigned setting = 0; setting < STAR_WORKER_SETTING_COUNT; ++setting) {
    output[offset++] = current[setting].length;
    copy_bytes(output + offset, current[setting].value,
               current[setting].length);
    offset = static_cast<uint16_t>(offset + current[setting].length);
  }
  const LabelView limits[2] = {aperture_min, aperture_max};
  for (const LabelView &limit : limits) {
    output[offset++] = limit.length;
    copy_bytes(output + offset, limit.value, limit.length);
    offset = static_cast<uint16_t>(offset + limit.length);
  }
  uint8_t lens_length = 0;
  if (lens.lens_name_valid) {
    if (lens.lens_name_length == 0 ||
        lens.lens_name_length > STAR_WORKER_LENS_NAME_MAX) {
      return false;
    }
    lens_length = lens.lens_name_length;
  }
  output[offset++] = lens_length;
  for (uint8_t byte = 0; byte < lens_length; ++byte) {
    const uint8_t value = static_cast<uint8_t>(lens.lens_name[byte]);
    if (value < 0x20u || value > 0x7eu) return false;
    output[offset++] = value;
  }
  if (offset < STAR_WORKER_STATE_VALUE_MIN ||
      offset > STAR_WORKER_STATE_VALUE_MAX) {
    return false;
  }
  *output_size = offset;
  return true;
}

void read_optional_direct_lens(LensMetadata *lens, const char *stage) {
  *lens = {};
  Error lens_error = {};
  if (!g_camera.read_direct_lens_metadata(lens, &lens_error))
    print_error(stage, lens_error);
}

SnapshotRead read_strict_snapshot(Error *error) {
  bool rejected = false;
  if (!g_camera.read_setting_catalog(&g_setting_catalog, &rejected, error))
    return rejected ? kSnapshotRejected : kSnapshotFailed;
  read_optional_direct_lens(&g_lens_metadata,
                            "worker direct lens metadata unavailable");
  return kSnapshotOk;
}

SnapshotRead read_hot_snapshot(bool *catalog_changed, Error *error) {
  bool transient = false;
  if (!g_camera.refresh_setting_state_hot(
          &g_setting_catalog, &g_lens_metadata, catalog_changed, &transient,
          error)) {
    return transient ? kSnapshotTransient : kSnapshotFailed;
  }
  return kSnapshotOk;
}

bool publish_catalog(const SettingCatalog &catalog, bool force,
                     bool *changed) {
  uint8_t *const payload = g_frame + STAR_WORKER_FRAME_HEADER_SIZE;
  uint16_t payload_size = 0;
  if (!encode_public_catalog(catalog, payload, &payload_size)) return false;
  const bool differs =
      !g_catalog_cache_valid || payload_size != g_catalog_cache_size ||
      !bytes_equal(payload, g_catalog_cache, payload_size);
  if (changed != nullptr) *changed = differs;
  if (force || differs) {
    if (!publish_frame(STAR_WORKER_EVENT_CATALOG, payload_size)) return false;
  }
  copy_bytes(g_catalog_cache, payload, payload_size);
  g_catalog_cache_size = payload_size;
  g_catalog_cache_valid = true;
  return true;
}

bool publish_state(const SettingCatalog &catalog, const LensMetadata &lens,
                   uint8_t reason, bool force, bool catalog_changed) {
  uint8_t state[STAR_WORKER_STATE_VALUE_MAX];
  uint16_t state_size = 0;
  if (!encode_public_state(catalog, lens, state, &state_size)) return false;
  const bool differs =
      !g_state_cache_valid || state_size != g_state_cache_size ||
      !bytes_equal(state, g_state_cache, state_size);
  if (force || catalog_changed || differs) {
    uint8_t *const payload = g_frame + STAR_WORKER_FRAME_HEADER_SIZE;
    store_le16(payload, state_size);
    payload[2] = reason;
    copy_bytes(payload + 3u, state, state_size);
    if (!publish_frame(STAR_WORKER_EVENT_STATE,
                       static_cast<uint16_t>(state_size + 3u))) {
      return false;
    }
  }
  copy_bytes(g_state_cache, state, state_size);
  g_state_cache_size = state_size;
  g_state_cache_valid = true;
  return true;
}

bool publish_snapshot(uint8_t reason, bool force_state,
                      bool force_catalog = false) {
  bool catalog_changed = false;
  return publish_catalog(g_setting_catalog, force_catalog, &catalog_changed) &&
         publish_state(g_setting_catalog, g_lens_metadata, reason,
                       force_state, catalog_changed);
}

bool publish_cached_terminal(uint8_t reason) {
  if (!g_catalog_cache_valid || !g_state_cache_valid) return false;
  uint8_t *const payload = g_frame + STAR_WORKER_FRAME_HEADER_SIZE;
  store_le16(payload, g_state_cache_size);
  payload[2] = reason;
  copy_bytes(payload + 3u, g_state_cache, g_state_cache_size);
  return publish_frame(STAR_WORKER_EVENT_STATE,
                       static_cast<uint16_t>(g_state_cache_size + 3u));
}

bool publish_capture_rejection() {
  uint8_t *const payload = g_frame + STAR_WORKER_FRAME_HEADER_SIZE;
  for (unsigned i = 0; i < STAR_WORKER_CAPTURE_RESULT_PREFIX_SIZE; ++i)
    payload[i] = 0;
  payload[0] = STAR_WORKER_CAPTURE_REJECTED_NO_MUTATION;
  return publish_frame(STAR_WORKER_EVENT_CAPTURE_RESULT,
                       STAR_WORKER_CAPTURE_RESULT_PREFIX_SIZE);
}

bool publish_capture_result(uint32_t detected_count,
                            const int16_t captured_settings[3],
                            const LensMetadata &lens) {
  if (detected_count > STAR_WORKER_MAX_PEAKS ||
      captured_settings == nullptr) {
    return false;
  }
  uint8_t lens_length = 0;
  if (lens.lens_name_valid) {
    if (lens.lens_name_length == 0 ||
        lens.lens_name_length > STAR_WORKER_LENS_NAME_MAX) {
      return false;
    }
    lens_length = lens.lens_name_length;
  }
  uint32_t focal = 0;
  if (lens.focal_length_valid) {
    if (!focal_bits_are_valid(lens.focal_length_f32_bits)) return false;
    focal = lens.focal_length_f32_bits;
  }
  const uint32_t payload_size =
      STAR_WORKER_CAPTURE_RESULT_PREFIX_SIZE +
      detected_count * sizeof(star_worker_peak) + lens_length;
  if (payload_size > STAR_WORKER_CAPTURE_RESULT_PAYLOAD_MAX) return false;
  uint8_t *const payload = g_frame + STAR_WORKER_FRAME_HEADER_SIZE;
  payload[0] = STAR_WORKER_CAPTURE_OK;
  store_le16(payload + 1u, static_cast<uint16_t>(detected_count));
  for (unsigned setting = 0; setting < STAR_WORKER_SETTING_COUNT; ++setting) {
    store_le16(payload + 3u + setting * 2u,
               static_cast<uint16_t>(captured_settings[setting]));
  }
  payload[9] = lens_length;
  store_le32(payload + 10u, focal);
  uint16_t offset = STAR_WORKER_CAPTURE_RESULT_PREFIX_SIZE;
  for (uint32_t index = 0; index < detected_count; ++index) {
    const Star &source = g_output_stars[index];
    uint32_t x = (source.x_q8 + 512u) / 1024u;
    uint32_t y = (source.y_q8 + 512u) / 1024u;
    if (x >= 1296u) x = 1295u;
    if (y >= 972u) y = 971u;
    if (source.flux == 0 || source.flux > UINT32_MAX) return false;
    store_le16(payload + offset, static_cast<uint16_t>(x));
    store_le16(payload + offset + 2u, static_cast<uint16_t>(y));
    store_le32(payload + offset + 4u,
               static_cast<uint32_t>(source.flux));
    offset = static_cast<uint16_t>(offset + sizeof(star_worker_peak));
  }
  for (uint8_t byte = 0; byte < lens_length; ++byte) {
    const uint8_t value = static_cast<uint8_t>(lens.lens_name[byte]);
    if (value < 0x20u || value > 0x7eu) return false;
    payload[offset++] = value;
  }
  return offset == payload_size &&
         publish_frame(STAR_WORKER_EVENT_CAPTURE_RESULT,
                       static_cast<uint16_t>(payload_size));
}

bool detection_reduced_row(void *context, uint32_t row, const uint32_t *sums,
                           const uint8_t *saturated, uint32_t width,
                           Error *error) {
  DetectionBridge *const bridge = static_cast<DetectionBridge *>(context);
  if (!bridge->started) {
    if (row != 0 || bridge->options == nullptr ||
        !detector_begin(*bridge->raw_info, *bridge->options, error)) {
      return false;
    }
    bridge->started = true;
  }
  return detector_consume_luma_row(nullptr, row, sums, saturated, width,
                                   error);
}

bool analyze_input(InputStream *input, const DetectorOptions &options,
                   uint32_t *detected_count, Error *error) {
  if (input == nullptr || detected_count == nullptr)
    return fail(error, "missing ORF analysis input");
  *detected_count = 0;
  RawInfo info = {};
  DetectionBridge bridge = {&info, &options, false};
  if (!process_orf_reduced(input, detection_reduced_row, &bridge, &info,
                           nullptr, error)) {
    return false;
  }
  if (!bridge.started)
    return fail(error, "ORF decoder produced no rows");
  return detector_finish(g_output_stars, detected_count, error);
}

int handle_keep_awake() {
  Error error = {};
  bool recovered = false;
  if (!g_camera.keep_awake(&recovered, &error)) {
    return terminate_worker_failure(
        false, "worker keep-awake U-shell recovery failed", error);
  }
  if (recovered) {
    print_error("worker keep-awake command outcome recovered", error);
  }
  const uint8_t result =
      recovered ? STAR_WORKER_KEEP_AWAKE_RECOVERED
                : STAR_WORKER_KEEP_AWAKE_OK;
  if (!publish_keep_awake_result(result)) return stop_after_publish_failure();
  return 0;
}

int handle_set(const LabelTuple &labels) {
  Error error = {};
  const SnapshotRead preflight = read_strict_snapshot(&error);
  if (preflight != kSnapshotOk) {
    if (preflight == kSnapshotRejected) {
      print_error("worker setting catalog rejected", error);
      if (publish_cached_terminal(STAR_WORKER_STATE_SET_REJECTED)) return 0;
    }
    return terminate_worker_failure(
        false, "worker setting preflight failed", error);
  }
  int16_t targets[STAR_WORKER_SETTING_COUNT] = {};
  if (!resolve_label_tuple(g_setting_catalog, labels, targets)) {
    if (!publish_snapshot(STAR_WORKER_STATE_SET_REJECTED, true))
      return stop_after_publish_failure();
    return 0;
  }

  bool catalog_rejected = false;
  bool safe_rejected = false;
  if (!g_camera.set_persistent_settings(targets, &error, &catalog_rejected,
                                        &safe_rejected)) {
    if (!catalog_rejected && !safe_rejected) {
      return terminate_worker_failure(
          true, "worker camera-setting transaction failed", error);
    }
    print_error("worker camera-setting request rejected", error);
    const SnapshotRead terminal = read_strict_snapshot(&error);
    if (terminal != kSnapshotOk) {
      return terminate_worker_failure(
          safe_rejected, "worker rejected-setting snapshot failed", error);
    }
    if (!publish_snapshot(STAR_WORKER_STATE_SET_REJECTED, true))
      return stop_after_publish_failure();
    return 0;
  }
  const SnapshotRead terminal = read_strict_snapshot(&error);
  if (terminal != kSnapshotOk) {
    return terminate_worker_failure(
        true, "worker applied-setting snapshot failed", error);
  }
  if (!publish_snapshot(STAR_WORKER_STATE_SET_APPLIED, true))
    return stop_after_publish_failure();
  return 0;
}

int handle_capture(const LabelTuple &labels,
                   const DetectorOptions &detector_options,
                   bool preposition_required) {
  Error error = {};
  const SnapshotRead preflight = read_strict_snapshot(&error);
  if (preflight != kSnapshotOk) {
    if (preflight == kSnapshotRejected) {
      print_error("worker capture catalog rejected", error);
      if (publish_cached_terminal(STAR_WORKER_STATE_CAPTURE_REJECTED) &&
          publish_capture_rejection()) {
        return 0;
      }
    }
    return terminate_worker_failure(
        false, "worker capture preflight failed", error);
  }
  int16_t targets[STAR_WORKER_SETTING_COUNT] = {};
  if (!resolve_label_tuple(g_setting_catalog, labels, targets)) {
    if (!publish_snapshot(STAR_WORKER_STATE_CAPTURE_REJECTED, true) ||
        !publish_capture_rejection()) {
      return stop_after_publish_failure();
    }
    return 0;
  }

  bool catalog_rejected = false;
  if (!g_camera.apply_capture_settings(targets, &error,
                                       &catalog_rejected)) {
    if (catalog_rejected) {
      print_error("worker capture settings rejected", error);
      const SnapshotRead terminal = read_strict_snapshot(&error);
      if (terminal != kSnapshotOk) {
        return terminate_worker_failure(
            false, "worker rejected-capture snapshot failed", error);
      }
      if (publish_snapshot(STAR_WORKER_STATE_CAPTURE_REJECTED, true) &&
          publish_capture_rejection()) {
        return 0;
      }
      return stop_after_publish_failure();
    }
    return terminate_worker_failure(
        true, "worker capture-recipe setup failed", error);
  }

  CaptureInfo capture = {};
  if (!g_camera.take_persistent_photo(kCaptureTimeoutMilliseconds, &capture,
                                      &error, preposition_required)) {
    return terminate_worker_failure(true, "worker capture failed", error);
  }

  LensMetadata capture_lens = {};
  Error lens_error = {};
  if (!g_camera.read_post_capture_lens_metadata(&capture_lens, &lens_error))
    print_error("worker post-capture lens metadata unavailable", lens_error);

  InputStream input = {};
  if (!g_camera.open_persistent_transfer_input(
          capture, kTransferTimeoutMilliseconds, &input, &error)) {
    return terminate_worker_failure(
        true, "worker ORF transfer open failed", error);
  }
  uint32_t detected_count = 0;
  if (!analyze_input(&input, detector_options, &detected_count, &error)) {
    Error release = {};
    if (!g_camera.cancel_transfer_input(&release))
      print_error("worker failed ORF transfer cleanup also failed", release);
    return terminate_worker_failure(true, "worker analysis failed", error);
  }

  if (!g_camera.rollover_persistent_capture(&error)) {
    return terminate_worker_failure(true, "worker local rollover failed",
                                    error);
  }
  const SnapshotRead restored = read_strict_snapshot(&error);
  if (restored != kSnapshotOk) {
    return terminate_worker_failure(
        true, "worker restored-state snapshot failed", error);
  }
  if (!publish_snapshot(STAR_WORKER_STATE_CAPTURE_RESTORED, true) ||
      !publish_capture_result(detected_count, targets, capture_lens)) {
    return stop_after_publish_failure();
  }
  return 0;
}

int run_capture_worker() {
  g_camera.initialize();
  Error error = {};
  if (!g_camera.open(kDefaultPtpGuidTail, &error)) {
    return terminate_worker_failure(false, "worker camera open failed",
                                    error);
  }
  if (!g_camera.arm_persistent_capture(&error)) {
    return terminate_worker_failure(false, "worker capture arm failed",
                                    error);
  }
  if (read_strict_snapshot(&error) != kSnapshotOk) {
    return terminate_worker_failure(false, "worker startup snapshot failed",
                                    error);
  }
  if (!publish_snapshot(STAR_WORKER_STATE_STARTUP, true, true))
    return stop_after_publish_failure();

  for (;;) {
    os::PollFd descriptor = {0, os::kPollIn, 0};
    const long poll_result =
        os::poll(&descriptor, 1, kStateObservationMilliseconds);
    if (os::failed(poll_result)) {
      if (os::error_number(poll_result) == os::kErrorInterrupted) continue;
      fail(&error, "worker command poll failed");
      return terminate_worker_failure(false, "worker protocol failed",
                                      error);
    }
    if (poll_result == 0) {
      bool catalog_changed = false;
      const SnapshotRead observation =
          read_hot_snapshot(&catalog_changed, &error);
      if (observation == kSnapshotTransient) continue;
      if (observation != kSnapshotOk) {
        return terminate_worker_failure(false,
                                        "worker state observation failed",
                                        error);
      }
      if (!(catalog_changed
                ? publish_snapshot(STAR_WORKER_STATE_OBSERVED, false)
                : publish_state(g_setting_catalog, g_lens_metadata,
                                STAR_WORKER_STATE_OBSERVED, false, false)))
        return stop_after_publish_failure();
      continue;
    }
    if ((descriptor.revents & os::kPollInvalid) != 0 ||
        (descriptor.revents & os::kPollError) != 0) {
      fail(&error, "worker command pipe became invalid");
      return terminate_worker_failure(false, "worker protocol failed",
                                      error);
    }

    star_worker_frame_header header = {};
    const FrameRead read = read_command(&header);
    if (read == kFrameReadEof) return close_worker_without_peer();
    if (read != kFrameReadOk) {
      fail(&error, read == kFrameReadTruncated
                       ? "truncated worker command frame"
                       : "invalid worker command frame");
      return terminate_worker_failure(false, "worker protocol failed",
                                      error);
    }
    if (header.type == STAR_WORKER_COMMAND_KEEP_AWAKE) {
      const int result = handle_keep_awake();
      if (result != 0) return result;
      continue;
    }
    LabelTuple labels = {};
    DetectorOptions detector_options = {};
    const bool decoded =
        header.type == STAR_WORKER_COMMAND_SET
            ? decode_label_tuple(g_command_payload, header.payload_size,
                                 &labels)
            : decode_capture_payload(g_command_payload, header.payload_size,
                                     &labels, &detector_options);
    if (!decoded) {
      fail(&error, header.type == STAR_WORKER_COMMAND_SET
                       ? "worker command labels are malformed"
                       : "worker capture options or labels are malformed");
      return terminate_worker_failure(false, "worker protocol failed",
                                      error);
    }
    const int result =
        header.type == STAR_WORKER_COMMAND_SET ? handle_set(labels)
                                               : handle_capture(
                                                     labels, detector_options,
                                                     header.type ==
                                                         STAR_WORKER_COMMAND_CAPTURE_PREPOSITION);
    if (result != 0) return result;
  }
}

int usage(int fd, int status) {
  static constexpr char kUsage[] =
      "usage: om1-stars --worker\n"
      "       om1-stars --help\n";
  (void)write_all(fd, kUsage, sizeof(kUsage) - 1u);
  return status;
}

}  // namespace
}  // namespace om1_stars

extern "C" int main(int argc, char **argv) {
  using namespace om1_stars;
  if (argc == 2 && string_equal(argv[1], "--worker"))
    return run_capture_worker();
  if (argc == 2 && string_equal(argv[1], "--help")) return usage(1, 0);
  return usage(2, 64);
}
