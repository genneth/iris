"""Characterise the IR camera's alternating black/faint frame pattern.

Discriminates: (a) real strobe vs MMAP buffering artifact -> via V4L2 sequence
number + timestamp spacing; (b) emitter glint vs ambient IR -> via spatial
distribution (where the bright pixels are).

Run: uv run python scripts/ir_strobe.py [nframes]
"""

import sys

import numpy as np
from linuxpy.video.device import BufferType, Device

W, H = 640, 360


def main() -> None:
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 40
    cam = Device("/dev/video2")
    cam.open()
    cam.set_format(BufferType.VIDEO_CAPTURE, W, H, pixel_format="GREY")
    fmt = cam.get_format(BufferType.VIDEO_CAPTURE)
    try:
        fps = cam.get_fps(BufferType.VIDEO_CAPTURE)
    except Exception as e:  # noqa: BLE001
        fps = f"<{e!r}>"
    print(f"format {fmt.width}x{fmt.height} {fmt.pixel_format.human_str()}  fps={fps}")
    print(f"{'i':>3} {'seq':>5} {'buf':>3} {'t(s)':>9} {'dt(ms)':>7} {'mean':>6} {'max':>4} "
          f"{'nnz>4':>7} {'argmax(r,c)':>12} {'where':>8}")

    prev_t = None
    with cam:
        for i, frame in enumerate(cam):
            a = np.frombuffer(bytes(frame), dtype=np.uint8).reshape(H, W).astype(np.int32)
            t = frame.timestamp
            dt = (t - prev_t) * 1000 if prev_t is not None else 0.0
            prev_t = t
            nnz = int((a > 4).sum())
            r, c = np.unravel_index(int(a.argmax()), a.shape)
            # which image region holds the brightest pixel (center bias = emitter glint)
            quad = f"{'T' if r < H/2 else 'B'}{'L' if c < W/2 else 'R'}"
            cen = "CENTER" if (H*0.25 < r < H*0.75 and W*0.25 < c < W*0.75) else quad
            print(f"{i:3d} {frame.frame_nb:5d} {frame.index:3d} {t:9.3f} {dt:7.1f} "
                  f"{a.mean():6.2f} {a.max():4d} {nnz:7d} {f'({r},{c})':>12} {cen:>8}")


if __name__ == "__main__":
    main()
