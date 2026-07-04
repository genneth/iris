"""Tests for config loading (iris.config)."""

from pathlib import Path

from pytest import approx

from iris import DEFAULT_ANCHORS, ReflexConfig, load_config


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
