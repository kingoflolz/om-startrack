#ifndef OM1_GYRO_ATTITUDE_H
#define OM1_GYRO_ATTITUDE_H

typedef signed int gyro_i32;
typedef unsigned int gyro_u32;
typedef unsigned char gyro_u8;
typedef unsigned short gyro_u16;
typedef long long gyro_i64;

#define GYRO_STATE_ADDRESS 0x000004e0u
#define GYRO_Q30_ONE 1073741824
#define GYRO_VALID 1u
#define GYRO_CAPTURE_VALID 2u
#define GYRO_CAPTURE_PREPARATION 4u

/* The first 48 bytes are the v1 little-endian wire record. A reader performs
 * one bounded seqlock attempt and clears validity flags if it is interrupted.
 * Boot's stock [0x1cc,0xdf8) zeroing initializes the complete 120-byte state.
 */
struct gyro_attitude_state {
    volatile gyro_u8 version;
    volatile gyro_u8 flags;
    volatile gyro_u16 reserved;
    volatile gyro_u32 tick;
    volatile gyro_u32 capture_sequence;
    volatile gyro_u32 capture_tick;
    volatile gyro_i32 q[4];
    volatile gyro_i32 capture_q[4];
    volatile gyro_u32 sequence;
    gyro_u32 residual[4];
    gyro_u32 gain_residual[3];
    volatile gyro_u32 irq_sequence;
    gyro_u32 last_sequence;
    volatile gyro_u32 snapshot_sequence;
    gyro_u32 initialized;
    gyro_u32 private_reserved[6];
};

void gyro_attitude_step(volatile gyro_i32 q[4], const gyro_i32 d[3],
                        gyro_u32 residual[4]);
void gyro_attitude_renormalize(volatile gyro_i32 q[4]);
void gyro_attitude_accept(struct gyro_attitude_state *s, gyro_u32 sequence,
                          const gyro_i32 native_rate[3], gyro_u32 mode);
void gyro_attitude_transition(struct gyro_attitude_state *s,
                              gyro_u32 new_mode, gyro_u32 old_mode);
void gyro_attitude_native_sample(struct gyro_attitude_state *s,
                                 gyro_u32 sequence);
void gyro_attitude_native_transition(struct gyro_attitude_state *s,
                                     gyro_u32 new_mode);

#endif
