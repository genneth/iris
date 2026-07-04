# Reflex — Phone-as-ALS Brightness Controller Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repurpose the iris daemon into a phone-driven screen-brightness controller: receive Pupil's calibrated lux over BLE and drive GNOME's backlight through the direct sink, so the panel tracks the room.

**Architecture:** Pupil (phone) broadcasts BTHome lux over BLE → the iris daemon receives it via `bleak`, decodes + gates it through the existing `iris.pupil` logic, maps lux → a backlight target with an absolute log-lux curve iris owns, eases the target at ~10 Hz, and pushes it to `org.gnome.Shell.Brightness.SetAutoBrightnessTarget` over `dbus-fast`. When the phone goes silent it releases to manual (`-1`). The pure logic (curve, config, state machine, watchdog predicate) is split into focused, unit-tested modules; the async BLE+D-Bus wiring is the thin shell in `daemon.py`, verified on-device.

**Tech Stack:** Python 3.13, `asyncio`, `bleak` (BLE scan, already a dep), `dbus-fast` (D-Bus, rides in transitively via `bleak`), stdlib `tomllib`; pytest for the pure logic.

## Global Constraints

- Python `>=3.13`; `mypy` runs `strict` over `src/` only (`scripts/` and `tests/` are not type-checked) — every new `src/` symbol must be fully typed.
- `ruff` lint select is `["E","F","W","I","UP","B","SIM","C4","RUF"]`; line-length 100. Format with `ruff format`.
- The whole gate is `./dev.sh check` (ruff format --check, ruff check src tests, mypy, pytest, rust-check) and it must pass at every commit (a pre-commit hook enforces it).
- **Reuse `iris/pupil.py` wholesale — do not modify it.** Consume `decode_bthome_illuminance`, `PupilTracker`, `TrackerConfig`, `TrackerState`, `PupilReading`, `BTHOME_SERVICE_UUID` as-is.
- **Direct sink only:** `SetAutoBrightnessTarget` (signature `d`) on destination `org.gnome.Shell`, path `/org/gnome/Shell/Brightness`, interface `org.gnome.Shell.Brightness`, **session** bus. Release = push `-1.0`.
- **Never subscribe to `BrightnessChanged`** (no re-anchoring — that is the design's whole point).
- **Never read the D-Bus `Backlight` property** for current state (it goes stale — FINDINGS bug 2). Read sysfs `/sys/class/backlight/intel_backlight` instead.
- Single service reusing the existing `python -m iris` entry point; `run()` in `iris/daemon.py` stays the callable `iris/__main__.py` imports.
- No new runtime dependency of substance: `dbus-fast` is already present transitively via `bleak`; add it explicitly.
- Tests are flat in `python/tests/`, named `test_<module>.py`, plain pytest functions (no classes), importing from `iris.*`. Match the style of `tests/test_pupil.py`.

## File Structure

- `python/src/iris/curve.py` **(new)** — `BrightnessCurve`: the pure lux→backlight-target mapping (log-lux piecewise-linear, clamped), plus `DEFAULT_ANCHORS`. No I/O.
- `python/src/iris/config.py` **(new)** — `ReflexConfig` dataclass + `load_config()`: TOML (`~/.config/iris/config.toml`) over code defaults. Depends on `curve.py` and `iris.pupil.TrackerConfig`.
- `python/src/iris/shell_brightness.py` **(new)** — `ShellBrightness` (the async `dbus-fast` sink), `read_backlight_sysfs()`/`read_backlight_max_sysfs()`, and the pure `is_backlight_wedged()` watchdog predicate.
- `python/src/iris/controller.py` **(new)** — `ReflexController`: the pure DRIVING/RELEASED state machine + easing math, emitting `SinkCommand`s. No BLE, no D-Bus, no clock of its own (time is passed in). This is the split-out core the spec attributes to `daemon.py`, kept separate for unit-testability.
- `python/src/iris/daemon.py` **(rewrite)** — the async shell: `run()` → `asyncio.run(main())`; `main()` wires `BleakScanner` → `PupilTracker` → `ReflexController` → `ShellBrightness`, with the scanner-recreate loop and the shutdown release. Replaces the webcam MVP body.
- `python/deploy/iris.service` **(new)** — `systemd --user` unit with `ExecStopPost=` sending `-1`.
- `python/pyproject.toml` **(modify)** — add `dbus-fast` dependency; add `bleak.*`/`dbus_fast.*` mypy import overrides.
- Docs **(modify)**: `STATUS.md` (resolve Next №1), `DESIGN.md` (§2 sink decided), `README.md` (run instructions).
- Untouched: `iris/pupil.py`, `iris/camera.py`, `iris/virtual_als.py`, `iris/brightness.py` (the last three left in the tree, unused by Reflex).

Tests: `python/tests/test_curve.py`, `test_config.py`, `test_shell_brightness.py`, `test_controller.py`.

---

### Task 1: The brightness curve (`iris/curve.py`)

**Files:**
- Create: `python/src/iris/curve.py`
- Test: `python/tests/test_curve.py`

**Interfaces:**
- Consumes: nothing (leaf module; stdlib `math`, `dataclasses` only).
- Produces:
  - `DEFAULT_ANCHORS: tuple[tuple[float, float], ...]` — `((1.0,0.08),(10.0,0.12),(50.0,0.16),(300.0,0.55),(2000.0,1.0))`.
  - `BrightnessCurve(anchors: tuple[tuple[float,float],...], floor: float = 0.08, ceil: float = 1.0, eps: float = 0.5)` — frozen dataclass; validates in `__post_init__`; method `target(self, lux: float) -> float` returns a backlight target in `[floor, ceil]`, piecewise-linear in `log10(lux)`.

- [ ] **Step 1: Write the failing tests**

Create `python/tests/test_curve.py`:

```python
"""Tests for the pure lux->backlight-target curve (iris.curve)."""

import math

import pytest
from pytest import approx

from iris.curve import DEFAULT_ANCHORS, BrightnessCurve


def _default() -> BrightnessCurve:
    return BrightnessCurve(anchors=DEFAULT_ANCHORS)


def test_hits_each_anchor() -> None:
    curve = _default()
    for lux, target in DEFAULT_ANCHORS:
        assert curve.target(lux) == approx(target)


def test_monotonic_non_decreasing() -> None:
    curve = _default()
    prev = -1.0
    lux = 0.1
    while lux <= 10_000:
        t = curve.target(lux)
        assert t >= prev - 1e-9
        prev = t
        lux *= 1.2


def test_clamps_below_and_above() -> None:
    curve = _default()
    assert curve.target(0.0) == approx(curve.floor)  # eps floor -> below first anchor
    assert curve.target(1e9) == approx(curve.ceil)


def test_log_space_interpolation_midpoint() -> None:
    # Between anchors (10, 0.12) and (50, 0.16), the geometric-mean lux
    # (10**((log10 10 + log10 50)/2) = sqrt(500) ~= 22.36) sits at the
    # arithmetic midpoint of the targets: (0.12 + 0.16)/2 = 0.14.
    curve = _default()
    assert curve.target(math.sqrt(500.0)) == approx(0.14, abs=1e-6)


def test_floor_ceil_clamp_wins_over_anchor() -> None:
    # A curve whose anchors exceed the clamp band is clamped to the band.
    curve = BrightnessCurve(anchors=((1.0, 0.0), (100.0, 1.0)), floor=0.1, ceil=0.9)
    assert curve.target(1.0) == approx(0.1)
    assert curve.target(100.0) == approx(0.9)


def test_rejects_too_few_anchors() -> None:
    with pytest.raises(ValueError):
        BrightnessCurve(anchors=((10.0, 0.2),))


def test_rejects_non_ascending_lux() -> None:
    with pytest.raises(ValueError):
        BrightnessCurve(anchors=((10.0, 0.2), (5.0, 0.3)))


def test_rejects_decreasing_targets() -> None:
    with pytest.raises(ValueError):
        BrightnessCurve(anchors=((1.0, 0.5), (10.0, 0.2)))


def test_rejects_target_out_of_unit_range() -> None:
    with pytest.raises(ValueError):
        BrightnessCurve(anchors=((1.0, 0.2), (10.0, 1.5)))


def test_rejects_bad_floor_ceil() -> None:
    with pytest.raises(ValueError):
        BrightnessCurve(anchors=DEFAULT_ANCHORS, floor=0.9, ceil=0.1)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd python && uv run pytest tests/test_curve.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'iris.curve'`.

- [ ] **Step 3: Implement `iris/curve.py`**

Create `python/src/iris/curve.py`:

```python
"""The absolute lux -> backlight-target curve iris owns for the direct sink.

Piecewise-linear in log10(lux) between a small table of (lux, target) anchors,
clamped to [floor, ceil]. Absolute and stable: a given room brightness always
maps to the same target (no gsd-style re-anchoring). See
docs/superpowers/specs/2026-07-04-reflex-phone-als-brightness-design.md sec 4.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

# Defaults: dark/covered -> floor, up through a sunlit-window top at ceil. The
# lit-evening anchor (50 lx -> 16%) is the known comfort point (FINDINGS); all
# are tunable per-room via config.toml.
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd python && uv run pytest tests/test_curve.py -q`
Expected: PASS (10 passed).

- [ ] **Step 5: Run the full gate and commit**

Run: `./dev.sh check` (from repo root) — expect all green.

```bash
git add python/src/iris/curve.py python/tests/test_curve.py
git commit -m "feat(reflex): add the absolute log-lux brightness curve"
```

---

### Task 2: Config loading (`iris/config.py`)

**Files:**
- Create: `python/src/iris/config.py`
- Test: `python/tests/test_config.py`

**Interfaces:**
- Consumes: `iris.curve.BrightnessCurve`, `iris.curve.DEFAULT_ANCHORS`; `iris.pupil.TrackerConfig`.
- Produces:
  - `ReflexConfig(curve: BrightnessCurve, tracker: TrackerConfig, ease_tau_s: float = 1.5, push_hz: float = 10.0, push_epsilon: float = 0.002)` — frozen dataclass.
  - `DEFAULT_CONFIG_PATH: Path` (`~/.config/iris/config.toml`).
  - `load_config(path: Path | None = None) -> ReflexConfig` — reads TOML over defaults; a missing file yields all defaults. The `[tracker].min_rssi` scalar maps to `admit = min_rssi + 5`, `drop = min_rssi - 5` (matching `ble_als_probe.py`).

- [ ] **Step 1: Write the failing tests**

Create `python/tests/test_config.py`:

```python
"""Tests for config loading (iris.config)."""

from pathlib import Path

from pytest import approx

from iris.config import ReflexConfig, load_config
from iris.curve import DEFAULT_ANCHORS


def test_defaults_when_no_file(tmp_path: Path) -> None:
    cfg = load_config(tmp_path / "does-not-exist.toml")
    assert isinstance(cfg, ReflexConfig)
    assert cfg.curve.anchors == DEFAULT_ANCHORS
    assert cfg.curve.floor == approx(0.08)
    assert cfg.ease_tau_s == approx(1.5)
    assert cfg.push_hz == approx(10.0)
    assert cfg.push_epsilon == approx(0.002)
    # Default min_rssi -75 -> admit -70, drop -80.
    assert cfg.tracker.admit_rssi_dbm == approx(-70.0)
    assert cfg.tracker.drop_rssi_dbm == approx(-80.0)
    assert cfg.tracker.stale_after_s == approx(25.0)
    assert cfg.tracker.scanner_dead_after_s == approx(30.0)


def test_overrides_from_toml(tmp_path: Path) -> None:
    path = tmp_path / "config.toml"
    path.write_text(
        """
[curve]
floor = 0.1
ceil = 0.95
eps = 0.3
anchors = [[2.0, 0.10], [200.0, 0.60], [3000.0, 0.95]]

[tracker]
min_rssi = -70.0
stale_after = 20.0
dead_after = 40.0

[ease]
tau = 2.5
push_hz = 5.0
epsilon = 0.01
"""
    )
    cfg = load_config(path)
    assert cfg.curve.anchors == ((2.0, 0.10), (200.0, 0.60), (3000.0, 0.95))
    assert cfg.curve.floor == approx(0.1)
    assert cfg.curve.ceil == approx(0.95)
    assert cfg.curve.eps == approx(0.3)
    assert cfg.tracker.admit_rssi_dbm == approx(-65.0)
    assert cfg.tracker.drop_rssi_dbm == approx(-75.0)
    assert cfg.tracker.stale_after_s == approx(20.0)
    assert cfg.tracker.scanner_dead_after_s == approx(40.0)
    assert cfg.ease_tau_s == approx(2.5)
    assert cfg.push_hz == approx(5.0)
    assert cfg.push_epsilon == approx(0.01)


def test_partial_toml_keeps_defaults(tmp_path: Path) -> None:
    path = tmp_path / "config.toml"
    path.write_text("[ease]\ntau = 3.0\n")
    cfg = load_config(path)
    assert cfg.ease_tau_s == approx(3.0)
    assert cfg.curve.anchors == DEFAULT_ANCHORS  # untouched section -> defaults
    assert cfg.push_hz == approx(10.0)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd python && uv run pytest tests/test_config.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'iris.config'`.

- [ ] **Step 3: Implement `iris/config.py`**

Create `python/src/iris/config.py`:

```python
"""Reflex runtime config: TOML at ~/.config/iris/config.toml over code defaults.

Only the keys present in the file override; everything else stays default, so an
empty or missing file is fully valid. See the design spec sec 7.
"""

from __future__ import annotations

import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .curve import DEFAULT_ANCHORS, BrightnessCurve
from .pupil import TrackerConfig

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
        data = tomllib.loads(path.read_text())

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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd python && uv run pytest tests/test_config.py -q`
Expected: PASS (3 passed).

- [ ] **Step 5: Run the full gate and commit**

Run: `./dev.sh check` — expect all green.

```bash
git add python/src/iris/config.py python/tests/test_config.py
git commit -m "feat(reflex): add TOML config loading over defaults"
```

---

### Task 3: The D-Bus sink + sysfs watchdog (`iris/shell_brightness.py`)

**Files:**
- Create: `python/src/iris/shell_brightness.py`
- Test: `python/tests/test_shell_brightness.py`
- Modify: `python/pyproject.toml` (add `dbus-fast` dep; add mypy overrides)

**Interfaces:**
- Consumes: `dbus_fast` (new dep).
- Produces:
  - `class ShellBrightness` with `async connect() -> None`, `async set_target(t: float) -> None`, `async release() -> None` (pushes `-1.0`), `async disconnect() -> None`. Session bus; `SetAutoBrightnessTarget` on `org.gnome.Shell` / `/org/gnome/Shell/Brightness`.
  - `read_backlight_sysfs(base: Path = _BACKLIGHT) -> int | None` and `read_backlight_max_sysfs(base: Path = _BACKLIGHT) -> int | None` — current / max raw brightness, `None` if unreadable.
  - `is_backlight_wedged(applied_delta: float, sysfs_delta: int, max_brightness: int | None) -> bool` — pure watchdog predicate (mutter #4432).

- [ ] **Step 1: Add the `dbus-fast` dependency and mypy overrides**

In `python/pyproject.toml`, add `dbus-fast` to `[project].dependencies` (keep the list sorted-ish; it already has `bleak`, `hid-tools`, `linuxpy`, `numpy`, `pillow`):

```toml
dependencies = [
    "bleak>=0.23",
    "dbus-fast>=2.24",
    "hid-tools>=0.12",
    "linuxpy>=0.16",
    "numpy>=2.1",
    "pillow>=12.2.0",
]
```

And extend the existing mypy override module list (so a strict run never trips on these libraries' import surface):

```toml
[[tool.mypy.overrides]]
module = ["linuxpy.*", "hidtools.*", "bleak.*", "dbus_fast.*"]
ignore_missing_imports = true
```

Then sync: `cd python && uv sync`
Expected: resolves and installs `dbus-fast` (may already be present as a `bleak` transitive dep).

- [ ] **Step 2: Write the failing tests**

Create `python/tests/test_shell_brightness.py`:

```python
"""Tests for the pure parts of the D-Bus sink module (iris.shell_brightness).

The async dbus-fast calls are covered by the on-device live test, not here.
"""

from pathlib import Path

from iris.shell_brightness import (
    is_backlight_wedged,
    read_backlight_max_sysfs,
    read_backlight_sysfs,
)


def _fake_backlight(tmp_path: Path, current: str, maximum: str) -> Path:
    (tmp_path / "brightness").write_text(current)
    (tmp_path / "max_brightness").write_text(maximum)
    return tmp_path


def test_read_sysfs_values(tmp_path: Path) -> None:
    base = _fake_backlight(tmp_path, "63\n", "400\n")
    assert read_backlight_sysfs(base) == 63
    assert read_backlight_max_sysfs(base) == 400


def test_read_sysfs_missing_returns_none(tmp_path: Path) -> None:
    assert read_backlight_sysfs(tmp_path) is None
    assert read_backlight_max_sysfs(tmp_path) is None


def test_read_sysfs_garbage_returns_none(tmp_path: Path) -> None:
    base = _fake_backlight(tmp_path, "notanumber", "also-bad")
    assert read_backlight_sysfs(base) is None
    assert read_backlight_max_sysfs(base) is None


def test_wedged_when_target_moved_but_panel_did_not() -> None:
    assert is_backlight_wedged(applied_delta=0.2, sysfs_delta=0, max_brightness=400) is True


def test_not_wedged_when_panel_moved() -> None:
    assert is_backlight_wedged(applied_delta=0.2, sysfs_delta=5, max_brightness=400) is False


def test_not_wedged_when_target_barely_moved() -> None:
    assert is_backlight_wedged(applied_delta=0.0, sysfs_delta=0, max_brightness=400) is False


def test_wedged_on_nvidia_max_100_tell() -> None:
    # The phantom nvidia_0 backlight reports max 100 (real intel is 400).
    assert is_backlight_wedged(applied_delta=0.0, sysfs_delta=0, max_brightness=100) is True
```

- [ ] **Step 2b: Run the tests to verify they fail**

Run: `cd python && uv run pytest tests/test_shell_brightness.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'iris.shell_brightness'`.

- [ ] **Step 3: Implement `iris/shell_brightness.py`**

Create `python/src/iris/shell_brightness.py`:

```python
"""The direct brightness sink: org.gnome.Shell.Brightness.SetAutoBrightnessTarget.

A published, sender-unrestricted session-bus API (BRIGHTNESS-MATH sec 6): iris owns
the curve, the shell owns the write, and -1 cleanly restores manual mode. Also holds
the sysfs backlight reader (never read the stale D-Bus Backlight property) and the
mutter-#4432 wedge predicate.
"""

from __future__ import annotations

from pathlib import Path

from dbus_fast import BusType, Message, MessageType
from dbus_fast.aio import MessageBus

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


def is_backlight_wedged(
    applied_delta: float, sysfs_delta: int, max_brightness: int | None
) -> bool:
    """True if brightness control looks wedged by mutter #4432.

    Either the panel's max reads 100 (the phantom nvidia_0 tell; real intel is
    400), or we pushed a materially different target but the panel didn't move.
    """
    if max_brightness == 100:
        return True
    return abs(applied_delta) > 0.1 and sysfs_delta == 0
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd python && uv run pytest tests/test_shell_brightness.py -q`
Expected: PASS (7 passed).

- [ ] **Step 5: Run the full gate and commit**

Run: `./dev.sh check` — expect all green (mypy included, with the new overrides).

```bash
git add python/src/iris/shell_brightness.py python/tests/test_shell_brightness.py python/pyproject.toml python/uv.lock
git commit -m "feat(reflex): add the dbus-fast brightness sink and sysfs watchdog"
```

---

### Task 4: The controller state machine (`iris/controller.py`)

**Files:**
- Create: `python/src/iris/controller.py`
- Test: `python/tests/test_controller.py`

**Interfaces:**
- Consumes: `iris.config.ReflexConfig`; `iris.pupil.TrackerState`.
- Produces:
  - `class SinkAction(Enum)`: `NONE`, `PUSH`, `RELEASE`.
  - `SinkCommand(action: SinkAction, target: float | None = None)` — frozen dataclass.
  - `class ReflexState(Enum)`: `RELEASED`, `DRIVING`.
  - `class ReflexController`:
    - `__init__(self, config: ReflexConfig, started_at: float) -> None`
    - `set_lux(self, lux: float) -> None` — updates the easing target via the curve.
    - `step(self, tracker_state: TrackerState, now: float) -> SinkCommand` — the state machine + easing; call at ~`push_hz`.
    - `on_shutdown(self) -> SinkCommand` — `RELEASE` if driving, else `NONE`.
    - attribute `state: ReflexState` (readable for the watchdog/logging).
- Behaviour contract:
  - On the FRESH tick that first engages (from RELEASED), `_applied` is set to `0.5` (neutral: `clamp(T + S − 0.5)` leaves the panel exactly where the slider had it → no jump) and the command is `PUSH(0.5)`.
  - Subsequent FRESH ticks ease `_applied` toward the curve target with `alpha = dt / (dt + ease_tau_s)`; a `PUSH` is emitted only when `_applied` has moved at least `push_epsilon` since the last push (else `NONE`).
  - The first non-FRESH tick after driving emits `RELEASE` exactly once and returns to RELEASED (subsequent non-FRESH ticks are `NONE`). This covers STALE (phone away), SCANNER_DEAD, and NEVER_SEEN.

- [ ] **Step 1: Write the failing tests**

Create `python/tests/test_controller.py`:

```python
"""Tests for the pure Reflex state machine + easing (iris.controller)."""

from pytest import approx

from iris.config import ReflexConfig
from iris.controller import ReflexController, ReflexState, SinkAction
from iris.curve import DEFAULT_ANCHORS, BrightnessCurve
from iris.pupil import TrackerConfig, TrackerState


def _cfg() -> ReflexConfig:
    # Build directly — hermetic, never touches ~/.config/iris/config.toml.
    return ReflexConfig(curve=BrightnessCurve(DEFAULT_ANCHORS), tracker=TrackerConfig())


def test_engage_pushes_neutral_half_first() -> None:
    c = ReflexController(_cfg(), started_at=0.0)
    c.set_lux(300.0)  # curve target ~0.55
    cmd = c.step(TrackerState.FRESH, now=0.0)
    assert cmd.action is SinkAction.PUSH
    assert cmd.target == approx(0.5)  # no jump at engage
    assert c.state is ReflexState.DRIVING


def test_eases_toward_target_over_ticks() -> None:
    c = ReflexController(_cfg(), started_at=0.0)
    c.set_lux(2000.0)  # curve target = ceil 1.0
    c.step(TrackerState.FRESH, now=0.0)  # engage -> 0.5
    now = 0.0
    last_push = 0.5
    for _ in range(300):  # 30 s at 10 Hz -> well past a few tau
        now += 0.1
        cmd = c.step(TrackerState.FRESH, now=now)
        if cmd.action is SinkAction.PUSH:
            assert cmd.target is not None
            assert cmd.target >= last_push - 1e-9  # monotonic up toward 1.0
            last_push = cmd.target
    assert last_push == approx(1.0, abs=5e-3)  # epsilon-gated: stops just shy of the asymptote


def test_stale_releases_once_then_quiet() -> None:
    c = ReflexController(_cfg(), started_at=0.0)
    c.set_lux(300.0)
    c.step(TrackerState.FRESH, now=0.0)
    assert c.step(TrackerState.STALE, now=1.0).action is SinkAction.RELEASE
    assert c.state is ReflexState.RELEASED
    assert c.step(TrackerState.STALE, now=2.0).action is SinkAction.NONE


def test_reengage_after_stale_pushes_neutral_again() -> None:
    c = ReflexController(_cfg(), started_at=0.0)
    c.set_lux(300.0)
    c.step(TrackerState.FRESH, now=0.0)
    c.step(TrackerState.STALE, now=1.0)  # release
    cmd = c.step(TrackerState.FRESH, now=2.0)  # re-engage
    assert cmd.action is SinkAction.PUSH
    assert cmd.target == approx(0.5)


def test_scanner_dead_releases_when_driving() -> None:
    c = ReflexController(_cfg(), started_at=0.0)
    c.set_lux(300.0)
    c.step(TrackerState.FRESH, now=0.0)
    assert c.step(TrackerState.SCANNER_DEAD, now=1.0).action is SinkAction.RELEASE


def test_never_seen_is_quiet_no_release() -> None:
    c = ReflexController(_cfg(), started_at=0.0)
    assert c.step(TrackerState.NEVER_SEEN, now=0.0).action is SinkAction.NONE
    assert c.state is ReflexState.RELEASED


def test_shutdown_releases_only_when_driving() -> None:
    c = ReflexController(_cfg(), started_at=0.0)
    assert c.on_shutdown().action is SinkAction.NONE  # never engaged
    c.set_lux(300.0)
    c.step(TrackerState.FRESH, now=0.0)
    assert c.on_shutdown().action is SinkAction.RELEASE


def test_lux_zero_while_fresh_drives_to_floor_not_release() -> None:
    c = ReflexController(_cfg(), started_at=0.0)
    c.set_lux(0.0)  # real darkness -> curve floor 0.08, NOT a dropout
    c.step(TrackerState.FRESH, now=0.0)  # engage 0.5
    now = 0.0
    last_push = 0.5
    for _ in range(300):
        now += 0.1
        cmd = c.step(TrackerState.FRESH, now=now)
        if cmd.action is SinkAction.PUSH:
            assert cmd.target is not None
            last_push = cmd.target
    assert last_push == approx(0.08, abs=5e-3)  # eased down to floor
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd python && uv run pytest tests/test_controller.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'iris.controller'`.

- [ ] **Step 3: Implement `iris/controller.py`**

Create `python/src/iris/controller.py`:

```python
"""The pure Reflex controller: DRIVING/RELEASED state machine + target easing.

No BLE, no D-Bus, no clock — time is passed in, so it is fully unit-testable. It
consumes the tracker's freshness state and the latest lux, and emits SinkCommands
the async shell (daemon.py) executes. Design spec sec 5.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from .config import ReflexConfig
from .pupil import TrackerState


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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd python && uv run pytest tests/test_controller.py -q`
Expected: PASS (8 passed).

- [ ] **Step 5: Run the full gate and commit**

Run: `./dev.sh check` — expect all green.

```bash
git add python/src/iris/controller.py python/tests/test_controller.py
git commit -m "feat(reflex): add the DRIVING/RELEASED controller with target easing"
```

---

### Task 5: Async wiring, service unit, and docs (`daemon.py` rewrite)

**Files:**
- Modify (rewrite): `python/src/iris/daemon.py`
- Create: `python/deploy/iris.service`
- Modify: `STATUS.md`, `DESIGN.md`, `README.md`

**Interfaces:**
- Consumes: `iris.config.load_config`; `iris.pupil` (`BTHOME_SERVICE_UUID`, `PupilTracker`, `TrackerState`); `iris.controller` (`ReflexController`, `SinkAction`, `SinkCommand`); `iris.shell_brightness` (`ShellBrightness`, `read_backlight_sysfs`, `read_backlight_max_sysfs`, `is_backlight_wedged`); `bleak.BleakScanner`.
- Produces: `run() -> None` (unchanged name — `iris/__main__.py` imports it) that runs the full loop; `async main() -> None`.
- This task has no new unit test (per spec sec 8, the async shell is verified on-device). Its gate is `./dev.sh check` green (typecheck/lint of the wiring + all prior tests still pass) plus the documented live procedure.

- [ ] **Step 1: Rewrite `iris/daemon.py`**

Replace the entire contents of `python/src/iris/daemon.py` with:

```python
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

from .config import load_config
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
    config = load_config()
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
                                wedged = is_backlight_wedged(
                                    applied_delta=wd_applied - wd_prev_applied,
                                    sysfs_delta=cur - wd_prev_sysfs,
                                    max_brightness=read_backlight_max_sysfs(),
                                )
                                if wedged and not warned_wedged:
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
```

- [ ] **Step 2: Verify the module imports and typechecks**

Run: `cd python && uv run python -c "import iris.daemon; print('ok')"`
Expected: prints `ok` (no import error).

Run: `./dev.sh check` — expect all green (ruff + mypy over the rewritten `daemon.py`, and all four new test files plus the existing suite pass).

- [ ] **Step 3: Create the systemd --user unit**

Create `python/deploy/iris.service`:

```ini
[Unit]
Description=iris — the phone (Pupil) as this laptop's ambient-light sensor
PartOf=graphical-session.target
After=graphical-session.target

[Service]
Type=simple
# Adjust the path to the checkout if it moves; uv keeps the venv at python/.venv.
ExecStart=%h/iris/python/.venv/bin/python -m iris
# Belt-and-braces: hand brightness back to manual even on a hard stop/crash.
ExecStopPost=/usr/bin/gdbus call --session --dest org.gnome.Shell --object-path /org/gnome/Shell/Brightness --method org.gnome.Shell.Brightness.SetAutoBrightnessTarget -1.0
Restart=on-failure
RestartSec=3

[Install]
WantedBy=graphical-session.target
```

- [ ] **Step 4: Live on-device validation (manual)**

This is the acceptance test (spec sec 8), run with Pupil broadcasting on the phone:

1. Start the daemon in the foreground: `cd python && uv run python -m iris`
2. **Engage:** bring the phone near the laptop → logs show `state: never-seen -> fresh`; the panel does **not** jump at engage, then eases toward the curve target for the ambient lux.
3. **Track:** raise/lower the light on the phone → the panel follows smoothly (no steppy jumps).
4. **Manual bias persists:** nudge the GNOME slider → the offset persists across subsequent updates (no re-anchor/rescale).
5. **Dropout → manual:** take the phone away (or stop Pupil) → after ~25 s logs show `-> stale`, the daemon sends `-1`, and the manual slider is back in control.
6. **Re-engage:** bring the phone back → drives again.
7. **Clean stop:** Ctrl-C → logs `released to manual`; the slider controls brightness normally.

Record the outcome (and any curve-anchor tuning for this room) in `README.md`.

- [ ] **Step 5: Update the docs**

In `STATUS.md`, mark Next №1 resolved — the sink decision is made (direct sink) and built as Reflex; note the webcam path is parked (modules retained, unused). In `DESIGN.md` §2, note the Tier-1b direct sink is the chosen and implemented sink for the phone-ALS mode. In `README.md`, add a short "Run Reflex" section: `cd python && uv run python -m iris`, the `deploy/iris.service` install (`systemctl --user`), the `~/.config/iris/config.toml` calibration knobs, and the live-test result from Step 4. Keep edits factual and brief; match each doc's existing voice.

- [ ] **Step 6: Commit**

```bash
git add python/src/iris/daemon.py python/deploy/iris.service STATUS.md DESIGN.md README.md
git commit -m "feat(reflex): wire the async BLE->curve->sink daemon; service unit; docs"
```

---

## Notes for the implementer

- **Formatting:** the code blocks here are hand-formatted and may not exactly match `ruff format`'s output. Before every `./dev.sh check`, run `cd python && uv run ruff format .` and re-stage — otherwise `ruff format --check` (part of the gate) can fail on whitespace alone.
- **Do not touch `iris/pupil.py`** — it is the validated, cross-language-contracted receive core; consume it as-is.
- The `dbus-fast` call surface in Task 3 (`MessageBus`, `Message`, `bus.call`) is the documented low-level API; if a minor signature differs in the installed version, adapt the `ShellBrightness._call` body only — the method name, path, interface, signature `"d"`, and the `-1.0` release contract are fixed by the Global Constraints and must not change.
- The controller's `0.5`-neutral engage is load-bearing (it is why bringing the phone near doesn't jump the panel); keep it exactly.
- Keep `run()`'s name and location — `iris/__main__.py` does `from iris.daemon import run`.
</content>
