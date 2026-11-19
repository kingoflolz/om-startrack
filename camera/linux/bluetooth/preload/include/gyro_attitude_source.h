#ifndef GYRO_ATTITUDE_SOURCE_H
#define GYRO_ATTITUDE_SOURCE_H

#include "gyro_attitude_protocol.h"

#include <stdint.h>
#include <string.h>

/* OM-1 v1.9 native extension. The mirror is published through its uncached
 * MAIN alias. All words are aligned and little endian on MAIN and Linux. */
#define GYRO_ATTITUDE_MIRROR_PHYSICAL UINT64_C(0x2a0047a80)
#define GYRO_ATTITUDE_MIRROR_SIZE 64u
#define GYRO_ATTITUDE_MIRROR_MAGIC UINT32_C(0x31525947)
#define GYRO_ATTITUDE_MIRROR_SEQUENCE_OFFSET 4u
#define GYRO_ATTITUDE_MIRROR_VALUE_OFFSET 8u
#define GYRO_ATTITUDE_LEASE_PHYSICAL UINT64_C(0x2a0047ac0)
#define GYRO_ATTITUDE_LEASE_SIZE 16u
#define GYRO_ATTITUDE_LEASE_MAGIC UINT32_C(0x314c5947)
#define GYRO_ATTITUDE_POLL_MS INT64_C(100)
#define GYRO_ATTITUDE_STALE_MS INT64_C(500)

struct gyro_attitude_source {
    uint8_t value[GYRO_ATTITUDE_VALUE_SIZE];
    uint32_t observed_tick;
    int observed;
    int live;
    int64_t last_advance_ms;
};

static inline uint32_t gyro_attitude_source_word(
    const volatile uint8_t *source, size_t offset)
{
    return *(const volatile uint32_t *)(source + offset);
}

static inline void gyro_attitude_source_reset(struct gyro_attitude_source *state)
{
    memset(state, 0, sizeof(*state));
    state->value[0] = GYRO_ATTITUDE_VERSION;
}

static inline int gyro_attitude_source_snapshot(
    const volatile uint8_t *mirror, uint8_t value[GYRO_ATTITUDE_VALUE_SIZE])
{
    struct gyro_attitude_value decoded;
    unsigned int attempt;

    if (mirror == NULL)
        return 0;
    for (attempt = 0u; attempt < 3u; ++attempt) {
        uint32_t sequence = gyro_attitude_source_word(mirror, 4u);
        size_t index;

        if (sequence == 0u || (sequence & 1u) != 0u ||
            gyro_attitude_source_word(mirror, 0u) !=
                GYRO_ATTITUDE_MIRROR_MAGIC)
            continue;
        __sync_synchronize();
        for (index = 0u; index < GYRO_ATTITUDE_VALUE_SIZE; ++index)
            value[index] = mirror[GYRO_ATTITUDE_MIRROR_VALUE_OFFSET + index];
        __sync_synchronize();
        if (sequence == gyro_attitude_source_word(mirror, 4u) &&
            gyro_attitude_source_word(mirror, 0u) ==
                GYRO_ATTITUDE_MIRROR_MAGIC &&
            gyro_attitude_source_word(mirror, 56u) == 0u &&
            gyro_attitude_source_word(mirror, 60u) == 0u &&
            gyro_attitude_decode(value, GYRO_ATTITUDE_VALUE_SIZE, &decoded))
            return 1;
    }
    return 0;
}

/* Return whether the public value changed. A new relay generation alone is
 * not evidence of acquisition: only an advancing CPU0 sample tick is live.
 * First observation and reconnection require a second tick
 * for live attitude. A coherent historical capture remains valid independently
 * of live acquisition, including paused acquisition and stale current samples. */
static inline int gyro_attitude_source_sample(
    struct gyro_attitude_source *state, const volatile uint8_t *mirror,
    int64_t now_ms)
{
    uint8_t next[GYRO_ATTITUDE_VALUE_SIZE];
    struct gyro_attitude_value decoded;
    int coherent = gyro_attitude_source_snapshot(mirror, next);

    if (!coherent || now_ms < 0) {
        memcpy(next, state->value, sizeof(next));
        next[GYRO_ATTITUDE_FLAGS_OFFSET] = 0u;
        state->observed = 0;
        state->live = 0;
    } else {
        (void)gyro_attitude_decode(next, sizeof(next), &decoded);
        if (!state->observed) {
            state->observed = 1;
            state->observed_tick = decoded.tick;
            state->live = 0;
        } else if (state->observed_tick != decoded.tick) {
            uint32_t delta = decoded.tick - state->observed_tick;

            state->observed_tick = decoded.tick;
            state->live = delta < UINT32_C(0x80000000);
            state->last_advance_ms = now_ms;
        }
        if (!state->live || now_ms < state->last_advance_ms ||
            now_ms - state->last_advance_ms > GYRO_ATTITUDE_STALE_MS)
            next[GYRO_ATTITUDE_FLAGS_OFFSET] &=
                (uint8_t)~GYRO_ATTITUDE_FLAG_CURRENT_VALID;
    }
    if (memcmp(state->value, next, sizeof(next)) == 0)
        return 0;
    memcpy(state->value, next, sizeof(next));
    return 1;
}

#endif
