"""Tests for the pure Pupil receive-side logic (iris.pupil)."""

import json
from pathlib import Path

from pytest import approx

from iris.pupil import (
    BthomeIlluminance,
    PupilTracker,
    TrackerConfig,
    TrackerState,
    decode_bthome_illuminance,
)

GOLDEN = json.loads(
    (Path(__file__).parent / ".." / ".." / "contract" / "bthome-golden.json").read_text()
)


def test_decode_golden_vectors() -> None:
    for case in GOLDEN["cases"]:
        decoded = decode_bthome_illuminance(bytes.fromhex(case["service_data_hex"]))
        assert decoded is not None, case["description"]
        assert decoded.packet_id == case["packet_id"], case["description"]
        assert decoded.lux == approx(case["decoded_lux"]), case["description"]


def test_decode_rejects_encrypted() -> None:
    # Same payload as the worked example but with the encryption bit set.
    assert decode_bthome_illuminance(bytes.fromhex("41002a050e3800")) is None


def test_decode_rejects_wrong_version() -> None:
    # Version bits (5-7) = 1, not 2.
    assert decode_bthome_illuminance(bytes.fromhex("20002a050e3800")) is None


def test_decode_rejects_truncated() -> None:
    assert decode_bthome_illuminance(b"") is None
    assert decode_bthome_illuminance(bytes.fromhex("40002a050e")) is None  # cut mid-uint24


def test_decode_without_packet_id() -> None:
    decoded = decode_bthome_illuminance(bytes.fromhex("40050e3800"))
    assert decoded == BthomeIlluminance(packet_id=None, lux=143.5)


def test_decode_stops_at_unknown_object() -> None:
    # 0x02 (temperature) has an id-specific length we don't know how to skip;
    # decoding must stop rather than misparse. No illuminance seen => None.
    assert (
        decode_bthome_illuminance(bytes.fromhex("4000 2a 02 c409 05 0e3800".replace(" ", "")))
        is None
    )


PAYLOAD_143 = bytes.fromhex("40002a050e3800")  # pid 42, 143.5 lx
PAYLOAD_144 = bytes.fromhex("40002b050e3800")  # pid 43, same lux


def _tracker(**overrides: float) -> PupilTracker:
    return PupilTracker(TrackerConfig(**overrides), started_at=0.0)  # type: ignore[arg-type]


def test_tracker_never_seen_then_fresh() -> None:
    t = _tracker()
    assert t.state(1.0) is TrackerState.NEVER_SEEN
    reading = t.on_pupil_advert(PAYLOAD_143, rssi=-60.0, now=2.0)
    assert reading is not None and reading.accepted and reading.lux == 143.5
    assert t.state(3.0) is TrackerState.FRESH


def test_tracker_goes_stale_after_25s() -> None:
    t = _tracker()
    t.on_pupil_advert(PAYLOAD_143, rssi=-60.0, now=0.0)
    t.on_any_advert(now=20.0)  # other devices keep the scanner alive
    t.on_any_advert(now=26.0)
    assert t.state(26.0) is TrackerState.STALE


def test_tracker_repeated_packet_id_does_not_refresh() -> None:
    t = _tracker()
    t.on_pupil_advert(PAYLOAD_143, rssi=-60.0, now=0.0)
    second = t.on_pupil_advert(PAYLOAD_143, rssi=-60.0, now=20.0)  # same pid 42
    assert second is not None and not second.accepted
    t.on_any_advert(now=26.0)
    assert t.state(26.0) is TrackerState.STALE  # freshness anchored at t=0


def test_tracker_changed_packet_id_refreshes() -> None:
    t = _tracker()
    t.on_pupil_advert(PAYLOAD_143, rssi=-60.0, now=0.0)
    t.on_pupil_advert(PAYLOAD_144, rssi=-60.0, now=20.0)
    assert t.state(40.0) is TrackerState.FRESH


def test_rssi_hysteresis() -> None:
    t = _tracker()
    weak = t.on_pupil_advert(PAYLOAD_143, rssi=-75.0, now=0.0)  # below admit bar
    assert weak is not None and not weak.accepted
    admitted = t.on_pupil_advert(PAYLOAD_144, rssi=-65.0, now=1.0)  # above -70
    assert admitted is not None and admitted.accepted
    held = t.on_pupil_advert(bytes.fromhex("40002c050e3800"), rssi=-75.0, now=2.0)
    assert held is not None and held.accepted  # -75 is within hysteresis once admitted
    dropped = t.on_pupil_advert(bytes.fromhex("40002d050e3800"), rssi=-85.0, now=3.0)
    assert dropped is not None and not dropped.accepted  # below -80: dropped


def test_scanner_dead_and_reset() -> None:
    t = _tracker()
    t.on_pupil_advert(PAYLOAD_143, rssi=-60.0, now=0.0)
    assert t.state(31.0) is TrackerState.SCANNER_DEAD  # nothing heard for >30 s
    t.reset_scanner(now=31.0)  # shell restarted the scanner
    assert t.state(32.0) is not TrackerState.SCANNER_DEAD


def test_non_bthome_service_data_returns_none() -> None:
    t = _tracker()
    assert t.on_pupil_advert(b"\x00\x01", rssi=-60.0, now=0.0) is None


def test_rejected_reading_does_not_poison_dedup() -> None:
    t = _tracker()
    weak = t.on_pupil_advert(PAYLOAD_143, rssi=-78.0, now=0.0)  # pid 42, below admit bar
    assert weak is not None and not weak.accepted
    strong = t.on_pupil_advert(PAYLOAD_143, rssi=-55.0, now=2.0)  # same pid 42, now admitted
    assert strong is not None and strong.accepted
    assert t.state(3.0) is TrackerState.FRESH
