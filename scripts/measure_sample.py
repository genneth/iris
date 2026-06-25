"""Measure the cost of one ambient-light sample cycle: open -> manual exposure ->
grab frames -> close. Reports wall time, process CPU time, and how many frames it
takes for mean luma to stabilise (decides the minimum capture window).

Run: uv run python scripts/measure_sample.py [ncycles]
"""

import os
import sys
import time

import numpy as np
from linuxpy.video.device import BufferType, Device, set_control

CID_GAIN, CID_AE, CID_EXP = 0x00980913, 0x009A0901, 0x009A0902


def sample(nframes=8):
    w0, c0 = time.monotonic(), sum(os.times()[:2])
    cam = Device("/dev/video0")
    cam.open()
    cam.set_format(BufferType.VIDEO_CAPTURE, 320, 180, pixel_format="YUYV")
    set_control(cam, CID_AE, 1)        # manual
    set_control(cam, CID_EXP, 1500)
    set_control(cam, CID_GAIN, 64)
    t_open = time.monotonic() - w0
    lumas, t_frames = [], []
    for i, frame in enumerate(cam):
        lumas.append(round(float(np.frombuffer(bytes(frame), np.uint8)[0::2].mean()), 2))
        t_frames.append(round(time.monotonic() - w0, 3))
        if i + 1 >= nframes:
            break
    cam.close()
    return {
        "wall_s": round(time.monotonic() - w0, 3),
        "cpu_s": round(sum(os.times()[:2]) - c0, 3),
        "open_to_first_frame_s": round(t_frames[0] - t_open, 3) if t_frames else None,
        "t_open_s": round(t_open, 3),
        "lumas": lumas,
        "frame_times_s": t_frames,
    }


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    print(f"{'cyc':>3} {'wall_s':>7} {'cpu_s':>6} {'t_open':>7} {'1stframe':>9}  luma trajectory")
    walls, cpus = [], []
    for c in range(n):
        r = sample()
        walls.append(r["wall_s"]); cpus.append(r["cpu_s"])
        print(f"{c:>3} {r['wall_s']:>7} {r['cpu_s']:>6} {r['t_open_s']:>7} "
              f"{r['open_to_first_frame_s']:>9}  {r['lumas']}")
        time.sleep(1.0)
    print(f"\nmedian wall={sorted(walls)[len(walls)//2]}s  median cpu={sorted(cpus)[len(cpus)//2]}s per sample")
    print("(luma should stabilise within a frame or two under fixed manual exposure)")


if __name__ == "__main__":
    main()
