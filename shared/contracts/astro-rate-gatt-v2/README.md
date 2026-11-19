# Astro Rate GATT v2 contract

This contract carries three Q16.16 body-rate commands from the phone to camera
Linux. The simplified firmware converts them to native CPU0 increments in
Linux, then relays one prepared packet through MAIN. The public value remains
18 bytes. The two former lens-GAVE fields are reserved and must be zero;
nonzero GAVE is rejected with ATT `0x13`.

[`schema.json`](schema.json) defines the layout and semantics;
[`vectors/golden.json`](vectors/golden.json) and
[`vectors/invalid.json`](vectors/invalid.json) specify accepted and rejected
values. Validate with:

```sh
python3 shared/contracts/astro-rate-gatt-v2/validate_vectors.py
python3 -m unittest discover -s shared/contracts/astro-rate-gatt-v2 -p 'test_*.py'
```

## Public surface

The stock Olympus service remains the discovery anchor. The current extension
service set requires STAR and Astro Rate v2.

| Role | UUID | Properties |
| --- | --- | --- |
| Astro Rate v2 service | `7e6d7a50-2c56-4f17-9c51-9a5f64b02440` | Primary |
| RATE | `7e6d7a50-2c56-4f17-9c51-9a5f64b02441` | Write Request |
| Optional PREPOSITION | `7e6d7a50-2c56-4f17-9c51-9a5f64b02442` | Write Request |

PREPOSITION is specified by
[`ibis-preposition-v1`](../ibis-preposition-v1/README.md). RATE has no read
value, CCCD, application generation, checksum, or application acknowledgement.
ATT success means the validated command was retained for publication. It does
not establish MAIN serialization, CPU0 admission, sensor movement, or retirement
of a previous correction.

Every RATE value is little-endian `<iiiHhh>`:

| Offset | Type | Field |
| --- | --- | --- |
| 0 | i32 | Yaw rate Q16.16 |
| 4 | i32 | Pitch rate Q16.16 |
| 8 | i32 | Roll rate Q16.16 |
| 12 | u16 | TTL in CPU0 hook ticks |
| 14 | i16 | Former GAVE lane 0; reserved zero |
| 16 | i16 | Former GAVE lane 1; reserved zero |

Each body lane is bounded to `[-191168512, 191168512]`, equivalent to
`[-2917, 2917]` legacy counts. A raw integer `q` denotes
`q * (1e-7 / 65536)` rad/s. Producers round physical rates to the nearest raw
integer, with ties away from zero. Positive yaw moves captured content right,
positive pitch moves it up, and positive roll rotates it counter-clockwise in
the normally viewed, unrotated ORF raster.

An active command requires at least one nonzero body rate and TTL `1..65535`.
The exact cancellation value is 18 zero bytes; it bypasses focal admission.
Other all-zero-body values are invalid. GAVE-only commands are unsupported.
For the canonical equator/north-up attitude at Earth rate
`7.2921150e-5 rad/s`, yaw is `-47789605` and the complete 4095-tick value is
`dbc926fd0000000000000000ff0f00000000`.

## Camera focal and lease

For an initial or idle replacement write, Linux samples coherent cached STAR
STATE from the roughly 10 Hz worker-idle sampler. STATE must be valid, the
worker running, the operation idle, and no catalog staged. Focal is the
focus-breathed `FLact` value. Linux computes `F = floor(FLact_mm * 32)` and
requires `1..31368`. Missing/zero focal or missing coherent STATE returns ATT
`0x0e`; a present finite focal outside the supported range returns `0x13`.
The phone never supplies focal.

During the same peer's STAR capture, Linux accepts otherwise-valid rate and
TTL changes using the latest valid cached focal, or the last valid focal from
this BLE connection. It bypasses the worker-idle gate. With neither source it
returns `0x0e`. Peer detach clears this focal fallback. Writes without an ATT
response cookie are ignored before modifying retained state.

The phone renews its latest ATT-acknowledged command once per nominal second
with TTL 4095 while interactive or while an admitted capture owns correction.
At about 2 kHz this is 2.0475 seconds of hook time. The hook can pause, so TTL
is not a wall-clock lease. Linux has no autonomous correction renewal timer.
Startup, active peer detach, worker/focal-authority loss, and orderly shutdown
queue a best-effort cancellation. Repeated explicit cancellation is valid.

## Native conversion and shared mailbox

Linux performs this arithmetic once per accepted publication:

```text
planar_gain_q16 = -(23*F + ((30694*F + 32768) >> 16))
yaw_step_q32   = yaw_rate_q16 * planar_gain_q16
pitch_step_q32 = pitch_rate_q16 * planar_gain_q16
roll_step_q32  = round_nearest_away_from_zero(roll_rate_q16 * -192253 / 2048)
whole         = floor(step_q32 / 2^32)
fraction      = step_q32 modulo 2^32
```

The native RATE body is 20 bytes, `<IIIhhhH>`: three unsigned fractional
increments, three signed whole increments, then TTL. Cancellation is 20 zero
bytes. MAIN emits opcode `0x37` plus the complete body, an exact TX21. The
bridge copies the first 18 bytes and publishes TTL last. CPU0 maintains three
unsigned residuals, adds each fraction, adds its carry to the corresponding
whole increment, and saturating-adds the result to the stock selected rate
word. RATE reads are deliberately unmasked: a refresh can tear the fraction/whole
pair or change the command between axes. This accepted transient behavior keeps
the RATE hook small. Residuals survive command renewal. Planar conversion is exact relative
to the previous implementation; roll rounds its former Q43 step to Q32.

RATE and PREPOSITION share a mailbox:

| Physical Linux address | MAIN uncached alias | Purpose |
| --- | --- | --- |
| `0x2a2985fa4..0x2a2985fb7` | `0xa2985fa4..0xa2985fb7` | At most 20 body bytes |
| `0x2a2985fb8` | `0xa2985fb8` | Published length: 0 empty, 20 RATE, 14 PRE |
| `0x2a2985fbc` | Not read by MAIN | Linux/worker capture coordination byte |

Linux writes an empty mailbox's payload, applies a publication barrier, then
writes the body length last. It never overwrites an in-flight body. Pending
RATE updates can be coalesced locally; staged PRE intent stays in Linux and
occupies no MAIN mailbox space. Thus routine rate renewals cannot overwrite a
pre-position command in flight. MAIN invokes the stock descriptor-24 serializer
first, validates owner/buffer/length, copies the body after opcode `0x37`, clears
the mailbox length, and commits the final TX length last. Consumption means
MAIN took the body for serialization, not that CPU0 applied it.

Both TX15 and TX21 fit the stock 24-byte allocation class. The stock transport
fragments TX21 as 15+6 bytes. An inactive opcode-37 owner may leave the mailbox
pending; the PRE capture sequence handles this with half-press and a bounded
preparation wait.

## Compatibility



The native mailbox and TX21/TX15 are incompatible with the earlier focal-bearing
TX17/TX9 implementation. Camera Linux, MAIN, and CPU0 must be updated together.
Direct `/dev/mem` bench senders using the previous mailbox cannot be reused.
The public phone RATE and PREPOSITION layouts stay unchanged, with nonzero GAVE
now rejected. MAIN contains no additive GAVE hooks or MOVESTEP publisher;
STAR retains the lens telemetry wire fields as unavailable zeros.
