"""SPIKE: present a virtual HID ambient-light sensor via /dev/uhid and check that
the kernel turns it into a real IIO illuminance device that iio-sensor-proxy can see.

Descriptor mirrors majbthrd/HIDsensor's Windows-validated HID-sensor structure,
swapped to Ambient Light (type 0x41) + Illuminance data field (0x04D1). No report
IDs: feature report = report_interval(u32); input report = [state(u8), event(u8),
illuminance(u32 LE)].

  uv run python scripts/uhid_als_spike.py validate   # parse only, no root
  sudo .venv/bin/python scripts/uhid_als_spike.py     # real test, needs /dev/uhid
"""

import glob
import struct
import subprocess
import sys
import time

# --- HID item helpers (standard 1-byte-tag encodings) ---
UP_SENSOR = [0x05, 0x20]  # Usage Page (Sensors)
COLL_LOG, END = [0xA1, 0x02], [0xC0]  # Collection(Logical), End Collection


def u(usage):  # 2-byte sensor usage: 0x0A lo hi
    return [0x0A, usage & 0xFF, (usage >> 8) & 0xFF]


ALS_TYPE = [0x09, 0x41]  # Usage: Sensor.Light.AmbientLight
PROP_REPORT_INTERVAL = u(0x030E)
STATE = u(0x0201)  # Sensor State (NAry)
STATE_SEL = [u(0x0800 + i) for i in range(7)]  # Unknown..Error
EVENT = u(0x0202)  # Sensor Event (NAry)
EVENT_SEL = [u(0x0810 + i) for i in range(17)]  # Unknown..ComplexTrigger
REP_STATE = u(0x0316)  # Property: Reporting State (NAry)
REP_STATE_SEL = [u(0x0840 + i) for i in range(6)]
PWR_STATE = u(0x0319)  # Property: Power State (NAry, MANDATORY)
PWR_STATE_SEL = [u(0x0850 + i) for i in range(6)]
DATA_ILLUM = u(0x04D1)  # Data Field: Illuminance

REPORT_DESC = (
    UP_SENSOR
    + ALS_TYPE
    + [0xA1, 0x00]  # Collection (Physical), ALS -- single (majbthrd style)
    + [0x85, 0x01]  # REPORT_ID(1) -- Linux hid-sensor-hub requires it
    + PROP_REPORT_INTERVAL  # feature: report interval (u32)
    + [0x15, 0x00]
    + [0x27, 0xFF, 0xFF, 0xFF, 0xFF]
    + [0x75, 0x20, 0x95, 0x01, 0x55, 0x00, 0xB1, 0x02]
    + REP_STATE
    + [0x15, 0x00, 0x25, 0x05, 0x75, 0x08, 0x95, 0x01]
    + COLL_LOG
    + sum(REP_STATE_SEL, [])
    + [0xB1, 0x00]
    + END  # feature: reporting state (NAry)
    + PWR_STATE
    + [0x15, 0x00, 0x25, 0x05, 0x75, 0x08, 0x95, 0x01]
    + COLL_LOG
    + sum(PWR_STATE_SEL, [])
    + [0xB1, 0x00]
    + END  # feature: power state (NAry, mandatory)
    + STATE
    + [0x15, 0x00, 0x25, 0x06, 0x75, 0x08, 0x95, 0x01]
    + COLL_LOG
    + sum(STATE_SEL, [])
    + [0x81, 0x00]
    + END  # input: sensor state (array)
    + EVENT
    + [0x15, 0x00, 0x25, 0x10, 0x75, 0x08, 0x95, 0x01]
    + COLL_LOG
    + sum(EVENT_SEL, [])
    + [0x81, 0x00]
    + END  # input: sensor event (array)
    + DATA_ILLUM  # input: illuminance (u32)
    + [0x15, 0x00]
    + [0x27, 0xFF, 0xFF, 0xFF, 0xFF]
    + [0x75, 0x20, 0x95, 0x01, 0x55, 0x00, 0x81, 0x02]
    + END  # end Collection (Physical)
)


def validate():
    from hidtools.hid import ReportDescriptor

    rd = ReportDescriptor.from_bytes(bytes(REPORT_DESC))
    print(f"parsed OK: {len(REPORT_DESC)} bytes")
    print(f"  input reports : {dict((k, v.size) for k, v in rd.input_reports.items())}")
    print(f"  feature reports: {dict((k, v.size) for k, v in rd.feature_reports.items())}")
    found = [
        f"{f.usage_name}"
        for r in rd.input_reports.values()
        for f in r
        if "llumin" in (f.usage_name or "")
    ]
    print(f"  illuminance field present: {found or 'NO'}")


def iio_illum():
    return sorted(glob.glob("/sys/bus/iio/devices/iio:device*/in_illuminance_raw"))


def run():
    from hidtools.uhid import UHIDDevice
    from hidtools.util import BusType

    class IrisALS(UHIDDevice):
        def __init__(self):
            super().__init__()
            self.name = "iris virtual ALS"
            self.info = (BusType.USB, 0x1209, 0x4953)
            self.rdesc = bytes(REPORT_DESC)
            self.interval = 200
            self.lux = 50

        def start(self, flags):
            self._ready = True

        def get_report(self, req, rnum, rtype):
            if rtype == UHIDDevice.UHID_INPUT_REPORT:  # poll of the data: return current lux
                return (0, [1, 1, 3] + list(struct.pack("<I", int(self.lux))))
            # feature: report id, interval u32, reporting=all(1), power=D0(1)
            return (0, [1] + list(struct.pack("<I", self.interval)) + [1, 1])

        def set_report(self, req, rnum, rtype, data):
            tail = bytes(data[1:5]) if len(data) >= 5 else bytes(data[:4])
            if len(tail) == 4:
                self.interval = struct.unpack("<I", tail)[0] or self.interval
            return 0

        def send_lux(self, lux):  # [report_id, state, event, illum u32]
            self.lux = lux
            self.call_input_event([1, 1, 3] + list(struct.pack("<I", int(lux))))

    subprocess.run(["modprobe", "hid-sensor-als"], check=False)
    dev = IrisALS()
    dev.create_kernel_device()
    dev._ready = True
    print(f"created uhid device ({len(REPORT_DESC)}-byte rdesc)")

    dur = next((float(a) for a in sys.argv[1:] if a.replace(".", "", 1).isdigit()), 18.0)
    t0 = time.monotonic()
    next_send, lux, reported = 0.0, 50, False
    while time.monotonic() - t0 < dur:
        UHIDDevice.dispatch(0.05)
        now = time.monotonic()
        if now >= next_send:
            dev.send_lux(lux)
            next_send = now + 1.0
            lux = min(lux + 100, 4000)
        if not reported and now - t0 > 3:
            reported = True
            print(f"sys_path: {dev.sys_path}")
            print(f"hid devices: {glob.glob('/sys/bus/hid/devices/*')}")
            illum = iio_illum()
            print(f"IIO illuminance nodes: {illum or 'NONE'}")
            for ln in subprocess.run(["dmesg"], capture_output=True, text=True).stdout.splitlines()[
                -120:
            ]:
                if any(k in ln.lower() for k in ("hid-sensor", "als", "uhid", "illumin")):
                    print("   dmesg:", ln)
    for f in iio_illum():
        print(f"final {f} = {open(f).read().strip()} (last pushed lux={lux})")
    dev.destroy()


if __name__ == "__main__":
    (validate if "validate" in sys.argv else run)()
