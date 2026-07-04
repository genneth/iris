"""iris daemon (Reflex): the phone as this laptop's ambient-light sensor.

Receives Pupil's calibrated lux over BLE, maps it through an absolute curve, and
drives GNOME's backlight via the direct sink (SetAutoBrightnessTarget). When the
phone goes silent, releases to manual (-1). No camera, no uhid, no root.

Run:  python -m iris   (as a systemd --user service; see deploy/iris.service)
Spec: docs/superpowers/specs/2026-07-04-reflex-phone-als-brightness-design.md
"""

from __future__ import annotations

import asyncio
import logging
import signal
import time
from typing import Any

from bleak import BleakScanner

from .config import DEFAULT_CONFIG_PATH, load_config
from .controller import ReflexController, SinkAction, SinkCommand
from .pupil import BTHOME_SERVICE_UUID, PupilTracker, TrackerState
from .shell_brightness import (
    ShellBrightness,
    is_backlight_wedged,
    read_backlight_max_sysfs,
    read_backlight_sysfs,
)

log = logging.getLogger("iris")

_WATCHDOG_EVERY_TICKS = 30  # ~3 s at 10 Hz


async def _apply(sink: ShellBrightness, cmd: SinkCommand) -> None:
    if cmd.action is SinkAction.PUSH and cmd.target is not None:
        await sink.set_target(cmd.target)
    elif cmd.action is SinkAction.RELEASE:
        await sink.release()


async def main() -> None:
    log.info("loading config from %s (defaults if absent)", DEFAULT_CONFIG_PATH)
    try:
        config = load_config()
    except Exception as e:
        log.error("invalid config at %s: %s", DEFAULT_CONFIG_PATH, e)
        raise
    tracker = PupilTracker(config.tracker, started_at=time.monotonic())
    controller = ReflexController(config, started_at=time.monotonic())
    sink = ShellBrightness()
    await sink.connect()

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)

    def on_advert(_device: Any, adv: Any) -> None:
        now = time.monotonic()
        payload = adv.service_data.get(BTHOME_SERVICE_UUID)
        if payload is None:
            tracker.on_any_advert(now)
            return
        reading = tracker.on_pupil_advert(bytes(payload), rssi=float(adv.rssi), now=now)
        if reading is not None and reading.accepted:
            controller.set_lux(reading.lux)

    tick = 1.0 / config.push_hz
    last_state = TrackerState.NEVER_SEEN
    # Watchdog bookkeeping (mutter #4432): compare applied-change vs panel movement.
    wd_ticks = 0
    wd_prev_sysfs = read_backlight_sysfs()
    wd_prev_applied = 0.5
    wd_applied = 0.5
    warned_wedged = False

    log.info("iris (Reflex) starting; scanning for Pupil BTHome adverts")
    try:
        while not stop.is_set():
            try:
                async with BleakScanner(on_advert):
                    while not stop.is_set():
                        await asyncio.sleep(tick)
                        now = time.monotonic()
                        state = tracker.state(now)
                        if state is not last_state:
                            log.info("state: %s -> %s", last_state.value, state.value)
                            last_state = state
                        cmd = controller.step(state, now)
                        if cmd.action is SinkAction.PUSH and cmd.target is not None:
                            wd_applied = cmd.target
                        await _apply(sink, cmd)

                        wd_ticks += 1
                        if wd_ticks >= _WATCHDOG_EVERY_TICKS:
                            wd_ticks = 0
                            cur = read_backlight_sysfs()
                            if cur is not None and wd_prev_sysfs is not None:
                                mx = read_backlight_max_sysfs()
                                wedged = is_backlight_wedged(
                                    applied_delta=wd_applied - wd_prev_applied,
                                    sysfs_delta=cur - wd_prev_sysfs,
                                    max_brightness=mx,
                                )
                                # Don't warn when the panel is legitimately pinned at the top
                                # rail: a high manual slider bias saturates clamp(T+S-0.5), so
                                # our target keeps easing while the hardware correctly sits at
                                # max — not a mutter #4432 wedge.
                                at_top_rail = mx is not None and cur >= mx
                                if wedged and not at_top_rail and not warned_wedged:
                                    log.warning(
                                        "backlight looks wedged (mutter #4432?): target moved "
                                        "but intel_backlight didn't. A lid close/open or re-login "
                                        "usually re-binds it."
                                    )
                                    warned_wedged = True
                                elif not wedged:
                                    warned_wedged = False
                            wd_prev_sysfs = cur
                            wd_prev_applied = wd_applied

                        if state is TrackerState.SCANNER_DEAD:
                            break
            except Exception as e:
                # Never let a BlueZ hiccup (InProgress races, rfkill, suspend) kill the
                # daemon — fall through to the same recreate path as a clean SCANNER_DEAD.
                log.warning("scanner error: %r — recreating", e)
            if not stop.is_set():
                await asyncio.sleep(2.0)
                tracker.reset_scanner(time.monotonic())
    finally:
        await _apply(sink, controller.on_shutdown())
        await sink.disconnect()
        log.info("iris (Reflex) stopped; released to manual")


def run() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    asyncio.run(main())
