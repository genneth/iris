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
