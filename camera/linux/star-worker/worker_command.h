#ifndef OM1_STARS_WORKER_COMMAND_H
#define OM1_STARS_WORKER_COMMAND_H

#include "star_worker_protocol.h"
#include "types.h"

#include <stdint.h>

namespace om1_stars {

struct LabelTuple {
  uint8_t length[STAR_WORKER_SETTING_COUNT];
  uint8_t value[STAR_WORKER_SETTING_COUNT][STAR_WORKER_SETTING_LABEL_MAX];
};

inline bool decode_label_tuple(const uint8_t *payload, uint16_t payload_size,
                               LabelTuple *tuple) {
  if (payload == nullptr || tuple == nullptr ||
      payload_size < STAR_WORKER_LABEL_TUPLE_MIN_SIZE ||
      payload_size > STAR_WORKER_LABEL_TUPLE_MAX_SIZE) {
    return false;
  }
  *tuple = {};
  uint16_t offset = 0;
  for (unsigned setting = 0; setting < STAR_WORKER_SETTING_COUNT; ++setting) {
    if (offset >= payload_size) return false;
    const uint8_t length = payload[offset++];
    if (length == 0 || length > STAR_WORKER_SETTING_LABEL_MAX ||
        static_cast<uint32_t>(offset) + length > payload_size) {
      return false;
    }
    tuple->length[setting] = length;
    for (uint8_t byte = 0; byte < length; ++byte) {
      const uint8_t value = payload[offset++];
      if (value < 0x21u || value > 0x7eu) return false;
      tuple->value[setting][byte] = value;
    }
  }
  return offset == payload_size;
}

inline bool decode_capture_payload(const uint8_t *payload,
                                   uint16_t payload_size,
                                   LabelTuple *labels,
                                   DetectorOptions *options) {
  if (payload == nullptr || labels == nullptr || options == nullptr ||
      payload_size < STAR_WORKER_CAPTURE_PAYLOAD_MIN ||
      payload_size > STAR_WORKER_CAPTURE_PAYLOAD_MAX) {
    return false;
  }
  star_worker_detector_options wire = {};
  if (!star_worker_decode_detector_options(
          payload, STAR_WORKER_DETECTOR_OPTIONS_SIZE, &wire)) {
    return false;
  }
  options->sigma_q8 = wire.sigma_q8;
  options->min_area = wire.min_area;
  options->max_area = wire.max_area;
  options->max_elongation_q8 = wire.max_elongation_q8;
  options->max_sharpness_q8 = wire.max_sharpness_q8;
  options->border_margin = wire.border_margin;
  return decode_label_tuple(
      payload + STAR_WORKER_DETECTOR_OPTIONS_SIZE,
      static_cast<uint16_t>(payload_size - STAR_WORKER_DETECTOR_OPTIONS_SIZE),
      labels);
}

}  // namespace om1_stars

#endif
