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
    ap.add_argument(
        "--min-rssi",
        type=float,
        default=-75.0,
        help="centre of the RSSI gate; admit at +5, drop at -5 dBm around it",
    )
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
            csv_writer.writerow(
                [
                    datetime.datetime.now().isoformat(timespec="milliseconds"),
                    f"{reading.lux:.2f}",
                    reading.packet_id,
                    f"{reading.rssi:.0f}",
                    int(reading.accepted),
                    state.value,
                ]
            )
        if reading.accepted or args.all:
            tag = "" if reading.accepted else "  [rejected: rssi gate or repeat]"
            print(
                f"{_ts()}  {reading.lux:9.2f} lx  rssi {reading.rssi:4.0f}  "
                f"pid {reading.packet_id}  {state.value}{tag}"
            )

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
