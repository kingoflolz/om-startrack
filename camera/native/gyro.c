/* Camera-body (right, down, forward) to boot-reference quaternion, Q30.
 *
 * Input is the stock calibrated, runtime bias-adjusted, pre-HPF FULL rate:
 * yaw 0x4ee0, pitch 0x52b8, roll 0x5690. Do not apply a second matrix/bias.
 * Canonical slopes are -2000/-2000/-2000 counts per degree/second.
 * Body mapping is (pitch, yaw, -roll). The nominal Q24 gain therefore is
 * negative for x/y and positive for z. A 500 us sample is assumed ONLY when
 * independent detector-event sequence continuity has been established.
 *
 * All inputs are bounded to 1000 degrees/second per axis. At this bound the
 * half-angle components are <=0.004364 radians; all sum-of-three signed 64-bit
 * products fit, and normalization every 32 samples remains near unit length.
 * The multiply-workaround NOPs emitted by base-MN10300 GCC must be retained.
 */
#include "gyro_attitude.h"

typedef char assert_state_size[(sizeof(struct gyro_attitude_state) == 120) ? 1 : -1];
typedef char assert_i32_size[(sizeof(gyro_i32) == 4) ? 1 : -1];
#define CHECK_OFFSET(field, offset) \
    typedef char assert_offset_##field[(__builtin_offsetof(struct gyro_attitude_state, field) == offset) ? 1 : -1]
CHECK_OFFSET(q, 16);
CHECK_OFFSET(capture_q, 32);
CHECK_OFFSET(sequence, 48);
CHECK_OFFSET(residual, 52);
CHECK_OFFSET(gain_residual, 68);
CHECK_OFFSET(irq_sequence, 80);
CHECK_OFFSET(last_sequence, 84);
CHECK_OFFSET(snapshot_sequence, 88);
CHECK_OFFSET(initialized, 92);

static __inline__ gyro_i32 product_q30(gyro_i32 a, gyro_i32 b)
{
    return (gyro_i32)(((gyro_i64)a * b) >> 30);
}

static __inline__ gyro_i32 quantize(gyro_i64 product, gyro_u32 *residual)
{
    gyro_i64 sum = product + (gyro_i64)*residual;
    *residual = (gyro_u32)sum;
    return (gyro_i32)(sum >> 32);
}

void gyro_attitude_step(volatile gyro_i32 q[4], const gyro_i32 d[3],
                        gyro_u32 residual[4])
{
    gyro_i32 w = q[0], x = q[1], y = q[2], z = q[3];
    gyro_i32 a = d[0], b = d[1], c = d[2];
    q[0] = w + quantize(-((gyro_i64)x*a + (gyro_i64)y*b + (gyro_i64)z*c), residual+0);
    q[1] = x + quantize( (gyro_i64)w*a + (gyro_i64)y*c - (gyro_i64)z*b, residual+1);
    q[2] = y + quantize( (gyro_i64)w*b + (gyro_i64)z*a - (gyro_i64)x*c, residual+2);
    q[3] = z + quantize( (gyro_i64)w*c + (gyro_i64)x*b - (gyro_i64)y*a, residual+3);
}

void gyro_attitude_renormalize(volatile gyro_i32 q[4])
{
    gyro_i32 w = q[0], x = q[1], y = q[2], z = q[3];
    gyro_i32 norm = product_q30(w,w) + product_q30(x,x)
                  + product_q30(y,y) + product_q30(z,z);
    gyro_i32 factor = GYRO_Q30_ONE + ((GYRO_Q30_ONE - norm) >> 1);
    /* Round the normalized components. Flooring every negative component
     * every 16 ms would introduce a directional low-rate drift. */
    q[0] = (gyro_i32)(((gyro_i64)w*factor + (1 << 29)) >> 30);
    q[1] = (gyro_i32)(((gyro_i64)x*factor + (1 << 29)) >> 30);
    q[2] = (gyro_i32)(((gyro_i64)y*factor + (1 << 29)) >> 30);
    q[3] = (gyro_i32)(((gyro_i64)z*factor + (1 << 29)) >> 30);
}

static __inline__ void begin(struct gyro_attitude_state *s)
{
    ++s->sequence;
    __asm__ volatile ("" ::: "memory");
    s->version = 1;
    s->reserved = 0;
}

static __inline__ void end(struct gyro_attitude_state *s)
{
    __asm__ volatile ("" ::: "memory");
    ++s->sequence;
}

/* Initialize identity once after boot. Pauses and gaps retain all attitude,
 * fractional residuals, tick and capture state; only the unknown interval is
 * skipped. The following contiguous observation resumes normal integration. */
static void prime(struct gyro_attitude_state *s, gyro_u32 sequence)
{
    gyro_u32 i;
    if (!s->initialized) {
        for (i=0; i<4; ++i) {
            s->q[i] = i == 0 ? GYRO_Q30_ONE : 0;
            s->residual[i] = 0;
        }
        for (i=0; i<3; ++i) s->gain_residual[i] = 0;
        s->initialized = 1;
    }
    s->last_sequence = sequence;
    s->flags = (s->flags & (GYRO_CAPTURE_VALID | GYRO_CAPTURE_PREPARATION)) | GYRO_VALID;
}

void gyro_attitude_accept(struct gyro_attitude_state *s, gyro_u32 sequence,
                          const gyro_i32 native_rate[3], gyro_u32 mode)
{
    gyro_i32 d[3], rate[3];
    gyro_u32 i;
    begin(s);
    /* Bounds use comparisons, never abs(INT_MIN), and never silently clamp. */
    if (mode > 5u || native_rate[0] < -2000000 || native_rate[0] > 2000000 ||
        native_rate[1] < -2000000 || native_rate[1] > 2000000 ||
        native_rate[2] < -2000000 || native_rate[2] > 2000000) {
        s->flags &= ~GYRO_VALID;
        end(s);
        return;
    }
    if (!s->initialized || !(s->flags & GYRO_VALID) ||
        sequence - s->last_sequence != 1u) {
        prime(s, sequence);
        end(s);
        return;
    }
    rate[0] = native_rate[1];
    rate[1] = native_rate[0];
    rate[2] = native_rate[2];
    for (i=0; i<3; ++i) {
        gyro_i32 gain = i == 2 ? 157205283 : -157205283;
        gyro_i64 product = (gyro_i64)rate[i] * gain + s->gain_residual[i];
        d[i] = (gyro_i32)(product >> 24);
        s->gain_residual[i] = (gyro_u32)product & 0xffffffu;
    }
    gyro_attitude_step(s->q, d, s->residual);
    ++s->tick;
    s->last_sequence = sequence;
    if ((s->tick & 31u) == 0) gyro_attitude_renormalize(s->q);
    end(s);
}

void gyro_attitude_transition(struct gyro_attitude_state *s,
                              gyro_u32 new_mode, gyro_u32 old_mode)
{
    gyro_u32 i;
    begin(s);
    if (new_mode > 5u) s->flags &= ~GYRO_VALID;
    if (new_mode == 1u && old_mode != 1u) {
        ++s->capture_sequence;
        s->capture_tick = s->tick;
        for (i=0; i<4; ++i) s->capture_q[i] = s->q[i];
        s->flags = (s->flags & GYRO_VALID)
                 ? (GYRO_VALID | GYRO_CAPTURE_VALID | GYRO_CAPTURE_PREPARATION)
                 : 0u;
    }
    end(s);
}

#ifndef GYRO_ATTITUDE_HOST_TEST
void gyro_attitude_native_sample(struct gyro_attitude_state *s,
                                 gyro_u32 sequence)
{
    gyro_i32 native_rate[3];
    gyro_u32 mode = *(volatile gyro_u16 *)0x489au;
    native_rate[0] = *(volatile gyro_i32 *)0x4ee0u;
    native_rate[1] = *(volatile gyro_i32 *)0x52b8u;
    native_rate[2] = *(volatile gyro_i32 *)0x5690u;
    /* The stock sampler returns before updating rates when this byte is 0x80.
     * Never integrate its stale outputs. The sequence was latched beside raw
     * snapshots under the existing stock critical section. */
    if (*(volatile gyro_u8 *)0x676cu == 0x80u) mode = 0xffu;
    gyro_attitude_accept(s, sequence, native_rate, mode);
}

void gyro_attitude_native_transition(struct gyro_attitude_state *s,
                                     gyro_u32 new_mode)
{
    gyro_attitude_transition(s, new_mode, *(volatile gyro_u16 *)0x489au);
}
#endif
