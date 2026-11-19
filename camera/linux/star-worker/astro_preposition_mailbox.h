#ifndef OM1_ASTRO_PREPOSITION_MAILBOX_H
#define OM1_ASTRO_PREPOSITION_MAILBOX_H

#include <stdint.h>

/* Public preposition command; RATE v2's public layout is unchanged. Public
 * UUID 7e6d7a50-2c56-4f17-9c51-9a5f64b02442 is write-only <hhhH>:
 * logical X/Y counts (3000/mm), roll counts (8192/degree), action.
 * GATT actions: 0 cancel, 1 stage for STAR, 2 apply after app half-press.
 * Linux converts these logical coordinates to native integrator units.
 * The worker only shares the capture coordination byte with the server;
 * it never writes the MAIN command body. Acceptance is not actuator feedback. */
#define ASTRO_PREPOSITION_PAYLOAD_SIZE 8u
#define ASTRO_PREPOSITION_ACTION_OFFSET 6u
#define ASTRO_PREPOSITION_CANCEL 0u
#define ASTRO_PREPOSITION_STAGE 1u
#define ASTRO_PREPOSITION_APPLY 2u
#define ASTRO_PREPOSITION_PHYSICAL UINT64_C(0x2a2985fbc)
#define ASTRO_PREPOSITION_RATE_RELATIVE_OFFSET 24u
#define ASTRO_PREPOSITION_READY_OFFSET 0u
#define ASTRO_PREPOSITION_MAILBOX_SIZE 1u
#define ASTRO_PREPOSITION_EMPTY 0u
#define ASTRO_PREPOSITION_PUBLISHED 1u
#define ASTRO_PREPOSITION_STAGED 2u
#define ASTRO_PREPOSITION_REQUESTED 3u
#define ASTRO_PREPOSITION_CONSUMED 4u
#define ASTRO_PREPOSITION_CANCELLED 5u
#define ASTRO_PREPOSITION_NATIVE_SIZE 14u
#define ASTRO_PREPOSITION_NATIVE_TAG UINT16_C(0x5051)
#define ASTRO_PREPOSITION_CONSUME_TIMEOUT_MS 1000u
/* Fixed experimental allowance; admission is not a measured settling guarantee. */
#define ASTRO_PREPOSITION_SETTLE_MS 750u

/* Conversion checks representation only. Travel policy belongs to the app;
 * stock combined-target and actuator limits still apply on CPU0. */
static inline int astro_preposition_native_axis(int16_t logical,
                                                unsigned int axis,
                                                int32_t *native) {
  const int64_t scale = axis == 2u ? INT64_C(262144) : INT64_C(134217728);
  const int64_t divisor = axis == 2u ? INT64_C(4295) : INT64_C(1757);
  const int64_t product = (int64_t)logical * scale;
  /* C division truncates toward zero: negative values already ceil. */
  const int64_t converted =
      (product + (product > 0 ? divisor - 1 : 0)) / divisor;
  /* CPU0 negates the bias to restore its relative accumulator. */
  if (converted <= INT32_MIN || converted > INT32_MAX)
    return 0;
  *native = (int32_t)converted;
  return 1;
}

#endif
