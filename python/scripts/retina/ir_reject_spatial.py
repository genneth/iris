"""Definitive RGB IR-rejection test via spatial differencing.

Averages many RGB Y-frames with the IR emitter OFF, then ON, and subtracts.
Constant room light cancels; a real IR leak shows up as a spatially-structured
(face/center) brightening matching the emitter's reflection pattern, while
sensor noise averages toward zero. Saves the difference as an image.

    robust rejection -> difference ~0 everywhere (black image)
    IR leakage       -> positive blob where the emitter reflects

Run: uv run python scripts/ir_reject_spatial.py [out_dir]
Hold still during the ~10 s capture.
"""

import sys
import threading

import numpy as np
from linuxpy.video.device import BufferType, Device, set_control
from PIL import Image

W, H = 320, 180
N = 25  # frames averaged per phase
OUT = sys.argv[1] if len(sys.argv) > 1 else "."


def ir_worker(stop, counter):
    cam = Device("/dev/video2")
    cam.open()
    cam.set_format(BufferType.VIDEO_CAPTURE, 640, 360, pixel_format="GREY")
    with cam:
        for _ in cam:
            counter[0] += 1
            if stop.is_set():
                break


def y_image(frame) -> np.ndarray:
    return np.frombuffer(bytes(frame), dtype=np.uint8)[0::2].reshape(H, W).astype(np.float64)


def main() -> None:
    cam = Device("/dev/video0")
    cam.open()
    cam.set_format(BufferType.VIDEO_CAPTURE, W, H, pixel_format="YUYV")
    set_control(cam, 0x009A0901, 1)  # AE manual
    set_control(cam, 0x009A0902, 1500)  # exposure
    set_control(cam, 0x00980913, 128)  # gain

    off_sum = np.zeros((H, W))
    on_sum = np.zeros((H, W))
    off_n = on_n = 0
    stop = threading.Event()
    ir_count = [0]
    ir_thread = None
    try:
        for i, frame in enumerate(cam):
            if i < N:  # phase 1: IR off
                off_sum += y_image(frame)
                off_n += 1
                if i == N - 1:
                    ir_thread = threading.Thread(target=ir_worker, args=(stop, ir_count))
                    ir_thread.start()
            elif i < 2 * N + 4:  # phase 2: IR on (skip 4 spin-up)
                if i >= N + 4:
                    on_sum += y_image(frame)
                    on_n += 1
            else:
                break
    finally:
        if ir_thread is not None:
            stop.set()
            ir_thread.join()
        set_control(cam, 0x009A0901, 3)
        set_control(cam, 0x00980913, 64)
        cam.close()

    off = off_sum / off_n
    on = on_sum / on_n
    diff = on - off
    print(f"IR emitter streamed {ir_count[0]} frames during phase 2")
    print(f"off avg luma = {off.mean():.3f}   on avg luma = {on.mean():.3f}")
    print(f"difference: mean={diff.mean():+.4f}  max={diff.max():+.3f}  min={diff.min():+.3f}")
    r, c = np.unravel_index(int(diff.argmax()), diff.shape)
    central = H * 0.25 < r < H * 0.75 and W * 0.25 < c < W * 0.75
    print(
        f"max-diff pixel at (row={r}, col={c})  {'CENTER (emitter-like)' if central else 'edge (motion-like)'}"
    )

    # Save difference, stretched so even a faint leak is visible (scale to +5 luma full-white).
    img = np.clip(diff / 5.0 * 255.0, 0, 255).astype(np.uint8)
    Image.fromarray(img, mode="L").save(f"{OUT}/ir_reject_diff.png")
    Image.fromarray(on.astype(np.uint8), mode="L").save(f"{OUT}/ir_reject_on.png")
    print(
        f"wrote ir_reject_diff.png (stretched: full white = +5 luma) and ir_reject_on.png to {OUT}/"
    )


if __name__ == "__main__":
    main()
