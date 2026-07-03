"""Receive-side logic for Pupil, the phone-as-BLE-ambient-light-sensor.

Pure logic only — no bleak, no I/O: BTHome v2 illuminance decoding, the RSSI
hysteresis gate, and the freshness state machine (added in a later task). The
bleak shell lives in scripts/ble_als_probe.py; the iris daemon imports this
module later. Spec: docs/superpowers/specs/2026-07-03-pupil-ble-als-design.md.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

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


class TrackerState(Enum):
    NEVER_SEEN = "never-seen"
    FRESH = "fresh"
    STALE = "stale"
    SCANNER_DEAD = "scanner-dead"


@dataclass(frozen=True)
class TrackerConfig:
    stale_after_s: float = 25.0
    scanner_dead_after_s: float = 30.0
    admit_rssi_dbm: float = -70.0
    drop_rssi_dbm: float = -80.0


@dataclass(frozen=True)
class PupilReading:
    lux: float
    packet_id: int | None
    rssi: float
    timestamp: float
    accepted: bool


class PupilTracker:
    """Freshness + presence state for one Pupil, fed by a scanner shell.

    Never conflate STALE (scanner healthy, Pupil silent/frozen) with
    SCANNER_DEAD (nothing heard from anyone): conflating them is how a frozen
    value gets trusted. Timestamps are caller-supplied monotonic seconds.
    """

    def __init__(self, config: TrackerConfig, started_at: float) -> None:
        self._config = config
        self._last_any_advert = started_at
        self._last_fresh: float | None = None
        self._last_packet_id: int | None = None
        self._admitted = False

    def on_any_advert(self, now: float) -> None:
        """Any BLE advert from any device — liveness of the scanner itself."""
        self._last_any_advert = now

    def reset_scanner(self, now: float) -> None:
        """The shell recreated the scanner; restart the dead-man's clock."""
        self._last_any_advert = now

    def on_pupil_advert(self, service_data: bytes, rssi: float, now: float) -> PupilReading | None:
        self._last_any_advert = now
        decoded = decode_bthome_illuminance(service_data)
        if decoded is None:
            return None
        # RSSI hysteresis: admit above the high bar, drop only below the low one.
        if self._admitted:
            if rssi < self._config.drop_rssi_dbm:
                self._admitted = False
        elif rssi > self._config.admit_rssi_dbm:
            self._admitted = True
        # Only a *changed* packet id refreshes freshness — a re-heard identical
        # advert (or a frozen phone behind a live heartbeat repeat) must not
        # look alive. No packet id at all => cannot dedup, treat as new.
        is_new = decoded.packet_id is None or decoded.packet_id != self._last_packet_id
        self._last_packet_id = decoded.packet_id
        accepted = self._admitted and is_new
        if accepted:
            self._last_fresh = now
        return PupilReading(
            lux=decoded.lux,
            packet_id=decoded.packet_id,
            rssi=rssi,
            timestamp=now,
            accepted=accepted,
        )

    def state(self, now: float) -> TrackerState:
        if now - self._last_any_advert > self._config.scanner_dead_after_s:
            return TrackerState.SCANNER_DEAD
        if self._last_fresh is None:
            return TrackerState.NEVER_SEEN
        if now - self._last_fresh > self._config.stale_after_s:
            return TrackerState.STALE
        return TrackerState.FRESH
