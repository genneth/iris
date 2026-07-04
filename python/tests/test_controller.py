"""Tests for the pure Reflex state machine + easing (iris.controller)."""

from pytest import approx

from iris.config import ReflexConfig
from iris.controller import ReflexController, ReflexState, SinkAction
from iris.curve import DEFAULT_ANCHORS, BrightnessCurve
from pupil import TrackerConfig, TrackerState


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
