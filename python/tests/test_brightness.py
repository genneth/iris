import numpy as np

from retina.brightness import brightness_to_lux, frame_brightness


def gray(val: int, n: int = 64) -> np.ndarray:
    return np.full((n, n), val, dtype=np.uint8)


def test_black_level_is_zero() -> None:
    # YUYV studio-range black is Y=16.
    assert frame_brightness(gray(16)) == 0.0


def test_white_level_is_one() -> None:
    assert frame_brightness(gray(235)) == 1.0


def test_midgray_is_about_half() -> None:
    b = frame_brightness(gray(125))  # (125-16)/(235-16) ~= 0.50
    assert 0.45 < b < 0.55


def test_monotonic_in_scene_brightness() -> None:
    assert frame_brightness(gray(60)) < frame_brightness(gray(160))


def test_robust_to_a_few_bright_outliers() -> None:
    # A mostly-dark frame with a handful of blown-out pixels should still read dark.
    a = gray(16)
    a[0, :5] = 255
    assert frame_brightness(a) < 0.1


def test_lux_is_monotonic_and_nonnegative() -> None:
    assert brightness_to_lux(0.0) >= 0
    assert brightness_to_lux(0.0) < brightness_to_lux(0.5) < brightness_to_lux(1.0)
