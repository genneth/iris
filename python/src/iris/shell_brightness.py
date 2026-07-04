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


def is_backlight_wedged(applied_delta: float, sysfs_delta: int, max_brightness: int | None) -> bool:
    """True if brightness control looks wedged by mutter #4432.

    Either the panel's max reads 100 (the phantom nvidia_0 tell; real intel is
    400), or we pushed a materially different target but the panel didn't move.
    """
    if max_brightness == 100:
        return True
    return abs(applied_delta) > 0.1 and sysfs_delta == 0
