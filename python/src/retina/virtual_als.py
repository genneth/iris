"""A virtual HID ambient-light sensor on /dev/uhid.

Presenting this makes the kernel expose a real /sys/bus/iio illuminance device that
iio-sensor-proxy — and hence GNOME's auto-brightness — consumes. The descriptor is the
validated recipe from scripts/uhid_als_spike.py (see DESIGN.md §3): a single Physical
collection with the ALS usage, a report ID, a mandatory Power State property, and a
get_report that serves the *input* report (current lux) on an input poll.

Requires write access to /dev/uhid (root, or a udev rule granting the user).
"""

import struct
from typing import Any

from hidtools.uhid import UHIDDevice
from hidtools.util import BusType

_UP_SENSOR = [0x05, 0x20]
_COLL_LOG, _END = [0xA1, 0x02], [0xC0]


def _u(usage: int) -> list[int]:
    """A 2-byte Sensor-page usage item (0x0A lo hi)."""
    return [0x0A, usage & 0xFF, (usage >> 8) & 0xFF]


def _flat(seqs: list[list[int]]) -> list[int]:
    return [b for seq in seqs for b in seq]


_ALS_TYPE = [0x09, 0x41]  # Usage: Sensor.Light.AmbientLight
_PROP_INTERVAL = _u(0x030E)
_STATE = _u(0x0201)
_STATE_SEL = [_u(0x0800 + i) for i in range(7)]
_EVENT = _u(0x0202)
_EVENT_SEL = [_u(0x0810 + i) for i in range(17)]
_REP_STATE = _u(0x0316)
_REP_STATE_SEL = [_u(0x0840 + i) for i in range(6)]
_PWR_STATE = _u(0x0319)  # mandatory: hid-sensor-hub get_features this at probe
_PWR_STATE_SEL = [_u(0x0850 + i) for i in range(6)]
_DATA_ILLUM = _u(0x04D1)

# fmt: off
REPORT_DESC = bytes(
    _UP_SENSOR + _ALS_TYPE + [0xA1, 0x00]                       # Collection (Physical), ALS
    + [0x85, 0x01]                                              # Report ID 1
    + _PROP_INTERVAL + [0x15, 0x00] + [0x27, 0xFF, 0xFF, 0xFF, 0xFF]
    + [0x75, 0x20, 0x95, 0x01, 0x55, 0x00, 0xB1, 0x02]          # feature: report interval (u32)
    + _REP_STATE + [0x15, 0x00, 0x25, 0x05, 0x75, 0x08, 0x95, 0x01] + _COLL_LOG
    + _flat(_REP_STATE_SEL) + [0xB1, 0x00] + _END             # feature: reporting state
    + _PWR_STATE + [0x15, 0x00, 0x25, 0x05, 0x75, 0x08, 0x95, 0x01] + _COLL_LOG
    + _flat(_PWR_STATE_SEL) + [0xB1, 0x00] + _END             # feature: power state (mandatory)
    + _STATE + [0x15, 0x00, 0x25, 0x06, 0x75, 0x08, 0x95, 0x01] + _COLL_LOG
    + _flat(_STATE_SEL) + [0x81, 0x00] + _END                 # input: sensor state
    + _EVENT + [0x15, 0x00, 0x25, 0x10, 0x75, 0x08, 0x95, 0x01] + _COLL_LOG
    + _flat(_EVENT_SEL) + [0x81, 0x00] + _END                 # input: sensor event
    + _DATA_ILLUM + [0x15, 0x00] + [0x27, 0xFF, 0xFF, 0xFF, 0xFF]
    + [0x75, 0x20, 0x95, 0x01, 0x55, 0x00, 0x81, 0x02]          # input: illuminance (u32)
    + _END
)
# fmt: on


class VirtualALS(UHIDDevice):  # hidtools is untyped (no stubs)
    def __init__(self, interval_ms: int = 1000) -> None:
        super().__init__()
        self.name = "iris virtual ALS"
        self.info = (BusType.USB, 0x1209, 0x4953)
        self.rdesc = REPORT_DESC
        self.interval = interval_ms
        self.lux = 0

    def create(self) -> None:
        self.create_kernel_device()
        self._ready = True

    def start(self, flags: Any) -> None:  # uhid lifecycle callback
        self._ready = True

    def get_report(self, req: int, rnum: int, rtype: int) -> tuple[int, list[int]]:
        if rtype == self.UHID_INPUT_REPORT:  # poll of the data → current lux
            return (0, [1, 1, 3, *struct.pack("<I", self.lux)])
        return (0, [1, *struct.pack("<I", self.interval), 1, 1])  # feature report

    def set_report(self, req: int, rnum: int, rtype: int, data: list[int]) -> int:
        return 0  # accept (and ignore) reporting/power/interval writes

    def push_lux(self, lux: int) -> None:
        """Publish a new reading as an input report (and keep it for polls)."""
        self.lux = max(0, int(lux))
        self.call_input_event([1, 1, 3, *struct.pack("<I", self.lux)])
