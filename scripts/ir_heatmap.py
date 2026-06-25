"""Visual confirmation of the IR emitter strobe: render a coarse ASCII heatmap
of an emitter-ON frame (central flood blob) vs an emitter-OFF frame (ambient
only), and show how to extract the ambient-IR reading by keeping OFF frames.

Run: uv run python scripts/ir_heatmap.py
"""

import numpy as np
from linuxpy.video.device import BufferType, Device

W, H = 640, 360
ROWS, COLS = 10, 20  # heatmap blocks (H/ROWS and W/COLS must divide evenly)
RAMP = " .:-=+*#%@"


def heatmap(a: np.ndarray) -> str:
    blocks = a.reshape(ROWS, H // ROWS, COLS, W // COLS).mean(axis=(1, 3))
    lines = []
    for row in blocks:
        chars = [RAMP[min(int(v / 256 * len(RAMP)), len(RAMP) - 1)] for v in row]
        lines.append("    |" + "".join(chars) + "|")
    return "\n".join(lines)


def main() -> None:
    cam = Device("/dev/video2")
    cam.open()
    cam.set_format(BufferType.VIDEO_CAPTURE, W, H, pixel_format="GREY")
    frames = []
    with cam:
        for i, frame in enumerate(cam):
            frames.append(np.frombuffer(bytes(frame), dtype=np.uint8).reshape(H, W))
            if i >= 11:
                break

    on = [f for f in frames if f.max() > 50]
    off = [f for f in frames if f.max() <= 10]
    print(f"captured {len(frames)} frames: {len(on)} emitter-ON, {len(off)} ambient-OFF\n")
    if on:
        print(f"EMITTER-ON  (mean={on[0].mean():.1f} max={on[0].max()}):")
        print(heatmap(on[0].astype(np.float64)))
    if off:
        print(f"\nAMBIENT-OFF (mean={off[0].mean():.2f} max={off[0].max()}):")
        print(heatmap(off[0].astype(np.float64)))
    if off:
        amb = np.mean([f.mean() for f in off])
        print(f"\nambient-IR signal (mean of OFF frames) = {amb:.3f}  "
              f"(near-zero => IR-poor light, e.g. LED)")


if __name__ == "__main__":
    main()
