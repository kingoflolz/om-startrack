#ifndef GYRO_ATTITUDE_PROTOCOL_H
#define GYRO_ATTITUDE_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>

/* 48-byte gyro attitude trailer in STAR STATE (...2452).
 * Hamilton Q30 (w,x,y,z) maps body to a reference fixed at boot.
 * Serialize explicitly: this host struct's padding is not part of the wire ABI.
 */
#define GYRO_ATTITUDE_VERSION UINT8_C(1)
#define GYRO_ATTITUDE_VALUE_SIZE 48u
#define GYRO_ATTITUDE_TICK_HZ 2000u
#define GYRO_ATTITUDE_FLAG_CURRENT_VALID UINT8_C(1)
#define GYRO_ATTITUDE_FLAG_CAPTURE_VALID UINT8_C(2)
#define GYRO_ATTITUDE_FLAG_CAPTURE_PREPARATION UINT8_C(4)
#define GYRO_ATTITUDE_KNOWN_FLAGS UINT8_C(7)
#define GYRO_ATTITUDE_FLAGS_OFFSET 1u
#define GYRO_ATTITUDE_TICK_OFFSET 4u
#define GYRO_ATTITUDE_CAPTURE_SEQUENCE_OFFSET 8u
#define GYRO_ATTITUDE_CAPTURE_TICK_OFFSET 12u
#define GYRO_ATTITUDE_CURRENT_Q30_OFFSET 16u
#define GYRO_ATTITUDE_CAPTURE_Q30_OFFSET 32u

struct gyro_attitude_value {
    uint8_t flags;
    uint32_t tick;
    uint32_t capture_sequence;
    uint32_t capture_tick;
    int32_t current_q30[4];
    int32_t capture_q30[4];
};

static inline uint32_t gyro_attitude_read_u32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8)
            | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static inline int32_t gyro_attitude_read_i32(const uint8_t *p) {
    uint32_t value = gyro_attitude_read_u32(p);
    return (int32_t)(value <= INT32_MAX ? (int64_t)value
            : (int64_t)value - INT64_C(4294967296));
}

static inline void gyro_attitude_write_u32(uint8_t *p, uint32_t value) {
    p[0] = (uint8_t)value;
    p[1] = (uint8_t)(value >> 8);
    p[2] = (uint8_t)(value >> 16);
    p[3] = (uint8_t)(value >> 24);
}

static inline int gyro_attitude_quaternion_valid(const int32_t q[4]) {
    double norm_squared = 0.0;
    unsigned i;
    for (i = 0; i < 4; ++i) {
        double component = (double)q[i] / 1073741824.0;
        norm_squared += component * component;
    }
    return norm_squared >= 0.98 * 0.98 && norm_squared <= 1.02 * 1.02;
}

static inline int gyro_attitude_valid(const struct gyro_attitude_value *value) {
    if (!value || (value->flags & ~GYRO_ATTITUDE_KNOWN_FLAGS) != 0) return 0;
    if ((value->flags & GYRO_ATTITUDE_FLAG_CAPTURE_PREPARATION)
            && !(value->flags & GYRO_ATTITUDE_FLAG_CAPTURE_VALID)) return 0;
    if ((value->flags & GYRO_ATTITUDE_FLAG_CURRENT_VALID)
            && !gyro_attitude_quaternion_valid(value->current_q30)) return 0;
    if ((value->flags & GYRO_ATTITUDE_FLAG_CAPTURE_VALID)
            && !gyro_attitude_quaternion_valid(value->capture_q30)) return 0;
    return 1;
}

/* Does not alter *out on malformed input. Invalid quaternion lanes may be retained or zero. */
static inline int gyro_attitude_decode(const uint8_t *data, size_t length,
        struct gyro_attitude_value *out) {
    struct gyro_attitude_value value;
    unsigned i;
    if (!data || !out || length != GYRO_ATTITUDE_VALUE_SIZE
            || data[0] != GYRO_ATTITUDE_VERSION || data[2] != 0 || data[3] != 0) return 0;
    value.flags = data[1];
    value.tick = gyro_attitude_read_u32(data + GYRO_ATTITUDE_TICK_OFFSET);
    value.capture_sequence = gyro_attitude_read_u32(data + GYRO_ATTITUDE_CAPTURE_SEQUENCE_OFFSET);
    value.capture_tick = gyro_attitude_read_u32(data + GYRO_ATTITUDE_CAPTURE_TICK_OFFSET);
    for (i = 0; i < 4; ++i) {
        value.current_q30[i] = gyro_attitude_read_i32(data + GYRO_ATTITUDE_CURRENT_Q30_OFFSET + i * 4);
        value.capture_q30[i] = gyro_attitude_read_i32(data + GYRO_ATTITUDE_CAPTURE_Q30_OFFSET + i * 4);
    }
    if (!gyro_attitude_valid(&value)) return 0;
    *out = value;
    return 1;
}

static inline int gyro_attitude_encode(uint8_t *data, size_t length,
        const struct gyro_attitude_value *value) {
    unsigned i;
    if (!data || length != GYRO_ATTITUDE_VALUE_SIZE || !gyro_attitude_valid(value)) return 0;
    data[0] = GYRO_ATTITUDE_VERSION;
    data[1] = value->flags;
    data[2] = data[3] = 0;
    gyro_attitude_write_u32(data + GYRO_ATTITUDE_TICK_OFFSET, value->tick);
    gyro_attitude_write_u32(data + GYRO_ATTITUDE_CAPTURE_SEQUENCE_OFFSET, value->capture_sequence);
    gyro_attitude_write_u32(data + GYRO_ATTITUDE_CAPTURE_TICK_OFFSET, value->capture_tick);
    for (i = 0; i < 4; ++i) {
        gyro_attitude_write_u32(data + GYRO_ATTITUDE_CURRENT_Q30_OFFSET + i * 4,
                (uint32_t)value->current_q30[i]);
        gyro_attitude_write_u32(data + GYRO_ATTITUDE_CAPTURE_Q30_OFFSET + i * 4,
                (uint32_t)value->capture_q30[i]);
    }
    return 1;
}

#endif
