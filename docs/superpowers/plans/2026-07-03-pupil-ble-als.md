# Pupil (phone BLE ambient-light sensor) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Pupil — an Android app that broadcasts the phone's ambient-light sensor as BTHome v2 BLE adverts — plus the laptop-side receiver (pure decode/state-machine module + bleak probe script), per the approved spec `docs/superpowers/specs/2026-07-03-pupil-ble-als-design.md`.

**Architecture:** Phone: one foreground service (type `connectedDevice`) reads the ALS via an acquisition ladder (wakeup ALS → non-wakeup + wakelock), encodes `(packetId, lux)` into BTHome v2 service data, and updates one non-connectable legacy `AdvertisingSet` in place — event-driven with a 10 s heartbeat, no polling loop. Laptop: `python/src/iris/pupil.py` holds all pure logic (BTHome decode, RSSI hysteresis gate, FRESH/STALE/SCANNER_DEAD tracker); `python/scripts/ble_als_probe.py` is a thin bleak shell. A shared golden-vector file keeps encoder and decoder byte-identical, with `bthome-ble` (Home Assistant's parser) as a third-party oracle.

**Tech Stack:** Kotlin (plain Views, no Compose), AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.10.2, minSdk 31 / compileSdk 35; Python 3.13 (uv project), `bleak` (runtime), `bthome-ble` (test oracle only), pytest, mypy `--strict` on `src/`.

## Global Constraints

- **Environment tiers (molly):** the host is immutable — Java/gradle/SDK work happens in the **dev toolbox** (`toolbox run -c dev …`); the Android SDK lives user-scoped at `~/Android/Sdk`. Python work runs on the host via `uv` from `python/`.
- **Package id:** `io.github.genneth.pupil`. App display name: **Pupil**.
- **BTHome v2, unencrypted:** service-data payload = `0x40` device-info byte, then objects in ascending id order: `0x00` packet id (uint8), `0x05` illuminance (uint24 LE, 0.01 lx). The encoder emits the **payload only** — the Android stack adds the AD header + `0xFCD2` UUID (`AdvertiseData.addServiceData`); no local name, no flags (non-connectable legacy advert; our receiver uses active scanning).
- **Timing defaults (spec §6):** advert interval 400 ms (`setInterval(640)` — 0.625 ms units), TX `TX_POWER_LOW` (−15 dBm), deadband max(1 lx, 5 %), min update gap 500 ms, heartbeat 10 s; receiver stale-after 25 s, scanner-dead 30 s, RSSI hysteresis admit > −70 / drop < −80 dBm.
- **Identify Pupil by payload, never MAC** (Android rotates the address ~15 min).
- **Only a changed packet id refreshes freshness** (frozen phone must not look alive).
- **Python gates:** `./dev.sh check` (ruff format+lint, mypy strict on `src`, pytest) must stay green; line length 100. Android unit tests run via `./dev.sh android` (NOT part of `check` — the host has no JVM).
- **Commit after every task** (the pre-commit hook runs the Python gate automatically).

---

### Task 1: Golden contract fixtures + Python BTHome decoder

**Files:**
- Create: `contract/bthome-golden.json`
- Create: `python/src/iris/pupil.py` (decode half)
- Test: `python/tests/test_pupil.py`

**Interfaces:**
- Produces: `iris.pupil.decode_bthome_illuminance(service_data: bytes) -> BthomeIlluminance | None`; `BthomeIlluminance(packet_id: int | None, lux: float)`; constant `BTHOME_SERVICE_UUID = "0000fcd2-0000-1000-8000-00805f9b34fb"`. The JSON fixture schema: `{"cases": [{"description", "packet_id", "input_lux", "decoded_lux", "service_data_hex"}]}` — consumed by Tasks 2 and 7.

- [ ] **Step 1: Write the fixture file**

`contract/bthome-golden.json` (repo root — the single source of truth both languages test against):

```json
{
  "comment": "BTHome v2 service-data payloads (no AD header / UUID). Kotlin BthomeEncoder must produce service_data_hex from (packet_id, input_lux); Python decode must recover (packet_id, decoded_lux). decoded_lux differs from input_lux only by 0.01-lx quantisation / uint24 clamping.",
  "cases": [
    {"description": "spec worked example", "packet_id": 42, "input_lux": 143.5, "decoded_lux": 143.5, "service_data_hex": "40002a050e3800"},
    {"description": "bthome.io spec's own illuminance example", "packet_id": 0, "input_lux": 13460.67, "decoded_lux": 13460.67, "service_data_hex": "40000005138a14"},
    {"description": "darkness, packet id wraps high", "packet_id": 255, "input_lux": 0.0, "decoded_lux": 0.0, "service_data_hex": "4000ff05000000"},
    {"description": "clamped at uint24 ceiling", "packet_id": 1, "input_lux": 200000.0, "decoded_lux": 167772.15, "service_data_hex": "40000105ffffff"},
    {"description": "sub-resolution rounds to 0.01 lx", "packet_id": 7, "input_lux": 0.006, "decoded_lux": 0.01, "service_data_hex": "40000705010000"}
  ]
}
```

- [ ] **Step 2: Write the failing decoder tests**

`python/tests/test_pupil.py`:

```python
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
    assert decode_bthome_illuminance(bytes.fromhex("4000 2a 02 c409 05 0e3800".replace(" ", ""))) is None
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd python && uv run pytest tests/test_pupil.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'iris.pupil'`

- [ ] **Step 4: Write the decoder**

`python/src/iris/pupil.py`:

```python
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd python && uv run pytest tests/test_pupil.py -v`
Expected: 6 passed

- [ ] **Step 6: Run the full gate and commit**

```bash
./dev.sh check
git add contract/bthome-golden.json python/src/iris/pupil.py python/tests/test_pupil.py
git commit -m "Pupil receiver: BTHome v2 illuminance decoder + shared golden vectors"
```

---

### Task 2: bthome-ble oracle test (HA-compatibility contract)

**Files:**
- Modify: `python/pyproject.toml` (dev dependency)
- Test: `python/tests/test_pupil_oracle.py`

**Interfaces:**
- Consumes: `contract/bthome-golden.json` (Task 1 schema).
- Produces: nothing new — this test pins our wire format to what Home Assistant's parser actually decodes.

- [ ] **Step 1: Add the dev dependency**

Run: `cd python && uv add --group dev bthome-ble`
Expected: resolves and adds `bthome-ble` to `[dependency-groups] dev` in `pyproject.toml`.

- [ ] **Step 2: Write the failing oracle test**

`python/tests/test_pupil_oracle.py`:

```python
"""Golden vectors decoded by bthome-ble — the exact parser Home Assistant uses.

If this passes, anything that understands BTHome (HA, Theengs) reads Pupil's
adverts identically to our own decoder. A fresh parser per case: bthome-ble
dedups repeated packet ids within one parser instance.
"""

import json
from pathlib import Path

from bthome_ble import BTHomeBluetoothDeviceData
from home_assistant_bluetooth import BluetoothServiceInfo
from pytest import approx

from iris.pupil import BTHOME_SERVICE_UUID

GOLDEN = json.loads(
    (Path(__file__).parent / ".." / ".." / "contract" / "bthome-golden.json").read_text()
)


def _service_info(payload: bytes) -> BluetoothServiceInfo:
    return BluetoothServiceInfo(
        name="Pupil",
        address="AA:BB:CC:DD:EE:FF",
        rssi=-60,
        manufacturer_data={},
        service_data={BTHOME_SERVICE_UUID: payload},
        service_uuids=[],
        source="local",
    )


def test_bthome_ble_oracle_decodes_golden_vectors() -> None:
    for case in GOLDEN["cases"]:
        parser = BTHomeBluetoothDeviceData()
        update = parser.update(_service_info(bytes.fromhex(case["service_data_hex"])))
        lux_values = [
            v.native_value
            for key, v in update.entity_values.items()
            if key.key == "illuminance"
        ]
        assert lux_values, f"oracle saw no illuminance: {case['description']}"
        assert lux_values[0] == approx(case["decoded_lux"]), case["description"]
```

- [ ] **Step 3: Run it**

Run: `cd python && uv run pytest tests/test_pupil_oracle.py -v`
Expected: 1 passed. (If the `bthome-ble` API surface differs — e.g. `entity_values` naming — consult `https://github.com/Bluetooth-Devices/bthome-ble` README and adjust the *test*, never the fixture bytes: the bytes are spec-verified.)

- [ ] **Step 4: Gate and commit**

```bash
./dev.sh check
git add python/pyproject.toml python/uv.lock python/tests/test_pupil_oracle.py
git commit -m "Pupil receiver: bthome-ble (Home Assistant parser) as decode oracle"
```

---

### Task 3: PupilTracker — RSSI hysteresis gate + freshness state machine

**Files:**
- Modify: `python/src/iris/pupil.py` (append)
- Test: `python/tests/test_pupil.py` (append)

**Interfaces:**
- Produces (consumed by Task 4 and later by the iris daemon):
  - `TrackerConfig(stale_after_s: float = 25.0, scanner_dead_after_s: float = 30.0, admit_rssi_dbm: float = -70.0, drop_rssi_dbm: float = -80.0)`
  - `TrackerState` enum: `NEVER_SEEN | FRESH | STALE | SCANNER_DEAD` (`.value` is the kebab-case string)
  - `PupilReading(lux: float, packet_id: int | None, rssi: float, timestamp: float, accepted: bool)`
  - `PupilTracker(config, started_at)` with `.on_any_advert(now)`, `.on_pupil_advert(service_data, rssi, now) -> PupilReading | None`, `.state(now) -> TrackerState`, `.reset_scanner(now)`
  - All timestamps are caller-supplied monotonic seconds (`time.monotonic()`).

- [ ] **Step 1: Write the failing tests** (append to `python/tests/test_pupil.py`)

```python
from iris.pupil import PupilTracker, TrackerConfig, TrackerState

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
```

- [ ] **Step 2: Run to verify failure**

Run: `cd python && uv run pytest tests/test_pupil.py -v`
Expected: FAIL — `ImportError: cannot import name 'PupilTracker'`

- [ ] **Step 3: Implement** (append to `python/src/iris/pupil.py`; add `from enum import Enum` to the imports)

```python
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

    def on_pupil_advert(
        self, service_data: bytes, rssi: float, now: float
    ) -> PupilReading | None:
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
```

- [ ] **Step 4: Run tests**

Run: `cd python && uv run pytest tests/test_pupil.py -v`
Expected: 13 passed

- [ ] **Step 5: Gate and commit**

```bash
./dev.sh check
git add python/src/iris/pupil.py python/tests/test_pupil.py
git commit -m "Pupil receiver: RSSI hysteresis gate + freshness state machine"
```

---

### Task 4: The probe script (bleak shell)

**Files:**
- Modify: `python/pyproject.toml` (runtime dependency `bleak`)
- Create: `python/scripts/ble_als_probe.py`
- Modify: `python/scripts/README.md` (one index line, matching the existing entries' style)

**Interfaces:**
- Consumes: everything Task 3 produces.
- Produces: the CLI (`uv run python scripts/ble_als_probe.py [--min-rssi -75] [--stale-after 25] [--dead-after 30] [--csv PATH] [--all]`) — also the on-device acceptance instrument for Task 10.

- [ ] **Step 1: Add bleak**

Run: `cd python && uv add bleak`

- [ ] **Step 2: Write the script**

`python/scripts/ble_als_probe.py` (scripts are exempt from mypy but not from ruff format):

```python
#!/usr/bin/env python3
"""Live probe for Pupil's BTHome BLE adverts.

Prints every accepted reading plus state transitions; --csv logs every decoded
advert (accepted or not) for the RSSI room walk. Active scanning — no BlueZ
config needed. Identify by payload, never MAC (Android rotates it ~15 min).

Usage:  cd python && uv run python scripts/ble_als_probe.py [--csv walk.csv]
"""

import argparse
import asyncio
import csv
import datetime
import time
from typing import Any

from bleak import BleakScanner

from iris.pupil import BTHOME_SERVICE_UUID, PupilTracker, TrackerConfig, TrackerState


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--min-rssi", type=float, default=-75.0,
                    help="centre of the RSSI gate; admit at +5, drop at -5 dBm around it")
    ap.add_argument("--stale-after", type=float, default=25.0)
    ap.add_argument("--dead-after", type=float, default=30.0)
    ap.add_argument("--csv", type=str, default=None, help="log every decoded advert to CSV")
    ap.add_argument("--all", action="store_true", help="also print rejected/stale readings")
    args = ap.parse_args()
    asyncio.run(run(args))


async def run(args: argparse.Namespace) -> None:
    config = TrackerConfig(
        stale_after_s=args.stale_after,
        scanner_dead_after_s=args.dead_after,
        admit_rssi_dbm=args.min_rssi + 5.0,
        drop_rssi_dbm=args.min_rssi - 5.0,
    )
    tracker = PupilTracker(config, started_at=time.monotonic())
    csv_writer = None
    if args.csv:
        csv_file = open(args.csv, "w", newline="")
        csv_writer = csv.writer(csv_file)
        csv_writer.writerow(["iso_ts", "lux", "packet_id", "rssi", "accepted", "state"])

    def on_advert(_device: Any, adv: Any) -> None:
        now = time.monotonic()
        payload = adv.service_data.get(BTHOME_SERVICE_UUID)
        if payload is None:
            tracker.on_any_advert(now)
            return
        reading = tracker.on_pupil_advert(bytes(payload), rssi=float(adv.rssi), now=now)
        if reading is None:
            return
        state = tracker.state(now)
        if csv_writer:
            csv_writer.writerow([
                datetime.datetime.now().isoformat(timespec="milliseconds"),
                f"{reading.lux:.2f}", reading.packet_id, f"{reading.rssi:.0f}",
                int(reading.accepted), state.value,
            ])
        if reading.accepted or args.all:
            tag = "" if reading.accepted else "  [rejected: rssi gate or repeat]"
            print(f"{_ts()}  {reading.lux:9.2f} lx  rssi {reading.rssi:4.0f}  "
                  f"pid {reading.packet_id}  {state.value}{tag}")

    last_state = TrackerState.NEVER_SEEN
    while True:  # outer loop: recreate the scanner when it goes dead
        print(f"{_ts()}  scanning (active) for BTHome service data {BTHOME_SERVICE_UUID[:8]}…")
        async with BleakScanner(on_advert, service_uuids=[BTHOME_SERVICE_UUID]):
            while True:
                await asyncio.sleep(1.0)
                state = tracker.state(time.monotonic())
                if state is not last_state:
                    print(f"{_ts()}  state: {last_state.value} -> {state.value}")
                    last_state = state
                if state is TrackerState.SCANNER_DEAD:
                    break
        print(f"{_ts()}  scanner dead — recreating in 2 s (rfkill/suspend/adapter race?)")
        await asyncio.sleep(2.0)
        tracker.reset_scanner(time.monotonic())


def _ts() -> str:
    return datetime.datetime.now().strftime("%H:%M:%S")


if __name__ == "__main__":
    main()
```

Note: `service_uuids=` narrows BlueZ's discovery filter, but BlueZ merges all clients' filters (GNOME widens ours) — the `adv.service_data.get(...)` lookup IS the client-side re-filter; nothing further needed.

- [ ] **Step 3: Smoke-test on the laptop (no phone yet)**

Run: `cd python && timeout 15 uv run python scripts/ble_als_probe.py --all || true`
Expected: prints the "scanning (active)…" line, runs quietly (or shows neighbours' BTHome devices if any), exits after the timeout without a traceback. If BlueZ raises `org.bluez.Error.InProgress`, re-run once — the outer restart loop is the long-term handling.

- [ ] **Step 4: Add the script to `python/scripts/README.md`** — one line in the index, e.g. `ble_als_probe.py — live Pupil/BTHome advert probe (lux, RSSI, freshness state; --csv for the room walk)`, formatted like the neighbouring entries.

- [ ] **Step 5: Gate and commit**

```bash
./dev.sh check
git add python/pyproject.toml python/uv.lock python/scripts/ble_als_probe.py python/scripts/README.md
git commit -m "Pupil receiver: bleak probe script with scanner watchdog"
```

---

### Task 5: Android toolchain (dev toolbox + user-scoped SDK)

**Files:**
- No repo files; installs JDK+gradle in the dev toolbox and the Android SDK at `~/Android/Sdk`.

**Interfaces:**
- Produces: working `java`, `gradle`, `sdkmanager`, `adb` for all later tasks. All gradle/adb invocations in later tasks run as `toolbox run -c dev bash -lc '…'`.

- [ ] **Step 1: JDK + gradle + unzip in the dev toolbox**

Run: `toolbox run -c dev sudo dnf install -y java-21-openjdk-devel gradle unzip`
Expected: installs cleanly (dnf works inside toolboxes).

- [ ] **Step 2: Android command-line tools into `~/Android/Sdk`**

```bash
toolbox run -c dev bash -lc '
  mkdir -p ~/Android/Sdk/cmdline-tools &&
  curl -Lo /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip &&
  unzip -q -o /tmp/clt.zip -d ~/Android/Sdk/cmdline-tools &&
  rm -f /tmp/clt.zip &&
  [ -d ~/Android/Sdk/cmdline-tools/latest ] || mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest'
```

- [ ] **Step 3: Accept licenses, install platform + build tools**

```bash
toolbox run -c dev bash -lc 'yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses'
toolbox run -c dev bash -lc '~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"'
```

- [ ] **Step 4: Verify**

Run: `toolbox run -c dev bash -lc 'java -version && gradle --version && ~/Android/Sdk/platform-tools/adb --version'`
Expected: OpenJDK 21, Gradle (any Fedora-packaged version ≥ 8 — only used to bootstrap the wrapper), adb version string. Nothing to commit.

---

### Task 6: Android project scaffold (builds an installable empty app)

**Files:**
- Create: `android/.gitignore`, `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/io/github/genneth/pupil/MainActivity.kt` (stub — replaced in Task 10)
- Create resources: `android/app/src/main/res/values/strings.xml`, `res/values/themes.xml`, `res/values/colors.xml`, `res/drawable/ic_launcher_foreground.xml`, `res/drawable/ic_stat_pupil.xml`, `res/mipmap-anydpi-v26/ic_launcher.xml`, `res/mipmap-anydpi-v26/ic_launcher_round.xml`, `res/layout/activity_main.xml`
- Create: gradle wrapper (generated) — commit `gradlew`, `gradlew.bat`, `gradle/wrapper/*`

**Interfaces:**
- Produces: `./gradlew :app:assembleDebug` and `:app:testDebugUnitTest` working from `android/`; namespace `io.github.genneth.pupil`; theme `Theme.Pupil`; notification small icon `R.drawable.ic_stat_pupil`; layout ids consumed by Task 10: `statusText`, `luxText`, `sensorReport`, `toggleButton`, `batteryButton`, `settingsButton`.

- [ ] **Step 0: Generate the wrapper first, in the still-empty `android/`**

The Fedora-packaged gradle only bootstraps the wrapper; it must NOT see the build files (it may be too old to resolve AGP 8.7.3). Empty-dir wrapper generation is supported and warning-only:

```bash
mkdir -p /var/home/genneth/iris/android
toolbox run -c dev bash -lc 'cd ~/iris/android && gradle wrapper --gradle-version 8.10.2'
```

Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/`. All later gradle commands use `./gradlew` (which downloads and runs Gradle 8.10.2 itself).

- [ ] **Step 1: Write the gradle/build files**

`android/.gitignore`:

```
.gradle/
build/
local.properties
.idea/
*.iml
```

`android/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "pupil"
include(":app")
```

`android/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
```

`android/gradle.properties`:

```
android.useAndroidX=true
org.gradle.jvmargs=-Xmx2g
```

`android/app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.genneth.pupil"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.genneth.pupil"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
```

- [ ] **Step 2: Manifest and stub activity**

`android/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
    <uses-feature android:name="android.hardware.sensor.light" android:required="true" />

    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:theme="@style/Theme.Pupil">

        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity android:name=".SettingsActivity" android:exported="false" />

        <service
            android:name=".PupilService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
    </application>
</manifest>
```

(The manifest references `.SettingsActivity` and `.PupilService` which arrive in Tasks 8–9; for this task's build to succeed, create both as empty stubs alongside the stub MainActivity:)

`android/app/src/main/java/io/github/genneth/pupil/MainActivity.kt` (stub):

```kotlin
package io.github.genneth.pupil

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
```

`android/app/src/main/java/io/github/genneth/pupil/SettingsActivity.kt` (stub, replaced in Task 8):

```kotlin
package io.github.genneth.pupil

import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity()
```

`android/app/src/main/java/io/github/genneth/pupil/PupilService.kt` (stub, replaced in Task 9):

```kotlin
package io.github.genneth.pupil

import android.app.Service
import android.content.Intent
import android.os.IBinder

class PupilService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
```

- [ ] **Step 3: Resources**

`android/app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">Pupil</string>
</resources>
```

`android/app/src/main/res/values/colors.xml`:

```xml
<resources>
    <color name="pupil_navy">#16283C</color>
    <color name="pupil_iris">#3D6FA5</color>
</resources>
```

`android/app/src/main/res/values/themes.xml`:

```xml
<resources>
    <style name="Theme.Pupil" parent="Theme.AppCompat.DayNight">
        <item name="colorPrimary">@color/pupil_iris</item>
        <item name="preferenceTheme">@style/PreferenceThemeOverlay</item>
    </style>
</resources>
```

`android/app/src/main/res/drawable/ic_launcher_foreground.xml` — the eye whose pupil is the Bluetooth rune:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <!-- iris -->
    <path android:pathData="M54,54m-26,0a26,26 0 1,0 52,0a26,26 0 1,0 -52,0"
        android:fillColor="@color/pupil_iris" />
    <path android:pathData="M54,54m-26,0a26,26 0 1,0 52,0a26,26 0 1,0 -52,0"
        android:strokeColor="#FFFFFF" android:strokeWidth="3" android:fillColor="#00000000" />
    <!-- Bluetooth rune as the pupil -->
    <path android:pathData="M54,38 L54,70 M54,38 L63,46 L45,62 M54,70 L63,62 L45,46"
        android:strokeColor="#FFFFFF" android:strokeWidth="4"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:fillColor="#00000000" />
</vector>
```

`android/app/src/main/res/drawable/ic_stat_pupil.xml` (notification small icon — rune only, white):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M12,4 L12,20 M12,4 L16.5,8 L7.5,16 M12,20 L16.5,16 L7.5,8"
        android:strokeColor="#FFFFFF" android:strokeWidth="2"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:fillColor="#00000000" />
</vector>
```

`android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (and an identical `ic_launcher_round.xml`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/pupil_navy" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

`android/app/src/main/res/layout/activity_main.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:orientation="vertical" android:padding="24dp">

    <TextView android:id="@+id/statusText"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="stopped" android:textSize="16sp" />

    <TextView android:id="@+id/luxText"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="—" android:textSize="48sp" android:paddingVertical="16dp" />

    <TextView android:id="@+id/sensorReport"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="" android:textSize="13sp" android:fontFamily="monospace"
        android:paddingBottom="16dp" />

    <Button android:id="@+id/toggleButton"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="Start broadcasting" />

    <Button android:id="@+id/batteryButton"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="Battery exemption…" />

    <Button android:id="@+id/settingsButton"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="Settings" />
</LinearLayout>
```

- [ ] **Step 4: Point at the SDK, build**

```bash
echo "sdk.dir=/var/home/genneth/Android/Sdk" > /var/home/genneth/iris/android/local.properties
toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:assembleDebug'
```

Expected: `BUILD SUCCESSFUL`; APK at `android/app/build/outputs/apk/debug/app-debug.apk`. (First run downloads Gradle 8.10.2 + dependencies; takes minutes.)

- [ ] **Step 5: Commit**

```bash
git add android/
git commit -m "Pupil app: gradle scaffold, manifest, icon (eye with Bluetooth-rune pupil)"
```

(`local.properties` is gitignored; the wrapper jar under `android/gradle/wrapper/` is committed deliberately.)

---

### Task 7: BthomeEncoder + JUnit golden-vector test

**Files:**
- Create: `android/app/src/main/java/io/github/genneth/pupil/BthomeEncoder.kt`
- Test: `android/app/src/test/java/io/github/genneth/pupil/BthomeEncoderTest.kt`

**Interfaces:**
- Consumes: `contract/bthome-golden.json` (Task 1).
- Produces: `BthomeEncoder.encode(packetId: Int, lux: Float): ByteArray` — the service-data payload (no AD header/UUID). Consumed by Task 9.

- [ ] **Step 1: Write the failing test**

`android/app/src/test/java/io/github/genneth/pupil/BthomeEncoderTest.kt`:

```kotlin
package io.github.genneth.pupil

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class BthomeEncoderTest {

    // Gradle runs unit tests with the module dir (android/app) as CWD.
    private val golden = JSONObject(File("../../contract/bthome-golden.json").readText())

    @Test
    fun goldenVectors() {
        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = BthomeEncoder
                .encode(c.getInt("packet_id"), c.getDouble("input_lux").toFloat())
                .joinToString("") { "%02x".format(it) }
            assertEquals(c.getString("description"), c.getString("service_data_hex"), got)
        }
    }

    @Test
    fun rejectsOutOfRangePacketId() {
        assertThrows(IllegalArgumentException::class.java) { BthomeEncoder.encode(256, 1f) }
        assertThrows(IllegalArgumentException::class.java) { BthomeEncoder.encode(-1, 1f) }
    }

    @Test
    fun negativeLuxClampsToZero() {
        assertEquals(
            "4000050500 0000".replace(" ", ""),
            BthomeEncoder.encode(5, -3f).joinToString("") { "%02x".format(it) },
        )
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:testDebugUnitTest'`
Expected: compilation FAILS — `unresolved reference: BthomeEncoder`

- [ ] **Step 3: Implement**

`android/app/src/main/java/io/github/genneth/pupil/BthomeEncoder.kt`:

```kotlin
package io.github.genneth.pupil

/**
 * BTHome v2 service-data payload builder (unencrypted, non-trigger).
 *
 * Emits ONLY the payload — the Android stack adds the AD header and the
 * 0xFCD2 UUID via AdvertiseData.addServiceData(). Object order (packet id
 * 0x00 before illuminance 0x05) is mandated by the spec. Golden vectors:
 * contract/bthome-golden.json (shared with the Python receiver tests).
 */
object BthomeEncoder {
    private const val DEVICE_INFO: Byte = 0x40 // v2, unencrypted, non-trigger

    fun encode(packetId: Int, lux: Float): ByteArray {
        require(packetId in 0..255) { "packetId must fit a uint8, got $packetId" }
        val centiLux = Math.round(lux.toDouble() * 100.0).coerceIn(0L, 0xFFFFFFL)
        return byteArrayOf(
            DEVICE_INFO,
            0x00, packetId.toByte(),
            0x05,
            (centiLux and 0xFF).toByte(),
            ((centiLux shr 8) and 0xFF).toByte(),
            ((centiLux shr 16) and 0xFF).toByte(),
        )
    }
}
```

- [ ] **Step 4: Run tests**

Run: `toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:testDebugUnitTest'`
Expected: `BUILD SUCCESSFUL`, 3 tests passing.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/github/genneth/pupil/BthomeEncoder.kt android/app/src/test/java/io/github/genneth/pupil/BthomeEncoderTest.kt
git commit -m "Pupil app: BTHome v2 encoder, tested against the shared golden vectors"
```

---

### Task 8: PupilConfig, UpdateGovernor, settings screen

**Files:**
- Create: `android/app/src/main/java/io/github/genneth/pupil/PupilConfig.kt`
- Create: `android/app/src/main/java/io/github/genneth/pupil/UpdateGovernor.kt`
- Replace stub: `android/app/src/main/java/io/github/genneth/pupil/SettingsActivity.kt`
- Create: `android/app/src/main/res/xml/prefs.xml`
- Test: `android/app/src/test/java/io/github/genneth/pupil/UpdateGovernorTest.kt`

**Interfaces:**
- Produces (consumed by Task 9):
  - `PupilConfig(context)` with `intervalUnits: Int`, `txPowerLevel: Int`, `heartbeatMs: Long`, `deadbandFraction: Float`, `minGapMs: Long` (500), `deadbandAbsLux: Float` (1f)
  - `UpdateGovernor(minGapMs: Long, deadbandFraction: Float, deadbandAbsLux: Float)` with `significantChange(lux: Float): Boolean`, `gapRemainingMs(nowMs: Long): Long`, `recordSent(lux: Float, nowMs: Long)`

- [ ] **Step 1: Write the failing UpdateGovernor test**

`android/app/src/test/java/io/github/genneth/pupil/UpdateGovernorTest.kt`:

```kotlin
package io.github.genneth.pupil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateGovernorTest {
    private fun governor() = UpdateGovernor(minGapMs = 500, deadbandFraction = 0.05f, deadbandAbsLux = 1f)

    @Test
    fun firstReadingIsAlwaysSignificant() {
        assertTrue(governor().significantChange(0f))
    }

    @Test
    fun deadbandAbsoluteFloorAtLowLux() {
        val g = governor()
        g.recordSent(10f, 0)
        assertFalse(g.significantChange(10.4f)) // < max(1 lx, 0.5 lx)
        assertTrue(g.significantChange(11.1f))  // > 1 lx
    }

    @Test
    fun deadbandRelativeAtHighLux() {
        val g = governor()
        g.recordSent(1000f, 0)
        assertFalse(g.significantChange(1040f)) // < 5 % (50 lx)
        assertTrue(g.significantChange(1060f))
    }

    @Test
    fun rateGap() {
        val g = governor()
        g.recordSent(10f, 1000)
        assertEquals(400, g.gapRemainingMs(1100))
        assertEquals(0, g.gapRemainingMs(1500))
        assertEquals(0, g.gapRemainingMs(9999))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:testDebugUnitTest'`
Expected: compilation FAILS — `unresolved reference: UpdateGovernor`

- [ ] **Step 3: Implement UpdateGovernor**

`android/app/src/main/java/io/github/genneth/pupil/UpdateGovernor.kt`:

```kotlin
package io.github.genneth.pupil

import kotlin.math.abs
import kotlin.math.max

/** Pure deadband + rate-limit policy for advert payload updates (spec §5). */
class UpdateGovernor(
    private val minGapMs: Long,
    private val deadbandFraction: Float,
    private val deadbandAbsLux: Float,
) {
    private var lastSentLux = Float.NEGATIVE_INFINITY
    private var lastSentAtMs = Long.MIN_VALUE / 2

    fun significantChange(lux: Float): Boolean =
        abs(lux - lastSentLux) >= max(deadbandAbsLux, abs(lastSentLux) * deadbandFraction)

    fun gapRemainingMs(nowMs: Long): Long = max(0L, lastSentAtMs + minGapMs - nowMs)

    fun recordSent(lux: Float, nowMs: Long) {
        lastSentLux = lux
        lastSentAtMs = nowMs
    }
}
```

- [ ] **Step 4: Run tests** — same gradle command, expected `BUILD SUCCESSFUL`, 7 tests total passing.

- [ ] **Step 5: PupilConfig + preferences UI**

`android/app/src/main/java/io/github/genneth/pupil/PupilConfig.kt`:

```kotlin
package io.github.genneth.pupil

import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import androidx.preference.PreferenceManager

/** Spec §6 knobs, SharedPreferences-backed; changes apply on next service start. */
class PupilConfig(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    /** AdvertisingSetParameters.setInterval units are 0.625 ms: 400 ms -> 640. */
    val intervalUnits: Int
        get() = (prefs.getString("interval_ms", "400")!!.toInt() * 1000) / 625

    val txPowerLevel: Int
        get() = when (prefs.getString("tx_power", "low")) {
            "ultra_low" -> AdvertisingSetParameters.TX_POWER_ULTRA_LOW
            "medium" -> AdvertisingSetParameters.TX_POWER_MEDIUM
            "high" -> AdvertisingSetParameters.TX_POWER_HIGH
            else -> AdvertisingSetParameters.TX_POWER_LOW
        }

    val heartbeatMs: Long
        get() = prefs.getString("heartbeat_s", "10")!!.toLong() * 1000

    val deadbandFraction: Float
        get() = prefs.getString("deadband_pct", "5")!!.toFloat() / 100f

    val minGapMs: Long = 500
    val deadbandAbsLux: Float = 1f
}
```

`android/app/src/main/res/xml/prefs.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android">
    <ListPreference
        android:key="interval_ms" android:title="Advertising interval"
        android:entries="@array/interval_labels" android:entryValues="@array/interval_values"
        android:defaultValue="400" android:summary="%s" />
    <ListPreference
        android:key="tx_power" android:title="TX power"
        android:entries="@array/tx_labels" android:entryValues="@array/tx_values"
        android:defaultValue="low" android:summary="%s" />
    <ListPreference
        android:key="deadband_pct" android:title="Deadband"
        android:entries="@array/deadband_labels" android:entryValues="@array/deadband_values"
        android:defaultValue="5" android:summary="%s" />
    <ListPreference
        android:key="heartbeat_s" android:title="Heartbeat"
        android:entries="@array/heartbeat_labels" android:entryValues="@array/heartbeat_values"
        android:defaultValue="10" android:summary="%s" />
</PreferenceScreen>
```

Append to `android/app/src/main/res/values/strings.xml` (inside `<resources>`):

```xml
    <string-array name="interval_labels"><item>100 ms</item><item>250 ms</item><item>400 ms (default)</item><item>1 s</item></string-array>
    <string-array name="interval_values"><item>100</item><item>250</item><item>400</item><item>1000</item></string-array>
    <string-array name="tx_labels"><item>Ultra low (−21 dBm, arm's reach)</item><item>Low (−15 dBm, default: room-scale)</item><item>Medium (−7 dBm)</item><item>High (+1 dBm, through walls)</item></string-array>
    <string-array name="tx_values"><item>ultra_low</item><item>low</item><item>medium</item><item>high</item></string-array>
    <string-array name="deadband_labels"><item>1 %</item><item>5 % (default)</item><item>10 %</item><item>20 %</item></string-array>
    <string-array name="deadband_values"><item>1</item><item>5</item><item>10</item><item>20</item></string-array>
    <string-array name="heartbeat_labels"><item>5 s</item><item>10 s (default)</item><item>30 s</item><item>60 s</item></string-array>
    <string-array name="heartbeat_values"><item>5</item><item>10</item><item>30</item><item>60</item></string-array>
```

Replace `android/app/src/main/java/io/github/genneth/pupil/SettingsActivity.kt`:

```kotlin
package io.github.genneth.pupil

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, PrefsFragment())
            .commit()
    }

    class PrefsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
        }
    }
}
```

- [ ] **Step 6: Build + test, commit**

Run: `toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:testDebugUnitTest :app:assembleDebug'`
Expected: `BUILD SUCCESSFUL`.

```bash
git add android/app/src/main/java/io/github/genneth/pupil/ android/app/src/main/res/ android/app/src/test/
git commit -m "Pupil app: config knobs, deadband/rate governor (tested), settings screen"
```

---

### Task 9: PupilService — sensor ladder, advertising set, heartbeat

**Files:**
- Create: `android/app/src/main/java/io/github/genneth/pupil/PupilState.kt`
- Replace stub: `android/app/src/main/java/io/github/genneth/pupil/PupilService.kt`

**Interfaces:**
- Consumes: `BthomeEncoder.encode` (Task 7), `PupilConfig` + `UpdateGovernor` (Task 8).
- Produces (consumed by Task 10): `PupilState` singleton — `@Volatile var running: Boolean`, `lastLux: Float?`, `packetId: Int`, `sensorRung: String`; service started/stopped via explicit `Intent(context, PupilService::class.java)`.

- [ ] **Step 1: PupilState**

`android/app/src/main/java/io/github/genneth/pupil/PupilState.kt`:

```kotlin
package io.github.genneth.pupil

/** Tiny UI-facing snapshot; the activity polls it (no binder ceremony for v1). */
object PupilState {
    @Volatile var running = false
    @Volatile var lastLux: Float? = null
    @Volatile var packetId = 0
    @Volatile var sensorRung = "not started"
}
```

- [ ] **Step 2: The service**

`android/app/src/main/java/io/github/genneth/pupil/PupilService.kt` (replace the stub):

```kotlin
package io.github.genneth.pupil

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service (type connectedDevice — no FGS timeout on Android 14+):
 * ALS -> deadband/rate governor -> BTHome payload -> setAdvertisingData in
 * place. Event-driven with a heartbeat; no polling loop (spec §5).
 *
 * All advertise calls are guarded: BLUETOOTH_ADVERTISE is checked in
 * onStartCommand, and the activity only starts us after granting it.
 */
class PupilService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "PupilService"
        private const val CHANNEL_ID = "pupil"
        private const val NOTIFICATION_ID = 1
        private val BTHOME_UUID: ParcelUuid =
            ParcelUuid.fromString("0000fcd2-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var config: PupilConfig
    private lateinit var governor: UpdateGovernor
    private val handler = Handler(Looper.getMainLooper())

    private var sensorManager: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisingSet: AdvertisingSet? = null

    private var latestLux = 0f
    private var packetId = 0
    private var inFlight = false        // a setAdvertisingData awaiting its callback
    private var sendQueued = false      // a send will happen as soon as it legally can

    private val heartbeat = object : Runnable {
        override fun run() {
            sendNow() // bumps packet id even when lux is unchanged: liveness signal
            handler.postDelayed(this, config.heartbeatMs)
        }
    }

    private val setCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS && set != null) {
                advertisingSet = set
                sendNow()
            } else {
                Log.e(TAG, "advertising set failed to start: status=$status")
                stopSelf()
            }
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            // Bluetooth toggled off/on invalidates the set — recreate it.
            advertisingSet = null
            if (PupilState.running) handler.postDelayed({ startAdvertising() }, 1000)
        }

        override fun onAdvertisingDataSet(set: AdvertisingSet?, status: Int) {
            inFlight = false
            if (sendQueued) {
                sendQueued = false
                maybeSend()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "BLUETOOTH_ADVERTISE not granted; refusing to start")
            stopSelf()
            return START_NOT_STICKY
        }
        config = PupilConfig(this)
        governor = UpdateGovernor(config.minGapMs, config.deadbandFraction, config.deadbandAbsLux)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("starting…"))
        PupilState.running = true
        acquireSensor()
        startAdvertising()
        handler.postDelayed(heartbeat, config.heartbeatMs)
        return START_STICKY
    }

    /** Spec §4a: wakeup ALS (rung 1) if the hardware has one, else wakelock (rung 2). */
    private fun acquireSensor() {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        val wakeup: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT, true)
        val sensor = wakeup ?: sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (sensor == null) {
            PupilState.sensorRung = "no light sensor at all?!"
            stopSelf()
            return
        }
        if (wakeup != null) {
            PupilState.sensorRung = "rung 1: wakeup ALS (${sensor.name}), no wakelock"
        } else {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pupil:sensor")
                .apply { acquire() }
            PupilState.sensorRung = "rung 2: non-wakeup ALS (${sensor.name}) + partial wakelock"
        }
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun startAdvertising() {
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adv = bm.adapter?.bluetoothLeAdvertiser
        if (adv == null) {
            Log.e(TAG, "no BLE advertiser (Bluetooth off?)")
            stopSelf()
            return
        }
        advertiser = adv
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(config.intervalUnits)
            .setTxPowerLevel(config.txPowerLevel)
            .build()
        try {
            adv.startAdvertisingSet(params, buildAdvertiseData(), null, null, null, setCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "advertise permission lost", e)
            stopSelf()
        }
    }

    private fun buildAdvertiseData(): AdvertiseData {
        packetId = (packetId + 1) and 0xFF
        PupilState.packetId = packetId
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false) // would leak the phone's BT name, and costs bytes
            .setIncludeTxPowerLevel(false)
            .addServiceData(BTHOME_UUID, BthomeEncoder.encode(packetId, latestLux))
            .build()
    }

    override fun onSensorChanged(event: SensorEvent) {
        latestLux = event.values[0]
        PupilState.lastLux = latestLux
        if (governor.significantChange(latestLux)) maybeSend()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Coalescing send: at most one queued/in-flight update at any time. */
    private fun maybeSend() {
        if (inFlight) {
            sendQueued = true
            return
        }
        val gap = governor.gapRemainingMs(SystemClock.elapsedRealtime())
        if (gap > 0) {
            if (!sendQueued) {
                sendQueued = true
                handler.postDelayed({ sendQueued = false; maybeSend() }, gap)
            }
            return
        }
        sendNow()
    }

    @SuppressLint("MissingPermission")
    private fun sendNow() {
        val set = advertisingSet ?: return
        if (inFlight) {
            sendQueued = true
            return
        }
        try {
            set.setAdvertisingData(buildAdvertiseData())
        } catch (e: SecurityException) {
            Log.e(TAG, "advertise permission lost", e)
            stopSelf()
            return
        }
        inFlight = true
        governor.recordSent(latestLux, SystemClock.elapsedRealtime())
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification("broadcasting %.1f lx · #%d".format(latestLux, packetId)))
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pupil", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pupil)
            .setContentTitle("Pupil")
            .setContentText(text)
            .setOngoing(true)
            .build()

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        PupilState.running = false
        PupilState.sensorRung = "not started"
        handler.removeCallbacksAndMessages(null)
        sensorManager?.unregisterListener(this)
        wakeLock?.release()
        wakeLock = null
        advertisingSet = null
        try {
            advertiser?.stopAdvertisingSet(setCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "could not stop advertising set", e)
        }
        super.onDestroy()
    }
}
```

- [ ] **Step 3: Build**

Run: `toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:testDebugUnitTest :app:assembleDebug'`
Expected: `BUILD SUCCESSFUL` (7 unit tests still passing).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/io/github/genneth/pupil/
git commit -m "Pupil app: foreground service — sensor ladder, advertising set, heartbeat"
```

---

### Task 10: MainActivity, install, on-device acceptance, docs

**Files:**
- Replace stub: `android/app/src/main/java/io/github/genneth/pupil/MainActivity.kt`
- Create: `android/README.md`
- Modify: `dev.sh` (add `android` target), `STATUS.md` (pointer)

**Interfaces:**
- Consumes: `PupilState`, `PupilService`, layout ids from Task 6 (`statusText`, `luxText`, `sensorReport`, `toggleButton`, `batteryButton`, `settingsButton`).

- [ ] **Step 1: The activity**

`android/app/src/main/java/io/github/genneth/pupil/MainActivity.kt` (replace the stub):

```kotlin
package io.github.genneth.pupil

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.toggleButton).setOnClickListener {
            if (PupilState.running) {
                stopService(Intent(this, PupilService::class.java))
            } else {
                ensurePermissionsThenStart()
            }
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            // Load-bearing, not just ColorOS appeasement: Doze ignores wakelocks
            // without this exemption (spec §4a).
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.sensorReport).text = sensorReport()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    private fun ensurePermissionsThenStart() {
        val wanted = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startForegroundService(Intent(this, PupilService::class.java))
        } else {
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, granted: IntArray) {
        super.onRequestPermissionsResult(code, perms, granted)
        if (granted.isNotEmpty() && granted.all { it == PackageManager.PERMISSION_GRANTED }) {
            startForegroundService(Intent(this, PupilService::class.java))
        }
    }

    private fun render() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = if (pm.isIgnoringBatteryOptimizations(packageName)) "exempt" else "NOT exempt"
        findViewById<TextView>(R.id.statusText).text =
            if (PupilState.running) "broadcasting · ${PupilState.sensorRung} · battery: $exempt"
            else "stopped · battery: $exempt"
        findViewById<TextView>(R.id.luxText).text =
            PupilState.lastLux?.let { "%.1f lx  ·  #%d".format(it, PupilState.packetId) } ?: "—"
        findViewById<Button>(R.id.toggleButton).text =
            if (PupilState.running) "Stop broadcasting" else "Start broadcasting"
    }

    /** Spec §4: per-device sensor facts nobody has published for the Find N6. */
    private fun sensorReport(): String {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val wakeup = sm.getDefaultSensor(Sensor.TYPE_LIGHT, true)
        val default = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        return buildString {
            appendLine("wakeup ALS: ${wakeup?.name ?: "none"}")
            appendLine("default ALS: ${default?.name ?: "none"}")
            default?.let {
                appendLine("  vendor=${it.vendor} maxRange=${it.maximumRange} lx")
                appendLine("  fifoMax=${it.fifoMaxEventCount} isWakeUp=${it.isWakeUpSensor}")
            }
        }.trimEnd()
    }
}
```

- [ ] **Step 2: Build + install on the phone**

Enable Developer options on the phone (Settings → About device → Version → tap *Build number* 7×), then USB debugging (Settings → System → Developer options → USB debugging). Plug in via USB, accept the fingerprint dialog.

```bash
toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:assembleDebug'
toolbox run -c dev bash -lc '~/Android/Sdk/platform-tools/adb devices'   # expect the device listed
toolbox run -c dev bash -lc '~/Android/Sdk/platform-tools/adb install -r ~/iris/android/app/build/outputs/apk/debug/app-debug.apk'
```

If USB from the toolbox is flaky, use wireless debugging instead: Developer options → Wireless debugging → Pair device with pairing code, then `adb pair <ip:port>` + `adb connect <ip:port>`.

- [ ] **Step 3: On-device acceptance sequence** (spec §8 — record results in `android/README.md` as you go)

1. **Sensor report:** open Pupil, note the report panel — wakeup ALS present? FIFO? Which rung does the status line show after Start?
2. **First sighting:** laptop: `cd python && uv run python scripts/ble_als_probe.py --all` → expect readings within seconds of pressing Start; cover/uncover the phone sensor and watch lux move; note the packet ids advancing ~every 10 s (heartbeat) even in stable light.
3. **ColorOS setup + screen-off torch test:** apply the README checklist (Step 4 below), tap *Battery exemption…* and grant it; screen off 10 minutes with the probe running; then shine a torch at the phone. PASS = packet ids advanced the whole time AND lux jumped for the torch. Record which failure occurred otherwise (silence = killed; ids advancing but lux frozen = frozen-sensor mode).
4. **RSSI room walk:** `uv run python scripts/ble_als_probe.py --all --csv walk.csv`, carry the phone: desk → sofa → next room → pocket. Note RSSI per location; pick `--min-rssi` so desk passes and next-room/pocket fail; record the numbers in `android/README.md`.

- [ ] **Step 4: Write `android/README.md`**

Contents (write actual results from Step 3 where marked):

```markdown
# Pupil — the phone as a BLE ambient-light sensor

iris's star pupil: broadcasts the phone's ambient-light sensor as BTHome v2 BLE
adverts (non-connectable; receivable by `python/scripts/ble_als_probe.py`, Home
Assistant, or anything BTHome-aware). Spec:
`docs/superpowers/specs/2026-07-03-pupil-ble-als-design.md`.

## Build & install

    toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew :app:assembleDebug'
    toolbox run -c dev bash -lc '~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk'

Toolchain: JDK 21 + gradle in the dev toolbox; Android SDK at ~/Android/Sdk
(platforms;android-35, build-tools;35.0.0). Unit tests: `./dev.sh android`.

## ColorOS survival checklist (do all of these once)

1. In-app: tap **Battery exemption…** and allow (also keeps wakelocks honoured in Doze).
2. Settings → Battery → App battery management → Pupil: **Allow auto-launch**,
   **Allow foreground activity**, **Allow background activity**, **Optimize
   battery use → off**.
3. Battery → Advanced settings: **Sleep stand-by optimization → off**.
4. Recents → long-press Pupil's card → **Lock** (stops ColorOS silently
   reverting the exemption).
5. Decline any "high background power consumption" prompt about Pupil.

## Find N6 acceptance results (2026-07-__)

- Sensor report: <record: wakeup ALS present? name? fifoMax? rung used>
- Screen-off torch test: <record: PASS / killed / frozen-value>
- RSSI walk @ TX low (−15 dBm): desk ___ dBm · sofa ___ · next room ___ ·
  pocket ___ → chosen --min-rssi: ___
```

- [ ] **Step 5: dev.sh android target + STATUS.md pointer**

In `dev.sh`, add a case entry after `rust-check)` (NOT wired into `check` — the host has no JVM; gradle runs in the toolbox):

```bash
  android)
    toolbox run -c dev bash -lc "cd '$PWD/android' && ./gradlew --console=plain testDebugUnitTest"
    ;;
```

and add `android` to the usage line. In `STATUS.md`, find the "Phone ALS (adopted for calibration; optional live source)" open question and append to it: `→ **Building: Pupil** (spec docs/superpowers/specs/2026-07-03-pupil-ble-als-design.md, plan docs/superpowers/plans/2026-07-03-pupil-ble-als.md).`

- [ ] **Step 6: Final gate and commit**

```bash
./dev.sh check && ./dev.sh android
git add android/ dev.sh STATUS.md
git commit -m "Pupil app: main UI, ColorOS checklist, on-device acceptance results"
```

---

## Verification checklist (whole feature)

- [ ] `./dev.sh check` green (ruff, mypy strict, 13+ pytest)
- [ ] `./dev.sh android` green (7 JUnit tests)
- [ ] Golden vectors: same bytes asserted in Kotlin (encoder), Python (our decoder), and bthome-ble (HA oracle)
- [ ] Live: probe script shows lux tracking the phone's sensor, heartbeat packet ids every 10 s, STALE when the phone leaves / is pocketed, SCANNER_DEAD + recovery on `rfkill block bluetooth` / `rfkill unblock bluetooth`
- [ ] Acceptance results recorded in `android/README.md`
