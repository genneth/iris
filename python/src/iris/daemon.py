"""iris MVP daemon.

Holds the webcam streaming and feeds robust scene brightness to a virtual ambient-light
sensor every PERIOD_S seconds, so GNOME's auto-brightness tracks the room. The camera
stream also paces the loop (~30 fps), giving us frequent chances to service the kernel's
GET_REPORT polls for the virtual sensor.

Needs /dev/uhid (root, or a udev rule) and the camera (session uaccess ACL). For the MVP,
run it with sudo:  sudo python/.venv/bin/python -m iris
"""

import signal
import time
from types import FrameType

from hidtools.uhid import UHIDDevice

from .brightness import brightness_to_lux, frame_brightness
from .camera import open_camera
from .virtual_als import VirtualALS

PERIOD_S = 2.0  # recompute/publish cadence; gsd-power smooths over ~10 s regardless

_running = True


def _stop(signum: int, frame: FrameType | None) -> None:
    global _running
    _running = False


def run() -> None:
    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    als = VirtualALS()
    als.create()
    try:
        with open_camera() as cam:
            last = 0.0
            for luma in cam.luma_frames():
                UHIDDevice.dispatch(0)  # service kernel get/set-report for the virtual sensor
                now = time.monotonic()
                if now - last >= PERIOD_S:
                    lux = brightness_to_lux(frame_brightness(luma))
                    als.push_lux(lux)
                    last = now
                if not _running:
                    break
    finally:
        als.destroy()
