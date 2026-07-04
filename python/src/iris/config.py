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
