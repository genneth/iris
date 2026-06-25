"""Demonstrate IR video from /dev/video2.

The Windows-Hello flood emitter strobes every other frame (15 fps capture ->
7.5 fps illuminated + 7.5 fps ambient). The illuminated frames are active-IR
"night vision". We separate the two phases and save:
  - ir_nightvision.png : contrast-stretched single emitter-ON frame
  - ir_video.gif       : emitter-ON frames only -> smooth IR night-vision video
  - ir_strobe.gif      : all frames -> shows the raw on/off emitter flicker

Run: uv run python scripts/ir_video.py [out_dir]
"""

import sys

import numpy as np
from linuxpy.video.device import BufferType, Device
from PIL import Image

W, H = 640, 360
OUT = sys.argv[1] if len(sys.argv) > 1 else "."


def stretch(a: np.ndarray, hi: float) -> Image.Image:
    """Linear contrast stretch [0, hi] -> [0, 255], as an 8-bit grayscale image."""
    s = np.clip(a.astype(np.float64) / max(hi, 1.0) * 255.0, 0, 255).astype(np.uint8)
    return Image.fromarray(s, mode="L")


def main() -> None:
    cam = Device("/dev/video2")
    cam.open()
    cam.set_format(BufferType.VIDEO_CAPTURE, W, H, pixel_format="GREY")
    raw = []
    with cam:
        for i, frame in enumerate(cam):
            raw.append(np.frombuffer(bytes(frame), dtype=np.uint8).reshape(H, W))
            if i >= 60:
                break

    on = [f for f in raw if f.max() > 50]
    off = [f for f in raw if f.max() <= 10]
    print(f"captured {len(raw)} frames -> {len(on)} emitter-ON, {len(off)} ambient-OFF")
    print(
        f"emitter-ON brightness: mean={np.mean([f.mean() for f in on]):.1f}, "
        f"peak max={max(f.max() for f in on)}"
    )

    # Global scale so the GIF doesn't flicker from per-frame normalisation.
    hi = float(np.percentile(np.concatenate([f.ravel() for f in on]), 99.5))

    best = max(on, key=lambda f: f.mean())
    stretch(best, hi).save(f"{OUT}/ir_nightvision.png")

    onv = [stretch(f, hi) for f in on]
    onv[0].save(f"{OUT}/ir_video.gif", save_all=True, append_images=onv[1:], duration=133, loop=0)

    allv = [stretch(f, hi) for f in raw]
    allv[0].save(f"{OUT}/ir_strobe.gif", save_all=True, append_images=allv[1:], duration=67, loop=0)
    print(
        f"wrote ir_nightvision.png, ir_video.gif ({len(onv)} frames), "
        f"ir_strobe.gif ({len(allv)} frames) to {OUT}/"
    )


if __name__ == "__main__":
    main()
