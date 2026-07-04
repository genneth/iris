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
