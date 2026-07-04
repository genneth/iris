"""Does the RGB camera expose LIVE auto-chosen exposure/gain while streaming?

Streams /dev/video0 in its default auto-exposure (aperture-priority) mode and
samples mean luma + exposure_absolute + gain ~twice a second. If exposure/gain
stay pinned at defaults while luma moves, the camera does NOT surface the AE
state and the "metadata" sensing strategy is unavailable in auto mode.

Run: uv run python scripts/meta_stream.py [seconds]
Vary the light (cover the lens / shine a phone torch) while it runs.
"""

import sys
import time

import numpy as np
from linuxpy.video.device import BufferType, Device, get_control

CID_GAIN = 0x00980913
CID_AUTO_EXPOSURE = 0x009A0901
CID_EXPOSURE_ABS = 0x009A0902


def main() -> None:
    secs = float(sys.argv[1]) if len(sys.argv) > 1 else 8.0
    cam = Device("/dev/video0")
    cam.open()
    cam.set_format(BufferType.VIDEO_CAPTURE, 320, 180, pixel_format="YUYV")
    print(f"AE mode = {get_control(cam, CID_AUTO_EXPOSURE)} (3=aperture-pri/auto)")
    print(f"{'t(s)':>6} {'frame':>6} {'luma':>7} {'exp_abs':>8} {'gain':>6}")

    t0 = time.monotonic()
    last_print = -1.0
    with cam:
        for i, frame in enumerate(cam):
            t = time.monotonic() - t0
            if t - last_print >= 0.5:
                arr = np.frombuffer(bytes(frame), dtype=np.uint8)
                luma = arr[0::2].mean()
                exp = get_control(cam, CID_EXPOSURE_ABS)
                gain = get_control(cam, CID_GAIN)
                print(f"{t:6.1f} {i:6d} {luma:7.2f} {exp:8d} {gain:6d}")
                last_print = t
            if t >= secs:
                break


if __name__ == "__main__":
    main()
