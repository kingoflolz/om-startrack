# Gyro attitude v1

This record is embedded in the existing STATE Read/Notify characteristic
`7e6d7a50-2c56-4f17-9c51-9a5f64b02452`. [STATE v7](../star-gatt/README.md)
appends it after the existing length-delimited lens name. No GATT handles are
added. STATE writes and DONE remain unchanged. The complete STATE notification
requires ATT MTU 160 or greater. This trailer's exact 48-byte little-endian
layout is in [schema.json](schema.json).

Each coherent packet reports a live integrated quaternion and a retained
capture quaternion in the same reference, initialized once at boot. Quaternions are signed Q30,
Hamilton scalar-first `(w,x,y,z)`, mapping camera body coordinates
`(right, down, lens forward)` into that arbitrary reference.
Both quaternion signs represent the same rotation. Valid quaternion length
must be in the inclusive range 0.98–1.02; consumers normalize before use.
The camera integrates stock calibrated, runtime-bias-adjusted rates before the
stabilization HPF. The values carry no absolute sky or horizon alignment.

Flags are `1=current valid`, `2=capture valid`, and `4=capture timing is
preparation`. Flag 4 requires flag 2. Unknown flags, nonzero reserved bytes,
other versions, and lengths other than 48 bytes are rejected. Invalid quaternion
lanes are ignored and may contain zero or retained data; other counters may also
be retained for diagnostics. Validity does not by itself establish freshness.
The consumer must observe tick progress, mark stale live samples unavailable,
and discard its attitude anchor when the camera connection is replaced.

Ticks count nominal 2 kHz CPU0 integration samples rather than wall time during
an acquisition pause. Tick and capture sequence are unsigned modulo 2^32.
Capture sequence increments only on a new latch; zero is not a validity
sentinel. The retained quaternion, sequence, capture tick, and timing qualifier
remain available until another capture or reboot. Their validity does not
depend on the live sample being valid. Pausing acquisition retains both the
integrated orientation and the capture latch. Movement during missing samples
is unobserved and can introduce drift when acquisition resumes.
The latch is not inherently bound to a STAR request:
the consumer obtains a fresh sequence baseline before capture and accepts
exactly one new latch. Bit 4 marks the initial mode-1
preparation latch; it must not be described as the sensor's exact exposure
start. Movement between preparation and the effective exposure remains an
alignment error.

With `Q` mapping body to the fixed reference and `S` the plate-solved body-to-local-
horizon attitude at capture time, the phone stores `A = S * inverse(Qcapture)`
and displays `A * Qnow`. No CPU0 history buffer is needed. The horizon anchor
holds alt-az fixed when the gyro reports no movement; drift and capture-timing
uncertainty remain visible limitations. Reads, app solves, and pauses do not
consume or clear the capture latch.

Run the cross-runtime checks with:

```sh
python3 shared/contracts/gyro-attitude-v1/validate_vectors.py
python3 -m unittest discover -s shared/contracts/gyro-attitude-v1 -p 'test_*.py'
```

The Java decoder lives in `GyroAttitudeProtocol.java`; the portable Linux codec
is `camera/linux/bluetooth/preload/include/gyro_attitude_protocol.h`. Golden and
negative packets in `vectors/` are shared between their tests.
