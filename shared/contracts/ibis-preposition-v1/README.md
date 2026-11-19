# IBIS pre-position v1 contract

This optional characteristic extends the existing Astro Rate v2 service with
a temporary CPU0 position offset. The app can stage an offset for the next
STAR capture or apply it after half-press. CPU0 moves toward the requested
offset in detector mode 3, adopts the applied position at the transition to
still-preparation mode 1, and consumes the temporary command. The gyro-rate
characteristic and its 18-byte value remain unchanged.

[`schema.json`](schema.json) defines the wire format, native representation,
app travel policy, and transport semantics. The standard-library validator
checks the schema and accepted and rejected wire examples:

```sh
python3 shared/contracts/ibis-preposition-v1/validate_vectors.py
python3 -m unittest discover -s shared/contracts/ibis-preposition-v1 -p 'test_*.py'
```

## GATT value

The service UUID is `7e6d7a50-2c56-4f17-9c51-9a5f64b02440`; PREPOSITION is
`7e6d7a50-2c56-4f17-9c51-9a5f64b02442`. PREPOSITION is write-only and uses an
ATT Write Request. It has no read value, CCCD, application generation, or
application acknowledgement. Older cameras without the characteristic can
continue using the existing capture and rate features without pre-positioning.

Every write is exactly eight little-endian bytes (`<hhhH>`):

| Offset | Type | Field |
| --- | --- | --- |
| 0 | signed 16-bit | X logical position offset |
| 2 | signed 16-bit | Y logical position offset |
| 4 | signed 16-bit | Roll logical position offset |
| 6 | unsigned 16-bit | Action |

These are CPU0 calibrated logical position counts: 3000 counts/mm for X/Y and
8192 counts/degree for roll. They are not raw gyro or Hall readings. The
contract does not establish their physical axis signs.

| Action | Meaning | Successful ATT response |
| --- | --- | --- |
| 0 | Cancel; all three axes must be zero | Local intent invalidated and cancellation queued |
| 1 | Stage for the next STAR capture | Offset retained by the server for staging |
| 2 | Apply now while the app holds half-press | MAIN consumed the published mailbox |

Actions 1 and 2 require an idle STAR operation. Zero axes are valid with either
action. Other actions, incorrect lengths, and unrepresentable native offsets
are rejected.

The firmware has no additional PRE travel-policy limits. A wire value is
admitted when its action is valid and conversion can produce native offsets
whose values **and negations** fit signed 32-bit integers. CPU0 negates each
offset to restore its relative accumulator, so `INT32_MIN` is unusable even
though it fits the mailbox field. This allows X/Y counts from -28111 to
+28111 and the entire signed-16 roll range. These are arithmetic bounds, not
usable actuator travel. Conversion is checked in signed 64-bit arithmetic
before narrowing or changing the queued command.

The app enforces its own normal still-capture travel envelope. For
`r = abs(roll)`, app-generated offsets satisfy all of these constraints:

```text
abs(x) <= 2546 and abs(y) <= 2546
x*x + y*y <= 2546*2546
r <= 5325
abs(x) <= 2700 - floor(r*1628/32768)
abs(y) <= 2700 - floor(r*1571/32768)
```

These constraints are sign symmetric, with different X and Y roll coupling.
At zero roll a single planar axis can reach 2546 counts. At 5325 roll counts,
the X and Y bounds are 2436 and 2445 counts respectively, while the planar
radius remains 2546. A diagonal of (1800, 1800) fits; (1801, 1801) does not.
The app uses this policy directly, without firmware capability autodetection.
The wire vectors distinguish `app_admitted` from firmware wire validity.

The app bounds describe the requested offset. CPU0 retains stock limits on the
combined stock position and offset; a nonzero stock position can reduce the
offset actually reached. Linux checks native representation and converts the
axes to native accumulator units before publication. The active stock profile
and actuator limits remain authoritative for actual movement.

## Capture ordering

For action 1, ATT success means the server retained the intent in Linux.
Staging occupies no MAIN mailbox space, so normal RATE renewals can continue.
The worker asserts first-on and requests publication through the private
coordination byte. The server waits for any older in-flight command to finish,
publishes the staged native body, and reports consumption after MAIN takes it.
The worker bounds preparation waits to 1,000 ms, allows 750 ms for movement,
then asserts second-on. First-on enables the framing poller to consume a
command that was pending while correction was inactive.

For action 2, the app asserts first-on before the write. The server queues the
intent, waits for any previous MAIN command to be consumed, publishes the new
command, and waits for MAIN to consume that command before returning ATT
success. The app then waits 750 ms and asserts second-on. A staging or MAIN
consumption timeout fails preparation and cancels before second-on.

MAIN consumption establishes that MAIN took the command for serialization.
It does not confirm CPU0 admission, actuator position, settling, or the start
of optical exposure. The fixed delay is an unmeasured physical settling
allowance. CPU0 replaces the offset without a software ramp and accepts an
apply command only in
detector mode 3; a camera configured not to enter that mode will not acquire
an offset merely because its shutter is half-pressed.

CPU0 adds the offset before native bounded integration and correction
limiting, retaining it through the mode-3-to-mode-1 transition and exposure.
It subtracts the same offset from the updated accumulator after each native
integration/output call pair, so restoring feedback uses relative position.
After refreshing the stock producer bounds each tick, modes 1 and 3 with a
nonzero active PRE temporarily raise the planar producer bound (`B4`) to
16384 counts and the native accumulator bound (`B98`) to `0x70000000` for
all axes. This carries the offset through preparation and still integration
instead of clipping it to the old planar 1800-count producer bound. Under the
same guard CPU0 selects the stock coupled geometry builder. Its final
combined-target and active-profile limits remain in place; stock tables,
flags, and actuator protections are unchanged. Cancellation restores the
ordinary producer policy on the same tick because refresh precedes adoption.
Capture entry needs no origin seeding or predictor reset. Capture exit clears
the offset before stock exit processing; other mode exits also retire it.
Cancellation replaces the offset with zero during framing. Commands arriving
during capture do not change its offset. An offset is an absolute requested
displacement for this preparation; repeated writes do not accumulate movement.

## Internal transport

PRE and RATE share a generic mailbox at physical `0x2a2985fa4`, with published
body length at `0x2a2985fb8` (MAIN uncached aliases `0xa2985fa4` and
`0xa2985fb8`). Length is 0 when empty, 14 for PRE, and 20 for RATE. The server is
the sole payload writer and never overwrites an in-flight body. Staged intent
stays in Linux until the worker requests publication after first-on.

Linux converts each admitted logical axis using signed mathematical ceiling:

```text
native_x = ceil(x_counts * 2^27 / 1757)
native_y = ceil(y_counts * 2^27 / 1757)
native_roll = ceil(roll_counts * 2^18 / 4295)
```

Signed ceiling is asymmetric: planar `+1` becomes `+76391`, while `-1` becomes
`-76390`. The body is `<iiiH>`: three native signed-32 offsets followed by the
constant publication tag `0x5051`. A cancellation and a zero apply both deliver
three zero offsets with this valid tag. MAIN emits opcode `0x37` followed by
these 14 bytes, an exact TX15. CPU0 distinguishes PRE from RATE TX21 by length,
copies the offsets, and commits the tag last.

CPU0 pending storage is 16 bytes at `0x0558` and active offsets occupy 12 bytes
at `0x0568`. RATE occupies the following 32 bytes at `0x0574`. Together they
reserve the final three records of the stock trace ring, leaving 45 records.
CPU0 adopts a tagged PRE only at the first axis in framing mode 3 and retires
the tag. It uses ordinary loads/stores without interrupt masking, accepting
rare mixed vectors or loss of a newly arrived pending command. Mode 3→1
retains the active offset for exposure. Capture exit clears it before stock
exit processing; the next preparation needs a new command.

A private Linux/worker byte at physical `0x2a2985fbc` coordinates preparation:
0 empty, 1 published, 2 staged, 3 requested, 4 consumed, 5 cancelled. MAIN does
not read this byte. These states provide local ownership and capture ordering;
they add no public generation or physical acknowledgement.
