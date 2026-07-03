"""Tests for the pure Pupil receive-side logic (iris.pupil)."""

import json
from pathlib import Path

from pytest import approx

from iris.pupil import BthomeIlluminance, decode_bthome_illuminance

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
