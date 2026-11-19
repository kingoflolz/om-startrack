#ifndef OM1_STARS_WORKER_PROTOCOL_H
#define OM1_STARS_WORKER_PROTOCOL_H

#include <limits.h>
#include <linux/limits.h>
#include <stddef.h>
#include <stdint.h>

#define STAR_WORKER_MAGIC UINT32_C(0x39575453)
#define STAR_WORKER_VERSION UINT8_C(11)

#define STAR_WORKER_FRAME_HEADER_SIZE 8u
#define STAR_WORKER_SETTING_COUNT 3u
#define STAR_WORKER_SETTING_LABEL_MAX 31u
#define STAR_WORKER_LABEL_TUPLE_MIN_SIZE 6u
#define STAR_WORKER_LABEL_TUPLE_MAX_SIZE 96u

/*
 * A CAPTURE snapshots all six source-extraction controls before its exact
 * public label tuple. The tuple is byte-defined little-endian data rather than
 * a C struct so both the C companion and freestanding C++ worker share one
 * unambiguous private ABI.
 */
#define STAR_WORKER_DETECTOR_OPTIONS_COUNT 6u
#define STAR_WORKER_DETECTOR_OPTIONS_SIZE 12u
#define STAR_WORKER_DETECTOR_SIGMA_Q8_OFFSET 0u
#define STAR_WORKER_DETECTOR_MIN_AREA_OFFSET 2u
#define STAR_WORKER_DETECTOR_MAX_AREA_OFFSET 4u
#define STAR_WORKER_DETECTOR_MAX_ELONGATION_Q8_OFFSET 6u
#define STAR_WORKER_DETECTOR_MAX_SHARPNESS_Q8_OFFSET 8u
#define STAR_WORKER_DETECTOR_BORDER_MARGIN_OFFSET 10u

#define STAR_WORKER_DETECTOR_SIGMA_Q8_DEFAULT UINT16_C(1280)
#define STAR_WORKER_DETECTOR_MIN_AREA_DEFAULT UINT16_C(3)
#define STAR_WORKER_DETECTOR_MAX_AREA_DEFAULT UINT16_C(1024)
#define STAR_WORKER_DETECTOR_MAX_ELONGATION_Q8_DEFAULT UINT16_C(768)
#define STAR_WORKER_DETECTOR_MAX_SHARPNESS_Q8_DEFAULT UINT16_C(230)
#define STAR_WORKER_DETECTOR_BORDER_MARGIN_DEFAULT UINT16_C(1)

#define STAR_WORKER_DETECTOR_SIGMA_Q8_MIN UINT16_C(1)
#define STAR_WORKER_DETECTOR_SIGMA_Q8_MAX UINT16_C(16384)
#define STAR_WORKER_DETECTOR_AREA_MIN UINT16_C(1)
#define STAR_WORKER_DETECTOR_ELONGATION_Q8_MIN UINT16_C(256)
#define STAR_WORKER_DETECTOR_SHARPNESS_Q8_MAX UINT16_C(256)
/* Fixed OM-1 5184x3888 active raster reduced to 2592x1944 detector cells. */
#define STAR_WORKER_DETECTOR_BORDER_MARGIN_MAX UINT16_C(1943)

#define STAR_WORKER_SET_PAYLOAD_MIN STAR_WORKER_LABEL_TUPLE_MIN_SIZE
#define STAR_WORKER_SET_PAYLOAD_MAX STAR_WORKER_LABEL_TUPLE_MAX_SIZE
#define STAR_WORKER_CAPTURE_PAYLOAD_MIN                                  \
  (STAR_WORKER_DETECTOR_OPTIONS_SIZE + STAR_WORKER_LABEL_TUPLE_MIN_SIZE)
#define STAR_WORKER_CAPTURE_PAYLOAD_MAX                                  \
  (STAR_WORKER_DETECTOR_OPTIONS_SIZE + STAR_WORKER_LABEL_TUPLE_MAX_SIZE)
#define STAR_WORKER_KEEP_AWAKE_PAYLOAD_SIZE 0u
#define STAR_WORKER_COMMAND_PAYLOAD_MIN 0u
#define STAR_WORKER_COMMAND_PAYLOAD_MAX STAR_WORKER_CAPTURE_PAYLOAD_MAX

#define STAR_WORKER_CATALOG_VALUE_MAX 512u
#define STAR_WORKER_CATALOG_PAYLOAD_MIN 15u
#define STAR_WORKER_CATALOG_PAYLOAD_MAX 1542u
#define STAR_WORKER_STATE_VALUE_MIN 21u
#define STAR_WORKER_STATE_VALUE_MAX 203u
#define STAR_WORKER_STATE_PAYLOAD_MIN 24u
#define STAR_WORKER_STATE_PAYLOAD_MAX 206u

#define STAR_WORKER_MAX_PEAKS 256u
#define STAR_WORKER_CAPTURE_RESULT_PREFIX_SIZE 14u
#define STAR_WORKER_LENS_NAME_MAX 32u
#define STAR_WORKER_CAPTURE_RESULT_PAYLOAD_MAX 2094u
#define STAR_WORKER_FAILED_PAYLOAD_SIZE 1u
#define STAR_WORKER_KEEP_AWAKE_RESULT_PAYLOAD_SIZE 1u
#define STAR_WORKER_MAX_PAYLOAD_SIZE STAR_WORKER_CAPTURE_RESULT_PAYLOAD_MAX
#define STAR_WORKER_MAX_FRAME_SIZE \
  (STAR_WORKER_FRAME_HEADER_SIZE + STAR_WORKER_MAX_PAYLOAD_SIZE)

/*
 * The ARM camera and its Linux companion are little-endian. Commands travel
 * from the companion to worker fd 0; events travel atomically from worker fd
 * 1. Every frame begins with this naturally aligned eight-byte header. The
 * payload size is exact: no payload has reserved bytes or implicit padding.
 */
struct star_worker_frame_header {
  uint32_t magic;
  uint16_t payload_size;
  uint8_t version;
  uint8_t type;
};

enum star_worker_command_type {
  STAR_WORKER_COMMAND_SET = 0x01,
  STAR_WORKER_COMMAND_CAPTURE = 0x02,
  STAR_WORKER_COMMAND_KEEP_AWAKE = 0x03,
  /* Additive v11 command: identical CAPTURE payload, mandatory preposition.
   * Retains admission intent even if the staged mailbox is cancelled while
   * the worker applies settings. Public STAR control is unchanged. */
  STAR_WORKER_COMMAND_CAPTURE_PREPOSITION = 0x04,
};

enum star_worker_event_type {
  STAR_WORKER_EVENT_CATALOG = 0x81,
  STAR_WORKER_EVENT_STATE = 0x82,
  STAR_WORKER_EVENT_CAPTURE_RESULT = 0x83,
  STAR_WORKER_EVENT_FAILED = 0x84,
  STAR_WORKER_EVENT_KEEP_AWAKE_RESULT = 0x85,
};

enum star_worker_keep_awake_result {
  STAR_WORKER_KEEP_AWAKE_OK = 0,
  STAR_WORKER_KEEP_AWAKE_RECOVERED = 1,
};

static inline int star_worker_command_payload_size_valid(uint8_t type,
                                                          uint16_t size) {
  switch (type) {
    case STAR_WORKER_COMMAND_SET:
      return size >= STAR_WORKER_SET_PAYLOAD_MIN &&
             size <= STAR_WORKER_SET_PAYLOAD_MAX;
    case STAR_WORKER_COMMAND_CAPTURE:
    case STAR_WORKER_COMMAND_CAPTURE_PREPOSITION:
      return size >= STAR_WORKER_CAPTURE_PAYLOAD_MIN &&
             size <= STAR_WORKER_CAPTURE_PAYLOAD_MAX;
    case STAR_WORKER_COMMAND_KEEP_AWAKE:
      return size == STAR_WORKER_KEEP_AWAKE_PAYLOAD_SIZE;
    default:
      return 0;
  }
}

enum star_worker_state_reason {
  STAR_WORKER_STATE_OBSERVED = 0,
  STAR_WORKER_STATE_STARTUP = 1,
  STAR_WORKER_STATE_SET_APPLIED = 2,
  STAR_WORKER_STATE_SET_REJECTED = 3,
  STAR_WORKER_STATE_CAPTURE_RESTORED = 4,
  STAR_WORKER_STATE_CAPTURE_REJECTED = 5,
};

enum star_worker_capture_outcome {
  STAR_WORKER_CAPTURE_OK = 0,
  STAR_WORKER_CAPTURE_REJECTED_NO_MUTATION = 1,
};

enum star_worker_failure_safety {
  STAR_WORKER_FAILURE_NO_MUTATION = 1,
  STAR_WORKER_FAILURE_RESTORED = 2,
  STAR_WORKER_FAILURE_UNCERTAIN = 3,
};

struct star_worker_peak {
  uint16_t x;
  uint16_t y;
  uint32_t flux;
};

struct star_worker_detector_options {
  uint16_t sigma_q8;
  uint16_t min_area;
  uint16_t max_area;
  uint16_t max_elongation_q8;
  uint16_t max_sharpness_q8;
  uint16_t border_margin;
};

static inline uint16_t star_worker_detector_get_le16(const uint8_t *bytes) {
  return (uint16_t)((uint16_t)bytes[0] | ((uint16_t)bytes[1] << 8));
}

static inline void star_worker_detector_put_le16(uint8_t *bytes,
                                                  uint16_t value) {
  bytes[0] = (uint8_t)value;
  bytes[1] = (uint8_t)(value >> 8);
}

static inline int star_worker_detector_options_valid(
    const struct star_worker_detector_options *options) {
  return options != NULL &&
         options->sigma_q8 >= STAR_WORKER_DETECTOR_SIGMA_Q8_MIN &&
         options->sigma_q8 <= STAR_WORKER_DETECTOR_SIGMA_Q8_MAX &&
         options->min_area >= STAR_WORKER_DETECTOR_AREA_MIN &&
         options->max_area >= options->min_area &&
         (options->max_elongation_q8 == 0u ||
          options->max_elongation_q8 >=
              STAR_WORKER_DETECTOR_ELONGATION_Q8_MIN) &&
         options->max_sharpness_q8 <=
             STAR_WORKER_DETECTOR_SHARPNESS_Q8_MAX &&
         options->border_margin <=
             STAR_WORKER_DETECTOR_BORDER_MARGIN_MAX;
}

static inline int star_worker_decode_detector_options(
    const uint8_t *bytes, size_t size,
    struct star_worker_detector_options *options) {
  if (bytes == NULL || options == NULL ||
      size != STAR_WORKER_DETECTOR_OPTIONS_SIZE)
    return 0;
  options->sigma_q8 = star_worker_detector_get_le16(
      bytes + STAR_WORKER_DETECTOR_SIGMA_Q8_OFFSET);
  options->min_area = star_worker_detector_get_le16(
      bytes + STAR_WORKER_DETECTOR_MIN_AREA_OFFSET);
  options->max_area = star_worker_detector_get_le16(
      bytes + STAR_WORKER_DETECTOR_MAX_AREA_OFFSET);
  options->max_elongation_q8 = star_worker_detector_get_le16(
      bytes + STAR_WORKER_DETECTOR_MAX_ELONGATION_Q8_OFFSET);
  options->max_sharpness_q8 = star_worker_detector_get_le16(
      bytes + STAR_WORKER_DETECTOR_MAX_SHARPNESS_Q8_OFFSET);
  options->border_margin = star_worker_detector_get_le16(
      bytes + STAR_WORKER_DETECTOR_BORDER_MARGIN_OFFSET);
  return star_worker_detector_options_valid(options);
}

static inline void star_worker_encode_default_detector_options(
    uint8_t bytes[STAR_WORKER_DETECTOR_OPTIONS_SIZE]) {
  star_worker_detector_put_le16(
      bytes + STAR_WORKER_DETECTOR_SIGMA_Q8_OFFSET,
      STAR_WORKER_DETECTOR_SIGMA_Q8_DEFAULT);
  star_worker_detector_put_le16(
      bytes + STAR_WORKER_DETECTOR_MIN_AREA_OFFSET,
      STAR_WORKER_DETECTOR_MIN_AREA_DEFAULT);
  star_worker_detector_put_le16(
      bytes + STAR_WORKER_DETECTOR_MAX_AREA_OFFSET,
      STAR_WORKER_DETECTOR_MAX_AREA_DEFAULT);
  star_worker_detector_put_le16(
      bytes + STAR_WORKER_DETECTOR_MAX_ELONGATION_Q8_OFFSET,
      STAR_WORKER_DETECTOR_MAX_ELONGATION_Q8_DEFAULT);
  star_worker_detector_put_le16(
      bytes + STAR_WORKER_DETECTOR_MAX_SHARPNESS_Q8_OFFSET,
      STAR_WORKER_DETECTOR_MAX_SHARPNESS_Q8_DEFAULT);
  star_worker_detector_put_le16(
      bytes + STAR_WORKER_DETECTOR_BORDER_MARGIN_OFFSET,
      STAR_WORKER_DETECTOR_BORDER_MARGIN_DEFAULT);
}

/*
 * SET is the public label tuple verbatim:
 *
 *   repeat three times: label_length:u8, exact_label:ASCII[]
 *
 * KEEP_AWAKE has an exact zero-byte payload. It invokes one fixed camera-side
 * command; the protocol deliberately provides no arbitrary U-shell text
 * transport. KEEP_AWAKE_RESULT has exactly one result byte: OK means the
 * command reached its terminal prompt, while RECOVERED means its outcome was
 * ambiguous but a later FIFO resynchronization succeeded without replaying it.
 *
 * CAPTURE snapshots the detector controls followed by that exact tuple:
 *
 *   sigma_q8:u16le
 *   min_area:u16le
 *   max_area:u16le
 *   max_elongation_q8:u16le
 *   max_sharpness_q8:u16le
 *   border_margin:u16le
 *   repeat three times: label_length:u8, exact_label:ASCII[]
 *
 * CATALOG payloads contain three little-endian u16 value sizes followed by
 * the three exact public catalog values in shutter/aperture/ISO order.
 *
 * STATE payloads contain:
 *
 *   value_size:u16le, reason:u8, exact_public_state[value_size]
 *
 * CAPTURE_RESULT has a byte-defined 14-byte prefix (not a C struct):
 *
 *   outcome:u8
 *   peak_count:u16le
 *   captured_settings:i16le[3]
 *   lens_length:u8
 *   focal_length_binary32:u32le
 *   peaks[peak_count]: {x:u16le, y:u16le, flux:u32le}
 *   lens_name[lens_length]: printable ASCII
 *
 * A rejected result has exactly the prefix, outcome REJECTED_NO_MUTATION,
 * and zero in every other prefix byte. FAILED has exactly one safety byte.
 * The worker auto-arms and publishes CATALOG then STARTUP STATE. Pipe EOF is
 * shutdown. Later idle, SET, and CAPTURE snapshots publish CATALOG only when
 * its public bytes changed, immediately before the corresponding STATE; the
 * ordinary terminal form is therefore STATE-only. The companion may stage
 * that optional CATALOG until STATE commits the coherent snapshot. CAPTURE
 * additionally ends with CAPTURE_RESULT; a successful result is sent only
 * after strict local rollover, so it also authorizes the next command.
 * KEEP_AWAKE additionally ends with exactly one KEEP_AWAKE_RESULT. An
 * unrecoverable U-shell ambiguity instead terminates with
 * FAILED(NO_MUTATION). FAILED safety classifies capture/settings mutation
 * only; it does not classify the auxiliary sleep-timer rearm side effect.
 */

#ifdef __cplusplus
static_assert(sizeof(star_worker_frame_header) ==
                  STAR_WORKER_FRAME_HEADER_SIZE,
              "STAR worker frame header ABI changed");
static_assert(offsetof(star_worker_frame_header, magic) == 0 &&
                  offsetof(star_worker_frame_header, payload_size) == 4 &&
                  offsetof(star_worker_frame_header, version) == 6 &&
                  offsetof(star_worker_frame_header, type) == 7,
              "STAR worker frame-header offsets changed");
static_assert(sizeof(star_worker_peak) == 8,
              "STAR worker peak ABI changed");
static_assert(sizeof(star_worker_detector_options) ==
                  STAR_WORKER_DETECTOR_OPTIONS_SIZE,
              "STAR worker detector-options value changed");
static_assert(STAR_WORKER_MAX_FRAME_SIZE <= PIPE_BUF,
              "STAR worker frame exceeds Linux PIPE_BUF");
#else
_Static_assert(sizeof(struct star_worker_frame_header) ==
                   STAR_WORKER_FRAME_HEADER_SIZE,
               "STAR worker frame header ABI changed");
_Static_assert(offsetof(struct star_worker_frame_header, magic) == 0 &&
                   offsetof(struct star_worker_frame_header, payload_size) ==
                       4 &&
                   offsetof(struct star_worker_frame_header, version) == 6 &&
                   offsetof(struct star_worker_frame_header, type) == 7,
               "STAR worker frame-header offsets changed");
_Static_assert(sizeof(struct star_worker_peak) == 8,
               "STAR worker peak ABI changed");
_Static_assert(sizeof(struct star_worker_detector_options) ==
                   STAR_WORKER_DETECTOR_OPTIONS_SIZE,
               "STAR worker detector-options value changed");
_Static_assert(STAR_WORKER_MAX_FRAME_SIZE <= PIPE_BUF,
               "STAR worker frame exceeds Linux PIPE_BUF");
#endif

#endif
