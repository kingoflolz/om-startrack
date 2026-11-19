#include "camera.h"

#include "astro_preposition_capture.h"
#include "setting_batch_parser.h"
#include "setting_write_retry.h"
#include "station_state_parser.h"
#include "syscalls.h"
#include "util.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {

namespace {

constexpr uint64_t kWifiAnchorPhysical = UINT64_C(0x2a1934d7c);
constexpr uint64_t kConnectModePhysical = UINT64_C(0x2a2cde316);
constexpr uint64_t kInnerGatePhysical = UINT64_C(0x2a2cdad58);
constexpr uint32_t kWifiAnchorValue = UINT32_C(0x49464957);
constexpr uint16_t kRequiredConnectMode = 9;
constexpr size_t kPageSize = 4096;

constexpr uint16_t kOperationShellRead = 0x902a;
constexpr uint16_t kOperationShellExecute = 0x902b;
constexpr uint16_t kOperationUntransferredList = 0x94bb;
constexpr uint16_t kOperationTransferMetadata = 0x94dd;

constexpr uint32_t kPtpTimeoutMs = 10000;
constexpr uint32_t kShellTimeoutMs = 10000;
constexpr uint32_t kSettingSettleTimeoutMs = 4000;
constexpr uint32_t kSettingPollMs = 200;
constexpr unsigned kSettingWriteAttempts = 2;
constexpr uint32_t kSettingUnresponsiveRetryMs = 200;
constexpr unsigned kSettingRestoreAttempts = 3;
constexpr uint32_t kSettingRestoreRetryMs = 50;
constexpr unsigned kShutterRecoveryAttempts = 3;
// Direct state polling observes setting convergence without another U-shell
// read transaction.
constexpr uint32_t kDirectSettingPollMs = 1;
// A rejected/unconfirmed B01 gets a long ambiguity observation window.  Poll
// its strict direct snapshot at a modest rate so the exceptional path cannot
// spend four seconds continuously evaluating the full catalog.
constexpr uint32_t kSettingWriteObservationPollMs = 20;
// Empty retained 902A reads are one-NUL, read-only polls.  A 20 ms backoff
// avoids the former fixed 100 ms command floor without busy-spinning on the
// camera's asynchronous U-shell task.
constexpr uint32_t kShellPollMs = 20;
// Once a stock 0x902A transcript has byte-for-byte shadow-qualified the
// uncached diagnostic ring, Linux can poll its private cursor without loading
// the camera's PTP task.  One millisecond removes prompt-read round trips while
// preserving bounded pacing.
constexpr uint32_t kDirectShellPollMs = 1;
constexpr uint32_t kDirectShellQualificationTimeoutMs = 2000;
// Strict recovery/close paths can perform a stock-shadow checkpoint. Under the
// worker's exclusive U-shell contract, modulo-visible output between such
// checkpoints is bounded to half the 16-KiB firmware ring. Crossing the bound
// forces an authoritative stock FIFO fence. Every successful rollover now
// performs that proof before publishing the next generation.
constexpr uint32_t kDirectShellCheckpointByteLimit = 8192;
constexpr uint32_t kStationReadyTimeoutMs = 10000;
// A read-only direct load is only a wake hint for the asynchronous 94DE
// cleanup timer. Strict teardown still requires one stock U-shell station
// proof after the hint reaches pic_trans_state=0; successful rollover uses the
// same authoritative wait before it can rearm.
constexpr uint32_t kStationDirectPollMs = 20;
constexpr uint32_t kShutterStateTimeoutMs = 2000;
constexpr uint32_t kShutterStatePollMs = 50;
// The qualified TrigState mapping pairs each asynchronous persistent SDK-event
// post with its one downstream edge without four prompt-read round trips.
constexpr uint32_t kPersistentSdkStatePollMs = 1;
constexpr uint32_t kPersistentSdkFullPressStabilityMs = 1;
constexpr uint32_t kPersistentSdkDrainTimeoutMs = 2000;
constexpr uint32_t kShutterPressDwellMs = 100;
constexpr uint32_t kTransferRecordPollMs = 100;
// Persistent 94BB itself supplies most of the pacing.  The worker is the sole
// capture owner, so a short sleep keeps publication latency low without a
// tight read-only poll loop.  The legacy one-shot path keeps its 100 ms delay.
constexpr uint32_t kPersistentTransferRecordPollMs = 20;
// Direct discovery polls the firmware's post-record occupied counters between
// exact scans.  One millisecond is enough pacing for eight descriptor loads;
// an exact scan at least every eight probes bounds a cache-stale hint or an
// equal-count remove/add ABA to well below the retained 20 ms PTP backoff.
constexpr uint32_t kPersistentDirectTransferPollMs = 1;
constexpr uint32_t kPersistentDirectAuditPollInterval = 8;
// A publication burst can mutate a second record while the first changed
// occupied-count hint is driving a full two-pass scan.  Retry only that typed
// instability a bounded number of times; layout/mapping failures still demote
// immediately to stock 94BB/94DD discovery.
constexpr uint32_t kPersistentDirectTransientRetryLimit = 4;
constexpr uint32_t kPersistentTransferRecordFinalWindowMs = 100;
constexpr uint32_t kInactiveTransferSlotPollInterval = 4;
constexpr uint32_t kEffectiveRecordMediaCameraInfoId = 23;
constexpr uint32_t kPictureRecordModeControllerId = 1129;
constexpr uint32_t kPictureRecordSlotControllerId = 1130;
// The on-camera U-shell and stock BLE entry wrappers both reach 0x50025095
// with the same downstream event, data=0, and auxiliary=0 arguments here.
constexpr char kBleFirstStageOn[] = "sdk event 33816832 0";   // 0x02040100
constexpr char kBleSecondStageOn[] = "sdk event 33816833 0";  // 0x02040101
constexpr char kBleSecondStageOff[] = "sdk event 33882369 0"; // 0x02050101
constexpr char kBleFirstStageOff[] = "sdk event 33882368 0";  // 0x02050100
constexpr char kKeepAwakeCommand[] = "sdk sleep api state";

constexpr char kReadEventInitCookie[] = "sys mem_get1 a31f1d26";
constexpr char kReadBleConverterState[] = "sys mem_get4 a3212194";
constexpr char kReadShutterTriggerState[] = "sys mem_get1 60357bbc";
constexpr uint32_t kRequiredEventInitCookie = 0x77;
constexpr uint32_t kShutterFirstStageState = 0x44;
constexpr uint32_t kShutterBothStagesState = 0xcc;

struct SettingDefinition {
  uint32_t camera_info_id;
};

// This order follows the live-tested SettingController exercise.  Cleanup
// iterates it backwards: ISO, aperture, shutter.
constexpr SettingDefinition kSettings[3] = {
    {4},  // shutter
    {3},  // aperture
    {6},  // ISO
};
constexpr uint16_t kDefaultSettingControllerIds[3] = {1669, 1667, 1671};
constexpr unsigned kSettingCatalogExposureStepIndex = 9;
constexpr unsigned kSettingCatalogIsoStepIndex = 10;
constexpr int32_t kDefaultCaptureTargets[3] = {
    512,   // shutter: 1/4 s
    1024,  // aperture: f/4
    2304,  // ISO 1600
};

void memory_barrier() { asm volatile("dmb sy" ::: "memory"); }

bool deadline_after(uint32_t timeout_ms, uint64_t *deadline, Error *error) {
  uint64_t now = 0;
  if (os::failed(os::monotonic_milliseconds(&now))) {
    return fail(error, "CLOCK_MONOTONIC failed");
  }
  *deadline = now + timeout_ms;
  if (*deadline < now) *deadline = UINT64_MAX;
  return true;
}

bool deadline_remaining(uint64_t deadline, uint32_t *remaining,
                        Error *error) {
  uint64_t now = 0;
  if (os::failed(os::monotonic_milliseconds(&now))) {
    return fail(error, "CLOCK_MONOTONIC failed");
  }
  if (now >= deadline) return fail(error, "camera operation timed out");
  uint64_t result = deadline - now;
  if (result > UINT32_MAX) result = UINT32_MAX;
  *remaining = static_cast<uint32_t>(result);
  return true;
}

bool contains_bytes(const char *data, size_t size, const char *needle) {
  const size_t needle_size = string_length(needle);
  if (needle_size == 0) return true;
  if (needle_size > size) return false;
  for (size_t i = 0; i <= size - needle_size; ++i) {
    size_t j = 0;
    while (j < needle_size && data[i + j] == needle[j]) ++j;
    if (j == needle_size) return true;
  }
  return false;
}

uint32_t fnv1a_bytes(const char *data, size_t size) {
  uint32_t hash = UINT32_C(2166136261);
  for (size_t index = 0; index < size; ++index) {
    hash ^= static_cast<uint8_t>(data[index]);
    hash *= UINT32_C(16777619);
  }
  return hash;
}

bool line_has_b01_result(const char *data, size_t begin, size_t end) {
  if (data == nullptr || begin >= end) return false;
  return contains_bytes(data + begin, end - begin, "SettingController::") ||
         contains_bytes(data + begin, end - begin, "API_");
}

void append_sanitized_b01_result(TextBuffer *text, const char *data,
                                 size_t size) {
  if (text == nullptr || data == nullptr) return;
  bool emitted = false;
  size_t begin = 0;
  while (begin < size) {
    size_t end = begin;
    while (end < size && data[end] != '\n' && data[end] != '\r') ++end;
    if (line_has_b01_result(data, begin, end)) {
      if (emitted) text->append_char('|');
      size_t visible = 0;
      for (size_t index = begin; index < end && visible < 96; ++index) {
        const uint8_t byte = static_cast<uint8_t>(data[index]);
        text->append_char(byte >= 0x20 && byte <= 0x7e
                              ? static_cast<char>(byte)
                              : '.');
        ++visible;
      }
      if (end - begin > visible) text->append("...");
      emitted = true;
    }
    while (end < size && (data[end] == '\n' || data[end] == '\r')) ++end;
    begin = end;
  }
  if (!emitted) text->append("<no-result-line>");
}

void describe_b01_terminal_response(Error *error, unsigned setting_index,
                                    unsigned attempt, const char *data,
                                    size_t size) {
  char storage[384];
  TextBuffer line(storage, sizeof(storage));
  line.append("om1-stars: B01 terminal response without API_SUCCESS setting=");
  line.append_u64(setting_index);
  line.append(" attempt=");
  line.append_u64(attempt);
  line.append(" bytes=");
  line.append_u64(size);
  line.append(" fnv1a=0x");
  line.append_hex(fnv1a_bytes(data, size), 8);
  line.append(" result=\"");
  append_sanitized_b01_result(&line, data, size);
  line.append("\"\n");
  if (line.ok()) (void)write_all(2, line.data(), line.size());

  if (error != nullptr) {
    TextBuffer message(error->text, sizeof(error->text));
    message.append("B01 terminal response lacked API_SUCCESS; setting=");
    message.append_u64(setting_index);
    message.append(" attempt=");
    message.append_u64(attempt);
    message.append(" result=\"");
    append_sanitized_b01_result(&message, data, size);
    message.append_char('"');
  }
}

void describe_b01_unresponsive_retry(bool restoring, unsigned setting_index,
                                     unsigned attempts,
                                     const char *outcome) {
  char storage[192];
  TextBuffer line(storage, sizeof(storage));
  line.append("om1-stars: B01 unresponsive retry phase=");
  line.append(restoring ? "restore" : "setup");
  line.append(" setting=");
  line.append_u64(setting_index);
  line.append(" attempts=");
  line.append_u64(attempts);
  line.append(" outcome=");
  line.append(outcome == nullptr ? "internal-error" : outcome);
  line.append_char('\n');
  if (line.ok()) (void)write_all(2, line.data(), line.size());
}

size_t count_bytes(const char *data, size_t size, const char *needle) {
  const size_t needle_size = string_length(needle);
  if (needle_size == 0 || needle_size > size) return 0;
  size_t count = 0;
  for (size_t i = 0; i <= size - needle_size;) {
    size_t j = 0;
    while (j < needle_size && data[i + j] == needle[j]) ++j;
    if (j == needle_size) {
      ++count;
      i += needle_size;
    } else {
      ++i;
    }
  }
  return count;
}

bool parse_prefixed_line_i32(const char *data, size_t size,
                             const char *prefix, int32_t *result) {
  const size_t prefix_size = string_length(prefix);
  bool found = false;
  int32_t parsed = 0;
  for (size_t start = 0; start < size;) {
    size_t end = start;
    while (end < size && data[end] != '\n') ++end;
    if (end - start >= prefix_size) {
      size_t prefix_at = 0;
      while (prefix_at < prefix_size &&
             data[start + prefix_at] == prefix[prefix_at]) {
        ++prefix_at;
      }
      if (prefix_at == prefix_size) {
        size_t cursor = start + prefix_size;
        while (cursor < end &&
               (data[cursor] == ' ' || data[cursor] == '\t')) {
          ++cursor;
        }
        bool negative = false;
        if (cursor < end && data[cursor] == '-') {
          negative = true;
          ++cursor;
        } else if (cursor < end && data[cursor] == '+') {
          ++cursor;
        }
        if (cursor == end || data[cursor] < '0' || data[cursor] > '9') {
          return false;
        }
        uint64_t magnitude = 0;
        while (cursor < end && data[cursor] >= '0' && data[cursor] <= '9') {
          const uint32_t digit =
              static_cast<unsigned>(data[cursor] - '0');
          if (magnitude > (UINT64_C(2147483648) - digit) / 10u) {
            return false;
          }
          magnitude = magnitude * 10u + digit;
          ++cursor;
        }
        while (cursor < end && (data[cursor] == ' ' || data[cursor] == '\t' ||
                                data[cursor] == '\r')) {
          ++cursor;
        }
        if (cursor != end || (!negative && magnitude > INT32_MAX) ||
            (negative && magnitude > UINT64_C(2147483648)) || found) {
          return false;
        }
        if (negative) {
          parsed = magnitude == UINT64_C(2147483648)
                       ? INT32_MIN
                       : -static_cast<int32_t>(magnitude);
        } else {
          parsed = static_cast<int32_t>(magnitude);
        }
        found = true;
      }
    }
    start = end < size ? end + 1u : size;
  }
  if (found && result != nullptr) *result = parsed;
  return found;
}

bool append_setting_command(char *storage, size_t capacity, const char *prefix,
                            uint32_t id, bool include_value, int32_t value) {
  TextBuffer command(storage, capacity);
  command.append(prefix);
  command.append_u64(id);
  if (include_value) {
    command.append_char(' ');
    command.append_i64(value);
  }
  return command.ok();
}

bool response_ok(const PtpOperationResult &result, const char *operation,
                 Error *error) {
  if (result.response_code == kPtpResponseOk) return true;
  if (error == nullptr) return false;
  TextBuffer text(error->text, sizeof(error->text));
  text.append(operation);
  text.append(" returned 0x");
  text.append_hex(result.response_code, 4);
  return false;
}

bool response_has_five_zero_parameters(const PtpOperationResult &result) {
  if (result.parameter_count != 5) return false;
  for (unsigned i = 0; i < 5; ++i) {
    if (result.parameters[i] != 0) return false;
  }
  return true;
}

void retain_first_error(bool success, const Error &candidate, bool *all_ok,
                        Error *first) {
  if (success) return;
  if (*all_ok) *first = candidate;
  *all_ok = false;
}

bool fail_devmem_shadow_mismatch(Error *error, const char *command,
                                 uint32_t before, uint32_t shell,
                                 uint32_t after) {
  if (error == nullptr) return false;
  TextBuffer text(error->text, sizeof(error->text));
  text.append("/dev/mem shadow mismatch for '");
  text.append(command);
  text.append("': before=0x");
  text.append_hex(before, 8);
  text.append(" shell=0x");
  text.append_hex(shell, 8);
  text.append(" after=0x");
  text.append_hex(after, 8);
  return false;
}

void append_station_tuple(TextBuffer *text, const DevMemStationState &state) {
  text->append_char('(');
  text->append_u64(state.station);
  text->append_char(',');
  text->append_u64(state.registration);
  text->append_char(',');
  text->append_u64(state.priority);
  text->append_char(',');
  text->append_u64(state.connection);
  text->append_char(',');
  text->append_u64(state.picture_transfer);
  text->append_char(')');
}

bool fail_station_shadow_mismatch(Error *error,
                                  const DevMemStationState &before,
                                  const DevMemStationState &shell,
                                  const DevMemStationState &after) {
  if (error == nullptr) return false;
  TextBuffer text(error->text, sizeof(error->text));
  text.append("/dev/mem station shadow mismatch: before=");
  append_station_tuple(&text, before);
  text.append(" shell=");
  append_station_tuple(&text, shell);
  text.append(" after=");
  append_station_tuple(&text, after);
  return false;
}

int lowercase_hex_digit(char value) {
  if (value >= '0' && value <= '9') return value - '0';
  if (value >= 'a' && value <= 'f') return value - 'a' + 10;
  return -1;
}

// `sys mem_get*` prints exactly one canonical lower-case `%x` value on a
// line beginning with one space.  Reject duplicate, padded, signed, upper-case,
// or otherwise decorated values so unrelated diagnostic text cannot satisfy a
// state-machine guard.
bool parse_exact_shell_hex(const char *data, size_t size, uint32_t maximum,
                           uint32_t *value) {
  constexpr char kPrefix[] = " return ";
  const size_t prefix_size = sizeof(kPrefix) - 1u;
  bool found = false;
  uint32_t parsed = 0;
  for (size_t begin = 0; begin < size;) {
    size_t end = begin;
    while (end < size && data[end] != '\n') ++end;
    size_t content_end = end;
    if (content_end != begin && data[content_end - 1u] == '\r') --content_end;
    if (content_end - begin >= prefix_size) {
      size_t matched = 0;
      while (matched < prefix_size &&
             data[begin + matched] == kPrefix[matched]) {
        ++matched;
      }
      if (matched == prefix_size) {
        size_t cursor = begin + prefix_size;
        if (found || cursor == content_end ||
            (data[cursor] == '0' && cursor + 1u != content_end) ||
            content_end - cursor > 8u) {
          return false;
        }
        uint32_t current = 0;
        while (cursor < content_end) {
          const int digit = lowercase_hex_digit(data[cursor]);
          if (digit < 0 ||
              current > (UINT32_MAX - static_cast<uint32_t>(digit)) / 16u) {
            return false;
          }
          current = current * 16u + static_cast<uint32_t>(digit);
          ++cursor;
        }
        if (current > maximum) return false;
        parsed = current;
        found = true;
      }
    }
    begin = end < size ? end + 1u : size;
  }
  if (found && value != nullptr) *value = parsed;
  return found;
}

bool valid_dcf_prefix(char value) {
  return (value >= 'A' && value <= 'Z') ||
         (value >= '0' && value <= '9') || value == '_';
}

bool valid_capture_leaf(const CaptureInfo &capture) {
  if (capture.slot != 1 && capture.slot != 2) return false;
  for (unsigned i = 0; i < 4; ++i) {
    if (!valid_dcf_prefix(capture.leaf[i])) return false;
  }
  for (unsigned i = 4; i < 8; ++i) {
    if (capture.leaf[i] < '0' || capture.leaf[i] > '9') return false;
  }
  return capture.leaf[8] == '.' && capture.leaf[9] == 'O' &&
         capture.leaf[10] == 'R' && capture.leaf[11] == 'F' &&
         capture.leaf[12] == '\0' && capture.file_size != 0;
}

bool valid_direct_transfer_record(const DevMemTransferRecord &record) {
  for (unsigned i = 0; i < 4; ++i) {
    if (!valid_dcf_prefix(record.leaf[i])) return false;
  }
  for (unsigned i = 4; i < 8; ++i) {
    if (record.leaf[i] < '0' || record.leaf[i] > '9') return false;
  }
  if (record.leaf[8] != '.' || record.leaf[12] != '\0' ||
      record.file_size == 0) {
    return false;
  }
  for (unsigned i = 9; i < 12; ++i) {
    if (record.leaf[i] < 'A' || record.leaf[i] > 'Z') return false;
  }
  return true;
}

struct LensIdentityData {
  uint8_t id[6];
  uint16_t wide_mm;
  uint16_t tele_mm;
};

struct LensStateData {
  uint16_t focus_pulse;
  uint16_t autofocus_reference;
};

struct LensCalibrationRow {
  float focal_k0;
  float focal_k1;
  float focal_k2;
  uint16_t infinity_reference;
};

bool canonical_signed16(uint32_t raw, int32_t *value) {
  if (raw > UINT16_MAX && raw < UINT32_C(0xffff8000)) return false;
  const uint16_t low = static_cast<uint16_t>(raw);
  const int32_t parsed = (low & UINT16_C(0x8000)) != 0
                             ? static_cast<int32_t>(low) - 65536
                             : static_cast<int32_t>(low);
  if (value != nullptr) *value = parsed;
  return true;
}

float float_from_bits(uint32_t bits) {
  union {
    uint32_t bits;
    float value;
  } converted = {bits};
  return converted.value;
}

uint32_t float_to_bits(float value) {
  union {
    float value;
    uint32_t bits;
  } converted = {value};
  return converted.bits;
}

bool finite_float_word(uint32_t bits) {
  return (bits & UINT32_C(0x7f800000)) != UINT32_C(0x7f800000);
}

__attribute__((noinline)) float rounded_float(float value) {
  volatile float rounded = value;
  return rounded;
}

bool evaluate_focus_breathed_focal_length(
    const LensIdentityData &identity, const LensStateData &state,
    const LensCalibrationRow &calibration, uint32_t *focal_bits) {
  int32_t focus = 0;
  int32_t reference = 0;
  if (!canonical_signed16(state.focus_pulse, &focus)) return false;
  const bool special_reference =
      identity.id[0] == 0 && identity.id[1] == 0 &&
      identity.id[2] == UINT8_C(0x26) && identity.id[3] == UINT8_C(0x10);
  if (!canonical_signed16(special_reference ? state.autofocus_reference
                                            : calibration.infinity_reference,
                          &reference)) {
    return false;
  }
  const uint32_t delta = focus > reference
                             ? static_cast<uint32_t>(focus - reference)
                             : 0u;
  const float delta_f = rounded_float(static_cast<float>(delta));
  const float delta_squared =
      rounded_float(static_cast<float>(delta * delta));
  const float focal_linear = rounded_float(
      rounded_float(calibration.focal_k1 * delta_f) + calibration.focal_k0);
  const float flact = rounded_float(
      focal_linear + rounded_float(calibration.focal_k2 * delta_squared));
  // L_FLact is the firmware's focus-dependent actual focal length.  The
  // separate F*(1+M)+P*M composite belongs to IBIS StartCorr opt_param and
  // includes finite-object translation geometry; it is not the image-plane
  // projection focal length published to the solver.
  const uint32_t bits = float_to_bits(flact);
  if (!finite_float_word(bits) || flact < 0.1f || flact > 2047.96875f ||
      flact < static_cast<float>(identity.wide_mm) * 0.5f ||
      flact > static_cast<float>(identity.tele_mm) * 2.0f) {
    return false;
  }
  if (focal_bits != nullptr) *focal_bits = bits;
  return true;
}

bool direct_lens_snapshots_equal(const DevMemLensSnapshot &left,
                                 const DevMemLensSnapshot &right) {
  if (left.name_length != right.name_length ||
      left.wide_mm != right.wide_mm || left.tele_mm != right.tele_mm ||
      left.zoom_index != right.zoom_index ||
      left.focus_pulse != right.focus_pulse ||
      left.autofocus_reference != right.autofocus_reference ||
      left.private_status != right.private_status ||
      left.infinity_reference != right.infinity_reference ||
      left.macro_is != right.macro_is) {
    return false;
  }
  for (unsigned i = 0; i < sizeof(left.id); ++i) {
    if (left.id[i] != right.id[i]) return false;
  }
  for (uint8_t i = 0; i < left.name_length; ++i) {
    if (left.name[i] != right.name[i]) return false;
  }
  for (unsigned i = 0;
       i < sizeof(left.calibration_f32_bits) /
               sizeof(left.calibration_f32_bits[0]);
       ++i) {
    if (left.calibration_f32_bits[i] !=
        right.calibration_f32_bits[i]) {
      return false;
    }
  }
  return true;
}

uint64_t get_le64(const uint8_t *input) {
  uint64_t result = 0;
  for (unsigned i = 0; i < 8; ++i) {
    result |= static_cast<uint64_t>(input[i]) << (i * 8u);
  }
  return result;
}

uint32_t get_le32(const uint8_t *input) {
  uint32_t result = 0;
  for (unsigned i = 0; i < 4; ++i) {
    result |= static_cast<uint32_t>(input[i]) << (i * 8u);
  }
  return result;
}

}  // namespace

void CameraController::initialize() {
  devmem_state_.initialize();
  ptp_.initialize();
  transfer_input_.initialize();
  memory_fd_ = -1;
  anchor_page_ = nullptr;
  mode_page_ = nullptr;
  gate_page_ = nullptr;
  astro_preposition_page_ = nullptr;
  astro_preposition_owned_ = false;
  gate_original_ = UINT16_MAX;
  gate_changed_ = false;
  capture_allocated_ = false;
  shell_ready_ = false;
  picture_transfer_cleanup_unverified_ = false;
  shutter_release_pending_ = false;
  shutter_recovery_required_ = false;
  shutter_settings_restore_blocked_ = false;
  persistent_sdk_output_pending_ = false;
  persistent_sdk_expected_prompts_ = 0;
  shell_resync_required_ = false;
  direct_shell_capture_qualified_ = false;
  direct_shell_capture_recovery_pending_ = false;
  direct_shell_capture_cursor_ = 0;
  direct_shell_capture_firmware_read_checkpoint_ = 0;
  direct_shell_capture_checkpoint_bytes_ = 0;
  shell_resync_generation_ = 0;
  persistent_shell_sdk_ready_ = false;
  settings_applied_ = false;
  persistent_armed_ = false;
  persistent_settings_snapshot_valid_ = false;
  setting_catalog_evidence_valid_ = false;
  setting_catalog_mutation_grant_valid_ = false;
  setting_write_attempted_ = false;
  setting_prewrite_rejection_safe_ = false;
  post_capture_lens_window_ = false;
  deferred_controller_proof_pending_ = false;
  settings_set_batch_performed_ = false;
  direct_transfer_records_qualified_ = false;
  persistent_transfer_slot_hint_ = 0;
  saved_mask_ = 0;
  changed_mask_ = 0;
  for (unsigned i = 0; i < 3; ++i) {
    original_settings_[i] = 0;
    capture_targets_[i] = kDefaultCaptureTargets[i];
    persistent_settings_snapshot_[i] = 0;
    setting_catalog_validated_current_[i] = 0;
    selected_setting_controller_ids_[i] =
        kDefaultSettingControllerIds[i];
  }
  direct_catalog_snapshot_scratch_ = {};
  direct_catalog_result_scratch_ = {};
  direct_catalog_snapshot_certificate_ = {};
  direct_catalog_result_certificate_ = {};
  direct_catalog_public_context_ = {};
  direct_catalog_public_context_valid_ = false;
  for (unsigned slot = 0; slot < 2; ++slot) {
    transfer_before_[slot].count = 0;
    transfer_after_[slot].count = 0;
    transfer_verify_[slot].count = 0;
    transfer_counts_.occupied[slot] = 0;
  }
  shell_output_size_ = 0;
  shell_output_[0] = '\0';
  direct_shell_shadow_[0] = '\0';
}

bool CameraController::open(const uint8_t guid_tail[6], Error *error) {
  clear_error(error);
  if (ptp_.connected() || memory_fd_ >= 0 || devmem_state_.opened() ||
      capture_allocated_ || persistent_shell_sdk_ready_) {
    return fail(error, "camera controller is already open");
  }
  if (changed_mask_ != 0 || gate_changed_ ||
      picture_transfer_cleanup_unverified_ || shutter_release_pending_ ||
      shutter_recovery_required_ || shutter_settings_restore_blocked_ ||
      persistent_sdk_output_pending_ || shell_resync_required_) {
    return fail(error, "previous camera state remains unrestored");
  }
  if (!ptp_.connect_loopback(guid_tail, kPtpTimeoutMs, error) ||
      !map_and_open_gate(error) || !devmem_state_.open_read_only(error) ||
      !allocate_and_prime_shell(error)) {
    const Error primary = error == nullptr ? Error{} : *error;
    Error ignored = {};
    (void)close(&ignored);
    if (error != nullptr) *error = primary;
    return false;
  }
  shell_ready_ = true;
  // A predecessor can exit after a completed 94DE has restored shutter and
  // exposure state but before the stock picture-transfer cleanup timer returns
  // pic_trans_state from 1 to 0.  Fresh startup has no mutation journal to
  // replay, so wait only through that exact read-only transient instead of
  // forcing supervisor relaunch churn.  Any other station tuple still fails
  // immediately inside wait_for_station_baseline().
  if (!wait_for_station_baseline(kStationReadyTimeoutMs, error)) {
    const Error primary = error == nullptr ? Error{} : *error;
    Error ignored = {};
    (void)close(&ignored);
    if (error != nullptr) *error = primary;
    return false;
  }
  return true;
}

bool CameraController::map_and_open_gate(Error *error) {
  const int flags = os::kOpenReadWrite | os::kOpenSync | os::kOpenCloseOnExec;
  const long descriptor = os::open("/dev/mem", flags);
  if (os::failed(descriptor)) return fail(error, "opening /dev/mem failed");
  memory_fd_ = static_cast<int>(descriptor);

  auto map_page = [&](uint64_t physical, int protection,
                      void **mapping) -> bool {
    const uint64_t page = physical / kPageSize;
    if (page > UINT32_MAX) return fail(error, "physical page is out of range");
    const long address = os::mmap2(nullptr, kPageSize, protection,
                                   os::kMapShared, memory_fd_,
                                   static_cast<uint32_t>(page));
    if (os::failed(address)) return fail(error, "mapping /dev/mem failed");
    *mapping = reinterpret_cast<void *>(static_cast<uintptr_t>(address));
    return true;
  };

  if (!map_page(kWifiAnchorPhysical, os::kProtectionRead, &anchor_page_) ||
      !map_page(kConnectModePhysical, os::kProtectionRead, &mode_page_) ||
      !map_page(kInnerGatePhysical,
                os::kProtectionRead | os::kProtectionWrite, &gate_page_)) {
    return false;
  }

  const auto *anchor = reinterpret_cast<volatile const uint32_t *>(
      static_cast<const uint8_t *>(anchor_page_) +
      (kWifiAnchorPhysical & (kPageSize - 1u)));
  const auto *mode = reinterpret_cast<volatile const uint16_t *>(
      static_cast<const uint8_t *>(mode_page_) +
      (kConnectModePhysical & (kPageSize - 1u)));
  auto *gate = reinterpret_cast<volatile uint16_t *>(
      static_cast<uint8_t *>(gate_page_) +
      (kInnerGatePhysical & (kPageSize - 1u)));

  if (*anchor != kWifiAnchorValue) {
    return fail(error, "OM-1 v1.9 WIFI memory anchor mismatch");
  }
  if (*mode != kRequiredConnectMode) {
    return fail(error, "PTP inner gate requires camera connect mode 9");
  }
  // Qualify the existing firmware anchors before mapping the separate
  // preposition coordinator. Opening an idle worker never changes its state.
  if (!map_page(ASTRO_PREPOSITION_PHYSICAL,
                os::kProtectionRead | os::kProtectionWrite,
                &astro_preposition_page_)) {
    return false;
  }
  gate_original_ = *gate;
  if (gate_original_ != 0 && gate_original_ != 1) {
    return fail(error, "unexpected PTP inner gate value");
  }
  if (gate_original_ == 0) {
    // From this point onward the write may have reached the device even if a
    // later read or protocol step fails, so cleanup must compare-and-restore.
    gate_changed_ = true;
    *gate = 1;
    memory_barrier();
    if (*gate != 1) return fail(error, "PTP inner gate write did not stick");
  }
  return true;
}

bool CameraController::restore_and_unmap_gate(Error *error) {
  clear_error(error);
  bool all_ok = true;
  Error first = {};

  if (gate_changed_ && gate_page_ == nullptr) {
    Error local = {};
    (void)fail(&local, "cannot restore PTP inner gate without its mapping");
    retain_first_error(false, local, &all_ok, &first);
  }
  if (gate_page_ != nullptr && gate_changed_) {
    auto *gate = reinterpret_cast<volatile uint16_t *>(
        static_cast<uint8_t *>(gate_page_) +
        (kInnerGatePhysical & (kPageSize - 1u)));
    Error local = {};
    const uint16_t current = *gate;
    if (current == gate_original_) {
      // The attempted write may have failed before changing the halfword.
      gate_changed_ = false;
    } else if (current != 1) {
      (void)fail(&local, "PTP inner gate changed externally; not restoring");
      retain_first_error(false, local, &all_ok, &first);
    } else {
      *gate = gate_original_;
      memory_barrier();
      if (*gate != gate_original_) {
        (void)fail(&local, "PTP inner gate restore did not stick");
        retain_first_error(false, local, &all_ok, &first);
      } else {
        gate_changed_ = false;
      }
    }
  }

  void **mappings[4] = {&astro_preposition_page_, &gate_page_, &mode_page_,
                       &anchor_page_};
  for (void **mapping : mappings) {
    if (*mapping != nullptr) {
      Error local = {};
      const bool unmapped = !os::failed(os::munmap(*mapping, kPageSize));
      if (!unmapped) (void)fail(&local, "unmapping /dev/mem failed");
      retain_first_error(unmapped, local, &all_ok, &first);
      *mapping = nullptr;
    }
  }
  if (memory_fd_ >= 0) {
    Error local = {};
    const bool closed = !os::failed(os::close(memory_fd_));
    if (!closed) (void)fail(&local, "closing /dev/mem failed");
    retain_first_error(closed, local, &all_ok, &first);
    memory_fd_ = -1;
  }
  if (!all_ok && error != nullptr) *error = first;
  return all_ok;
}

bool CameraController::post_shell(const uint8_t *command, size_t command_size,
                                  uint32_t flags, Error *error) {
  const uint32_t parameters[2] = {15, flags};
  PtpOperationResult result = {};
  if (!ptp_.operation(2, kOperationShellExecute, parameters, 2, command,
                      command_size, nullptr, 0, kPtpTimeoutMs, &result,
                      error)) {
    return false;
  }
  return response_ok(result, "PTP U-shell execute", error);
}

bool CameraController::read_shell_capture_ptp(bool keep, uint32_t timeout_ms,
                                              Error *error) {
  if (shell_output_size_ >= sizeof(shell_output_)) {
    return fail(error, "U-shell output buffer is full");
  }
  const uint32_t parameters[2] = {47, keep ? 1u : 0u};
  PtpOperationResult result = {};
  uint8_t *destination =
      reinterpret_cast<uint8_t *>(shell_output_ + shell_output_size_);
  const size_t capacity = sizeof(shell_output_) - shell_output_size_;
  if (!ptp_.operation(1, kOperationShellRead, parameters, 2, nullptr, 0,
                      destination, capacity, timeout_ms, &result, error)) {
    return false;
  }
  if (!response_ok(result, "PTP U-shell read", error)) return false;
  if (result.data_size == 0 || destination[result.data_size - 1u] != 0) {
    return fail(error, "U-shell capture lacked its terminating NUL");
  }
  shell_output_size_ += result.data_size - 1u;
  shell_output_[shell_output_size_] = '\0';
  if (!keep) {
    capture_allocated_ = false;
    demote_direct_shell_capture(false);
  }
  return true;
}

void CameraController::demote_direct_shell_capture(bool recovery_pending) {
  direct_shell_capture_qualified_ = false;
  direct_shell_capture_recovery_pending_ = recovery_pending;
  direct_shell_capture_cursor_ = 0;
  direct_shell_capture_firmware_read_checkpoint_ = 0;
  direct_shell_capture_checkpoint_bytes_ = 0;
  Error ignored = {};
  (void)devmem_state_.unmap_shell_capture(&ignored);
}

bool CameraController::read_direct_shell_capture(
    uint8_t *destination, size_t capacity, size_t *size, uint16_t *cursor,
    uint16_t firmware_read_checkpoint, Error *error) {
  if (destination == nullptr || size == nullptr || cursor == nullptr) {
    return fail(error, "missing direct U-shell capture destination");
  }
  *size = 0;
  // A printer can publish another complete chunk while Linux copies the
  // preceding one.  Retry that typed race immediately; if output remains
  // continuously active, report no bytes and let the caller's paced deadline
  // loop try again.  Structural changes demote and require a stock FIFO fence.
  for (unsigned attempt = 0; attempt < 4; ++attempt) {
    size_t copied = 0;
    uint16_t next = *cursor;
    const DevMemShellCaptureReadStatus status =
        devmem_state_.read_shell_capture(
            *cursor, firmware_read_checkpoint, destination, capacity, &copied,
            &next, error);
    if (status == DevMemShellCaptureReadStatus::kChangedDuringRead) continue;
    if (status == DevMemShellCaptureReadStatus::kError) return false;
    if (copied > kDirectShellCheckpointByteLimit -
                     direct_shell_capture_checkpoint_bytes_) {
      return fail(error, "direct U-shell checkpoint byte budget exceeded");
    }
    direct_shell_capture_checkpoint_bytes_ += static_cast<uint32_t>(copied);
    *size = copied;
    *cursor = next;
    return true;
  }
  clear_error(error);
  return true;
}

bool CameraController::read_shell_capture(bool keep, uint32_t timeout_ms,
                                          Error *error) {
  if (!keep || !direct_shell_capture_qualified_) {
    return read_shell_capture_ptp(keep, timeout_ms, error);
  }
  if (shell_output_size_ >= sizeof(shell_output_)) {
    return fail(error, "U-shell output buffer is full");
  }
  const size_t capacity =
      sizeof(shell_output_) - 1u - shell_output_size_;
  size_t copied = 0;
  if (!read_direct_shell_capture(
          reinterpret_cast<uint8_t *>(shell_output_ + shell_output_size_),
          capacity, &copied, &direct_shell_capture_cursor_,
          direct_shell_capture_firmware_read_checkpoint_, error)) {
    demote_direct_shell_capture(true);
    shell_resync_required_ = true;
    return false;
  }
  shell_output_size_ += copied;
  shell_output_[shell_output_size_] = '\0';
  return true;
}

bool CameraController::wait_for_prompt(uint32_t timeout_ms, Error *error) {
  uint64_t deadline = 0;
  if (!deadline_after(timeout_ms, &deadline, error)) return false;
  for (;;) {
    if (contains_bytes(shell_output_, shell_output_size_, "\nUSR> ")) {
      return true;
    }
    uint32_t remaining = 0;
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for U-shell prompt");
    }
    // The capture read is safe when the command has not produced output yet:
    // firmware returns a single terminating NUL and leaves the allocation
    // active.  Poll once immediately, then back off only after a nonterminal
    // response instead of imposing 100 ms on every successful command.
    if (!read_shell_capture(true, remaining, error)) return false;
    if (contains_bytes(shell_output_, shell_output_size_, "\nUSR> ")) {
      return true;
    }
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for U-shell prompt");
    }
    const uint32_t poll_ms = direct_shell_capture_qualified_
                                 ? kDirectShellPollMs
                                 : kShellPollMs;
    uint32_t delay = remaining < poll_ms ? remaining : poll_ms;
    if (delay == 0) delay = 1;
    if (os::failed(os::nanosleep_ms(delay))) {
      return fail(error, "U-shell poll sleep failed");
    }
  }
}

bool CameraController::drain_persistent_sdk_output(uint32_t timeout_ms,
                                                   Error *error) {
  if (!persistent_sdk_output_pending_) return true;
  uint64_t deadline = 0;
  if (!deadline_after(timeout_ms, &deadline, error)) return false;
  for (;;) {
    // The batch starts with a locally and remotely drained capture.  Exactly
    // one prompt per attempted single-shot command fences the entire batch.
    // A preposition abort before fullpress has only first-stage-on/off; an
    // ordinary capture still requires all four distinct commands and prompts.
    const size_t prompts =
        count_bytes(shell_output_, shell_output_size_, "\nUSR> ");
    if (prompts > persistent_sdk_expected_prompts_) {
      shell_resync_required_ = true;
      return fail(error, "persistent SDK-event output had extra prompts");
    }
    if (prompts == persistent_sdk_expected_prompts_) {
      persistent_sdk_output_pending_ = false;
      shell_resync_required_ = false;
      return true;
    }
    uint32_t remaining = 0;
    if (!deadline_remaining(deadline, &remaining, error)) {
      shell_resync_required_ = true;
      return fail(error, "timed out draining persistent SDK-event output");
    }
    if (!read_shell_capture(true, remaining, error)) {
      shell_resync_required_ = true;
      return false;
    }
    const size_t updated_prompts =
        count_bytes(shell_output_, shell_output_size_, "\nUSR> ");
    if (updated_prompts > persistent_sdk_expected_prompts_) {
      shell_resync_required_ = true;
      return fail(error, "persistent SDK-event output had extra prompts");
    }
    if (updated_prompts == persistent_sdk_expected_prompts_) {
      persistent_sdk_output_pending_ = false;
      shell_resync_required_ = false;
      return true;
    }
    if (!deadline_remaining(deadline, &remaining, error)) {
      shell_resync_required_ = true;
      return fail(error, "timed out draining persistent SDK-event output");
    }
    const uint32_t poll_ms = direct_shell_capture_qualified_
                                 ? kDirectShellPollMs
                                 : kShellPollMs;
    const uint32_t delay = remaining < poll_ms ? remaining : poll_ms;
    if (os::failed(os::nanosleep_ms(delay == 0 ? 1 : delay))) {
      shell_resync_required_ = true;
      return fail(error, "persistent SDK-event drain sleep failed");
    }
  }
}

bool CameraController::wait_for_shell_barrier_ptp(const char *token,
                                                  uint64_t deadline,
                                                  Error *error) {
  for (;;) {
    if (detail::setting_batch_barrier_complete(
            shell_output_, shell_output_size_, token)) {
      return true;
    }
    uint32_t remaining = 0;
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for stock U-shell barrier");
    }
    if (!read_shell_capture_ptp(true, remaining, error)) return false;
    if (detail::setting_batch_barrier_complete(
            shell_output_, shell_output_size_, token)) {
      return true;
    }
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for stock U-shell barrier");
    }
    const uint32_t delay =
        remaining < kShellPollMs ? remaining : kShellPollMs;
    if (os::failed(os::nanosleep_ms(delay == 0 ? 1 : delay))) {
      return fail(error, "stock U-shell barrier sleep failed");
    }
  }
}

bool CameraController::resynchronize_shell(Error *error) {
  if (!shell_resync_required_) return true;
  if (!shell_ready_) {
    return fail(error, "cannot resynchronize a closed camera U-shell");
  }
  if (persistent_sdk_output_pending_ &&
      !direct_shell_capture_recovery_pending_) {
    if (drain_persistent_sdk_output(kPersistentSdkDrainTimeoutMs, error)) {
      if (!shell_resync_required_) return true;
    } else {
      // Whether the failed drain was a direct-memory demotion, a short prompt
      // batch, or an ambiguous PTP read, the fresh unique barrier below is the
      // only allowed recovery.  If the command socket is not synchronized it
      // will fail closed rather than allowing another camera command.
      clear_error(error);
    }
  }

  ++shell_resync_generation_;
  if (shell_resync_generation_ == 0) ++shell_resync_generation_;
  char token[40];
  TextBuffer token_text(token, sizeof(token));
  token_text.append("STAR_BATCH_SYNC_");
  token_text.append_hex(shell_resync_generation_, 8);
  char command[48];
  TextBuffer command_text(command, sizeof(command));
  command_text.append("echo ");
  command_text.append(token);
  if (!token_text.ok() || !command_text.ok()) {
    return fail(error, "could not construct U-shell synchronization barrier");
  }

  // A fresh bare echo-handler line followed by its prompt is a FIFO fence.
  // The execute handler's synchronous `echo TOKEN` line is deliberately not
  // sufficient: 902B success does not expose message-buffer send failure.
  shell_output_size_ = 0;
  shell_output_[0] = '\0';
  if (!post_shell(reinterpret_cast<const uint8_t *>(command),
                  command_text.size(), 4, error)) {
    return false;
  }

  uint64_t deadline = 0;
  if (!deadline_after(kShellTimeoutMs, &deadline, error)) return false;
  for (;;) {
    if (detail::setting_batch_barrier_complete(
            shell_output_, shell_output_size_, token)) {
      if (persistent_sdk_output_pending_) {
        // The barrier was enqueued after every outstanding SDK-event post, so
        // it is a stronger completion fence than their individual prompts
        // when direct-ring transport itself has failed.
        persistent_sdk_output_pending_ = false;
      }
      direct_shell_capture_recovery_pending_ = false;
      shell_resync_required_ = false;
      return true;
    }
    uint32_t remaining = 0;
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out resynchronizing camera U-shell");
    }
    if (!read_shell_capture(true, remaining, error)) {
      if (!direct_shell_capture_recovery_pending_) return false;
      // Firmware's 0x902A read cursor still points at the last shadow/checkpoint
      // read.  Discard the duplicated backlog locally, then require this new
      // unique terminal FIFO barrier through the stock consumer before any
      // recovery command may run.
      shell_output_size_ = 0;
      shell_output_[0] = '\0';
      clear_error(error);
      if (!wait_for_shell_barrier_ptp(token, deadline, error)) return false;
    }
    if (detail::setting_batch_barrier_complete(
            shell_output_, shell_output_size_, token)) {
      if (persistent_sdk_output_pending_) {
        persistent_sdk_output_pending_ = false;
      }
      direct_shell_capture_recovery_pending_ = false;
      shell_resync_required_ = false;
      return true;
    }
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out resynchronizing camera U-shell");
    }
    const uint32_t poll_ms = direct_shell_capture_qualified_
                                 ? kDirectShellPollMs
                                 : kShellPollMs;
    const uint32_t delay = remaining < poll_ms ? remaining : poll_ms;
    if (os::failed(os::nanosleep_ms(delay == 0 ? 1 : delay))) {
      return fail(error, "U-shell synchronization sleep failed");
    }
  }
}

bool CameraController::qualify_direct_shell_capture(
    bool checkpoint_generation, Error *error) {
  const bool was_qualified = direct_shell_capture_qualified_;
  const uint16_t previous_cursor = direct_shell_capture_cursor_;
  const uint16_t previous_firmware_read =
      direct_shell_capture_firmware_read_checkpoint_;
  const uint32_t previous_checkpoint_bytes =
      direct_shell_capture_checkpoint_bytes_;
  demote_direct_shell_capture(false);
  auto checkpoint_stock_fallback = [&]() -> bool {
    if (!checkpoint_generation || !was_qualified) return true;
    shell_output_size_ = 0;
    shell_output_[0] = '\0';
    if (!read_shell_capture_ptp(true, kPtpTimeoutMs, error)) {
      shell_resync_required_ = true;
      return false;
    }
    shell_output_size_ = 0;
    shell_output_[0] = '\0';
    return true;
  };
  if (!devmem_state_.shell_capture_state_available()) {
    return checkpoint_stock_fallback();
  }

  DevMemShellCaptureState initial = {};
  Error optional = {};
  if (!devmem_state_.prepare_shell_capture(&initial, &optional) ||
      initial.pointer == 0) {
    demote_direct_shell_capture(false);
    return checkpoint_stock_fallback();
  }
  const bool empty_checkpoint = initial.read == initial.write;
  const bool checkpoint_backlog =
      checkpoint_generation && was_qualified &&
      previous_checkpoint_bytes <= kDirectShellCheckpointByteLimit &&
      initial.read == previous_firmware_read &&
      initial.write == previous_cursor;
  if (!empty_checkpoint && !checkpoint_backlog) {
    // Do not leave stale stock backlog ahead of the next fallback command.
    // No new command has been posted, so one retained 902A is a sufficient
    // consumer checkpoint; failure is authoritative and cannot be optional.
    shell_output_size_ = 0;
    shell_output_[0] = '\0';
    if (!read_shell_capture_ptp(true, kPtpTimeoutMs, error)) {
      shell_resync_required_ = true;
      demote_direct_shell_capture(false);
      return false;
    }
    shell_output_size_ = 0;
    shell_output_[0] = '\0';
    demote_direct_shell_capture(false);
    return true;
  }

  ++shell_resync_generation_;
  if (shell_resync_generation_ == 0) ++shell_resync_generation_;
  char token[40];
  TextBuffer token_text(token, sizeof(token));
  token_text.append("STAR_DIRECT_SHELL_");
  token_text.append_hex(shell_resync_generation_, 8);
  char command[48];
  TextBuffer command_text(command, sizeof(command));
  command_text.append("echo ");
  command_text.append(token);
  if (!token_text.ok() || !command_text.ok()) {
    const bool checkpointed = checkpoint_stock_fallback();
    demote_direct_shell_capture(false);
    if (!checkpointed) return false;
    return fail(error, "could not construct direct U-shell shadow barrier");
  }

  // Once posted, this harmless command must be drained through a terminal
  // bare-token prompt even if the optional mapping proves unusable.  A 902B
  // success alone cannot prove the asynchronous MBF handler ran.
  shell_resync_required_ = true;
  if (!post_shell(reinterpret_cast<const uint8_t *>(command),
                  command_text.size(), 4, error)) {
    demote_direct_shell_capture(false);
    return false;
  }

  uint16_t cursor = initial.write;
  size_t shadow_size = 0;
  direct_shell_shadow_[0] = '\0';
  bool direct_complete = false;
  uint64_t direct_deadline = 0;
  if (!deadline_after(kDirectShellQualificationTimeoutMs, &direct_deadline,
                      &optional)) {
    demote_direct_shell_capture(false);
  } else {
    for (;;) {
      if (detail::setting_batch_barrier_complete(
              direct_shell_shadow_, shadow_size, token)) {
        direct_complete = true;
        break;
      }
      uint32_t remaining = 0;
      if (!deadline_remaining(direct_deadline, &remaining, &optional)) break;
      size_t copied = 0;
      if (!read_direct_shell_capture(
              reinterpret_cast<uint8_t *>(direct_shell_shadow_ + shadow_size),
              kDevMemShellCaptureCapacity - shadow_size, &copied, &cursor,
              initial.read, &optional)) {
        break;
      }
      shadow_size += copied;
      direct_shell_shadow_[shadow_size] = '\0';
      if (detail::setting_batch_barrier_complete(
              direct_shell_shadow_, shadow_size, token)) {
        direct_complete = true;
        break;
      }
      if (os::failed(os::nanosleep_ms(kDirectShellPollMs))) break;
    }
  }

  // The stock retained read is both the authority and a firmware-consumer
  // checkpoint.  It runs even when the candidate failed so this optional
  // qualification never leaves an undrained command behind.
  shell_output_size_ = 0;
  shell_output_[0] = '\0';
  uint64_t stock_deadline = 0;
  if (!deadline_after(kShellTimeoutMs, &stock_deadline, error) ||
      !wait_for_shell_barrier_ptp(token, stock_deadline, error)) {
    demote_direct_shell_capture(false);
    return false;
  }

  const size_t stock_offset =
      checkpoint_backlog && shell_output_size_ >= shadow_size
          ? shell_output_size_ - shadow_size
          : 0;
  bool bytes_match =
      direct_complete && shell_output_size_ >= shadow_size &&
      ((checkpoint_backlog &&
        stock_offset == previous_checkpoint_bytes) ||
       (!checkpoint_backlog && shadow_size == shell_output_size_));
  for (size_t i = 0; bytes_match && i < shadow_size; ++i) {
    if (direct_shell_shadow_[i] != shell_output_[stock_offset + i]) {
      bytes_match = false;
    }
  }
  DevMemShellCaptureState verified = {};
  optional = {};
  const bool state_read =
      devmem_state_.read_shell_capture_state(&verified, &optional);
  const bool state_matches = state_read &&
      verified.pointer == initial.pointer && verified.read == verified.write &&
      verified.write == cursor;
  if (!state_matches) {
    if (!state_read || verified.pointer != initial.pointer) {
      demote_direct_shell_capture(false);
      return fail(error,
                  "direct U-shell state changed after stock shadow barrier");
    }
    // The unique token already fenced Tsk_USHL, but a global diagnostic may
    // have appended after the authoritative 902A response.  Consume that tail
    // once through stock and require an exact empty/stable state before a soft
    // demotion can allow later stock commands.
    shell_output_size_ = 0;
    shell_output_[0] = '\0';
    if (!read_shell_capture_ptp(true, kPtpTimeoutMs, error)) {
      demote_direct_shell_capture(false);
      return false;
    }
    DevMemShellCaptureState drained = {};
    optional = {};
    if (!devmem_state_.read_shell_capture_state(&drained, &optional) ||
        drained.pointer != initial.pointer || drained.read != drained.write) {
      demote_direct_shell_capture(false);
      return fail(error,
                  "direct U-shell state did not drain after stock barrier");
    }
    demote_direct_shell_capture(false);
  } else if (!bytes_match) {
    demote_direct_shell_capture(false);
  } else {
    direct_shell_capture_cursor_ = verified.write;
    direct_shell_capture_firmware_read_checkpoint_ = verified.read;
    direct_shell_capture_checkpoint_bytes_ = 0;
    direct_shell_capture_recovery_pending_ = false;
    direct_shell_capture_qualified_ = true;
  }
  // The authoritative stock terminal barrier makes either outcome safe: an
  // exact shadow promotes; a healthy mismatch simply keeps stock 902A active.
  shell_resync_required_ = false;
  shell_output_size_ = 0;
  shell_output_[0] = '\0';
  direct_shell_shadow_[0] = '\0';
  return true;
}

bool CameraController::allocate_and_prime_shell(Error *error) {
  static constexpr uint8_t kNul = 0;
  // Treat the allocation as owned before sending: a lost response does not
  // prove that the camera failed to execute the request.
  capture_allocated_ = true;
  if (!post_shell(&kNul, 1, 1, error)) return false;
  shell_output_size_ = 0;
  shell_output_[0] = '\0';
  if (!read_shell_capture(true, kPtpTimeoutMs, error)) return false;
  // From the successful retained read onward the allocation is usable for a
  // FIFO recovery barrier even though open() has not finished its remaining
  // qualification.  This makes an ambiguous priming execute safely closable.
  shell_ready_ = true;
  shell_output_size_ = 0;
  shell_output_[0] = '\0';
  shell_resync_required_ = true;
  if (!post_shell(&kNul, 1, 4, error) ||
      !wait_for_prompt(kShellTimeoutMs, error)) {
    return false;
  }
  shell_resync_required_ = false;
  shell_output_size_ = 0;
  shell_output_[0] = '\0';
  return qualify_direct_shell_capture(false, error);
}

bool CameraController::shell_command(const char *command, Error *error) {
  if (!shell_ready_) return fail(error, "camera U-shell is not open");
  if (shell_resync_required_ && !resynchronize_shell(error)) return false;
  // Never let a generic command mistake one of the four asynchronous shutter
  // prompts for its own completion.
  if (persistent_sdk_output_pending_ &&
      !drain_persistent_sdk_output(kPersistentSdkDrainTimeoutMs, error)) {
    return false;
  }
  const size_t length = string_length(command);
  if (length == 0 || length > 255) {
    return fail(error, "U-shell command must contain 1..255 bytes");
  }
  for (size_t i = 0; i < length; ++i) {
    if (command[i] == '\0') return fail(error, "U-shell command contains NUL");
  }
  shell_output_size_ = 0;
  shell_output_[0] = '\0';
  // A lost execute response or prompt cannot prove that the asynchronous
  // command was absent.  Keep a sticky FIFO obligation until its terminal
  // prompt is observed; the next command must otherwise use a unique barrier.
  shell_resync_required_ = true;
  if (!post_shell(reinterpret_cast<const uint8_t *>(command), length, 4,
                  error)) {
    return false;
  }
  if (!wait_for_prompt(kShellTimeoutMs, error)) return false;
  shell_resync_required_ = false;
  return true;
}

bool CameraController::keep_awake(bool *recovered, Error *error) {
  clear_error(error);
  if (recovered == nullptr) {
    return fail(error, "keep-awake result storage is missing");
  }
  *recovered = false;
  if (!shell_ready_ || !persistent_shell_sdk_ready_ || !persistent_armed_ ||
      !persistent_settings_snapshot_valid_ || settings_applied_ ||
      saved_mask_ != 0 || changed_mask_ != 0 ||
      deferred_controller_proof_pending_ || transfer_input_.active() ||
      picture_transfer_cleanup_unverified_ || shutter_release_pending_ ||
      shutter_recovery_required_ || shutter_settings_restore_blocked_ ||
      persistent_sdk_output_pending_) {
    return fail(error, "camera is not at a keep-awake-safe idle boundary");
  }

  // This is deliberately a closed command surface. A lost response or prompt
  // cannot prove whether the conditional timer rearm committed, so never
  // replay it.
  Error command_error = {};
  if (shell_command(kKeepAwakeCommand, &command_error)) return true;

  // Establish one later FIFO boundary. Success makes the ambiguity harmless:
  // the conditional rearm either committed or did not, and the next lease
  // renewal can issue a fresh attempt. Failure leaves the U-shell unusable and
  // is terminal.
  Error recovery_error = {};
  if (!resynchronize_shell(&recovery_error)) {
    if (error != nullptr) {
      TextBuffer combined(error->text, sizeof(error->text));
      combined.append("keep-awake command failed: ");
      combined.append(command_error.text);
      combined.append("; U-shell recovery failed: ");
      combined.append(recovery_error.text);
    }
    return false;
  }

  *recovered = true;
  if (error != nullptr) *error = command_error;
  return true;
}

bool CameraController::read_station_state(uint32_t *station,
                                          uint32_t *registration,
                                          uint32_t *priority,
                                          uint32_t *connection,
                                          uint32_t *picture_transfer,
                                          Error *error) {
  if (station == nullptr || registration == nullptr || priority == nullptr ||
      connection == nullptr || picture_transfer == nullptr) {
    return fail(error, "missing station-mode state output");
  }
  // Validation build: retain the stock diagnostic as the decision source and
  // bracket it with read-only Linux snapshots.  A transition may occur while
  // the asynchronous shell command runs, so its tuple must equal at least one
  // endpoint.  Direct-only use is forbidden until this pairing is proven on
  // the real camera.
  DevMemStationState direct_before = {};
  if (!devmem_state_.read_station(&direct_before, error)) return false;
  if (!shell_command("tpc stamode info", error)) return false;
  uint32_t parsed_station = 0;
  uint32_t parsed_registration = 0;
  uint32_t parsed_priority = 0;
  uint32_t parsed_connection = 0;
  uint32_t parsed_picture_transfer = 0;
  if (!detail::parse_exact_prefixed_zero_padded_u32(
          shell_output_, shell_output_size_, "--> sta_mode_state   = ", 1,
          &parsed_station) ||
      !detail::parse_exact_prefixed_zero_padded_u32(
          shell_output_, shell_output_size_, "--> regist_state     = ", 1,
          &parsed_registration) ||
      !detail::parse_exact_prefixed_zero_padded_u32(
          shell_output_, shell_output_size_, "--> prioritize_state = ", 3,
          &parsed_priority) ||
      !detail::parse_exact_prefixed_zero_padded_u32(
          shell_output_, shell_output_size_, "--> connect_pc_state = ", 1,
          &parsed_connection) ||
      !detail::parse_exact_prefixed_zero_padded_u32(
          shell_output_, shell_output_size_, "--> pic_trans_state  = ", 1,
          &parsed_picture_transfer)) {
    return fail(error, "tpc stamode info returned malformed state");
  }
  const DevMemStationState parsed = {
      static_cast<uint16_t>(parsed_station),
      static_cast<uint16_t>(parsed_registration),
      static_cast<uint16_t>(parsed_priority),
      static_cast<uint16_t>(parsed_connection),
      static_cast<uint16_t>(parsed_picture_transfer),
  };
  DevMemStationState direct_after = {};
  if (!devmem_state_.read_station(&direct_after, error)) return false;
  if (!devmem_station_equal(parsed, direct_before) &&
      !devmem_station_equal(parsed, direct_after)) {
    return fail_station_shadow_mismatch(error, direct_before, parsed,
                                        direct_after);
  }
  *station = parsed_station;
  *registration = parsed_registration;
  *priority = parsed_priority;
  *connection = parsed_connection;
  *picture_transfer = parsed_picture_transfer;
  return true;
}

bool CameraController::require_station_baseline(Error *error) {
  uint32_t station = 0;
  uint32_t registration = 0;
  uint32_t priority = 0;
  uint32_t connection = 0;
  uint32_t picture_transfer = 0;
  if (!read_station_state(&station, &registration, &priority, &connection,
                          &picture_transfer, error)) {
    return false;
  }
  if (station != 1 || registration != 1 || priority != 1 || connection != 0 ||
      picture_transfer != 0) {
    return fail(error, "camera is not at the no-PC station baseline");
  }
  return true;
}

bool CameraController::wait_for_station_baseline(uint32_t timeout_ms,
                                                 Error *error) {
  uint64_t deadline = 0;
  if (!deadline_after(timeout_ms, &deadline, error)) return false;
  for (;;) {
    DevMemStationState direct = {};
    if (!devmem_state_.read_station(&direct, error)) return false;
    const bool direct_baseline =
        direct.station == 1 && direct.registration == 1 &&
        direct.priority == 1 && direct.connection == 0 &&
        direct.picture_transfer == 0;
    const bool direct_pending =
        direct.station == 1 && direct.registration == 1 &&
        direct.priority == 1 && direct.connection == 0 &&
        direct.picture_transfer == 1;

    // The direct tuple never authorizes an idle generation or diagnoses
    // drift. It merely avoids repeated 902B/902A station transactions while
    // the known pic=1
    // timer is running.  Candidate pic=0 (or any unexpected direct tuple)
    // triggers the retained, bracketed U-shell authority exactly as before.
    if (direct_baseline || !direct_pending) {
      uint32_t station = 0;
      uint32_t registration = 0;
      uint32_t priority = 0;
      uint32_t connection = 0;
      uint32_t picture_transfer = 0;
      if (!read_station_state(&station, &registration, &priority, &connection,
                              &picture_transfer, error)) {
        return false;
      }
      if (station != 1 || registration != 1 || priority != 1 ||
          connection != 0 || picture_transfer > 1) {
        return fail(error, "camera drifted from the no-PC station baseline");
      }
      if (picture_transfer == 0) return true;
    }
    uint32_t remaining = 0;
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for picture-transfer cleanup");
    }
    uint32_t delay = remaining < kStationDirectPollMs
                         ? remaining
                         : kStationDirectPollMs;
    if (delay == 0) delay = 1;
    if (os::failed(os::nanosleep_ms(delay))) {
      return fail(error, "picture-transfer cleanup poll sleep failed");
    }
  }
}

bool CameraController::read_shell_hex(DevMemStateField field,
                                      const char *command, uint32_t maximum,
                                      uint32_t *value, Error *error) {
  if (value == nullptr) return fail(error, "missing U-shell hex result");
  // Validation build: U-shell remains authoritative.  Bracketing tolerates a
  // legitimate asynchronous transition but fails closed when the candidate
  // physical mapping agrees with neither side of the diagnostic read.
  uint32_t direct_before = 0;
  if (!devmem_state_.read(field, &direct_before, error)) return false;
  if (!shell_command(command, error)) return false;
  uint32_t parsed = 0;
  if (!parse_exact_shell_hex(shell_output_, shell_output_size_, maximum,
                             &parsed)) {
    return fail(error, "U-shell memory read returned malformed output");
  }
  uint32_t direct_after = 0;
  if (!devmem_state_.read(field, &direct_after, error)) return false;
  if (parsed != direct_before && parsed != direct_after) {
    return fail_devmem_shadow_mismatch(error, command, direct_before, parsed,
                                       direct_after);
  }
  *value = parsed;
  return true;
}

bool CameraController::read_shutter_trigger_state(uint32_t *value,
                                                  Error *error) {
  return read_shell_hex(DevMemStateField::kShutterTriggerState,
                        kReadShutterTriggerState, UINT8_MAX, value, error);
}

bool CameraController::wait_for_shutter_trigger_state(
    uint32_t previous, uint32_t expected, uint32_t timeout_ms, Error *error) {
  uint64_t deadline = 0;
  if (!deadline_after(timeout_ms, &deadline, error)) return false;
  for (;;) {
    uint32_t state = 0;
    if (!read_shutter_trigger_state(&state, error)) return false;
    if (state == expected) return true;
    if (state != previous) {
      return fail(error, "shutter trigger state took an unexpected transition");
    }
    uint32_t remaining = 0;
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for shutter trigger state");
    }
    uint32_t delay =
        remaining < kShutterStatePollMs ? remaining : kShutterStatePollMs;
    if (delay == 0) delay = 1;
    if (os::failed(os::nanosleep_ms(delay))) {
      return fail(error, "shutter trigger-state poll sleep failed");
    }
  }
}

bool CameraController::require_shutter_preflight(Error *error) {
  uint32_t value = 0;
  if (!read_shell_hex(DevMemStateField::kEventInitCookie,
                      kReadEventInitCookie, UINT8_MAX, &value, error)) {
    return false;
  }
  if (value != kRequiredEventInitCookie) {
    return fail(error, "SDK event subsystem is not initialized");
  }
  if (!read_shell_hex(DevMemStateField::kBleConverterState,
                      kReadBleConverterState, UINT32_MAX, &value, error)) {
    return false;
  }
  if (value != 0) {
    return fail(error, "stock BLE shutter converter is not idle");
  }
  if (!read_shutter_trigger_state(&value, error)) return false;
  if (value != 0) return fail(error, "shutter trigger state is not idle");
  if (os::failed(os::nanosleep_ms(kShutterStatePollMs))) {
    return fail(error, "shutter preflight stability sleep failed");
  }
  if (!read_shutter_trigger_state(&value, error)) return false;
  if (value != 0) {
    return fail(error, "shutter trigger state did not remain idle");
  }
  return true;
}

bool CameraController::post_shutter_edge(const char *command,
                                         uint32_t previous,
                                         uint32_t expected, Error *error) {
  // Never replay an edge after an ambiguous shell result.  The caller owns
  // the complete four-edge sequence and advances to cleanup exactly once.
  return shell_command(command, error) &&
         wait_for_shutter_trigger_state(previous, expected,
                                        kShutterStateTimeoutMs, error);
}

bool CameraController::wait_for_persistent_sdk_edge(uint32_t previous,
                                                    uint32_t expected,
                                                    Error *error) {
  uint64_t deadline = 0;
  if (!deadline_after(kShutterStateTimeoutMs, &deadline, error)) return false;
  for (;;) {
    uint32_t converter = UINT32_MAX;
    uint32_t trigger = UINT32_MAX;
    if (!devmem_state_.read(DevMemStateField::kBleConverterState, &converter,
                            error) ||
        !devmem_state_.read(DevMemStateField::kShutterTriggerState, &trigger,
                            error)) {
      return false;
    }
    // The qualified shell-SDK route bypasses the stock BLE converter.  A
    // nonzero converter or a skipped TrigState means another shutter source
    // interfered, so consume the generation without issuing any edge twice.
    if (converter != 0) {
      return fail(error,
                  "stock BLE converter changed during persistent SDK edge");
    }
    if (trigger == expected) return true;
    if (trigger != previous) {
      return fail(error,
                  "persistent SDK trigger took an unexpected transition");
    }
    uint32_t remaining = 0;
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for persistent SDK trigger");
    }
    const uint32_t delay = remaining < kPersistentSdkStatePollMs
                               ? remaining
                               : kPersistentSdkStatePollMs;
    if (os::failed(os::nanosleep_ms(delay == 0 ? 1 : delay))) {
      return fail(error, "persistent SDK trigger poll sleep failed");
    }
  }
}

bool CameraController::post_persistent_sdk_edge(const char *command,
                                                uint32_t previous,
                                                uint32_t expected,
                                                Error *error) {
  const size_t length = string_length(command);
  if (!shell_ready_ || !persistent_sdk_output_pending_ || length == 0 ||
      length > 255) {
    return fail(error, "persistent SDK edge is not ready to post");
  }

  // Exactly one 902B is issued for this edge.  Even if its operation response
  // is ambiguous, observe read-only state before advancing to the next
  // single-shot edge.  Neither this helper nor terminal cleanup replays it.
  bool all_ok = true;
  Error first = {};
  Error local = {};
  ++persistent_sdk_expected_prompts_;
  const bool posted =
      post_shell(reinterpret_cast<const uint8_t *>(command), length, 4,
                 &local);
  if (!posted) shell_resync_required_ = true;
  retain_first_error(posted, local, &all_ok, &first);
  local = {};
  retain_first_error(wait_for_persistent_sdk_edge(previous, expected, &local),
                     local, &all_ok, &first);
  if (!all_ok && error != nullptr) *error = first;
  return all_ok;
}

volatile uint8_t *CameraController::astro_preposition_mailbox() const {
  if (astro_preposition_page_ == nullptr) return nullptr;
  return static_cast<volatile uint8_t *>(astro_preposition_page_) +
         (ASTRO_PREPOSITION_PHYSICAL & (kPageSize - 1u));
}

bool CameraController::consume_staged_astro_preposition(Error *error) {
  volatile uint8_t *mailbox = astro_preposition_mailbox();
  if (mailbox == nullptr) {
    return fail(error, "capture preposition mailbox is unavailable");
  }
  // The companion stages its private vector before dispatching this capture.
  // Only this byte is shared with the worker; MAIN's payload has one writer.
  if (astro_preposition_owned_) {
    return fail(error, "capture preposition is already owned");
  }
  if (mailbox[ASTRO_PREPOSITION_READY_OFFSET] != ASTRO_PREPOSITION_STAGED) {
    return fail(error, "capture preposition stage is no longer available");
  }
  mailbox[ASTRO_PREPOSITION_READY_OFFSET] = ASTRO_PREPOSITION_REQUESTED;
  // Retain ownership before a clock or poll can fail so cleanup can cancel
  // this request. The companion alone publishes the native vector to MAIN.
  astro_preposition_owned_ = true;
  uint64_t now = 0;
  if (os::failed(os::monotonic_milliseconds(&now))) {
    return fail(error, "CLOCK_MONOTONIC failed");
  }
  detail::AstroPrepositionCaptureWait wait(now);
  for (;;) {
    const uint8_t ready = mailbox[ASTRO_PREPOSITION_READY_OFFSET];
    if (os::failed(os::monotonic_milliseconds(&now))) {
      return fail(error, "CLOCK_MONOTONIC failed");
    }
    switch (wait.observe(now, ready)) {
      case detail::AstroPrepositionCaptureWait::Result::kReady:
        // Retain the halfpress while waiting. A second shutter source must
        // not silently change its state during the experimental allowance.
        return wait_for_persistent_sdk_edge(kShutterFirstStageState,
                                             kShutterFirstStageState, error) &&
               ((mailbox[ASTRO_PREPOSITION_READY_OFFSET] ==
                     ASTRO_PREPOSITION_CONSUMED) ||
                fail(error, "capture preposition was cancelled while settling"));
      case detail::AstroPrepositionCaptureWait::Result::kCancelled:
        return fail(error, "capture preposition was cancelled while settling");
      case detail::AstroPrepositionCaptureWait::Result::kTimedOut:
        return fail(error, "MAIN did not consume capture preposition");
      case detail::AstroPrepositionCaptureWait::Result::kWaiting:
        break;
    }
    if (os::failed(os::nanosleep_ms(kPersistentSdkStatePollMs))) {
      return fail(error, "capture preposition wait sleep failed");
    }
  }
}

void CameraController::release_astro_preposition_ownership() {
  // The companion serializes cancellation behind any in-flight MAIN command.
  // Never change a new stage or touch the coordinator during an idle close.
  volatile uint8_t *mailbox = astro_preposition_mailbox();
  if (astro_preposition_owned_ && mailbox != nullptr) {
    const uint8_t state = mailbox[ASTRO_PREPOSITION_READY_OFFSET];
    if (state == ASTRO_PREPOSITION_REQUESTED ||
        state == ASTRO_PREPOSITION_PUBLISHED ||
        state == ASTRO_PREPOSITION_CONSUMED) {
      mailbox[ASTRO_PREPOSITION_READY_OFFSET] = ASTRO_PREPOSITION_CANCELLED;
    }
  }
  astro_preposition_owned_ = false;
}

bool CameraController::run_shell_sdk_shutter_cycle(
    Error *error, bool preposition_required) {
  clear_error(error);
  if (!persistent_shell_sdk_ready_ || shutter_release_pending_ ||
      shutter_recovery_required_ || shutter_settings_restore_blocked_ ||
      persistent_sdk_output_pending_) {
    return fail(error,
                "shell SDK shutter is not in a consumed idle state");
  }
  // Every edge is a distinct asynchronous 902B and is single-shot. Retain all
  // cleanup obligations before first-stage-on. Ordinary captures keep their
  // exact four-edge sequence; failed preposition skips both second-stage edges.
  shutter_release_pending_ = true;
  shutter_recovery_required_ = true;
  shutter_settings_restore_blocked_ = true;
  persistent_sdk_output_pending_ = true;
  persistent_sdk_expected_prompts_ = 0;
  shell_output_size_ = 0;
  shell_output_[0] = '\0';
  bool all_ok = true;
  Error first = {};
  Error local = {};
  retain_first_error(post_persistent_sdk_edge(kBleFirstStageOn, 0,
                                              kShutterFirstStageState,
                                              &local),
                     local, &all_ok, &first);
  if (preposition_required && all_ok) {
    local = {};
    retain_first_error(consume_staged_astro_preposition(&local), local,
                       &all_ok, &first);
  }
  // A failed/cancelled preposition must never issue fullpress. Preserve the
  // existing four-edge recovery behavior for ordinary captures, including
  // their ambiguous first-stage response case.
  const bool issue_fullpress = !preposition_required || all_ok;
  if (issue_fullpress) {
    local = {};
    retain_first_error(post_persistent_sdk_edge(kBleSecondStageOn,
                                              kShutterFirstStageState,
                                              kShutterBothStagesState,
                                              &local),
                       local, &all_ok, &first);

    // Keep one scheduler tick of margin, then prove the exact full-press state
    // remained stable using only the already-qualified direct mappings.
    local = {};
    const bool stability_delay_ok = !os::failed(
        os::nanosleep_ms(kPersistentSdkFullPressStabilityMs));
    if (!stability_delay_ok) {
      (void)fail(&local, "persistent SDK full-press stability sleep failed");
    }
    retain_first_error(stability_delay_ok, local, &all_ok, &first);
    local = {};
    retain_first_error(
        wait_for_persistent_sdk_edge(kShutterBothStagesState,
                                     kShutterBothStagesState, &local),
        local, &all_ok, &first);
  }

  // Clear the retryable marker before either release post: an ambiguous 902B
  // cannot prove an edge was not queued, so close must never replay it.  The
  // independent recovery and setting-restore fences remain sticky.
  shutter_release_pending_ = false;
  if (issue_fullpress) {
    local = {};
    retain_first_error(post_persistent_sdk_edge(kBleSecondStageOff,
                                              kShutterBothStagesState,
                                              kShutterFirstStageState,
                                              &local),
                       local, &all_ok, &first);
  }
  local = {};
  retain_first_error(post_persistent_sdk_edge(kBleFirstStageOff,
                                              kShutterFirstStageState, 0,
                                              &local),
                     local, &all_ok, &first);

  // Preserve the causal release fact separately from later transcript work.
  // If every single-shot post and direct transition through final 0 succeeded,
  // a subsequent prompt-drain failure cannot make the shutter pressed again.
  // Conversely, reaching 0 after any ambiguous earlier edge is not sufficient:
  // that path still needs the full transcript plus an authoritative idle proof.
  const bool release_idle_causally_proven = all_ok;

  // Always consume the exact issued prompts before exposing a post-capture
  // boundary.  An unchanged-settings capture has changed_mask_ == 0, but its
  // transcript must still be fenced before any later diagnostic command.
  // Changed settings also cannot be restored behind an undrained batch, and
  // every failed cycle still requires the authoritative idle proof below.
  local = {};
  const bool transcript_ok =
      drain_persistent_sdk_output(kPersistentSdkDrainTimeoutMs, &local);
  retain_first_error(transcript_ok, local, &all_ok, &first);
  if (release_idle_causally_proven) {
    // The exact final converter=0/TrigState=0 transition is the causal release
    // proof.  A later prompt/command error must not erase that fact.
    shutter_settings_restore_blocked_ = false;
  } else if (transcript_ok) {
    local = {};
    const bool idle = require_shutter_preflight(&local);
    if (idle) {
      shutter_settings_restore_blocked_ = false;
      shutter_recovery_required_ = false;
    }
    retain_first_error(idle, local, &all_ok, &first);
  }

  if (!all_ok && error != nullptr) *error = first;
  return all_ok;
}

bool CameraController::release_shutter(Error *error) {
  if (!shutter_release_pending_) return true;
  // Each downstream edge is single-shot.  Clear the retryable obligation
  // before sending: a lost U-shell prompt cannot distinguish an unexecuted
  // command from an already queued release, so close() must never replay it.
  shutter_release_pending_ = false;
  bool all_ok = true;
  Error first = {};
  Error local = {};
  retain_first_error(post_shutter_edge(kBleSecondStageOff,
                                       kShutterBothStagesState,
                                       kShutterFirstStageState, &local), local,
                     &all_ok, &first);
  local = {};
  const bool final_off = post_shutter_edge(kBleFirstStageOff,
                                           kShutterFirstStageState, 0, &local);
  retain_first_error(final_off, local,
                     &all_ok, &first);
  bool converter_idle = false;
  if (final_off) {
    uint32_t converter = UINT32_MAX;
    local = {};
    converter_idle =
        read_shell_hex(DevMemStateField::kBleConverterState,
                       kReadBleConverterState, UINT32_MAX, &converter,
                       &local) &&
        converter == 0;
    if (!converter_idle && local.text[0] == '\0') {
      (void)fail(&local, "stock BLE shutter converter changed unexpectedly");
    }
    retain_first_error(converter_idle, local, &all_ok, &first);
  }
  // A verified final TrigState=0 plus converter-idle proves release even if an
  // earlier transition check failed.  Otherwise recovery remains required and
  // close() must not replay either single-shot release edge.
  shutter_recovery_required_ = !(final_off && converter_idle);
  if (!all_ok && error != nullptr) *error = first;
  return all_ok;
}

bool CameraController::transfer_id_is_canonical(uint32_t slot,
                                                uint32_t transfer_id) {
  const uint32_t ordinal = transfer_id & UINT32_C(0x000fffff);
  return (slot == 1 || slot == 2) && (transfer_id >> 28) == slot &&
         (transfer_id & UINT32_C(0x0ff00000)) == 0 && ordinal != 0 &&
         ordinal <= kTransferRecordCapacity;
}

bool CameraController::transfer_lists_equal(const TransferIdList &left,
                                            const TransferIdList &right) {
  if (left.count != right.count) return false;
  for (uint32_t i = 0; i < left.count; ++i) {
    if (left.ids[i] != right.ids[i]) return false;
  }
  return true;
}

bool CameraController::transfer_list_contains(const TransferIdList &list,
                                              uint32_t transfer_id) {
  for (uint32_t i = 0; i < list.count; ++i) {
    if (list.ids[i] == transfer_id) return true;
    if (list.ids[i] > transfer_id) return false;
  }
  return false;
}

bool CameraController::read_untransferred_list(uint32_t slot,
                                               uint32_t timeout_ms,
                                               TransferIdList *list,
                                               Error *error) {
  if (list == nullptr || (slot != 1 && slot != 2)) {
    return fail(error, "invalid 94BB slot or destination");
  }
  list->count = 0;
  const uint32_t parameter = slot;
  PtpOperationResult result = {};
  if (!ptp_.operation(1, kOperationUntransferredList, &parameter, 1, nullptr,
                      0, transfer_list_data_, sizeof(transfer_list_data_),
                      timeout_ms, &result, error) ||
      !response_ok(result, "PTP 94BB untransferred list", error)) {
    return false;
  }
  if (!response_has_five_zero_parameters(result) || result.data_size < 4u ||
      result.declared_data_size != result.data_size) {
    return fail(error, "PTP 94BB returned a malformed response envelope");
  }

  const uint32_t count = get_le32(transfer_list_data_);
  if (count > kTransferRecordCapacity ||
      result.data_size != 4u + static_cast<size_t>(count) * 4u) {
    return fail(error, "PTP 94BB returned a malformed transfer-ID list");
  }
  uint32_t previous = 0;
  for (uint32_t i = 0; i < count; ++i) {
    const uint32_t transfer_id = get_le32(transfer_list_data_ + 4u + i * 4u);
    if (!transfer_id_is_canonical(slot, transfer_id) ||
        (i != 0 && transfer_id <= previous)) {
      return fail(error, "PTP 94BB returned a noncanonical transfer ID");
    }
    list->ids[i] = transfer_id;
    previous = transfer_id;
  }
  list->count = count;
  return true;
}

bool CameraController::read_transfer_snapshot(uint32_t operation_timeout_ms,
                                              TransferIdList lists[2],
                                              Error *error) {
  if (lists == nullptr) return fail(error, "missing transfer-list snapshot");
  for (uint32_t slot = 1; slot <= 2; ++slot) {
    if (!read_untransferred_list(slot, operation_timeout_ms,
                                 &lists[slot - 1u], error)) {
      return false;
    }
  }
  return true;
}

bool CameraController::read_transfer_record_metadata(
    uint32_t slot, uint32_t transfer_id, uint32_t timeout_ms, char leaf[13],
    uint64_t *file_size, Error *error) {
  if (leaf == nullptr || file_size == nullptr ||
      !transfer_id_is_canonical(slot, transfer_id)) {
    return fail(error, "invalid 94DD transfer identifier or destination");
  }
  leaf[0] = '\0';
  *file_size = 0;
  const uint32_t parameter = transfer_id;
  uint8_t data[21] = {};
  PtpOperationResult result = {};
  if (!ptp_.operation(1, kOperationTransferMetadata, &parameter, 1, nullptr, 0,
                      data, sizeof(data), timeout_ms, &result, error) ||
      !response_ok(result, "PTP 94DD metadata", error)) {
    return false;
  }
  if (!response_has_five_zero_parameters(result) ||
      result.data_size != sizeof(data) ||
      result.declared_data_size != sizeof(data)) {
    return fail(error, "PTP 94DD returned a malformed response envelope");
  }
  for (unsigned i = 0; i < 4; ++i) {
    if (!valid_dcf_prefix(static_cast<char>(data[i]))) {
      return fail(error, "PTP 94DD returned an invalid DCF basename");
    }
  }
  for (unsigned i = 4; i < 8; ++i) {
    if (data[i] < '0' || data[i] > '9') {
      return fail(error, "PTP 94DD returned an invalid DCF file number");
    }
  }
  if (data[8] != '.' || data[12] != 0) {
    return fail(error, "PTP 94DD returned a malformed DCF leaf");
  }
  for (unsigned i = 9; i < 12; ++i) {
    if (data[i] < 'A' || data[i] > 'Z') {
      return fail(error, "PTP 94DD returned a non-uppercase extension");
    }
  }
  const uint64_t size = get_le64(data + 13);
  if (size == 0) return fail(error, "PTP 94DD returned a zero file size");
  for (unsigned i = 0; i < 13; ++i) {
    leaf[i] = static_cast<char>(data[i]);
  }
  *file_size = size;
  return true;
}

bool CameraController::sleep_transfer_poll(uint32_t delay_ms, Error *error) {
  if (os::failed(os::nanosleep_ms(delay_ms))) {
    return fail(error, "94BB transfer-record poll sleep failed");
  }
  return true;
}

bool CameraController::read_direct_exposure_state(
    direct_catalog::Snapshot *snapshot, direct_catalog::Result *result,
    Error *error, direct_catalog::Status *status) {
  if (status != nullptr) *status = direct_catalog::Status::kInvalid;
  if (snapshot == nullptr || result == nullptr) {
    return fail(error, "missing direct exposure snapshot output");
  }
  if (!devmem_state_.read_cached_direct_exposure_snapshot(snapshot, error)) {
    return false;
  }
  const direct_catalog::Status evaluated =
      direct_catalog::evaluate(snapshot, result);
  if (status != nullptr) *status = evaluated;
  switch (evaluated) {
    case direct_catalog::Status::kOk:
      return true;
    case direct_catalog::Status::kUnsupported:
      return fail(error, "direct exposure state is unsupported");
    case direct_catalog::Status::kInvalid:
      return fail(error, "direct exposure snapshot is invalid");
  }
  return fail(error, "direct exposure evaluator returned an unknown status");
}

bool CameraController::direct_catalog_mapping_equal(
    const direct_catalog::Result &left,
    const direct_catalog::Result &right) {
  if (left.mapping_certificate != right.mapping_certificate ||
      left.label_bytes != right.label_bytes) {
    return false;
  }
  for (unsigned setting = 0; setting < direct_catalog::kSettingCount;
       ++setting) {
    if (left.value_count[setting] != right.value_count[setting]) return false;
    for (uint16_t index = 0; index < left.value_count[setting]; ++index) {
      if (left.values[setting][index] != right.values[setting][index]) {
        return false;
      }
    }
  }
  for (uint16_t byte = 0; byte < left.label_bytes; ++byte) {
    if (left.labels[byte] != right.labels[byte]) return false;
  }
  return true;
}

bool CameraController::direct_catalog_contains(
    const direct_catalog::Result &result, unsigned setting_index,
    int32_t value, uint16_t *compact_index) {
  if (setting_index >= direct_catalog::kSettingCount || value < INT16_MIN ||
      value > INT16_MAX) {
    return false;
  }
  bool found = false;
  uint16_t found_index = 0;
  for (uint16_t index = 0; index < result.value_count[setting_index]; ++index) {
    if (result.values[setting_index][index] != value) continue;
    if (found) return false;
    found = true;
    found_index = index;
  }
  if (found && compact_index != nullptr) *compact_index = found_index;
  return found;
}

bool CameraController::export_direct_setting_catalog(
    const direct_catalog::Snapshot &snapshot,
    const direct_catalog::Result &result, SettingCatalog *catalog,
    Error *error) {
  if (catalog == nullptr) return fail(error, "missing direct catalog output");
  *catalog = {};
  if (result.label_bytes > kSettingCatalogLabelStorage ||
      result.label_bytes > direct_catalog::kV19MaxLabelBytes) {
    return fail(error, "direct catalog label stream exceeds its bound");
  }
  for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
    if (result.value_count[setting] == 0 ||
        result.value_count[setting] > kSettingCatalogMaxValues ||
        result.current_index[setting] >= result.value_count[setting] ||
        snapshot.native_count[setting] == 0 ||
        snapshot.native_count[setting] > kSettingCatalogMaxValues) {
      return fail(error, "direct catalog shape is invalid");
    }
    catalog->value_count[setting] = result.value_count[setting];
    catalog->current_index[setting] = result.current_index[setting];
    catalog->source_id[setting] = snapshot.source_id[setting];
    for (uint16_t index = 0; index < result.value_count[setting]; ++index) {
      catalog->values[setting][index] = result.values[setting][index];
    }
  }
  for (unsigned index = 0; index < kSettingCatalogContextCount; ++index) {
    if (snapshot.context_values[index] < INT16_MIN ||
        snapshot.context_values[index] > INT16_MAX) {
      return fail(error, "direct catalog context exceeds signed wire range");
    }
    catalog->context_values[index] =
        static_cast<int16_t>(snapshot.context_values[index]);
  }
  const int32_t exposure_step =
      snapshot.context_values[kSettingCatalogExposureStepIndex];
  if (exposure_step != 85 && exposure_step != 128 && exposure_step != 256) {
    return fail(error, "direct catalog exposure step is unsupported");
  }
  catalog->exposure_step = static_cast<uint16_t>(exposure_step);
  catalog->mode_helper = snapshot.mode_helper;
  const direct_catalog::ApertureProjection &aperture = result.aperture;
  if (aperture.minimum_index >=
          snapshot.native_count[direct_catalog::kAperture] ||
      aperture.maximum_index >=
          snapshot.native_count[direct_catalog::kAperture] ||
      aperture.minimum_index > aperture.maximum_index ||
      snapshot.native[direct_catalog::kAperture][aperture.minimum_index]
              .value !=
          aperture.minimum_value ||
      snapshot.native[direct_catalog::kAperture][aperture.maximum_index]
              .value !=
          aperture.maximum_value) {
    return fail(error, "direct aperture bounds are not projectable");
  }
  catalog->aperture_min = aperture.minimum_value;
  catalog->aperture_max = aperture.maximum_value;
  catalog->label_bytes = result.label_bytes;
  for (uint16_t byte = 0; byte < result.label_bytes; ++byte) {
    catalog->labels[byte] = result.labels[byte];
  }
  return true;
}

void CameraController::commit_direct_setting_evidence(
    const direct_catalog::Snapshot &snapshot,
    const direct_catalog::Result &result) {
  direct_catalog_snapshot_certificate_ = snapshot;
  direct_catalog_result_certificate_ = result;
  for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
    selected_setting_controller_ids_[setting] = snapshot.source_id[setting];
    persistent_settings_snapshot_[setting] = snapshot.active[setting];
    setting_catalog_validated_current_[setting] = snapshot.active[setting];
  }
  persistent_settings_snapshot_valid_ = true;
}

bool CameraController::read_active(unsigned setting_index, int32_t *value,
                                   Error *error) {
  if (setting_index >= 3 || value == nullptr) {
    return fail(error, "invalid active camera-setting query");
  }
  int32_t active[3] = {};
  int32_t selected[3] = {};
  if (!read_setting_snapshot(active, selected, error)) return false;
  *value = active[setting_index];
  return true;
}

bool CameraController::read_controller(unsigned setting_index, int32_t *value,
                                       Error *error) {
  if (setting_index >= 3 || value == nullptr) {
    return fail(error, "invalid selected camera-setting query");
  }
  int32_t active[3] = {};
  int32_t selected[3] = {};
  if (!read_setting_snapshot(active, selected, error)) return false;
  *value = selected[setting_index];
  return true;
}

bool CameraController::read_pair(unsigned setting_index, int32_t *active,
                                 int32_t *controller, Error *error) {
  if (setting_index >= 3 || active == nullptr || controller == nullptr) {
    return fail(error, "invalid camera setting pair query");
  }
  int32_t active_values[3] = {};
  int32_t controller_values[3] = {};
  if (!read_setting_snapshot(active_values, controller_values, error)) {
    return false;
  }
  *active = active_values[setting_index];
  *controller = controller_values[setting_index];
  return true;
}

bool CameraController::refresh_persistent_transfer_slot_hint(Error *error) {
  persistent_transfer_slot_hint_ = 0;

  // This hint is optional, but every command transaction is not: a transport
  // or framing failure leaves the retained session unfit for an armed
  // generation and must fail startup. A healthy but unrecognized or mismatched
  // value merely selects the ordinary two-slot discovery path.
  int32_t record_mode = INT32_MIN;
  int32_t record_slot = INT32_MIN;
  int32_t effective_media = INT32_MIN;
  bool record_mode_valid = false;
  bool record_slot_valid = false;
  bool effective_media_valid = false;
  char command[48] = {};

  if (!append_setting_command(command, sizeof(command), "sdk api B02 ",
                              kPictureRecordModeControllerId, false, 0)) {
    return fail(error, "could not construct picture-record mode query");
  }
  if (!shell_command(command, error)) return false;
  record_mode_valid =
      contains_bytes(shell_output_, shell_output_size_, "API_SUCCESS") &&
      parse_prefixed_line_i32(shell_output_, shell_output_size_, "value,",
                              &record_mode);

  if (!append_setting_command(command, sizeof(command), "sdk api B02 ",
                              kPictureRecordSlotControllerId, false, 0)) {
    return fail(error, "could not construct picture-record slot query");
  }
  if (!shell_command(command, error)) return false;
  record_slot_valid =
      contains_bytes(shell_output_, shell_output_size_, "API_SUCCESS") &&
      parse_prefixed_line_i32(shell_output_, shell_output_size_, "value,",
                              &record_slot);

  if (!append_setting_command(command, sizeof(command),
                              "app Get_CameraInfo ",
                              kEffectiveRecordMediaCameraInfoId, false, 0)) {
    return fail(error, "could not construct effective record-media query");
  }
  if (!shell_command(command, error)) return false;
  effective_media_valid = parse_prefixed_line_i32(
      shell_output_, shell_output_size_, "value:", &effective_media);

  // v1.9's Get_CameraInfo(23) wrapper passes this zero-based value unchanged
  // to the DCF lookup.  PTP 94BB maps P1=1 to internal media 0 and P1=2 to
  // internal media 1.  Only NORMAL mode with controller/effective agreement
  // is stable enough to skip the other slot on empty polls; all other healthy
  // states retain the correctness-equivalent two-slot path.
  if (record_mode_valid && record_slot_valid && effective_media_valid &&
      record_mode == 0 && (record_slot == 0 || record_slot == 1) &&
      effective_media == record_slot) {
    persistent_transfer_slot_hint_ =
        static_cast<uint8_t>(record_slot + 1);
  }
  return true;
}

void CameraController::qualify_direct_transfer_records() {
  direct_transfer_records_qualified_ = false;
  if (!devmem_state_.transfer_records_available()) return;

  // This is deliberately optional.  Stock 94BB remains the authority at
  // every arm boundary; any layout, stability, or list mismatch silently
  // retains the correctness-equivalent PTP discovery path.
  Error ignored = {};
  DevMemTransferCounts counts = {};
  if (devmem_state_.read_stable_transfer_lists(transfer_verify_, &counts,
                                                &ignored) !=
      DevMemTransferListStatus::kStable) {
    return;
  }
  for (unsigned slot = 0; slot < 2; ++slot) {
    if (!transfer_lists_equal(transfer_before_[slot],
                              transfer_verify_[slot]) ||
        !transfer_lists_equal(transfer_after_[slot],
                              transfer_verify_[slot])) {
      return;
    }
  }
  transfer_counts_ = counts;
  direct_transfer_records_qualified_ = true;
}

bool CameraController::read_active_batch(int32_t values[3], Error *error) {
  if (values == nullptr) return fail(error, "missing active setting batch");
  if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                  &direct_catalog_result_scratch_, error)) {
    return false;
  }
  for (unsigned i = 0; i < 3; ++i) {
    values[i] = direct_catalog_snapshot_scratch_.active[i];
  }
  return true;
}

bool CameraController::read_controller_batch(int32_t values[3],
                                             Error *error) {
  if (values == nullptr) {
    return fail(error, "missing SettingController read batch");
  }
  if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                  &direct_catalog_result_scratch_, error)) {
    return false;
  }
  for (unsigned i = 0; i < 3; ++i) {
    values[i] = direct_catalog_snapshot_scratch_.selected[i];
  }
  return true;
}

bool CameraController::read_setting_snapshot(int32_t active[3],
                                             int32_t controller[3],
                                             Error *error) {
  if (active == nullptr || controller == nullptr) {
    return fail(error, "missing camera setting snapshot");
  }
  if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                  &direct_catalog_result_scratch_, error)) {
    return false;
  }
  for (unsigned i = 0; i < 3; ++i) {
    active[i] = direct_catalog_snapshot_scratch_.active[i];
    controller[i] = direct_catalog_snapshot_scratch_.selected[i];
  }
  return true;
}

bool CameraController::read_stock_setting_snapshot(int32_t active[3],
                                                   int32_t controller[3],
                                                   Error *error) {
  if (active == nullptr || controller == nullptr) {
    return fail(error, "missing stock setting snapshot");
  }
  return read_setting_snapshot(active, controller, error);
}

void CameraController::invalidate_setting_catalog_certificate() {
  setting_catalog_evidence_valid_ = false;
  setting_catalog_mutation_grant_valid_ = false;
}

bool CameraController::targets_are_legal_batch(Error *error) {
  if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                  &direct_catalog_result_scratch_, error)) {
    return false;
  }
  for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
    if (!direct_catalog_contains(direct_catalog_result_scratch_, setting,
                                 capture_targets_[setting]) ||
        !direct_catalog::target_is_legal(
            &direct_catalog_snapshot_scratch_, setting,
            static_cast<int16_t>(capture_targets_[setting]))) {
      return fail(error, "capture setting is not currently legal");
    }
  }
  return true;
}

bool CameraController::read_setting_write_snapshot_class(
    const int32_t prewrite[3], const int32_t applied[3],
    SettingWriteSnapshotClass *snapshot_class, Error *error) {
  if (prewrite == nullptr || applied == nullptr || snapshot_class == nullptr) {
    return fail(error, "missing setting-write snapshot classification state");
  }
  if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                  &direct_catalog_result_scratch_, error)) {
    return false;
  }
  for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
    if (direct_catalog_snapshot_scratch_.source_id[setting] !=
        direct_catalog_snapshot_certificate_.source_id[setting]) {
      return fail(error, "setting source changed after B01");
    }
  }
  for (unsigned index = 0; index < kSettingCatalogContextCount; ++index) {
    if (direct_catalog_snapshot_scratch_.context_values[index] !=
        direct_catalog_snapshot_certificate_.context_values[index]) {
      return fail(error, "setting context changed after B01");
    }
  }
  if (direct_catalog_snapshot_scratch_.mode_helper !=
      direct_catalog_snapshot_certificate_.mode_helper) {
    return fail(error, "setting mode changed after B01");
  }
  *snapshot_class = classify_setting_write_snapshot(
      direct_catalog_snapshot_scratch_.active,
      direct_catalog_snapshot_scratch_.selected, prewrite, applied);
  return true;
}

bool CameraController::write_and_observe_setting(unsigned setting_index,
                                                 int32_t value,
                                                 bool restoring,
                                                 Error *error) {
  if (setting_index >= 3) {
    return fail(error, "invalid setting write batch index");
  }
  int32_t prewrite[3] = {};
  int32_t applied[3] = {};
  for (unsigned setting = 0; setting < 3; ++setting) {
    const bool completed =
        restoring ? setting > setting_index : setting < setting_index;
    prewrite[setting] = completed ? original_settings_[setting]
                                  : capture_targets_[setting];
    if (!restoring) {
      prewrite[setting] = completed ? capture_targets_[setting]
                                    : original_settings_[setting];
    }
    applied[setting] = prewrite[setting];
  }
  applied[setting_index] = value;

  unsigned wire_attempt = 0;
  unsigned unresponsive_attempts = 0;
  bool fast_unresponsive_allowed = true;
  for (unsigned policy_attempt = 1; policy_attempt <= kSettingWriteAttempts;
       ++policy_attempt) {
    // This complete direct snapshot is the final read-only operation before
    // every ordinary B01, including the sole ambiguity retry.  It re-proves
    // the exact intermediate tuple, pinned context, and target legality rather
    // than relying on the earlier attempt's observation.
    if (!validate_pinned_setting_write(setting_index, value, restoring,
                                       error)) {
      if (unresponsive_attempts != 0) {
        describe_b01_unresponsive_retry(restoring, setting_index,
                                        unresponsive_attempts,
                                        "revalidation-failed");
      }
      return false;
    }

    // From this assignment onward a B01 may have reached firmware even when
    // its U-shell response is lost.  Keep the mutation journal sticky across
    // every observation and the optional retry.
    setting_write_attempted_ = true;
    setting_prewrite_rejection_safe_ = false;
    Error command_error = {};
    ++wire_attempt;
    DirectSetResult command =
        direct_set(setting_index, value, wire_attempt, &command_error);
    uint64_t observation_deadline = 0;
    bool observation_deadline_valid = false;

    if (command == DirectSetResult::kTerminalUnresponsive) {
      ++unresponsive_attempts;
      if (fast_unresponsive_allowed) {
        // 0xf0000058 is the synchronous SettingController can-execute
        // rejection.  The terminal prompt proves that no action dispatch,
        // setter, or callback was reached, so exact prewrite state may be
        // retried without the ordinary four-second ambiguity observation.
        // The retry window itself remains one absolute four-second bound.
        uint64_t next_retry_at = 0;
        if (!deadline_after(kSettingSettleTimeoutMs, &observation_deadline,
                            error) ||
            !deadline_after(kSettingUnresponsiveRetryMs, &next_retry_at,
                            error)) {
          describe_b01_unresponsive_retry(restoring, setting_index,
                                          unresponsive_attempts,
                                          "clock-failed");
          return false;
        }
        observation_deadline_valid = true;

        for (;;) {
          SettingWriteSnapshotClass observed =
              SettingWriteSnapshotClass::kUnexpected;
          if (!read_setting_write_snapshot_class(prewrite, applied, &observed,
                                                 error)) {
            describe_b01_unresponsive_retry(restoring, setting_index,
                                            unresponsive_attempts,
                                            "observation-failed");
            return false;
          }
          uint64_t now = 0;
          if (os::failed(os::monotonic_milliseconds(&now))) {
            describe_b01_unresponsive_retry(restoring, setting_index,
                                            unresponsive_attempts,
                                            "clock-failed");
            return fail(error, "CLOCK_MONOTONIC failed");
          }
          const UnresponsiveRetryDecision fast_decision =
              decide_unresponsive_setting_write_retry(
                  observed, now, next_retry_at, observation_deadline);
          if (fast_decision == UnresponsiveRetryDecision::kSuccess) {
            describe_b01_unresponsive_retry(restoring, setting_index,
                                            unresponsive_attempts, "success");
            return true;
          }
          if (fast_decision ==
              UnresponsiveRetryDecision::kStateChanged) {
            describe_b01_unresponsive_retry(restoring, setting_index,
                                            unresponsive_attempts,
                                            "state-changed");
            return fail(error,
                        observed == SettingWriteSnapshotClass::kTransitional
                            ? "setting became transitional after B01 "
                              "API_ERROR_UNRESPONSIVE"
                            : "setting became unowned after B01 "
                              "API_ERROR_UNRESPONSIVE");
          }
          if (fast_decision == UnresponsiveRetryDecision::kDeadline) {
            describe_b01_unresponsive_retry(restoring, setting_index,
                                            unresponsive_attempts,
                                            "deadline");
            return fail(error,
                        "B01 API_ERROR_UNRESPONSIVE retry deadline expired");
          }
          if (fast_decision == UnresponsiveRetryDecision::kWait) {
            const uint64_t wake_at =
                next_retry_at < observation_deadline ? next_retry_at
                                                     : observation_deadline;
            const uint64_t delay = wake_at - now;
            if (os::failed(os::nanosleep_ms(static_cast<uint32_t>(delay)))) {
              describe_b01_unresponsive_retry(restoring, setting_index,
                                              unresponsive_attempts,
                                              "sleep-failed");
              return fail(error, "B01 unresponsive retry sleep failed");
            }
            continue;
          }

          // Re-prove the complete tuple, source IDs, mode/context, catalog
          // membership, and legality immediately before every fast replay.
          if (!validate_pinned_setting_write(setting_index, value, restoring,
                                             error)) {
            describe_b01_unresponsive_retry(restoring, setting_index,
                                            unresponsive_attempts,
                                            "revalidation-failed");
            return false;
          }
          if (os::failed(os::monotonic_milliseconds(&now))) {
            describe_b01_unresponsive_retry(restoring, setting_index,
                                            unresponsive_attempts,
                                            "clock-failed");
            return fail(error, "CLOCK_MONOTONIC failed");
          }
          if (now >= observation_deadline) {
            describe_b01_unresponsive_retry(restoring, setting_index,
                                            unresponsive_attempts,
                                            "deadline");
            return fail(error,
                        "B01 API_ERROR_UNRESPONSIVE retry deadline expired");
          }

          Error retry_error = {};
          ++wire_attempt;
          command =
              direct_set(setting_index, value, wire_attempt, &retry_error);
          command_error = retry_error;
          if (command != DirectSetResult::kTerminalUnresponsive) {
            // API_SUCCESS continues below under the remaining absolute
            // convergence budget.  Any other result returns to the unchanged
            // conservative ambiguity policy, including its sole replay.
            fast_unresponsive_allowed = false;
            if (command != DirectSetResult::kSuccess) {
              observation_deadline_valid = false;
            }
            break;
          }
          ++unresponsive_attempts;
          if (!deadline_after(kSettingUnresponsiveRetryMs, &next_retry_at,
                              error)) {
            describe_b01_unresponsive_retry(restoring, setting_index,
                                            unresponsive_attempts,
                                            "clock-failed");
            return false;
          }
        }
      } else {
        // A preceding ambiguous or different terminal error has already spent
        // its one ordinary replay.  Do not use a later UNRESPONSIVE result to
        // broaden that policy into more posts.
        fast_unresponsive_allowed = false;
      }
    } else {
      fast_unresponsive_allowed = false;
    }

    if (command == DirectSetResult::kTransportAmbiguous) {
      // An immediate direct prewrite tuple is not proof of no mutation: the
      // old B01 may still be queued.  Establish one unique FIFO boundary before
      // observing or considering a replay.
      Error fence_error = {};
      if (!resynchronize_shell(&fence_error)) {
        if (error != nullptr) {
          TextBuffer combined(error->text, sizeof(error->text));
          combined.append("B01 transport ambiguous: ");
          combined.append(command_error.text);
          combined.append("; FIFO fence failed: ");
          combined.append(fence_error.text);
        }
        if (unresponsive_attempts != 0) {
          describe_b01_unresponsive_retry(restoring, setting_index,
                                          unresponsive_attempts,
                                          "fence-failed");
        }
        return false;
      }
    }

    if (!observation_deadline_valid &&
        !deadline_after(kSettingSettleTimeoutMs, &observation_deadline,
                        error)) {
      if (unresponsive_attempts != 0) {
        describe_b01_unresponsive_retry(restoring, setting_index,
                                        unresponsive_attempts,
                                        "clock-failed");
      }
      return false;
    }
    for (;;) {
      SettingWriteSnapshotClass observed =
          SettingWriteSnapshotClass::kUnexpected;
      if (!read_setting_write_snapshot_class(prewrite, applied, &observed,
                                             error)) {
        if (unresponsive_attempts != 0) {
          describe_b01_unresponsive_retry(restoring, setting_index,
                                          unresponsive_attempts,
                                          "observation-failed");
        }
        return false;
      }
      uint64_t now = 0;
      if (os::failed(os::monotonic_milliseconds(&now))) {
        if (unresponsive_attempts != 0) {
          describe_b01_unresponsive_retry(restoring, setting_index,
                                          unresponsive_attempts,
                                          "clock-failed");
        }
        return fail(error, "CLOCK_MONOTONIC failed");
      }
      const SettingWriteDecision decision = decide_setting_write_retry(
          observed, command == DirectSetResult::kSuccess, policy_attempt,
          kSettingWriteAttempts, now >= observation_deadline);
      if (decision == SettingWriteDecision::kSuccess) {
        if (unresponsive_attempts != 0) {
          describe_b01_unresponsive_retry(restoring, setting_index,
                                          unresponsive_attempts, "success");
        }
        return true;
      }
      if (decision == SettingWriteDecision::kRetry) break;
      if (decision == SettingWriteDecision::kFail) {
        if (unresponsive_attempts != 0) {
          describe_b01_unresponsive_retry(
              restoring, setting_index, unresponsive_attempts,
              observed == SettingWriteSnapshotClass::kUnexpected ||
                      observed == SettingWriteSnapshotClass::kTransitional
                  ? "state-changed"
                  : "deadline");
        }
        if (observed == SettingWriteSnapshotClass::kUnexpected) {
          return fail(error, "setting became unowned after B01");
        }
        if (observed == SettingWriteSnapshotClass::kTransitional) {
          return fail(error, "setting remained transitional after B01");
        }
        if (command == DirectSetResult::kSuccess) {
          return fail(
              error,
              "B01 API_SUCCESS did not apply the exact setting tuple");
        }
        if (error != nullptr) *error = command_error;
        return false;
      }
      uint64_t delay = observation_deadline - now;
      if (delay > kSettingWriteObservationPollMs) {
        delay = kSettingWriteObservationPollMs;
      }
      if (os::failed(
              os::nanosleep_ms(static_cast<uint32_t>(delay == 0 ? 1 : delay)))) {
        return fail(error, "setting-write observation sleep failed");
      }
    }

    // The sole retry is admitted only by a fresh exact-prewrite snapshot at
    // the ordinary absolute observation deadline.  The loop top immediately
    // rechecks the pinned tuple and legality before posting it.  This policy is
    // unchanged for transport ambiguity and terminal errors other than the
    // exact synchronous UNRESPONSIVE preflight rejection.
  }
  if (unresponsive_attempts != 0) {
    describe_b01_unresponsive_retry(restoring, setting_index,
                                    unresponsive_attempts, "internal-error");
  }
  return fail(error, "B01 retry state escaped its bound");
}

bool CameraController::wait_for_settings_batch(const int32_t expected[3],
                                               uint32_t timeout_ms,
                                               Error *error) {
  if (expected == nullptr) return fail(error, "missing expected settings");
  uint64_t deadline = 0;
  if (!deadline_after(timeout_ms, &deadline, error)) return false;
  for (;;) {
    bool transient = false;
    if (!devmem_state_.read_cached_direct_exposure_snapshot(
            &direct_catalog_snapshot_scratch_, error, &transient)) {
      if (transient) {
        uint32_t remaining = 0;
        if (!deadline_remaining(deadline, &remaining, error)) {
          return fail(error, "camera setting batch did not converge");
        }
        const uint32_t delay =
            remaining < kDirectSettingPollMs ? remaining
                                             : kDirectSettingPollMs;
        if (os::failed(os::nanosleep_ms(delay == 0 ? 1 : delay))) {
          return fail(error, "setting-batch convergence sleep failed");
        }
        continue;
      }
      return false;
    }
    bool matches = true;
    for (unsigned i = 0; i < 3; ++i) {
      const int32_t active = direct_catalog_snapshot_scratch_.active[i];
      const int32_t selected = direct_catalog_snapshot_scratch_.selected[i];
      if (persistent_shell_sdk_ready_) {
        if (direct_catalog_snapshot_scratch_.source_id[i] !=
            direct_catalog_snapshot_certificate_.source_id[i]) {
          return fail(error, "setting source changed during batch convergence");
        }
        const bool active_owned = active == original_settings_[i] ||
                                  active == capture_targets_[i];
        const bool selected_owned = selected == original_settings_[i] ||
                                    selected == capture_targets_[i];
        if (!active_owned || !selected_owned) {
          return fail(error,
                      "camera setting changed externally during convergence");
        }
      }
      if (active != expected[i] || selected != expected[i]) {
        matches = false;
      }
    }
    if (persistent_shell_sdk_ready_) {
      for (unsigned index = 0; index < kSettingCatalogContextCount; ++index) {
        if (direct_catalog_snapshot_scratch_.context_values[index] !=
            direct_catalog_snapshot_certificate_.context_values[index]) {
          return fail(error, "setting context changed during batch convergence");
        }
      }
    }
    if (matches) {
      if (direct_catalog::evaluate(&direct_catalog_snapshot_scratch_,
                                   &direct_catalog_result_scratch_) !=
          direct_catalog::Status::kOk) {
        return fail(error, "settled settings have no valid direct catalog");
      }
      return true;
    }
    uint32_t remaining = 0;
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "camera setting batch did not converge");
    }
    const uint32_t delay = remaining < kDirectSettingPollMs
                               ? remaining
                               : kDirectSettingPollMs;
    if (os::failed(os::nanosleep_ms(delay == 0 ? 1 : delay))) {
      return fail(error, "setting-batch convergence sleep failed");
    }
  }
}

bool CameraController::require_settings_batch(const int32_t expected[3],
                                              Error *error) {
  if (expected == nullptr) return fail(error, "missing expected settings");
  int32_t active[3] = {};
  int32_t controller[3] = {};
  if (!read_setting_snapshot(active, controller, error)) {
    return false;
  }
  for (unsigned i = 0; i < 3; ++i) {
    if (active[i] != expected[i] || controller[i] != expected[i]) {
      return fail(error, "camera setting changed unexpectedly");
    }
  }
  return true;
}

bool CameraController::target_is_legal(unsigned setting_index, Error *error) {
  if (setting_index >= kSettingCatalogCount) {
    return fail(error, "invalid direct setting-legality query");
  }
  if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                  &direct_catalog_result_scratch_, error)) {
    return false;
  }
  if (!direct_catalog_contains(direct_catalog_result_scratch_, setting_index,
                               capture_targets_[setting_index]) ||
      !direct_catalog::target_is_legal(
          &direct_catalog_snapshot_scratch_, setting_index,
          static_cast<int16_t>(capture_targets_[setting_index]))) {
    return fail(error, "capture setting is not currently legal");
  }
  return true;
}

CameraController::DirectSetResult CameraController::direct_set(
    unsigned setting_index, int32_t value, unsigned attempt, Error *error) {
  if (setting_index >= 3) {
    (void)fail(error, "invalid camera setting index");
    return DirectSetResult::kTransportAmbiguous;
  }
  char command[64];
  TextBuffer text(command, sizeof(command));
  text.append("sdk api B01 ");
  text.append_u64(selected_setting_controller_ids_[setting_index]);
  text.append(" 0 ");
  text.append_i64(value);
  if (!text.ok()) {
    (void)fail(error, "could not construct B01 command");
    return DirectSetResult::kTransportAmbiguous;
  }
  if (!shell_command(command, error)) {
    return DirectSetResult::kTransportAmbiguous;
  }
  if (!contains_bytes(shell_output_, shell_output_size_, "API_SUCCESS")) {
    if (classify_b01_terminal_response(shell_output_, shell_output_size_) ==
        B01TerminalResponseClass::kUnresponsive) {
      (void)fail(error, "B01 returned API_ERROR_UNRESPONSIVE");
      return DirectSetResult::kTerminalUnresponsive;
    }
    describe_b01_terminal_response(error, setting_index, attempt, shell_output_,
                                   shell_output_size_);
    return DirectSetResult::kTerminalNoSuccess;
  }
  return DirectSetResult::kSuccess;
}

bool CameraController::wait_for_setting(unsigned setting_index,
                                        int32_t expected,
                                        uint32_t timeout_ms, Error *error) {
  if (setting_index >= kSettingCatalogCount) {
    return fail(error, "invalid camera setting convergence index");
  }
  uint64_t deadline = 0;
  if (!deadline_after(timeout_ms, &deadline, error)) return false;
  for (;;) {
    bool transient = false;
    if (!devmem_state_.read_cached_direct_exposure_snapshot(
            &direct_catalog_snapshot_scratch_, error, &transient)) {
      if (transient) {
        uint32_t remaining = 0;
        if (!deadline_remaining(deadline, &remaining, error)) {
          return fail(error, "camera setting did not converge");
        }
        const uint32_t delay =
            remaining < kDirectSettingPollMs ? remaining
                                             : kDirectSettingPollMs;
        if (os::failed(os::nanosleep_ms(delay == 0 ? 1 : delay))) {
          return fail(error, "camera setting poll sleep failed");
        }
        continue;
      }
      return false;
    }
    if (persistent_shell_sdk_ready_) {
      for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
        if (direct_catalog_snapshot_scratch_.source_id[setting] !=
            direct_catalog_snapshot_certificate_.source_id[setting]) {
          return fail(error, "setting source changed during convergence");
        }
        const int32_t active =
            direct_catalog_snapshot_scratch_.active[setting];
        const int32_t selected =
            direct_catalog_snapshot_scratch_.selected[setting];
        const bool active_owned =
            active == original_settings_[setting] ||
            active == capture_targets_[setting];
        const bool selected_owned =
            selected == original_settings_[setting] ||
            selected == capture_targets_[setting];
        if (!active_owned || !selected_owned) {
          return fail(error,
                      "camera setting changed externally during convergence");
        }
      }
      for (unsigned index = 0; index < kSettingCatalogContextCount; ++index) {
        if (direct_catalog_snapshot_scratch_.context_values[index] !=
            direct_catalog_snapshot_certificate_.context_values[index]) {
          return fail(error, "setting context changed during convergence");
        }
      }
    }
    const int32_t active =
        direct_catalog_snapshot_scratch_.active[setting_index];
    const int32_t selected =
        direct_catalog_snapshot_scratch_.selected[setting_index];
    if (active == expected && selected == expected) {
      const direct_catalog::Status status = direct_catalog::evaluate(
          &direct_catalog_snapshot_scratch_, &direct_catalog_result_scratch_);
      if (status != direct_catalog::Status::kOk) {
        return fail(error,
                    "settled camera setting has no valid direct catalog");
      }
      return true;
    }
    uint32_t remaining = 0;
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "camera setting did not converge");
    }
    const uint32_t delay = remaining < kDirectSettingPollMs
                               ? remaining
                               : kDirectSettingPollMs;
    if (os::failed(os::nanosleep_ms(delay == 0 ? 1 : delay))) {
      return fail(error, "camera setting poll sleep failed");
    }
  }
}

bool CameraController::require_setting(unsigned setting_index,
                                       int32_t expected, Error *error) {
  int32_t active = 0;
  int32_t controller = 0;
  if (!read_pair(setting_index, &active, &controller, error)) return false;
  if (active != expected || controller != expected) {
    return fail(error, "camera setting changed unexpectedly");
  }
  return true;
}

bool CameraController::rollback_after_failure(const Error &primary,
                                              Error *error) {
  release_astro_preposition_ownership();
  // No failed command may retain an armed-generation snapshot. A deferred
  // controller proof remains sticky until restore_settings() releases the
  // no-write setting lease or close() reports that cleanup could not do so.
  persistent_settings_snapshot_valid_ = false;
  if (!setting_write_attempted_) {
    // A prewrite proof can fail after the restoration journal was reserved,
    // but before this operation posted any B01.  Never claim a coincidental
    // dial-to-target value as ours and overwrite it during cleanup.  The
    // reservation is dischargeable only when one full direct snapshot still
    // proves every saved setting exactly original; anything else remains
    // sticky and must be treated as terminal/externally changed.
    bool exact_originals = true;
    if (saved_mask_ != 0 || changed_mask_ != 0) {
      Error cleanup = {};
      exact_originals = read_direct_exposure_state(
          &direct_catalog_snapshot_scratch_, &direct_catalog_result_scratch_,
          &cleanup);
      for (unsigned setting = 0;
           setting < kSettingCatalogCount && exact_originals; ++setting) {
        const uint8_t bit = static_cast<uint8_t>(1u << setting);
        if ((saved_mask_ & bit) == 0 && (changed_mask_ & bit) == 0) continue;
        if (direct_catalog_snapshot_scratch_.active[setting] !=
                original_settings_[setting] ||
            direct_catalog_snapshot_scratch_.selected[setting] !=
                original_settings_[setting]) {
          exact_originals = false;
        }
      }
    }
    if (exact_originals) {
      saved_mask_ = 0;
      changed_mask_ = 0;
      settings_applied_ = false;
      deferred_controller_proof_pending_ = false;
      setting_prewrite_rejection_safe_ = true;
    } else {
      setting_prewrite_rejection_safe_ = false;
    }
    if (error != nullptr) *error = primary;
    return false;
  }
  // Once an SDK on-edge may have posted, exposure restoration is allowed only
  // after the release is causally proven idle.  Preserve saved/changed masks
  // for close() or manual recovery when that proof is ambiguous.
  if (!shutter_settings_restore_blocked_) {
    Error cleanup = {};
    (void)restore_settings(&cleanup);
  }
  if (error != nullptr) *error = primary;
  return false;
}

bool CameraController::complete_deferred_controller_proof(Error *error) {
  if (!deferred_controller_proof_pending_) return true;
  if (!settings_applied_ || saved_mask_ != UINT8_C(0x07) ||
      changed_mask_ != 0) {
    return fail(error, "deferred setting proof has an invalid lease state");
  }

  int32_t active_values[3] = {};
  int32_t controller_values[3] = {};
  if (!read_setting_snapshot(active_values, controller_values, error)) {
    return false;
  }
  for (unsigned i = 0; i < 3; ++i) {
    if (original_settings_[i] != capture_targets_[i] ||
        active_values[i] != capture_targets_[i] ||
        controller_values[i] != capture_targets_[i]) {
      return fail(error, "deferred controller setting changed unexpectedly");
    }
  }
  deferred_controller_proof_pending_ = false;
  return true;
}

bool CameraController::apply_capture_settings(const int16_t targets[3],
                                              Error *error,
                                              bool *catalog_rejected) {
  bool local_rejected = false;
  bool *const rejected =
      catalog_rejected == nullptr ? &local_rejected : catalog_rejected;
  *rejected = false;
  if (persistent_shell_sdk_ready_ &&
      !validate_setting_catalog_targets(targets, rejected, error)) {
    return false;
  }
  if (persistent_shell_sdk_ready_) {
    if (!setting_catalog_mutation_grant_valid_) {
      return fail(error, "catalog mutation grant was not established");
    }
    setting_catalog_mutation_grant_valid_ = false;
  }
  bool safe_rejected = false;
  const bool applied = apply_capture_settings_internal(
      targets, false, rejected, &safe_rejected, error);
  if (!applied && persistent_shell_sdk_ready_ &&
      setting_prewrite_rejection_safe_) {
    *rejected = true;
  }
  return applied;
}

bool CameraController::apply_capture_settings_internal(
    const int16_t targets[3], bool require_idle_match,
    bool *catalog_rejected, bool *safe_rejected, Error *error) {
  clear_error(error);
  post_capture_lens_window_ = false;
  if (targets == nullptr) return fail(error, "missing capture settings");
  if (!shell_ready_) {
    persistent_settings_snapshot_valid_ = false;
    return fail(error, "camera U-shell is not open");
  }
  if (saved_mask_ != 0 || changed_mask_ != 0 || settings_applied_ ||
      deferred_controller_proof_pending_) {
    persistent_settings_snapshot_valid_ = false;
    return fail(error, "camera settings are already saved or applied");
  }
  setting_write_attempted_ = false;
  setting_prewrite_rejection_safe_ = true;

  const bool persistent_generation = persistent_shell_sdk_ready_;
  int32_t armed_values[3] = {};
  if (persistent_generation) {
    if (!persistent_armed_ || !persistent_settings_snapshot_valid_) {
      persistent_settings_snapshot_valid_ = false;
      return fail(error,
                  "persistent capture lacks a coherent armed setting snapshot");
    }
    for (unsigned i = 0; i < 3; ++i) {
      armed_values[i] = persistent_settings_snapshot_[i];
    }
  }

  settings_set_batch_performed_ = false;

  bool all_targets = true;
  bool all_different = true;
  bool matches_armed = persistent_generation;
  int32_t active_values[3] = {};
  bool current_rebound = false;
  for (;;) {
    if (!read_active_batch(active_values, error)) {
      const Error primary = error == nullptr ? Error{} : *error;
      return rollback_after_failure(primary, error);
    }
    if (!persistent_generation) break;
    bool matches_catalog_current = true;
    for (unsigned i = 0; i < 3; ++i) {
      if (active_values[i] != setting_catalog_validated_current_[i]) {
        matches_catalog_current = false;
      }
    }
    if (matches_catalog_current) break;
    if (require_idle_match) {
      if (safe_rejected != nullptr) *safe_rejected = true;
      return fail(error,
                  "camera settings changed immediately before their SET");
    }
    if (current_rebound) {
      invalidate_setting_catalog_certificate();
      if (catalog_rejected != nullptr) *catalog_rejected = true;
      return fail(error,
                  "camera settings kept changing during catalog rebinding");
    }
    bool rebound_rejected = false;
    if (!validate_setting_catalog_targets(targets, &rebound_rejected,
                                          error)) {
      if (rebound_rejected && catalog_rejected != nullptr) {
        *catalog_rejected = true;
      }
      return false;
    }
    if (!setting_catalog_mutation_grant_valid_) {
      return fail(error, "catalog rebind did not establish a mutation grant");
    }
    setting_catalog_mutation_grant_valid_ = false;
    current_rebound = true;
  }
  for (unsigned i = 0; i < 3; ++i) {
    if (active_values[i] != targets[i]) all_targets = false;
    if (active_values[i] == targets[i]) all_different = false;
    if (persistent_generation && active_values[i] != armed_values[i]) {
      matches_armed = false;
    }
  }

  // Do not replace the restoration recipe or consume the armed snapshot
  // until the exact fresh original tuple is bound to the retained catalog.
  // A duplicate/reentrant request and a failed rebind therefore cannot alter
  // the targets used by restore_settings().
  for (unsigned i = 0; i < 3; ++i) capture_targets_[i] = targets[i];
  if (persistent_generation) {
    // CAPTURE consumes the armed snapshot even if any later operation fails.
    // Only a successful rejection refresh or complete local rollover may
    // publish another authoritative tuple.
    persistent_settings_snapshot_valid_ = false;
  }
  const int32_t target_values[3] = {capture_targets_[0], capture_targets_[1],
                                    capture_targets_[2]};

  // Persistent SET is a compare-and-set transaction.  Its outer stock snapshot
  // fences the server generation; this second active read closes the remaining
  // pre-write dial window.  CAPTURE instead rebinds a changed dial tuple to
  // the exact retained native catalog above before accepting it for restore.
  if (require_idle_match && (!persistent_generation || !matches_armed)) {
    if (safe_rejected != nullptr) *safe_rejected = true;
    return fail(error, "camera settings changed immediately before their SET");
  }

  // In the only deferred case, startup/local rollover and this fresh
  // post-START direct snapshot prove the effective and selected values did not
  // move. Claim the
  // no-write lease now and re-prove the complete tuple after release.
  if (persistent_generation && matches_armed && all_targets) {
    for (unsigned i = 0; i < 3; ++i) {
      original_settings_[i] = active_values[i];
      saved_mask_ |= static_cast<uint8_t>(1u << i);
    }
    deferred_controller_proof_pending_ = true;
    settings_applied_ = true;
    return true;
  }

  // Startup/local rollover proved this exact all-three-original tuple
  // coherent, and the fresh post-START direct snapshot above is its
  // compare-before-write fence
  // under the worker's no-concurrent-setting contract.  Setting legality can
  // depend on the current conflict-table state, so the direct evaluator is
  // rerun at each intermediate state immediately before that setting's B01.
  // Claim all three originals before the first mutation, and do not check the
  // next legality proof until the preceding B01 is proved converged.
  if (persistent_generation && matches_armed && all_different) {
    if (!target_is_legal(0, error)) {
      const Error primary = error == nullptr ? Error{} : *error;
      return rollback_after_failure(primary, error);
    }
    for (unsigned i = 0; i < 3; ++i) {
      original_settings_[i] = active_values[i];
      saved_mask_ |= static_cast<uint8_t>(1u << i);
    }
    changed_mask_ = UINT8_C(0x07);
    if (!write_and_observe_setting(0, capture_targets_[0], false, error)) {
      const Error primary = error == nullptr ? Error{} : *error;
      return rollback_after_failure(primary, error);
    }
    for (unsigned i = 1; i < 3; ++i) {
      if (!require_setting(i, original_settings_[i], error) ||
          !target_is_legal(i, error) ||
          !write_and_observe_setting(i, capture_targets_[i], false, error)) {
        const Error primary = error == nullptr ? Error{} : *error;
        return rollback_after_failure(primary, error);
      }
    }
    settings_set_batch_performed_ = true;
    if (!wait_for_settings_batch(target_values, kSettingSettleTimeoutMs,
                                 error)) {
      const Error primary = error == nullptr ? Error{} : *error;
      return rollback_after_failure(primary, error);
    }
    settings_applied_ = true;
    return true;
  }

  // Any armed-snapshot mismatch, every non-target generation, and the legacy
  // one-shot path retain the complete pre-shutter active/selected proof.
  int32_t controller_values[3] = {};
  if (!read_controller_batch(controller_values, error)) {
    const Error primary = error == nullptr ? Error{} : *error;
    return rollback_after_failure(primary, error);
  }
  for (unsigned i = 0; i < 3; ++i) {
    if (active_values[i] != controller_values[i]) {
      const Error primary = {};
      (void)fail(error, "active and controller settings disagree");
      return rollback_after_failure(error == nullptr ? primary : *error,
                                    error);
    }
    original_settings_[i] = active_values[i];
    saved_mask_ |= static_cast<uint8_t>(1u << i);
  }

  // A fresh active/selected tuple proving all three values already equal the
  // recipe requires no B01 write.  In that no-mutation case target legality is
  // irrelevant, so return before the all-target legality snapshot.
  if (all_targets) {
    settings_applied_ = true;
    return true;
  }

  // One complete direct evaluation is fresh for every START.  No B01 write is
  // attempted unless all three targets are present in its compact legal lists.
  if (!targets_are_legal_batch(error)) {
    const Error primary = error == nullptr ? Error{} : *error;
    return rollback_after_failure(primary, error);
  }

  for (unsigned i = 0; i < 3; ++i) {
    if (!require_setting(i, original_settings_[i], error) ||
        !target_is_legal(i, error)) {
      const Error primary = error == nullptr ? Error{} : *error;
      return rollback_after_failure(primary, error);
    }
    if (original_settings_[i] != capture_targets_[i]) {
      changed_mask_ |= static_cast<uint8_t>(1u << i);
      if (!write_and_observe_setting(i, capture_targets_[i], false, error)) {
        const Error primary = error == nullptr ? Error{} : *error;
        return rollback_after_failure(primary, error);
      }
    }
  }

  if (!require_settings_batch(target_values, error)) {
    const Error primary = error == nullptr ? Error{} : *error;
    return rollback_after_failure(primary, error);
  }
  settings_applied_ = true;
  return true;
}

bool CameraController::persistent_settings(int16_t values[3]) const {
  if (values == nullptr || !persistent_shell_sdk_ready_ ||
      !persistent_armed_ || !persistent_settings_snapshot_valid_ ||
      settings_applied_ || saved_mask_ != 0 || changed_mask_ != 0 ||
      deferred_controller_proof_pending_) {
    return false;
  }
  for (unsigned i = 0; i < 3; ++i) {
    const int32_t value = persistent_settings_snapshot_[i];
    if (value < INT16_MIN || value > INT16_MAX) return false;
    values[i] = static_cast<int16_t>(value);
  }
  return true;
}

bool CameraController::refresh_persistent_settings(int16_t values[3],
                                                   Error *error,
                                                   bool *rejected) {
  clear_error(error);
  bool local_rejected = false;
  bool *const semantic_rejection =
      rejected == nullptr ? &local_rejected : rejected;
  *semantic_rejection = false;
  if (values == nullptr) return fail(error, "missing refreshed settings");
  if (!persistent_shell_sdk_ready_ || !persistent_armed_ ||
      settings_applied_ || saved_mask_ != 0 || changed_mask_ != 0 ||
      deferred_controller_proof_pending_ || transfer_input_.active() ||
      picture_transfer_cleanup_unverified_ || shutter_release_pending_ ||
      shutter_recovery_required_ || shutter_settings_restore_blocked_ ||
      persistent_sdk_output_pending_) {
    return fail(error, "camera is not at a query-safe idle boundary");
  }

  const bool had_catalog_evidence = setting_catalog_evidence_valid_;
  direct_catalog::Status direct_status = direct_catalog::Status::kInvalid;
  if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                  &direct_catalog_result_scratch_, error,
                                  &direct_status)) {
    persistent_settings_snapshot_valid_ = false;
    *semantic_rejection = true;
    invalidate_setting_catalog_certificate();
    return false;
  }
  for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
    const int32_t active = direct_catalog_snapshot_scratch_.active[setting];
    if (active < INT16_MIN || active > INT16_MAX ||
        active != direct_catalog_snapshot_scratch_.selected[setting]) {
      persistent_settings_snapshot_valid_ = false;
      *semantic_rejection = true;
      invalidate_setting_catalog_certificate();
      return fail(error, "camera settings are incoherent while querying");
    }
    values[setting] = static_cast<int16_t>(active);
  }
  if (had_catalog_evidence &&
      !direct_catalog_mapping_equal(direct_catalog_result_certificate_,
                                    direct_catalog_result_scratch_)) {
    invalidate_setting_catalog_certificate();
    *semantic_rejection = true;
    return fail(error, "direct exposure mapping changed while querying");
  }
  commit_direct_setting_evidence(direct_catalog_snapshot_scratch_,
                                 direct_catalog_result_scratch_);
  setting_catalog_evidence_valid_ = had_catalog_evidence;
  setting_catalog_mutation_grant_valid_ = false;
  return true;

}

bool CameraController::read_setting_catalog(SettingCatalog *catalog,
                                            bool *rejected, Error *error) {
  clear_error(error);
  if (rejected != nullptr) *rejected = false;
  if (catalog == nullptr || rejected == nullptr) {
    return fail(error, "missing setting-catalog output");
  }
  *catalog = {};
  direct_catalog_public_context_valid_ = false;
  invalidate_setting_catalog_certificate();
  if (!persistent_shell_sdk_ready_ || !persistent_armed_ ||
      settings_applied_ || saved_mask_ != 0 || changed_mask_ != 0 ||
      deferred_controller_proof_pending_ || transfer_input_.active() ||
      picture_transfer_cleanup_unverified_ || shutter_release_pending_ ||
      shutter_recovery_required_ || shutter_settings_restore_blocked_ ||
      persistent_sdk_output_pending_) {
    return fail(error, "camera is not at a catalog-safe idle boundary");
  }

  direct_catalog::Status direct_status = direct_catalog::Status::kInvalid;
  if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                  &direct_catalog_result_scratch_, error,
                                  &direct_status)) {
    *rejected = true;
    return false;
  }
  if (!export_direct_setting_catalog(direct_catalog_snapshot_scratch_,
                                     direct_catalog_result_scratch_, catalog,
                                     error)) {
    return false;
  }
  direct_catalog::extract_catalog_context(&direct_catalog_snapshot_scratch_,
                                          &direct_catalog_public_context_);
  direct_catalog_public_context_valid_ = true;
  commit_direct_setting_evidence(direct_catalog_snapshot_scratch_,
                                 direct_catalog_result_scratch_);
  setting_catalog_evidence_valid_ = true;
  setting_catalog_mutation_grant_valid_ = false;
  return true;

}

bool CameraController::refresh_setting_state_hot(SettingCatalog *catalog,
                                                 LensMetadata *metadata,
                                                 bool *catalog_changed,
                                                 bool *transient,
                                                 Error *error) {
  clear_error(error);
  if (catalog_changed != nullptr) *catalog_changed = false;
  if (transient != nullptr) *transient = false;
  if (catalog == nullptr || metadata == nullptr || catalog_changed == nullptr ||
      transient == nullptr) {
    return fail(error, "missing hot setting-state output");
  }
  *metadata = {};
  if (!persistent_shell_sdk_ready_ || !persistent_armed_ ||
      settings_applied_ || saved_mask_ != 0 || changed_mask_ != 0 ||
      deferred_controller_proof_pending_ || transfer_input_.active() ||
      picture_transfer_cleanup_unverified_ || shutter_release_pending_ ||
      shutter_recovery_required_ || shutter_settings_restore_blocked_ ||
      persistent_sdk_output_pending_) {
    return fail(error, "camera is not at a hot-state idle boundary");
  }

  bool live_transient = false;
  if (!devmem_state_.refresh_direct_exposure_state(
          &direct_catalog_snapshot_scratch_, error, &live_transient)) {
    if (live_transient) {
      *transient = true;
      return false;
    }
    invalidate_setting_catalog_certificate();
    return false;
  }

  // STATE publishes the CameraInfo sample. The concurrently read private
  // controller element may legitimately be from the adjacent dial-update
  // instant; canonicalize it only in this read-only scratch snapshot so the
  // legality checks can test whether the active value itself is constructible.
  for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
    direct_catalog_snapshot_scratch_.selected[setting] =
        direct_catalog_snapshot_scratch_.active[setting];
  }

  direct_catalog::CatalogContext context = {};
  direct_catalog::extract_catalog_context(&direct_catalog_snapshot_scratch_,
                                          &context);
  if (!direct_catalog_public_context_valid_ ||
      !direct_catalog::catalog_context_equal(
          &context, &direct_catalog_public_context_)) {
    const direct_catalog::Status status = direct_catalog::evaluate(
        &direct_catalog_snapshot_scratch_, &direct_catalog_result_scratch_);
    if (status != direct_catalog::Status::kOk ||
        !export_direct_setting_catalog(direct_catalog_snapshot_scratch_,
                                       direct_catalog_result_scratch_, catalog,
                                       error)) {
      *transient = true;
      if (error != nullptr && error->text[0] == '\0')
        (void)fail(error, "hot exposure sample is not publishable");
      return false;
    }
    direct_catalog_public_context_ = context;
    direct_catalog_public_context_valid_ = true;
    *catalog_changed = true;
  } else {
    for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
      const int32_t active = direct_catalog_snapshot_scratch_.active[setting];
      if (active < INT16_MIN || active > INT16_MAX ||
          catalog->value_count[setting] == 0 ||
          catalog->value_count[setting] > kSettingCatalogMaxValues ||
          !direct_catalog::target_is_legal(
              &direct_catalog_snapshot_scratch_, setting,
              static_cast<int16_t>(active))) {
        *transient = true;
        return fail(error, "hot exposure value is not publishable");
      }
      bool found = false;
      for (uint16_t index = 0; index < catalog->value_count[setting];
           ++index) {
        if (catalog->values[setting][index] != active) continue;
        if (found) {
          *transient = true;
          return fail(error, "hot exposure catalog value is ambiguous");
        }
        catalog->current_index[setting] = index;
        found = true;
      }
      if (!found) {
        *transient = true;
        return fail(error, "hot exposure value is absent from its catalog");
      }
    }
    direct_catalog::ApertureProjection aperture = {};
    if (!direct_catalog::project_aperture_bounds(
            &direct_catalog_snapshot_scratch_, &aperture)) {
      *transient = true;
      return fail(error, "hot aperture bounds are not publishable");
    }
    catalog->aperture_min = aperture.minimum_value;
    catalog->aperture_max = aperture.maximum_value;
  }

  // Optics are independent best-effort metadata.  A failed direct snapshot is
  // represented by the already-zero metadata object and must not reject an
  // otherwise complete exposure state.
  Error lens_error = {};
  (void)read_direct_lens_metadata(metadata, &lens_error);
  // Optimistic publication never creates or updates mutation authority.
  // SET/CAPTURE prewrite always acquires a fresh strict A/B snapshot.
  clear_error(error);
  return true;
}

bool CameraController::validate_setting_catalog_targets(
    const int16_t targets[3], bool *rejected, Error *error) {
  if (rejected != nullptr) *rejected = false;
  if (targets == nullptr || rejected == nullptr) {
    return fail(error, "missing catalog-bound setting targets");
  }
  if (!setting_catalog_evidence_valid_) {
    *rejected = true;
    return fail(error, "setting catalog evidence is absent or stale");
  }
  // Preserve the full cached evidence separately from the one-shot mutation
  // grant.  Any stale/transport failure invalidates the evidence; success
  // restores it and creates a grant that the immediately following apply/SET
  // consumes before the first possible write.
  setting_catalog_evidence_valid_ = false;
  setting_catalog_mutation_grant_valid_ = false;

  direct_catalog::Status direct_status = direct_catalog::Status::kInvalid;
  if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                  &direct_catalog_result_scratch_, error,
                                  &direct_status)) {
    *rejected = true;
    return false;
  }
  if (!direct_catalog_mapping_equal(direct_catalog_result_certificate_,
                                    direct_catalog_result_scratch_)) {
    *rejected = true;
    return fail(error, "direct exposure mapping changed before mutation");
  }
  for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
    if (!direct_catalog_contains(direct_catalog_result_scratch_, setting,
                                 targets[setting]) ||
        !direct_catalog::target_is_legal(&direct_catalog_snapshot_scratch_,
                                         setting, targets[setting])) {
      *rejected = true;
      return fail(error, "target is absent from direct exposure catalog");
    }
    const int32_t active = direct_catalog_snapshot_scratch_.active[setting];
    if (active != direct_catalog_snapshot_scratch_.selected[setting] ||
        !direct_catalog_contains(direct_catalog_result_scratch_, setting,
                                 active) ||
        !direct_catalog::target_is_legal(
            &direct_catalog_snapshot_scratch_, setting,
            static_cast<int16_t>(active))) {
      *rejected = true;
      return fail(error, "current direct exposure value is not legal");
    }
    setting_catalog_validated_current_[setting] = active;
  }
  const bool idle_snapshot_valid = persistent_settings_snapshot_valid_;
  const int32_t idle_snapshot[3] = {persistent_settings_snapshot_[0],
                                    persistent_settings_snapshot_[1],
                                    persistent_settings_snapshot_[2]};
  commit_direct_setting_evidence(direct_catalog_snapshot_scratch_,
                                 direct_catalog_result_scratch_);
  for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
    persistent_settings_snapshot_[setting] = idle_snapshot[setting];
  }
  persistent_settings_snapshot_valid_ = idle_snapshot_valid;
  setting_catalog_evidence_valid_ = true;
  setting_catalog_mutation_grant_valid_ = true;
  return true;

}

bool CameraController::validate_catalog_restore_value(unsigned setting_index,
                                                      int32_t value,
                                                      Error *error) {
  if (setting_index >= 3 || value < INT16_MIN || value > INT16_MAX) {
    return fail(error, "invalid catalog-bound restoration value");
  }
  direct_catalog::Status direct_status = direct_catalog::Status::kInvalid;
  if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                  &direct_catalog_result_scratch_, error,
                                  &direct_status)) {
    return false;
  }
  for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
    if (direct_catalog_snapshot_scratch_.source_id[setting] !=
        direct_catalog_snapshot_certificate_.source_id[setting]) {
      return fail(error, "setting source changed before restoration write");
    }
  }
  for (unsigned index = 0; index < kSettingCatalogContextCount; ++index) {
    if (direct_catalog_snapshot_scratch_.context_values[index] !=
        direct_catalog_snapshot_certificate_.context_values[index]) {
      return fail(error, "setting context changed before restoration write");
    }
  }
  if (direct_catalog_snapshot_scratch_.mode_helper !=
          direct_catalog_snapshot_certificate_.mode_helper ||
      !direct_catalog_contains(direct_catalog_result_scratch_, setting_index,
                               value) ||
      !direct_catalog::target_is_legal(
          &direct_catalog_snapshot_scratch_, setting_index,
          static_cast<int16_t>(value))) {
    return fail(error, "restoration value is not currently legal");
  }
  return true;

}

bool CameraController::validate_pinned_setting_write(unsigned setting_index,
                                                     int32_t value,
                                                     bool restoring,
                                                     Error *error) {
  if (!persistent_shell_sdk_ready_) return true;
  if (setting_index >= 3 || value < INT16_MIN || value > INT16_MAX) {
    return fail(error, "invalid pinned setting write");
  }
  direct_catalog::Status direct_status = direct_catalog::Status::kInvalid;
  if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                  &direct_catalog_result_scratch_, error,
                                  &direct_status)) {
    return false;
  }
  for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
    if (direct_catalog_snapshot_scratch_.source_id[setting] !=
        direct_catalog_snapshot_certificate_.source_id[setting]) {
      return fail(error, "setting source changed immediately before write");
    }
    const int32_t expected =
        restoring
            ? (setting > setting_index ? original_settings_[setting]
                                       : capture_targets_[setting])
            : (setting < setting_index ? capture_targets_[setting]
                                       : original_settings_[setting]);
    if (direct_catalog_snapshot_scratch_.active[setting] != expected ||
        direct_catalog_snapshot_scratch_.selected[setting] != expected) {
      return fail(error, "setting state changed immediately before write");
    }
  }
  for (unsigned index = 0; index < kSettingCatalogContextCount; ++index) {
    if (direct_catalog_snapshot_scratch_.context_values[index] !=
        direct_catalog_snapshot_certificate_.context_values[index]) {
      return fail(error, "setting context changed immediately before write");
    }
  }
  if (direct_catalog_snapshot_scratch_.mode_helper !=
          direct_catalog_snapshot_certificate_.mode_helper ||
      !direct_catalog_contains(direct_catalog_result_scratch_, setting_index,
                               value) ||
      !direct_catalog::target_is_legal(
          &direct_catalog_snapshot_scratch_, setting_index,
          static_cast<int16_t>(value))) {
    return fail(error, "setting became illegal immediately before write");
  }
  return true;

}

bool CameraController::set_persistent_settings(const int16_t targets[3],
                                               Error *error,
                                               bool *catalog_rejected,
                                               bool *safe_rejected) {
  clear_error(error);
  bool local_rejected = false;
  bool *const rejected =
      catalog_rejected == nullptr ? &local_rejected : catalog_rejected;
  bool local_safe_rejected = false;
  bool *const safe =
      safe_rejected == nullptr ? &local_safe_rejected : safe_rejected;
  *rejected = false;
  *safe = false;
  if (targets == nullptr) return fail(error, "missing persistent settings");
  if (!persistent_shell_sdk_ready_ || !persistent_armed_ ||
      !persistent_settings_snapshot_valid_ || transfer_input_.active() ||
      picture_transfer_cleanup_unverified_ || shutter_release_pending_ ||
      shutter_recovery_required_ || shutter_settings_restore_blocked_ ||
      persistent_sdk_output_pending_) {
    return fail(error, "camera is not at a settings-safe idle boundary");
  }

  if (!validate_setting_catalog_targets(targets, rejected, error)) {
    return false;
  }
  if (!setting_catalog_mutation_grant_valid_) {
    return fail(error, "catalog mutation grant was not established");
  }
  setting_catalog_mutation_grant_valid_ = false;

  int32_t before_active[3] = {};
  int32_t before_controller[3] = {};
  if (!read_stock_setting_snapshot(before_active, before_controller, error)) {
    *rejected = true;
    return false;
  }
  for (unsigned i = 0; i < 3; ++i) {
    if (before_active[i] != before_controller[i] ||
        before_active[i] != persistent_settings_snapshot_[i]) {
      *safe = true;
      return fail(error,
                  "camera settings changed since their armed snapshot");
    }
  }

  if (!apply_capture_settings_internal(targets, true, rejected, safe, error)) {
    if (setting_prewrite_rejection_safe_ && !*safe) *rejected = true;
    return false;
  }
  if (!complete_deferred_controller_proof(error)) {
    const Error primary = error == nullptr ? Error{} : *error;
    const bool rolled_back = rollback_after_failure(primary, error);
    if (setting_prewrite_rejection_safe_) *rejected = true;
    return rolled_back;
  }

  const int32_t expected[3] = {targets[0], targets[1], targets[2]};
  int32_t active[3] = {};
  int32_t controller[3] = {};
  if (!read_stock_setting_snapshot(active, controller, error)) {
    const Error primary = error == nullptr ? Error{} : *error;
    const bool rolled_back = rollback_after_failure(primary, error);
    if (setting_prewrite_rejection_safe_) *rejected = true;
    return rolled_back;
  }
  for (unsigned i = 0; i < 3; ++i) {
    if (active[i] != expected[i] || controller[i] != expected[i]) {
      const Error primary = {};
      (void)fail(error, "persistent camera-setting readback disagrees");
      return rollback_after_failure(error == nullptr ? primary : *error,
                                    error);
    }
  }

  // This is an intentional user-setting commit, not a capture lease.  Once
  // the complete stock readback is exact, discard the restoration journal and
  // publish a new authoritative idle snapshot.
  for (unsigned i = 0; i < 3; ++i) {
    original_settings_[i] = expected[i];
    persistent_settings_snapshot_[i] = expected[i];
  }
  saved_mask_ = 0;
  changed_mask_ = 0;
  settings_applied_ = false;
  deferred_controller_proof_pending_ = false;
  settings_set_batch_performed_ = false;
  persistent_settings_snapshot_valid_ = true;
  return true;
}

bool CameraController::read_direct_lens_metadata(LensMetadata *metadata,
                                                 Error *error) {
  clear_error(error);
  if (metadata == nullptr) return fail(error, "missing lens metadata output");
  *metadata = {};
  if (!persistent_shell_sdk_ready_ || !persistent_armed_ ||
      !persistent_settings_snapshot_valid_ || settings_applied_ ||
      saved_mask_ != 0 || changed_mask_ != 0 ||
      deferred_controller_proof_pending_ || transfer_input_.active() ||
      picture_transfer_cleanup_unverified_ || shutter_release_pending_ ||
      shutter_recovery_required_ || shutter_settings_restore_blocked_ ||
      persistent_sdk_output_pending_) {
    return fail(error, "direct lens metadata requires a clean idle boundary");
  }
  return read_direct_lens_metadata_impl(metadata, false, error);
}

bool CameraController::read_direct_lens_metadata_impl(LensMetadata *metadata,
                                                      bool stable,
                                                      Error *error) {
  clear_error(error);
  if (metadata == nullptr) return fail(error, "missing lens metadata output");
  *metadata = {};

  DevMemLensSnapshot direct = {};
  if (stable) memory_barrier();
  if (!devmem_state_.read_direct_lens_snapshot(&direct, error)) return false;
  if (stable) {
    // RESULT metadata is authoritative rather than an optimistic WATCH
    // sample.  Two complete equal direct snapshots bracket every dynamic
    // focus/zoom word and its selected calibration row.  This keeps the
    // post-shot association strict without re-entering the U-shell on the
    // ordinary path.
    DevMemLensSnapshot verify = {};
    memory_barrier();
    if (!devmem_state_.read_direct_lens_snapshot(&verify, error)) return false;
    memory_barrier();
    if (!direct_lens_snapshots_equal(direct, verify)) {
      return fail(error, "direct lens state changed during its snapshot");
    }
    direct = verify;
  }
  if (direct.name_length == 0 ||
      direct.name_length > kDevMemLensNameStorage ||
      direct.name_length > sizeof(metadata->lens_name)) {
    return fail(error, "direct lens name exceeds its output bound");
  }
  metadata->lens_name_length = direct.name_length;
  for (uint8_t i = 0; i < direct.name_length; ++i) {
    metadata->lens_name[i] = direct.name[i];
  }
  metadata->lens_name_valid = true;

  // The normal table has no valid macro counterpart in the frozen direct
  // contract.  Identity remains authoritative while focal length is absent.
  if ((direct.private_status & UINT32_C(0x2)) != 0u &&
      direct.macro_is != 0u) {
    return true;
  }

  LensIdentityData identity = {};
  for (unsigned i = 0; i < 6; ++i) identity.id[i] = direct.id[i];
  identity.wide_mm = direct.wide_mm;
  identity.tele_mm = direct.tele_mm;

  LensStateData state = {};
  state.focus_pulse = direct.focus_pulse;
  state.autofocus_reference = direct.autofocus_reference;

  LensCalibrationRow calibration = {};
  calibration.focal_k0 = float_from_bits(direct.calibration_f32_bits[0]);
  calibration.focal_k1 = float_from_bits(direct.calibration_f32_bits[1]);
  calibration.focal_k2 = float_from_bits(direct.calibration_f32_bits[2]);
  calibration.infinity_reference = direct.infinity_reference;

  uint32_t focal_bits = 0;
  if (evaluate_focus_breathed_focal_length(identity, state, calibration,
                                            &focal_bits)) {
    metadata->focal_length_f32_bits = focal_bits;
    metadata->focal_length_valid = true;
  }
  return true;
}

bool CameraController::read_post_capture_lens_metadata(
    LensMetadata *metadata, Error *error) {
  clear_error(error);
  if (metadata == nullptr) return fail(error, "missing lens metadata output");
  *metadata = {};
  if (!post_capture_lens_window_ || !persistent_shell_sdk_ready_ ||
      persistent_armed_ || persistent_settings_snapshot_valid_ ||
      settings_applied_ || saved_mask_ != 0 || changed_mask_ != 0 ||
      deferred_controller_proof_pending_ || transfer_input_.active() ||
      picture_transfer_cleanup_unverified_ || shutter_release_pending_ ||
      !shutter_recovery_required_ || shutter_settings_restore_blocked_ ||
      persistent_sdk_output_pending_ || shell_resync_required_ ||
      direct_shell_capture_recovery_pending_) {
    return fail(error,
                "lens metadata lacks a clean post-capture boundary");
  }
  // The single snapshot is best-effort, but its boundary is one-shot even if
  // a lens diagnostic is unsupported.  The caller proceeds directly to 94DE.
  post_capture_lens_window_ = false;

  // A stable A/B read of the v1.9 lens object preserves the exact
  // focus-breathed FLact evaluator without entering the U-shell.  Optics are
  // optional at the worker boundary, so an unavailable direct snapshot is
  // reported to the caller instead of invoking a second provider.
  return read_direct_lens_metadata_impl(metadata, true, error);
}

bool CameraController::arm_persistent_capture(Error *error) {
  clear_error(error);
  persistent_settings_snapshot_valid_ = false;
  if (!shell_ready_) return fail(error, "camera U-shell is not open");
  if (settings_applied_ || saved_mask_ != 0 || changed_mask_ != 0 ||
      deferred_controller_proof_pending_) {
    return fail(error, "persistent startup requires unowned camera settings");
  }
  // arm_persistent_capture() is the initial entry into a warm worker.  Once
  // its sole shell-SDK route is initialized, only local rollover may publish
  // another generation.
  if (persistent_armed_ || persistent_shell_sdk_ready_) {
    return fail(error, "persistent capture is already initialized");
  }
  if (transfer_input_.active() || picture_transfer_cleanup_unverified_ ||
      shutter_release_pending_ || shutter_recovery_required_ ||
      shutter_settings_restore_blocked_ ||
      persistent_sdk_output_pending_) {
    return fail(error, "camera has an outstanding capture obligation");
  }

  if (!require_station_baseline(error) ||
      !read_transfer_snapshot(kPtpTimeoutMs, transfer_before_, error) ||
      !read_transfer_snapshot(kPtpTimeoutMs, transfer_after_, error)) {
    return false;
  }
  for (unsigned slot = 0; slot < 2; ++slot) {
    if (!transfer_lists_equal(transfer_before_[slot], transfer_after_[slot])) {
      return fail(error, "94BB transfer lists changed while arming capture");
    }
  }
  qualify_direct_transfer_records();
  direct_catalog::Status direct_status = direct_catalog::Status::kInvalid;
  if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                  &direct_catalog_result_scratch_, error,
                                  &direct_status)) {
    return false;
  }
  int32_t active_values[3] = {};
  for (unsigned i = 0; i < 3; ++i) {
    if (direct_catalog_snapshot_scratch_.active[i] !=
        direct_catalog_snapshot_scratch_.selected[i]) {
      return fail(error, "camera settings are changing while arming capture");
    }
    active_values[i] = direct_catalog_snapshot_scratch_.active[i];
  }
  commit_direct_setting_evidence(direct_catalog_snapshot_scratch_,
                                 direct_catalog_result_scratch_);
  setting_catalog_evidence_valid_ = false;
  setting_catalog_mutation_grant_valid_ = false;
  if (!refresh_persistent_transfer_slot_hint(error)) return false;
  // The fixed-256 worker has one shutter route: four camera-local SDK-event
  // posts.  Prove the event subsystem, converter, and trigger are stably idle
  // before publishing its startup snapshot; no handler/auth/mode gate is
  // required.
  if (!require_shutter_preflight(error) || !require_station_baseline(error)) {
    return false;
  }
  for (unsigned i = 0; i < 3; ++i) {
    persistent_settings_snapshot_[i] = active_values[i];
  }
  persistent_shell_sdk_ready_ = true;
  persistent_settings_snapshot_valid_ = true;
  persistent_armed_ = true;
  return true;
}

bool CameraController::discover_direct_persistent_capture(
    uint64_t deadline, CaptureInfo *capture, bool *fallback, Error *error) {
  if (capture == nullptr || fallback == nullptr) {
    return fail(error, "missing direct discovery state");
  }
  *fallback = false;
  uint32_t count_probes_since_snapshot = 0;
  uint32_t transient_snapshot_retries = 0;

  for (;;) {
    uint32_t remaining = 0;
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for a new .ORF transfer record");
    }
    // Descriptor+16 is written through the RTOS cached 0x62ce alias.  Even
    // though stock updates it after the corresponding record mutation under
    // the DCF mutex, Linux may keep seeing an old value.  It is strictly an
    // early-wake hint: an unchanged pair never suppresses the bounded exact
    // audit, which also catches an equal-count remove/add ABA.  Every accepted
    // record still passes the stable list and retained stock-94DD fence below.
    Error direct_error = {};
    DevMemTransferCounts observed_counts = {};
    if (!devmem_state_.read_transfer_counts(&observed_counts,
                                            &direct_error)) {
      direct_transfer_records_qualified_ = false;
      *fallback = true;
      return true;
    }
    const bool count_changed =
        observed_counts.occupied[0] != transfer_counts_.occupied[0] ||
        observed_counts.occupied[1] != transfer_counts_.occupied[1];
    if (count_probes_since_snapshot != UINT32_MAX) {
      ++count_probes_since_snapshot;
    }
    const bool periodic_audit =
        count_probes_since_snapshot >= kPersistentDirectAuditPollInterval;
    if (!count_changed && !periodic_audit) {
      if (!deadline_remaining(deadline, &remaining, error)) {
        return fail(error, "timed out waiting for a new .ORF transfer record");
      }
      uint32_t delay = remaining < kPersistentDirectTransferPollMs
                           ? remaining
                           : kPersistentDirectTransferPollMs;
      if (delay == 0) delay = 1;
      if (!sleep_transfer_poll(delay, error)) return false;
      continue;
    }

    if (count_changed) {
      if (!deadline_remaining(deadline, &remaining, error)) {
        return fail(error,
                    "timed out waiting for a new .ORF transfer record");
      }
      uint32_t settle_delay = remaining < kPersistentDirectTransferPollMs
                                  ? remaining
                                  : kPersistentDirectTransferPollMs;
      if (settle_delay == 0) settle_delay = 1;
      if (!sleep_transfer_poll(settle_delay, error)) {
        return false;
      }
      DevMemTransferCounts settled_counts = {};
      direct_error = {};
      if (!devmem_state_.read_transfer_counts(&settled_counts,
                                              &direct_error)) {
        direct_transfer_records_qualified_ = false;
        *fallback = true;
        return true;
      }
      if (settled_counts.occupied[0] != observed_counts.occupied[0] ||
          settled_counts.occupied[1] != observed_counts.occupied[1]) {
        continue;
      }
    }

    // The stable list compares two complete uncached occupancy scans and
    // exactly rereads every occupied record, independently of whether the
    // cached descriptor hint advances.  Any descriptor or byte instability
    // remains unaccepted.  A typed publication race gets a bounded retry;
    // descriptor/layout failures demote immediately to proven PTP discovery.
    DevMemTransferCounts snapshot_counts = {};
    const DevMemTransferListStatus snapshot_status =
        devmem_state_.read_stable_transfer_lists(
            transfer_after_, &snapshot_counts, &direct_error);
    if (snapshot_status != DevMemTransferListStatus::kStable) {
      if (snapshot_status ==
              DevMemTransferListStatus::kChangedDuringRead &&
          transient_snapshot_retries <
              kPersistentDirectTransientRetryLimit) {
        ++transient_snapshot_retries;
        // Force the next loop to perform another full two-pass scan even if
        // the cacheable occupied-count hint has not caught up.  The partial
        // failed snapshot is never compared, folded, or accepted.
        count_probes_since_snapshot =
            kPersistentDirectAuditPollInterval;
        if (!deadline_remaining(deadline, &remaining, error)) {
          return fail(error,
                      "timed out waiting for a new .ORF transfer record");
        }
        uint32_t retry_delay = remaining < kPersistentDirectTransferPollMs
                                   ? remaining
                                   : kPersistentDirectTransferPollMs;
        if (retry_delay == 0) retry_delay = 1;
        if (!sleep_transfer_poll(retry_delay, error)) {
          return false;
        }
        continue;
      }
      direct_transfer_records_qualified_ = false;
      *fallback = true;
      return true;
    }
    count_probes_since_snapshot = 0;
    transient_snapshot_retries = 0;

    uint32_t matching_slot = 0;
    uint32_t matching_id = 0;
    DevMemTransferRecord matching_record = {};
    unsigned matching_orfs = 0;
    for (unsigned slot_index = 0; slot_index < 2; ++slot_index) {
      const TransferIdList &before = transfer_before_[slot_index];
      const TransferIdList &after = transfer_after_[slot_index];
      for (uint32_t i = 0; i < before.count; ++i) {
        if (!transfer_list_contains(after, before.ids[i])) {
          return fail(error,
                      "a pre-capture direct transfer ID disappeared");
        }
      }
      for (uint32_t i = 0; i < after.count; ++i) {
        const uint32_t transfer_id = after.ids[i];
        if (transfer_list_contains(before, transfer_id)) continue;

        DevMemTransferRecord first = {};
        DevMemTransferRecord second = {};
        direct_error = {};
        if (!devmem_state_.read_transfer_record(
                slot_index + 1u, transfer_id, &first, &direct_error) ||
            !devmem_state_.read_transfer_record(
                slot_index + 1u, transfer_id, &second, &direct_error) ||
            !devmem_transfer_record_equal(first, second) ||
            !valid_direct_transfer_record(second)) {
          // Never accept while any new occupied record is unstable or
          // noncanonical: it could still become a second ORF.  Stock PTP is
          // the safe recovery path for this coherent direct-read mismatch.
          direct_transfer_records_qualified_ = false;
          *fallback = true;
          return true;
        }
        if (second.leaf[9] != 'O' || second.leaf[10] != 'R' ||
            second.leaf[11] != 'F') {
          continue;
        }
        ++matching_orfs;
        matching_slot = slot_index + 1u;
        matching_id = transfer_id;
        matching_record = second;
      }
    }

    if (matching_orfs > 1) {
      return fail(error, "capture produced multiple new .ORF transfer records");
    }
    if (matching_orfs == 1) {
      char ptp_leaf[13] = {};
      uint64_t ptp_size = 0;
      if (!read_transfer_record_metadata(
              matching_slot, matching_id, kPtpTimeoutMs, ptp_leaf,
              &ptp_size, error)) {
        return false;
      }
      bool metadata_equal = ptp_size == matching_record.file_size;
      for (unsigned byte = 0; byte < 13; ++byte) {
        metadata_equal = metadata_equal &&
                         ptp_leaf[byte] == matching_record.leaf[byte];
      }
      if (!metadata_equal) {
        // 94DD took the same stock mutex used by 94BB and observed a different
        // record.  The command stream is healthy, but direct memory is no
        // longer qualified for correlation; rescan through stock 94BB.
        direct_transfer_records_qualified_ = false;
        *fallback = true;
        return true;
      }
      capture->slot = matching_slot;
      capture->transfer_id = matching_id;
      capture->file_size = ptp_size;
      for (unsigned byte = 0; byte < 13; ++byte) {
        capture->leaf[byte] = ptp_leaf[byte];
      }
      capture->metadata_valid = true;
      return true;
    }

    // Under the worker's explicit exclusive/no-concurrent-trigger contract,
    // every stable new non-ORF belongs to this generation and can be folded
    // while waiting for its companion ORF without any PTP metadata request.
    for (unsigned slot = 0; slot < 2; ++slot) {
      transfer_before_[slot] = transfer_after_[slot];
    }
    transfer_counts_ = snapshot_counts;

    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for a new .ORF transfer record");
    }
    uint32_t delay = remaining < kPersistentDirectTransferPollMs
                         ? remaining
                         : kPersistentDirectTransferPollMs;
    if (delay == 0) delay = 1;
    if (!sleep_transfer_poll(delay, error)) return false;
  }
}

bool CameraController::discover_ptp_persistent_capture(
    uint64_t deadline, CaptureInfo *capture, Error *error) {
  if (capture == nullptr) return fail(error, "missing capture result");

  const unsigned first_slot =
      persistent_transfer_slot_hint_ == 2 ? 1u : 0u;
  const unsigned other_slot = 1u - first_slot;
  uint32_t polls = 0;

  for (;;) {
    // A logical poll that begins before the outer deadline completes every
    // required 94BB/94DD with the normal per-operation timeout.  This may
    // softly overrun the outer deadline, but never poisons the retained socket
    // with a few-millisecond transaction budget.
    uint32_t remaining = 0;
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for a new .ORF transfer record");
    }
    ++polls;
    bool queried[2] = {};

    if (!read_untransferred_list(
            first_slot + 1u, kPtpTimeoutMs, &transfer_after_[first_slot],
            error)) {
      return false;
    }
    queried[first_slot] = true;
    const bool first_changed = !transfer_lists_equal(
        transfer_before_[first_slot], transfer_after_[first_slot]);

    bool query_other =
        persistent_transfer_slot_hint_ == 0 || first_changed ||
        polls % kInactiveTransferSlotPollInterval == 0;
    if (!query_other) {
      // If the hinted 94BB consumed the remaining outer budget, the inactive
      // slot still gets one final fixed-time check before timeout.  A bounded
      // final window absorbs scheduler/sleep jitter and keeps checking both
      // slots through the last five nominal 20 ms backoffs.
      Error deadline_probe = {};
      uint32_t after_first_remaining = 0;
      if (!deadline_remaining(deadline, &after_first_remaining,
                              &deadline_probe) ||
          after_first_remaining <=
              kPersistentTransferRecordFinalWindowMs) {
        query_other = true;
      }
    }

    if (query_other) {
      if (!read_untransferred_list(
              other_slot + 1u, kPtpTimeoutMs, &transfer_after_[other_slot],
              error)) {
        return false;
      }
      queried[other_slot] = true;
      const bool other_changed = !transfer_lists_equal(
          transfer_before_[other_slot], transfer_after_[other_slot]);
      if (!first_changed && other_changed) {
        // The periodically/finally checked slot supplied the first delta.  Its
        // opposite was unchanged on the first read, so refreshing that list
        // provides the same after-delta final fence as the normal hinted path.
        // The later baseline-subset check also proves this reread did not lose
        // an ID that was present in the equal first snapshot.
        if (!read_untransferred_list(
                first_slot + 1u, kPtpTimeoutMs,
                &transfer_after_[first_slot], error)) {
          return false;
        }
      }
    }

    uint32_t matching_slot = 0;
    uint32_t matching_id = 0;
    uint64_t matching_size = 0;
    char matching_leaf[13] = {};
    unsigned matching_orfs = 0;
    for (unsigned slot_index = 0; slot_index < 2; ++slot_index) {
      if (!queried[slot_index]) continue;
      const TransferIdList &before = transfer_before_[slot_index];
      const TransferIdList &after = transfer_after_[slot_index];
      for (uint32_t i = 0; i < before.count; ++i) {
        if (!transfer_list_contains(after, before.ids[i])) {
          return fail(error, "a pre-capture 94BB transfer ID disappeared");
        }
      }
      for (uint32_t i = 0; i < after.count; ++i) {
        const uint32_t transfer_id = after.ids[i];
        if (transfer_list_contains(before, transfer_id)) continue;
        char leaf[13] = {};
        uint64_t file_size = 0;
        if (!read_transfer_record_metadata(
                slot_index + 1u, transfer_id, kPtpTimeoutMs, leaf,
                &file_size, error)) {
          return false;
        }
        // 94DD has already validated the complete uppercase DCF leaf and
        // nonzero length.  RAW+JPEG produces two records, so every valid
        // non-ORF extension is deliberately ignored rather than rejected.
        if (leaf[9] != 'O' || leaf[10] != 'R' || leaf[11] != 'F') continue;
        ++matching_orfs;
        matching_slot = slot_index + 1u;
        matching_id = transfer_id;
        matching_size = file_size;
        for (unsigned byte = 0; byte < 13; ++byte) {
          matching_leaf[byte] = leaf[byte];
        }
      }
    }

    if (matching_orfs > 1) {
      return fail(error, "capture produced multiple new .ORF transfer records");
    }
    if (matching_orfs == 1) {
      if (!queried[0] || !queried[1]) {
        return fail(error,
                    "capture ORF lacks a final two-slot 94BB check");
      }
      capture->slot = matching_slot;
      capture->transfer_id = matching_id;
      capture->file_size = matching_size;
      for (unsigned byte = 0; byte < 13; ++byte) {
        capture->leaf[byte] = matching_leaf[byte];
      }
      capture->metadata_valid = true;
      return true;
    }

    // Avoid repeating 94DD for an earlier companion JPEG while waiting for
    // its ORF.  With the caller's explicit exclusive/no-concurrent-trigger
    // contract, every new non-ORF in this generation is safe to fold into the
    // next poll's baseline.
    for (unsigned slot_index = 0; slot_index < 2; ++slot_index) {
      if (queried[slot_index]) {
        transfer_before_[slot_index] = transfer_after_[slot_index];
      }
    }

    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for a new .ORF transfer record");
    }
    uint32_t delay = remaining < kPersistentTransferRecordPollMs
                         ? remaining
                         : kPersistentTransferRecordPollMs;
    if (delay == 0) delay = 1;
    if (!sleep_transfer_poll(delay, error)) return false;
  }
}

bool CameraController::discover_persistent_capture(uint32_t timeout_ms,
                                                   CaptureInfo *capture,
                                                   Error *error) {
  if (capture == nullptr) return fail(error, "missing capture result");
  uint64_t deadline = 0;
  if (!deadline_after(timeout_ms, &deadline, error)) return false;

  if (direct_transfer_records_qualified_) {
    bool fallback = false;
    if (!discover_direct_persistent_capture(deadline, capture, &fallback,
                                            error)) {
      return false;
    }
    if (!fallback) return true;
  }
  return discover_ptp_persistent_capture(deadline, capture, error);
}

bool CameraController::take_persistent_photo(uint32_t timeout_ms,
                                             CaptureInfo *capture,
                                             Error *error,
                                             bool preposition_required) {
  clear_error(error);
  post_capture_lens_window_ = false;
  if (!shell_ready_ || !settings_applied_) {
    persistent_armed_ = false;
    return fail(error,
                "persistent capture requires an active target-setting lease");
  }
  if (!persistent_shell_sdk_ready_ || !persistent_armed_) {
    persistent_armed_ = false;
    (void)fail(error, "persistent capture generation is not armed");
    const Error primary = error == nullptr ? Error{} : *error;
    return rollback_after_failure(primary, error);
  }
  if (capture == nullptr) {
    persistent_armed_ = false;
    (void)fail(error, "missing capture result");
    const Error primary = error == nullptr ? Error{} : *error;
    return rollback_after_failure(primary, error);
  }
  capture->slot = 0;
  capture->transfer_id = 0;
  capture->leaf[0] = '\0';
  capture->file_size = 0;
  capture->metadata_valid = false;

  if (transfer_input_.active() || picture_transfer_cleanup_unverified_ ||
      shutter_release_pending_ || shutter_recovery_required_ ||
      shutter_settings_restore_blocked_ ||
      persistent_sdk_output_pending_) {
    persistent_armed_ = false;
    (void)fail(error, "camera has an outstanding capture obligation");
    const Error primary = error == nullptr ? Error{} : *error;
    return rollback_after_failure(primary, error);
  }

  // Consume this armed generation before the first operation that can reach
  // the camera.  No ambiguous 902B result permits another press.
  persistent_armed_ = false;
  if (!run_shell_sdk_shutter_cycle(error, preposition_required)) {
    const Error primary = error == nullptr ? Error{} : *error;
    return rollback_after_failure(primary, error);
  }
  if (!complete_deferred_controller_proof(error)) {
    const Error primary = error == nullptr ? Error{} : *error;
    return rollback_after_failure(primary, error);
  }

  if (!discover_persistent_capture(timeout_ms, capture, error)) {
    const Error primary = error == nullptr ? Error{} : *error;
    return rollback_after_failure(primary, error);
  }
  release_astro_preposition_ownership();
  // Restore only after a uniquely correlated record and terminal 94DD proof.
  // Reverse B01/readback begins behind a fresh direct ownership fence,
  // converges one setting at a time, and finishes with a complete original
  // tuple proof before 94DE or RESULT publication.
  if (!restore_settings(error)) return false;

  // A completed single-shot backend release plus a uniquely correlated ORF is
  // enough to proceed under the worker's exclusive/no-concurrent-trigger
  // contract.  Keep the release-verification obligation sticky through 94DE:
  // only local rollover or close() may clear it after proving converter and
  // TrigState are stably idle.
  post_capture_lens_window_ = true;
  return true;
}

bool CameraController::take_photo(uint32_t timeout_ms, CaptureInfo *capture,
                                  Error *error) {
  clear_error(error);
  if (!shell_ready_ || !settings_applied_) {
    return fail(error, "capture settings have not been applied");
  }
  if (persistent_shell_sdk_ready_ || persistent_armed_) {
    return fail(error, "persistent capture requires its dedicated fast path");
  }
  if (capture == nullptr) return fail(error, "missing capture result");
  capture->slot = 0;
  capture->transfer_id = 0;
  capture->leaf[0] = '\0';
  capture->file_size = 0;
  capture->metadata_valid = false;

  const int32_t targets[3] = {capture_targets_[0], capture_targets_[1],
                              capture_targets_[2]};
  if (!require_settings_batch(targets, error)) return false;

  if (!require_station_baseline(error)) return false;
  if (!read_transfer_snapshot(kPtpTimeoutMs, transfer_before_, error) ||
      !read_transfer_snapshot(kPtpTimeoutMs, transfer_after_, error)) {
    return false;
  }
  for (unsigned slot = 0; slot < 2; ++slot) {
    if (!transfer_lists_equal(transfer_before_[slot], transfer_after_[slot])) {
      return fail(error, "94BB transfer lists changed before capture");
    }
  }
  // The final station guard is deliberately adjacent to the shutter
  // preflight: the snapshots and every U-shell read must leave the camera in
  // the exact no-PC baseline before any single-shot edge is owned.
  if (!require_shutter_preflight(error) || !require_station_baseline(error)) {
    return false;
  }
  // Own both release edges before first-stage-on because a lost shell reply
  // does not prove that the asynchronous event failed to queue.  Each edge is
  // sent at most once and its downstream TrigState transition is observed
  // before the next edge is issued.
  shutter_release_pending_ = true;
  bool sequence_ok = true;
  Error first = {};
  Error local = {};
  retain_first_error(post_shutter_edge(kBleFirstStageOn, 0,
                                       kShutterFirstStageState, &local), local,
                     &sequence_ok, &first);
  local = {};
  retain_first_error(post_shutter_edge(kBleSecondStageOn,
                                       kShutterFirstStageState,
                                       kShutterBothStagesState, &local), local,
                     &sequence_ok, &first);
  // Immediate U-shell reads remove the incidental per-command delay that the
  // validated path previously relied on.  Preserve a deliberate full-press
  // dwell before the owed release, including when the second press reply was
  // ambiguous and the edge may already have reached the camera.
  local = {};
  const bool dwell_ok = !os::failed(os::nanosleep_ms(kShutterPressDwellMs));
  if (!dwell_ok) (void)fail(&local, "shutter press dwell failed");
  retain_first_error(dwell_ok, local, &sequence_ok, &first);
  // Once either press edge may have been queued, both release edges are owed.
  // Attempt both exactly once even if an earlier command became ambiguous.
  local = {};
  retain_first_error(release_shutter(&local), local, &sequence_ok, &first);
  if (!sequence_ok) {
    if (error != nullptr) *error = first;
    return false;
  }
  uint64_t deadline = 0;
  if (!deadline_after(timeout_ms, &deadline, error)) return false;
  for (;;) {
    // The outer capture deadline is checked only between fully framed command
    // operations.  Giving a new 94BB/94DD the last few milliseconds would
    // turn an ordinary no-record deadline into a command-socket failure and
    // could prevent the settings rollback that follows this method.
    uint32_t remaining = 0;
    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for a new .ORF transfer record");
    }
    for (uint32_t slot = 1; slot <= 2; ++slot) {
      if (!read_untransferred_list(
              slot, kPtpTimeoutMs, &transfer_after_[slot - 1u], error)) {
        return false;
      }
    }

    uint32_t matching_slot = 0;
    uint32_t matching_id = 0;
    unsigned matching_orfs = 0;
    for (unsigned slot_index = 0; slot_index < 2; ++slot_index) {
      const TransferIdList &before = transfer_before_[slot_index];
      const TransferIdList &after = transfer_after_[slot_index];
      for (uint32_t i = 0; i < before.count; ++i) {
        if (!transfer_list_contains(after, before.ids[i])) {
          return fail(error, "a pre-capture 94BB transfer ID disappeared");
        }
      }
      for (uint32_t i = 0; i < after.count; ++i) {
        const uint32_t transfer_id = after.ids[i];
        if (transfer_list_contains(before, transfer_id)) continue;
        char leaf[13] = {};
        uint64_t file_size = 0;
        if (!deadline_remaining(deadline, &remaining, error) ||
            !read_transfer_record_metadata(
                slot_index + 1u, transfer_id, kPtpTimeoutMs, leaf,
                &file_size, error)) {
          return false;
        }
        if (leaf[9] == 'O' && leaf[10] == 'R' && leaf[11] == 'F') {
          ++matching_orfs;
          matching_slot = slot_index + 1u;
          matching_id = transfer_id;
        }
      }
    }
    if (matching_orfs > 1) {
      return fail(error, "capture produced multiple new .ORF transfer records");
    }
    if (matching_orfs == 1) {
      if (!require_station_baseline(error)) return false;
      capture->slot = matching_slot;
      capture->transfer_id = matching_id;
      return true;
    }

    if (!deadline_remaining(deadline, &remaining, error)) {
      return fail(error, "timed out waiting for a new .ORF transfer record");
    }
    uint32_t delay = remaining < kTransferRecordPollMs
                         ? remaining
                         : kTransferRecordPollMs;
    if (delay == 0) delay = 1;
    if (!sleep_transfer_poll(delay, error)) return false;
  }
}

bool CameraController::read_transfer_metadata(CaptureInfo *capture,
                                              Error *error) {
  clear_error(error);
  if (!shell_ready_) return fail(error, "camera PTP session is not open");
  if (capture == nullptr) return fail(error, "missing capture result");
  capture->metadata_valid = false;
  capture->leaf[0] = '\0';
  capture->file_size = 0;
  if ((capture->slot != 1 && capture->slot != 2) ||
      capture->transfer_id == 0) {
    return fail(error, "missing capture slot or transfer identifier");
  }
  if (!require_station_baseline(error)) return false;
  if (!read_transfer_record_metadata(
          capture->slot, capture->transfer_id, kPtpTimeoutMs, capture->leaf,
          &capture->file_size, error)) {
    return false;
  }
  if (capture->leaf[9] != 'O' || capture->leaf[10] != 'R' ||
      capture->leaf[11] != 'F') {
    capture->leaf[0] = '\0';
    capture->file_size = 0;
    return fail(error, "PTP 94DD metadata is not an uppercase .ORF leaf");
  }
  capture->metadata_valid = true;
  return true;
}

bool CameraController::open_transfer_input(const CaptureInfo &capture,
                                           uint32_t timeout_ms,
                                           InputStream *input,
                                           Error *error) {
  clear_error(error);
  if (!shell_ready_) return fail(error, "camera PTP session is not open");
  if (persistent_shell_sdk_ready_ || persistent_armed_) {
    return fail(error, "persistent capture requires its dedicated 94DE path");
  }
  if (!capture.metadata_valid || !valid_capture_leaf(capture)) {
    return fail(error, "capture metadata is incomplete or invalid");
  }
  if (changed_mask_ != 0 || settings_applied_) {
    return fail(error, "restore camera settings before opening 94DE input");
  }
  if (!require_station_baseline(error)) return false;
  // 94DE may synchronously set pic_trans_state before any transport failure is
  // observable here.  Claim an unverified-cleanup obligation before sending;
  // only a complete response followed by an exact A-state observation clears
  // it.  In particular, closing an incomplete stream is not proof of cleanup.
  picture_transfer_cleanup_unverified_ = true;
  return ptp_.open_transfer_input(capture.transfer_id, capture.file_size,
                                  timeout_ms, &transfer_input_, input, error);
}

bool CameraController::open_persistent_transfer_input(
    const CaptureInfo &capture, uint32_t timeout_ms, InputStream *input,
    Error *error) {
  clear_error(error);
  if (!shell_ready_ || !persistent_shell_sdk_ready_) {
    return fail(error, "persistent camera session is not open");
  }
  if (settings_applied_ || saved_mask_ != 0 || changed_mask_ != 0 ||
      deferred_controller_proof_pending_) {
    return fail(error, "restore camera settings before persistent 94DE input");
  }
  if (persistent_armed_ || transfer_input_.active() ||
      transfer_input_.complete() || picture_transfer_cleanup_unverified_ ||
      shutter_release_pending_ || !shutter_recovery_required_) {
    return fail(error, "persistent capture is not ready for 94DE input");
  }
  if (!capture.metadata_valid || !valid_capture_leaf(capture) ||
      !transfer_id_is_canonical(capture.slot, capture.transfer_id)) {
    return fail(error, "capture metadata is incomplete or invalid");
  }
  const unsigned slot_index = capture.slot - 1u;
  if (transfer_list_contains(transfer_before_[slot_index],
                             capture.transfer_id) ||
      !transfer_list_contains(transfer_after_[slot_index],
                              capture.transfer_id)) {
    return fail(error, "capture metadata is not from the armed generation");
  }

  // 94DD was validated and cached by take_persistent_photo().  Claim cleanup
  // before the one 94DE request, but deliberately perform no setting, station,
  // or duplicate-metadata round trip on this timed path.
  post_capture_lens_window_ = false;
  picture_transfer_cleanup_unverified_ = true;
  return ptp_.open_transfer_input(capture.transfer_id, capture.file_size,
                                  timeout_ms, &transfer_input_, input, error);
}

bool CameraController::rollover_persistent_capture(Error *error) {
  clear_error(error);
  post_capture_lens_window_ = false;
  // A failed rollover can never leave its prior certificate reusable.
  persistent_settings_snapshot_valid_ = false;
  if (!shell_ready_ || !persistent_shell_sdk_ready_ || persistent_armed_) {
    return fail(error, "persistent camera session cannot roll over");
  }
  if (settings_applied_ || saved_mask_ != 0 || changed_mask_ != 0 ||
      deferred_controller_proof_pending_ ||
      shutter_settings_restore_blocked_) {
    return fail(error, "persistent rollover requires restored camera settings");
  }
  if (!transfer_input_.complete()) {
    return fail(error,
                "completed persistent 94DE input is required to roll over");
  }
  if (shutter_release_pending_) {
    return fail(error, "persistent shutter release remains outstanding");
  }
  if (!picture_transfer_cleanup_unverified_ ||
      !shutter_recovery_required_) {
    return fail(error, "completed capture lacks local rollover obligations");
  }

  // EndData/Response has released the PTP command lease, but neither the four
  // asynchronous shutter commands nor the camera's pic_trans_state cleanup
  // timer are thereby proved complete.  Fence the transcript, wait for the
  // authoritative no-PC station tuple, and consume the completed input lease
  // before considering another generation.
  if (!drain_persistent_sdk_output(kPersistentSdkDrainTimeoutMs, error) ||
      !cancel_transfer_input(error)) {
    return false;
  }
  if (picture_transfer_cleanup_unverified_) {
    return fail(error, "picture-transfer cleanup remains unverified");
  }
  transfer_input_.initialize();

  // Re-prove shutter idle and the complete restored setting tuple after the
  // transfer timer reaches baseline.  No rollover failure clears the shutter
  // recovery obligation, so close() can repeat this read-only proof before it
  // decides whether any setting restoration is safe.
  int32_t rollover_active[3] = {};
  int32_t rollover_controller[3] = {};
  if (!require_shutter_preflight(error) ||
      !read_stock_setting_snapshot(rollover_active, rollover_controller,
                                   error)) {
    return false;
  }
  for (unsigned i = 0; i < 3; ++i) {
    if (rollover_active[i] != original_settings_[i] ||
        rollover_controller[i] != original_settings_[i]) {
      return fail(error, "camera settings changed while rolling over capture");
    }
  }

  // Only two equal post-cleanup authoritative 94BB snapshots may become the
  // next generation's baseline.  Refresh the optional media-slot hint and
  // direct-record qualification from that same strict boundary.
  if (!refresh_persistent_transfer_slot_hint(error) ||
      !read_transfer_snapshot(kPtpTimeoutMs, transfer_before_, error) ||
      !read_transfer_snapshot(kPtpTimeoutMs, transfer_after_, error)) {
    return false;
  }
  for (unsigned slot = 0; slot < 2; ++slot) {
    if (!transfer_lists_equal(transfer_before_[slot], transfer_after_[slot])) {
      return fail(error,
                  "94BB transfer lists changed while rolling over capture");
    }
  }
  qualify_direct_transfer_records();

  // Reset the retained U-shell/direct-ring cursor at every accepted
  // generation boundary.  A healthy shadow mismatch merely demotes to stock
  // reads; an authoritative transport failure prevents rearm.
  if (direct_shell_capture_qualified_ &&
      !qualify_direct_shell_capture(true, error)) {
    return false;
  }

  shutter_recovery_required_ = false;
  shutter_settings_restore_blocked_ = false;

  for (unsigned i = 0; i < 3; ++i) {
    persistent_settings_snapshot_[i] = original_settings_[i];
  }
  persistent_settings_snapshot_valid_ = true;
  persistent_armed_ = true;
  return true;
}

bool CameraController::cancel_transfer_input(Error *error) {
  const bool completed = transfer_input_.complete();
  if (!ptp_.cancel_input(&transfer_input_, error)) return false;
  // A completed 94DE response leaves the command socket synchronized.  Prove
  // its transient pic_trans_state returned to the exact no-PC baseline before
  // normal teardown.  An incomplete transfer is cancelled by closing that
  // socket, so U-shell verification is no longer possible on that path.
  if (completed) {
    if (!wait_for_station_baseline(kStationReadyTimeoutMs, error)) return false;
    picture_transfer_cleanup_unverified_ = false;
    return true;
  }
  if (picture_transfer_cleanup_unverified_) {
    return fail(error, "picture-transfer cleanup is unverified after abort");
  }
  return true;
}

bool CameraController::restore_settings(Error *error) {
  clear_error(error);
  if (shutter_settings_restore_blocked_) {
    return fail(error,
                "shutter release is unproved; refusing settings restoration");
  }
  if (saved_mask_ == 0 && changed_mask_ == 0) {
    settings_applied_ = false;
    deferred_controller_proof_pending_ = false;
    return true;
  }
  if (!shell_ready_) return fail(error, "cannot restore without camera U-shell");
  // A reverse B01 may have executed even though its 902B response or prompt
  // was lost.  Fence that command before classifying each setting as target,
  // original, or conflicting; otherwise a fast retry can observe a transient
  // active/controller pair and strand an otherwise recoverable journal.
  if (shell_resync_required_ && !resynchronize_shell(error)) return false;

  if (changed_mask_ != 0 && !setting_write_attempted_) {
    // A journal reserved before the first forward B01 never owns a later
    // dial-to-target value.  This guard is repeated here (not only in the
    // immediate failure funnel) so terminal close cannot turn a sticky
    // prewrite conflict into an unauthorized restoration write.
    if (!read_direct_exposure_state(&direct_catalog_snapshot_scratch_,
                                    &direct_catalog_result_scratch_, error)) {
      setting_prewrite_rejection_safe_ = false;
      return false;
    }
    for (unsigned setting = 0; setting < kSettingCatalogCount; ++setting) {
      const uint8_t bit = static_cast<uint8_t>(1u << setting);
      if ((saved_mask_ & bit) == 0 && (changed_mask_ & bit) == 0) continue;
      if (direct_catalog_snapshot_scratch_.active[setting] !=
              original_settings_[setting] ||
          direct_catalog_snapshot_scratch_.selected[setting] !=
              original_settings_[setting]) {
        setting_prewrite_rejection_safe_ = false;
        return fail(error,
                    "prewrite setting changed; refusing restoration write");
      }
    }
    saved_mask_ = 0;
    changed_mask_ = 0;
    settings_applied_ = false;
    deferred_controller_proof_pending_ = false;
    setting_prewrite_rejection_safe_ = true;
    return true;
  }

  if (persistent_shell_sdk_ready_ && saved_mask_ == UINT8_C(0x07) &&
      changed_mask_ == UINT8_C(0x07) && settings_set_batch_performed_) {
    int32_t active[3] = {};
    int32_t controller[3] = {};
    if (!read_setting_snapshot(active, controller, error)) {
      return false;
    }
    for (unsigned i = 0; i < 3; ++i) {
      const int32_t original = original_settings_[i];
      const int32_t target = capture_targets_[i];
      const bool active_owned = active[i] == original || active[i] == target;
      const bool controller_owned =
          controller[i] == original || controller[i] == target;
      if (!active_owned || !controller_owned) {
        return fail(error,
                    "camera setting changed externally; refusing restoration");
      }
    }

    // Restore in strict reverse semantic order (ISO, aperture, shutter).  A
    // prior attempt may have reached the camera and then lost its response or
    // convergence proof.  Treat an exact original pair as an already
    // completed reverse step; requiring it to still equal the target would
    // make the journal impossible to resume during close().  A mixed pair is
    // not sufficient proof and remains a failure until a later bounded retry
    // observes one exact owned endpoint.
    for (int index = 2; index >= 0; --index) {
      const int32_t original = original_settings_[index];
      const int32_t target = capture_targets_[index];
      if (active[index] == original && controller[index] == original) {
        continue;
      }
      if (active[index] != target || controller[index] != target) {
        return fail(error,
                    "camera setting is between target and original during "
                    "restoration");
      }
      if (!validate_catalog_restore_value(static_cast<unsigned>(index),
                                          original, error)) {
        return false;
      }
      if (!write_and_observe_setting(static_cast<unsigned>(index), original,
                                     true, error)) {
        return false;
      }
    }
    // A later restoration can affect an earlier controller through the same
    // conflict machinery reconstructed by the direct evaluator.  Prove the
    // complete original tuple only after all three reverse writes terminate.
    if (!wait_for_settings_batch(original_settings_,
                                 kSettingSettleTimeoutMs, error)) {
      return false;
    }
    changed_mask_ = 0;
    saved_mask_ = 0;
    settings_applied_ = false;
    deferred_controller_proof_pending_ = false;
    return true;
  }

  bool all_ok = true;
  Error first = {};
  for (int index = 2; index >= 0; --index) {
    const uint8_t bit = static_cast<uint8_t>(1u << index);
    if ((changed_mask_ & bit) == 0) continue;
    Error local = {};
    int32_t active = 0;
    int32_t controller = 0;
    const unsigned setting_index = static_cast<unsigned>(index);
    bool observed = false;
    if (persistent_shell_sdk_ready_) {
      // A sticky journal may outlive a source/context change.  Before even the
      // no-write "already original" discharge, pin the original to the cached
      // full native slot and use the complete direct exposure snapshot; the
      // former partial provider view is never cleanup authority.
      observed =
          validate_catalog_restore_value(setting_index,
                                         original_settings_[index], &local) &&
          read_active(setting_index, &active, &local) &&
          read_controller(setting_index, &controller, &local);
    } else {
      observed = read_pair(setting_index, &active, &controller, &local);
    }
    if (!observed) {
      retain_first_error(false, local, &all_ok, &first);
      continue;
    }
    const int32_t original = original_settings_[index];
    const int32_t target = capture_targets_[index];
    if (active == original && controller == original) {
      changed_mask_ &= static_cast<uint8_t>(~bit);
      continue;
    }
    const bool active_owned = active == original || active == target;
    const bool controller_owned = controller == original || controller == target;
    if (!active_owned || !controller_owned) {
      (void)fail(&local,
                 "camera setting changed externally; refusing restoration");
      retain_first_error(false, local, &all_ok, &first);
      continue;
    }
    if (persistent_shell_sdk_ready_ &&
        !validate_catalog_restore_value(setting_index, original, &local)) {
      retain_first_error(false, local, &all_ok, &first);
      continue;
    }
    if (!write_and_observe_setting(setting_index, original, true, &local)) {
      retain_first_error(false, local, &all_ok, &first);
      continue;
    }
    changed_mask_ &= static_cast<uint8_t>(~bit);
  }

  if (changed_mask_ == 0) {
    saved_mask_ = 0;
    settings_applied_ = false;
    deferred_controller_proof_pending_ = false;
  }
  if (!all_ok && error != nullptr) *error = first;
  return all_ok;
}

bool CameraController::close(Error *error) {
  clear_error(error);
  release_astro_preposition_ownership();
  post_capture_lens_window_ = false;
  bool all_ok = true;
  Error first = {};

  persistent_armed_ = false;
  persistent_settings_snapshot_valid_ = false;
  direct_transfer_records_qualified_ = false;
  persistent_transfer_slot_hint_ = 0;

  Error local = {};
  if (transfer_input_.active() || picture_transfer_cleanup_unverified_) {
    // A complete 94DE can still use the synchronized command socket to prove
    // pic_trans_state returned to baseline.  An incomplete lease is cancelled
    // once and deliberately remains unverified; close must report that sticky
    // state instead of mislabeling a worker failure as restored.
    const bool released = cancel_transfer_input(&local);
    retain_first_error(released, local, &all_ok, &first);
  }

  local = {};
  if (shutter_release_pending_) {
    // The optimized persistent cycle normally reaches both off-edges in its
    // own no-early-return sequence.  This generic helper is retained for the
    // legacy one-shot path and an obligation predating that sequence.
    const bool release_sent = release_shutter(&local);
    if (persistent_shell_sdk_ready_) shutter_recovery_required_ = true;
    retain_first_error(release_sent, local, &all_ok, &first);
  }

  if (shutter_recovery_required_) {
    bool idle = false;
    Error idle_failure = {};
    if (!shell_ready_) {
      (void)fail(&idle_failure,
                 "cannot verify persistent shutter release");
    } else {
      // Shutter delivery is asynchronous.  Retry only the read-only stable-
      // idle proof; no shutter edge or setting write is replayed.  Its shell
      // command interlock fences any ambiguous four-prompt SDK transcript
      // before each observation.
      for (unsigned attempt = 0; attempt < kShutterRecoveryAttempts;
           ++attempt) {
        local = {};
        if (os::failed(os::nanosleep_ms(kShutterStatePollMs))) {
          (void)fail(&idle_failure,
                     "persistent shutter cleanup sleep failed");
          break;
        }
        if (require_shutter_preflight(&local)) {
          idle = true;
          break;
        }
        idle_failure = local;
      }
    }
    if (idle) {
      shutter_recovery_required_ = false;
      shutter_settings_restore_blocked_ = false;
    }
    retain_first_error(idle, idle_failure, &all_ok, &first);
  }

  if (shutter_release_pending_ || shutter_recovery_required_ ||
      shutter_settings_restore_blocked_) {
    local = {};
    (void)fail(&local,
               "shutter release is unproved; refusing settings restoration");
    retain_first_error(false, local, &all_ok, &first);
  } else {
    bool restored = false;
    Error restore_failure = {};
    for (unsigned attempt = 0; attempt < kSettingRestoreAttempts; ++attempt) {
      local = {};
      if (restore_settings(&local)) {
        restored = true;
        break;
      }
      restore_failure = local;
      if (attempt + 1u == kSettingRestoreAttempts) break;
      if (os::failed(os::nanosleep_ms(kSettingRestoreRetryMs))) {
        (void)fail(&restore_failure, "setting restoration retry sleep failed");
        break;
      }
    }
    retain_first_error(restored, restore_failure, &all_ok, &first);
  }
  // Keep the catalog-bound persistent route active through every cleanup B01
  // attempt.  Restoration retains its pinned source/context and immediate
  // direct legality guard even when close() follows a failed transaction.
  if (!shutter_release_pending_ && !shutter_recovery_required_ &&
      !persistent_sdk_output_pending_) {
    persistent_shell_sdk_ready_ = false;
  }

  // P2=0 frees the capture buffer but is not a FIFO fence for commands already
  // queued to Tsk_USHL.  Resolve every sticky prompt/transport obligation with
  // the ordinary exact drain or a unique bare-echo barrier before releasing
  // the allocation.  A failed/disconnected fence leaves ownership sticky.
  bool shell_fifo_synchronized =
      !persistent_sdk_output_pending_ && !shell_resync_required_;
  if (!shell_fifo_synchronized && shell_ready_) {
    if (persistent_sdk_output_pending_ && !shell_resync_required_) {
      Error ignored = {};
      (void)drain_persistent_sdk_output(kPersistentSdkDrainTimeoutMs,
                                        &ignored);
    }
    if (persistent_sdk_output_pending_ || shell_resync_required_) {
      shell_resync_required_ = true;
      local = {};
      const bool fenced = resynchronize_shell(&local);
      retain_first_error(fenced, local, &all_ok, &first);
    }
    shell_fifo_synchronized =
        !persistent_sdk_output_pending_ && !shell_resync_required_;
  }
  if (!shell_fifo_synchronized && !shell_ready_) {
    local = {};
    (void)fail(&local, "cannot fence outstanding camera U-shell commands");
    retain_first_error(false, local, &all_ok, &first);
  }

  if (capture_allocated_) {
    local = {};
    if (!ptp_.connected()) {
      (void)fail(&local,
                 "cannot release stock U-shell capture after PTP disconnect");
      retain_first_error(false, local, &all_ok, &first);
    } else if (!shell_fifo_synchronized) {
      (void)fail(&local,
                 "refusing U-shell capture release without a FIFO fence");
      retain_first_error(false, local, &all_ok, &first);
    } else {
      shell_output_size_ = 0;
      shell_output_[0] = '\0';
      retain_first_error(read_shell_capture(false, 3000, &local), local,
                         &all_ok, &first);
    }
  }
  shell_ready_ = false;

  local = {};
  retain_first_error(ptp_.close(&local), local, &all_ok, &first);

  local = {};
  retain_first_error(devmem_state_.close(&local), local, &all_ok, &first);

  local = {};
  retain_first_error(restore_and_unmap_gate(&local), local, &all_ok, &first);

  direct_shell_capture_qualified_ = false;
  direct_shell_capture_recovery_pending_ = false;
  direct_shell_capture_cursor_ = 0;
  direct_shell_capture_firmware_read_checkpoint_ = 0;
  direct_shell_capture_checkpoint_bytes_ = 0;
  if (!all_ok && error != nullptr) *error = first;
  return all_ok;
}

}  // namespace om1_stars
