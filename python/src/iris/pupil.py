"""Receive-side logic for Pupil, the phone-as-BLE-ambient-light-sensor.

Pure logic only — no bleak, no I/O: BTHome v2 illuminance decoding, the RSSI
hysteresis gate, and the freshness state machine (added in a later task). The
bleak shell lives in scripts/ble_als_probe.py; the iris daemon imports this
module later. Spec: docs/superpowers/specs/2026-07-03-pupil-ble-als-design.md.
"""

from __future__ import annotations

from dataclasses import dataclass

BTHOME_SERVICE_UUID = "0000fcd2-0000-1000-8000-00805f9b34fb"

_OBJ_PACKET_ID = 0x00
_OBJ_ILLUMINANCE = 0x05


@dataclass(frozen=True)
class BthomeIlluminance:
    packet_id: int | None
    lux: float


def decode_bthome_illuminance(service_data: bytes) -> BthomeIlluminance | None:
    """Decode an unencrypted BTHome v2 illuminance payload; None if it isn't one."""
    if not service_data:
        return None
    info = service_data[0]
    if info & 0x01:  # encrypted — out of scope for v1
        return None
    if (info >> 5) != 2:  # BTHome version 2
        return None
    packet_id: int | None = None
    lux: float | None = None
    pos = 1
    while pos < len(service_data):
        obj = service_data[pos]
        if obj == _OBJ_PACKET_ID and pos + 1 < len(service_data):
            packet_id = service_data[pos + 1]
            pos += 2
        elif obj == _OBJ_ILLUMINANCE and pos + 3 < len(service_data):
            raw = int.from_bytes(service_data[pos + 1 : pos + 4], "little")
            lux = raw / 100.0
            pos += 4
        else:
            # Object lengths are id-specific; we can't skip unknown ids.
            # We only ever parse our own sensor, so stop at anything else.
            break
    if lux is None:
        return None
    return BthomeIlluminance(packet_id=packet_id, lux=lux)
