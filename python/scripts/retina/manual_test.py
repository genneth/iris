"""Confirm the pivoted primary strategy: manual-exposure frame-mean.

Sets AE to manual, sweeps exposure_absolute (and gain), and checks that mean
luma responds monotonically. If so, we can fix exposure/gain and read luma as a
direct (un-normalised) ambient-light signal. Restores auto mode on exit.

Run: uv run python scripts/manual_test.py
"""

import numpy as np
from linuxpy.video.device import BufferType, Device, get_control, set_control

CID_GAIN = 0x00980913
CID_AUTO_EXPOSURE = 0x009A0901
CID_EXPOSURE_ABS = 0x009A0902
AE_MANUAL, AE_APERTURE = 1, 3


def mean_luma(cam, settle=5) -> float:
    """Grab a few frames to let the pipeline flush, return mean luma of the last."""
    luma = float("nan")
    for i, frame in enumerate(cam):
        if i >= settle:
            arr = np.frombuffer(bytes(frame), dtype=np.uint8)
            luma = float(arr[0::2].mean())
            break
    return luma


def main() -> None:
    cam = Device("/dev/video0")
    cam.open()
    cam.set_format(BufferType.VIDEO_CAPTURE, 320, 180, pixel_format="YUYV")
    try:
        set_control(cam, CID_AUTO_EXPOSURE, AE_MANUAL)
        print(
            f"set AE=manual -> reads back {get_control(cam, CID_AUTO_EXPOSURE)} "
            f"(exposure_abs now {get_control(cam, CID_EXPOSURE_ABS)})"
        )

        print("\n-- sweep exposure_absolute at gain=0 --")
        set_control(cam, CID_GAIN, 0)
        print(f"{'exp_abs':>8} {'luma':>7}")
        for exp in (50, 100, 200, 400, 800, 1600, 3200, 6400, 10000):
            set_control(cam, CID_EXPOSURE_ABS, exp)
            print(f"{exp:8d} {mean_luma(cam):7.2f}")

        print("\n-- sweep gain at exposure_absolute=2000 --")
        set_control(cam, CID_EXPOSURE_ABS, 2000)
        print(f"{'gain':>8} {'luma':>7}")
        for gain in (0, 16, 32, 64, 96, 128):
            set_control(cam, CID_GAIN, gain)
            print(f"{gain:8d} {mean_luma(cam):7.2f}")
    finally:
        set_control(cam, CID_AUTO_EXPOSURE, AE_APERTURE)  # be polite: restore auto
        set_control(cam, CID_GAIN, 64)
        print(f"\nrestored AE={get_control(cam, CID_AUTO_EXPOSURE)}")
        cam.close()


if __name__ == "__main__":
    main()
