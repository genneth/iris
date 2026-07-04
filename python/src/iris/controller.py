"""The pure Reflex controller: DRIVING/RELEASED state machine + target easing.

No BLE, no D-Bus, no clock — time is passed in, so it is fully unit-testable. It
consumes the tracker's freshness state and the latest lux, and emits SinkCommands
the async shell (daemon.py) executes. Design spec sec 5.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from pupil import TrackerState

from .config import ReflexConfig


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
