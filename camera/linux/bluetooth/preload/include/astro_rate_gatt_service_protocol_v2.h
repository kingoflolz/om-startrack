#ifndef ASTRO_RATE_GATT_SERVICE_PROTOCOL_V2_H
#define ASTRO_RATE_GATT_SERVICE_PROTOCOL_V2_H

#include <stdint.h>

/*
 * Public Astro Rate GATT v2 wire contract.
 *
 * UUIDs use canonical byte order:
 *   service 7e6d7a50-2c56-4f17-9c51-9a5f64b02440
 *   RATE    7e6d7a50-2c56-4f17-9c51-9a5f64b02441
 *
 * RATE is write-only and accepts exactly one little-endian value:
 *   yaw_q16_s32, pitch_q16_s32, roll_q16_s32, ttl_ticks_u16,
 *   gave0_offset_s16, gave1_offset_s16
 *
 * A signed lane is Q16.16 in legacy Astro Rate counts, so its physical
 * angular rate is lane * 1e-7 / 65536 rad/s.  An all-zero value is the exact
 * cancellation command.  Active commands require at least one nonzero rate
 * and a positive raw firmware-hook TTL.  Both former GAVE fields are reserved
 * zero; nonzero values are unsupported.  Focal length remains camera-owned. An
 * initial or idle replacement samples it from coherent live STAR STATE.
 * During STAR capture, any otherwise-valid active value from the active peer
 * may update body and TTL.  It uses current coherent cached focal, or a
 * last-valid focal scoped to that connection, without an idle-operation gate.
 * The Android client owns the TTL=4095/nominal-one-second adaptive-or-exact
 * publication policy and suspends that cadence while hidden and idle.
 */
#define ASTRO_RATE_GATT_V2_VERSION UINT8_C(2)
#define ASTRO_RATE_GATT_V2_VALUE_SIZE 18u
#define ASTRO_RATE_GATT_V2_RATE_FRACTION_BITS 16u
#define ASTRO_RATE_GATT_V2_RATE_LIMIT_Q16 INT32_C(0x0b650000)
#define ASTRO_RATE_GATT_V2_TTL_MIN UINT16_C(1)
#define ASTRO_RATE_GATT_V2_TTL_MAX UINT16_MAX
#define ASTRO_RATE_GATT_V2_GAVE_OFFSET_MIN INT16_C(0)
#define ASTRO_RATE_GATT_V2_GAVE_OFFSET_MAX INT16_C(0)

#define ASTRO_RATE_GATT_V2_YAW_OFFSET 0u
#define ASTRO_RATE_GATT_V2_PITCH_OFFSET 4u
#define ASTRO_RATE_GATT_V2_ROLL_OFFSET 8u
#define ASTRO_RATE_GATT_V2_TTL_OFFSET 12u
#define ASTRO_RATE_GATT_V2_GAVE0_OFFSET 14u
#define ASTRO_RATE_GATT_V2_GAVE1_OFFSET 16u

#endif
