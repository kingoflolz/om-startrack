# STAR GATT contract

One current service is implemented by both camera models and the app.
Configuration writes are 27 bytes with version byte **6**. STATE reads and
notifications have version byte **7**, a 77-byte prefix, a variable lens name,
and a required 48-byte gyro attitude record. Total STATE length is 125–157
bytes; the minimum ATT MTU is 160. These read and write values have distinct
wire layouts. No STATE format without the attitude record is accepted.

`schema.json` defines UUIDs, layouts, flags, catalog coherence, and configuration
transactions. `vectors/` covers valid and malformed states, catalog values,
leases, writes, and gyro attitude trailers. Run:

```sh
python3 validate_vectors.py
python3 -m unittest discover -s . -p 'test_*.py'
```

The native definition is `camera/linux/bluetooth/preload/include/star_protocol.h`;
the app counterpart is `phone/app/src/main/java/dev/omshutter/protocol/StarProtocol.java`.
Shared capture commands and PEAKS/DONE framing are in `star_capture_protocol.h`
and `StarCaptureProtocol.java`. The current camera publishes body Q10 and gyro
attitude; lens-command lanes are canonical unavailable values.
