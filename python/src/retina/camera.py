"""Capture luma from the RGB webcam, tuned for low continuous-streaming power.

Lowest-power continuous config (see STATUS / probe_fps.py): smallest size 320x180
YUYV. fps is firmware-locked at 30 and cannot be reduced. We force *manual* exposure
and turn auto-white-balance off so the camera does no auto-normalisation — the luma
then reflects actual scene light (auto-exposure would hide the very thing we measure).
"""

from collections.abc import Iterator
from contextlib import contextmanager

import numpy as np
import numpy.typing as npt
from linuxpy.video.device import BufferType, Device, set_control

WIDTH, HEIGHT = 320, 180

# V4L2 control IDs confirmed present on /dev/video0 (see scripts/controls.py).
_CID_GAIN = 0x00980913
_CID_AUTO_WHITE_BALANCE = 0x0098090C
_CID_AUTO_EXPOSURE = 0x009A0901  # 1 = manual, 3 = aperture-priority (auto)
_CID_EXPOSURE_ABS = 0x009A0902


class Camera:
    """Streams luma planes (the YUYV Y channel) from an opened, configured device."""

    def __init__(self, dev: Device) -> None:
        self._dev = dev

    def luma_frames(self) -> Iterator[npt.NDArray[np.uint8]]:
        for frame in self._dev:
            buf: npt.NDArray[np.uint8] = np.frombuffer(bytes(frame), dtype=np.uint8)
            yield buf[0::2].reshape(HEIGHT, WIDTH)  # YUYV: Y at even byte offsets


@contextmanager
def open_camera(
    device: str = "/dev/video0", exposure: int = 1500, gain: int = 64
) -> Iterator[Camera]:
    cam = Device(device)
    cam.open()
    try:
        cam.set_format(BufferType.VIDEO_CAPTURE, WIDTH, HEIGHT, pixel_format="YUYV")
        set_control(cam, _CID_AUTO_EXPOSURE, 1)  # manual
        set_control(cam, _CID_AUTO_WHITE_BALANCE, 0)
        set_control(cam, _CID_EXPOSURE_ABS, exposure)
        set_control(cam, _CID_GAIN, gain)
        yield Camera(cam)
    finally:
        try:  # be polite — restore auto modes
            set_control(cam, _CID_AUTO_EXPOSURE, 3)
            set_control(cam, _CID_AUTO_WHITE_BALANCE, 1)
        finally:
            cam.close()
