#!/bin/sh
# Deliberately fail closed: the STAR prototype reclaims this dormant binary.
echo "gdbserver is disabled in this STAR prototype to reserve rootfs space" >&2
exit 126
