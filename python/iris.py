#!/usr/bin/env -S uv run --script
"""iris — drive this laptop's screen brightness from the phone's ambient-light sensor.

Self-contained daemon (codename "Reflex" — the pupillary light reflex). It scans for
Pupil's BTHome BLE adverts (the phone broadcasting calibrated lux), maps lux through an
absolute log-lux curve, and drives GNOME's backlight via the direct sink
(org.gnome.Shell.Brightness.SetAutoBrightnessTarget). When the phone goes silent it
releases to the manual slider (-1). No camera, no uhid, no root.

This one file bundles what used to be the `iris` + `pupil` receive packages so it runs
standalone — `uv run iris.py` resolves the two deps below and Just Works; the systemd
--user unit (deploy/iris.service) runs it the same way. Pure functions are importable for
tests (run() is guarded). Sections: BTHome decode · presence tracker · brightness curve ·
config · controller · D-Bus sink · async daemon.

Design: docs/iris/ (BRIGHTNESS-MATH.md) and
docs/superpowers/specs/2026-07-04-reflex-phone-als-brightness-design.md.
IRIS_DEBUG=1 surfaces per-reading lux->target and a periodic panel readout (for calibration).
"""
# /// script
# requires-python = ">=3.13"
# dependencies = ["bleak>=0.23", "dbus-fast>=2.24"]
# ///

from __future__ import annotations

import asyncio
import logging
import math
import os
import signal
import time
import tomllib
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any

from bleak import BleakScanner
from dbus_fast import BusType, Message, MessageType
from dbus_fast.aio import MessageBus

log = logging.getLogger("iris")


# ─────────────────────────────── BTHome decode ───────────────────────────────
# The wire contract with the Pupil app (android/) — golden vectors in
# contract/bthome-golden.json. Unencrypted BTHome v2, illuminance object only.

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


# ────────────────────────────── presence tracker ─────────────────────────────
# RSSI hysteresis gate + freshness state machine. Pure logic; time is passed in.


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
        self._last_accepted_packet_id: int | None = None
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
        # look alive. No packet id at all => cannot dedup, treat as new. Compare
        # against the last ACCEPTED packet id only — a rejected reading must not
        # poison dedup, so a weak advert followed by the same packet id at strong
        # RSSI registers immediately.
        is_new = decoded.packet_id is None or decoded.packet_id != self._last_accepted_packet_id
        accepted = self._admitted and is_new
        if accepted:
            self._last_fresh = now
            self._last_accepted_packet_id = decoded.packet_id
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


# ─────────────────────────────── brightness curve ────────────────────────────
# Absolute lux -> backlight target, piecewise-linear in log10(lux), clamped to
# [floor, ceil]. Stable: a given room brightness always maps to the same target
# (no gsd-style re-anchoring). See docs/iris/BRIGHTNESS-MATH.md.

# Defaults: dark/covered -> floor, up through a sunlit-window top at ceil. The
# lit-evening anchor (50 lx -> 16%) is the known comfort point; all are tunable
# per-room via config.toml (deploy/config.example.toml ships a real tuning).
DEFAULT_ANCHORS: tuple[tuple[float, float], ...] = (
    (1.0, 0.08),
    (10.0, 0.12),
    (50.0, 0.16),
    (300.0, 0.55),
    (2000.0, 1.0),
)


@dataclass(frozen=True)
class BrightnessCurve:
    anchors: tuple[tuple[float, float], ...]
    floor: float = 0.08
    ceil: float = 1.0
    eps: float = 0.5  # lux floor so log10 stays finite; true dark -> floor

    def __post_init__(self) -> None:
        if len(self.anchors) < 2:
            raise ValueError("need at least 2 anchors")
        luxes = [a[0] for a in self.anchors]
        targets = [a[1] for a in self.anchors]
        if any(lux <= 0 for lux in luxes):
            raise ValueError("anchor lux must be > 0")
        if luxes != sorted(luxes) or len(set(luxes)) != len(luxes):
            raise ValueError("anchor lux must be strictly ascending")
        if targets != sorted(targets):
            raise ValueError("anchor targets must be non-decreasing")
        if not all(0.0 <= t <= 1.0 for t in targets):
            raise ValueError("anchor targets must be in [0, 1]")
        if not (0.0 <= self.floor <= self.ceil <= 1.0):
            raise ValueError("require 0 <= floor <= ceil <= 1")

    def target(self, lux: float) -> float:
        x = math.log10(max(lux, self.eps))
        xs = [math.log10(a[0]) for a in self.anchors]
        if x <= xs[0]:
            t = self.anchors[0][1]
        elif x >= xs[-1]:
            t = self.anchors[-1][1]
        else:
            t = self.anchors[-1][1]
            for i in range(1, len(self.anchors)):
                if x <= xs[i]:
                    frac = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
                    t0, t1 = self.anchors[i - 1][1], self.anchors[i][1]
                    t = t0 + frac * (t1 - t0)
                    break
        return min(self.ceil, max(self.floor, t))


# ─────────────────────────────────── config ──────────────────────────────────
# TOML at ~/.config/iris/config.toml over code defaults. Only present keys
# override, so an empty/missing file is fully valid.

DEFAULT_CONFIG_PATH = Path.home() / ".config" / "iris" / "config.toml"


@dataclass(frozen=True)
class ReflexConfig:
    curve: BrightnessCurve
    tracker: TrackerConfig
    ease_tau_s: float = 1.5
    push_hz: float = 10.0
    push_epsilon: float = 0.002  # skip a D-Bus push below this |delta target|


def load_config(path: Path | None = None) -> ReflexConfig:
    path = path or DEFAULT_CONFIG_PATH
    data: dict[str, Any] = {}
    if path.exists():
        with path.open("rb") as f:
            data = tomllib.load(f)

    curve_d = data.get("curve", {})
    anchors_raw = curve_d.get("anchors")
    anchors = (
        tuple((float(a[0]), float(a[1])) for a in anchors_raw)
        if anchors_raw is not None
        else DEFAULT_ANCHORS
    )
    curve = BrightnessCurve(
        anchors=anchors,
        floor=float(curve_d.get("floor", 0.08)),
        ceil=float(curve_d.get("ceil", 1.0)),
        eps=float(curve_d.get("eps", 0.5)),
    )

    tr = data.get("tracker", {})
    min_rssi = float(tr.get("min_rssi", -75.0))
    tracker = TrackerConfig(
        stale_after_s=float(tr.get("stale_after", 25.0)),
        scanner_dead_after_s=float(tr.get("dead_after", 30.0)),
        admit_rssi_dbm=min_rssi + 5.0,
        drop_rssi_dbm=min_rssi - 5.0,
    )

    ease = data.get("ease", {})
    return ReflexConfig(
        curve=curve,
        tracker=tracker,
        ease_tau_s=float(ease.get("tau", 1.5)),
        push_hz=float(ease.get("push_hz", 10.0)),
        push_epsilon=float(ease.get("epsilon", 0.002)),
    )


# ───────────────────────────────── controller ────────────────────────────────
# DRIVING/RELEASED state machine + target easing. Pure; time is passed in. Emits
# SinkCommands the async daemon executes.


class SinkAction(Enum):
    NONE = "none"
    PUSH = "push"
    RELEASE = "release"


@dataclass(frozen=True)
class SinkCommand:
    action: SinkAction
    target: float | None = None


class ReflexState(Enum):
    RELEASED = "released"
    DRIVING = "driving"


_NONE = SinkCommand(SinkAction.NONE)


class ReflexController:
    def __init__(self, config: ReflexConfig, started_at: float) -> None:
        self._config = config
        self._last_now = started_at
        self._target: float | None = None
        self._applied: float | None = None
        self._last_pushed: float | None = None
        self.state = ReflexState.RELEASED

    def set_lux(self, lux: float) -> None:
        self._target = self._config.curve.target(lux)

    def step(self, tracker_state: TrackerState, now: float) -> SinkCommand:
        dt = max(0.0, now - self._last_now)
        self._last_now = now

        if tracker_state is not TrackerState.FRESH:
            if self.state is ReflexState.DRIVING:
                self.state = ReflexState.RELEASED
                self._applied = None
                self._last_pushed = None
                return SinkCommand(SinkAction.RELEASE)
            return _NONE

        # FRESH from here.
        if self.state is ReflexState.RELEASED:
            # Engage at neutral: clamp(0.5 + S - 0.5) = S, so the panel stays
            # exactly where the manual slider had it -> no jump.
            self.state = ReflexState.DRIVING
            self._applied = 0.5
            self._last_pushed = 0.5
            return SinkCommand(SinkAction.PUSH, 0.5)

        if self._target is None or self._applied is None:
            return _NONE
        alpha = dt / (dt + self._config.ease_tau_s) if dt > 0 else 1.0
        self._applied += alpha * (self._target - self._applied)
        moved = self._last_pushed is None or (
            abs(self._applied - self._last_pushed) >= self._config.push_epsilon
        )
        if moved:
            self._last_pushed = self._applied
            return SinkCommand(SinkAction.PUSH, self._applied)
        return _NONE

    def on_shutdown(self) -> SinkCommand:
        if self.state is ReflexState.DRIVING:
            self.state = ReflexState.RELEASED
            return SinkCommand(SinkAction.RELEASE)
        return _NONE


# ──────────────────────────────── D-Bus sink ─────────────────────────────────
# org.gnome.Shell.Brightness.SetAutoBrightnessTarget: a published,
# sender-unrestricted session-bus API (BRIGHTNESS-MATH sec 6). iris owns the
# curve, the shell owns the write, -1 restores manual mode. Plus the sysfs
# backlight reader (never the stale D-Bus Backlight property) + the #4432 tell.

_DEST = "org.gnome.Shell"
_PATH = "/org/gnome/Shell/Brightness"
_IFACE = "org.gnome.Shell.Brightness"
_BACKLIGHT = Path("/sys/class/backlight/intel_backlight")


class ShellBrightness:
    """Async client for SetAutoBrightnessTarget on the session bus."""

    def __init__(self) -> None:
        self._bus: MessageBus | None = None

    async def connect(self) -> None:
        self._bus = await MessageBus(bus_type=BusType.SESSION).connect()

    async def _call(self, target: float) -> None:
        if self._bus is None:
            raise RuntimeError("ShellBrightness.connect() not called")
        reply = await self._bus.call(
            Message(
                destination=_DEST,
                path=_PATH,
                interface=_IFACE,
                member="SetAutoBrightnessTarget",
                signature="d",
                body=[target],
            )
        )
        if reply is None or reply.message_type == MessageType.ERROR:
            raise RuntimeError(f"SetAutoBrightnessTarget({target}) failed: {reply}")

    async def set_target(self, t: float) -> None:
        await self._call(t)

    async def release(self) -> None:
        await self._call(-1.0)

    async def disconnect(self) -> None:
        if self._bus is not None:
            self._bus.disconnect()
            self._bus = None


def read_backlight_sysfs(base: Path = _BACKLIGHT) -> int | None:
    try:
        return int((base / "brightness").read_text().strip())
    except (OSError, ValueError):
        return None


def read_backlight_max_sysfs(base: Path = _BACKLIGHT) -> int | None:
    try:
        return int((base / "max_brightness").read_text().strip())
    except (OSError, ValueError):
        return None


def is_backlight_wedged(applied_delta: float, sysfs_delta: int, max_brightness: int | None) -> bool:
    """True if brightness control looks wedged by mutter #4432.

    Either the panel's max reads 100 (the phantom nvidia_0 tell; real intel is
    400), or we pushed a materially different target but the panel didn't move.
    """
    if max_brightness == 100:
        return True
    return abs(applied_delta) > 0.1 and sysfs_delta == 0


# ──────────────────────────────── async daemon ───────────────────────────────

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
            log.debug(
                "recv lux=%.1f rssi=%.0f -> curve target=%.3f",
                reading.lux,
                reading.rssi,
                config.curve.target(reading.lux),
            )

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
        from bleak.args.bluez import BlueZScannerArgs, OrPattern
        from bleak.assigned_numbers import AdvertisementDataType

        bluez_args = BlueZScannerArgs(
            or_patterns=[
                OrPattern(0, AdvertisementDataType.SERVICE_DATA_UUID16, b"\xd2\xfc"),
                OrPattern(0, AdvertisementDataType.COMPLETE_LIST_SERVICE_UUID16, b"\xd2\xfc"),
            ]
        )

        while not stop.is_set():
            try:
                async with BleakScanner(
                    on_advert,
                    scanning_mode="passive",
                    bluez=bluez_args,
                ):
                    while not stop.is_set():
                        await asyncio.sleep(tick)
                        now = time.monotonic()
                        state = tracker.state(now)
                        if state is TrackerState.SCANNER_DEAD:
                            scanner_is_actually_dead = True
                            try:
                                from bleak.backends.bluezdbus.manager import (
                                    get_global_bluez_manager,
                                )

                                manager = await get_global_bluez_manager()
                                for _, interfaces in manager._properties.items():
                                    if "org.bluez.Adapter1" in interfaces:
                                        adapter = interfaces["org.bluez.Adapter1"]
                                        if adapter.get("Powered") and adapter.get("Discovering"):
                                            scanner_is_actually_dead = False
                                            break
                            except Exception:
                                pass

                            if scanner_is_actually_dead:
                                break
                            else:
                                tracker.reset_scanner(now)
                                state = TrackerState.STALE

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
                                frac = cur / mx if mx else 0.0
                                log.debug(
                                    "panel sysfs=%d/%s (%.0f%%) applied_target=%.3f",
                                    cur,
                                    mx,
                                    100.0 * frac,
                                    wd_applied,
                                )
                                wedged = is_backlight_wedged(
                                    applied_delta=wd_applied - wd_prev_applied,
                                    sysfs_delta=cur - wd_prev_sysfs,
                                    max_brightness=mx,
                                )
                                # Don't warn when the panel is legitimately pinned at a rail:
                                # clamp(T+S-0.5) saturates under a strong manual slider bias (or
                                # a dark/bright extreme), so our target keeps easing while the
                                # hardware correctly sits at min/max — not a mutter #4432 wedge.
                                # (No min_brightness sysfs node exists; the observed floor is ~4,
                                # so gate on fraction near either rail.)
                                at_rail = mx is not None and (frac <= 0.02 or frac >= 0.98)
                                if wedged and not at_rail and not warned_wedged:
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
    # IRIS_DEBUG=1 surfaces per-reading lux->target and the periodic panel readout
    # (for calibration/diagnosis). Scope DEBUG to the iris logger only — the root
    # stays INFO so bleak/dbus-fast don't flood the log with BlueZ D-Bus traffic.
    if os.environ.get("IRIS_DEBUG"):
        log.setLevel(logging.DEBUG)
    asyncio.run(main())


if __name__ == "__main__":
    run()
