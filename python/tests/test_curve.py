"""Tests for the pure lux->backlight-target curve (iris.curve)."""

import math

import pytest
from pytest import approx

from iris import DEFAULT_ANCHORS, BrightnessCurve


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
