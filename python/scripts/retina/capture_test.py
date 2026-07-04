"""End-to-end capture sanity check: grab low-res frames from RGB (YUYV) and IR
(GREY), compute mean luma, and read the RGB camera's live exposure/gain/AE
metadata. Proves the full sense pipeline before we build the playground.

Run: uv run python scripts/capture_test.py
"""

import numpy as np
from linuxpy.video.device import BufferType, Device, get_control

# Standard V4L2 control IDs (confirmed present on /dev/video0 by scripts/controls.py)
CID = {
    "brightness": 0x00980900,
    "gain": 0x00980913,
    "auto_white_balance": 0x0098090C,
    "white_balance_temp": 0x0098091A,
    "auto_exposure": 0x009A0901,  # menu: 1=manual, 3=aperture-priority(auto)
    "exposure_absolute": 0x009A0902,
}
AE_MODE = {0: "auto", 1: "manual", 2: "shutter-pri", 3: "aperture-pri"}


def grab(path: str, w: int, h: int, fourcc: str, n: int = 8):
    """Open device, set format, grab n frames; return (actual_format, [frames_bytes])."""
    cam = Device(path)
    cam.open()
    cam.set_format(BufferType.VIDEO_CAPTURE, w, h, pixel_format=fourcc)
    fmt = cam.get_format(BufferType.VIDEO_CAPTURE)
    frames = []
    for i, frame in enumerate(cam):
        frames.append((bytes(frame), len(frame)))
        if i + 1 >= n:
            break
    return cam, fmt, frames


def read_meta(cam) -> dict:
    out = {}
    for name, cid in CID.items():
        try:
            out[name] = get_control(cam, cid)
        except Exception as e:  # noqa: BLE001
            out[name] = f"<err {e!r}>"
    return out


def main() -> None:
    print("### RGB /dev/video0 (YUYV) ###")
    cam, fmt, frames = grab("/dev/video0", 160, 120, "YUYV")
    with cam:
        print(
            f"  negotiated: {fmt.width}x{fmt.height} {fmt.pixel_format.human_str()} "
            f"size={fmt.size} bpl={fmt.bytes_per_line}"
        )
        for i, (data, n) in enumerate(frames):
            arr = np.frombuffer(data, dtype=np.uint8)
            luma = arr[0::2].mean()  # YUYV: Y at even byte offsets
            print(f"  frame {i}: {n} bytes  mean_luma={luma:6.2f}")
        meta = read_meta(cam)
    ae = meta.get("auto_exposure")
    print(
        f"  metadata: AE={ae}({AE_MODE.get(ae, '?')}) "
        f"exposure_abs={meta['exposure_absolute']} gain={meta['gain']} "
        f"brightness={meta['brightness']} autoWB={meta['auto_white_balance']} "
        f"wb_temp={meta['white_balance_temp']}"
    )

    print("\n### IR /dev/video2 (GREY) ###")
    cam, fmt, frames = grab("/dev/video2", 160, 120, "GREY")
    with cam:
        print(
            f"  negotiated: {fmt.width}x{fmt.height} {fmt.pixel_format.human_str()} "
            f"size={fmt.size} bpl={fmt.bytes_per_line}"
        )
        for i, (data, n) in enumerate(frames):
            arr = np.frombuffer(data, dtype=np.uint8)
            print(f"  frame {i}: {n} bytes  mean={arr.mean():6.2f}  max={arr.max()}")


if __name__ == "__main__":
    main()
