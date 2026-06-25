"""Does the RGB sensor see the IR emitter? (i.e. how good is its IR-cut filter?)

Locks /dev/video0 (RGB) in manual exposure so it can't auto-compensate, then
streams it continuously while toggling the IR emitter on/off by starting and
stopping a stream on /dev/video2 (the emitter only fires while the IR camera
streams). Three phases guard against room-light drift:

    phase 1: IR off   (baseline)
    phase 2: IR on    (emitter strobing, reflecting off the scene)
    phase 3: IR off   (baseline again -> confirms no drift)

If RGB luma rises in phase 2 and falls back in phase 3, the RGB sensor is
IR-leaky. If all three phases match, IR rejection is robust.

Run: uv run python scripts/ir_rejection.py
"""

import threading

import numpy as np
from linuxpy.video.device import BufferType, Device, get_control, set_control

CID_GAIN = 0x00980913
CID_AUTO_EXPOSURE = 0x009A0901
CID_EXPOSURE_ABS = 0x009A0902
PER_PHASE = 24
EXPOSURE, GAIN = 1500, 128  # manual: ~150 ms integration, max gain (most sensitive)


def ir_emitter_worker(stop: threading.Event, counter: list) -> None:
    cam = Device("/dev/video2")
    cam.open()
    cam.set_format(BufferType.VIDEO_CAPTURE, 640, 360, pixel_format="GREY")
    with cam:
        for frame in cam:
            counter[0] += 1
            if stop.is_set():
                break


def stats(label: str, lumas: list) -> None:
    a = np.array(lumas)
    print(f"  {label:16s} n={len(a):3d}  mean={a.mean():7.3f}  std={a.std():6.3f}  "
          f"min={a.min():6.2f}  max={a.max():6.2f}")


def main() -> None:
    cam = Device("/dev/video0")
    cam.open()
    cam.set_format(BufferType.VIDEO_CAPTURE, 320, 180, pixel_format="YUYV")
    set_control(cam, CID_AUTO_EXPOSURE, 1)        # manual
    set_control(cam, CID_EXPOSURE_ABS, EXPOSURE)
    set_control(cam, CID_GAIN, GAIN)
    print(f"RGB locked manual: exposure_abs={get_control(cam, CID_EXPOSURE_ABS)} "
          f"gain={get_control(cam, CID_GAIN)}")

    stop = threading.Event()
    ir_count = [0]
    ir_thread = None
    phases = {"1 IR-off": [], "2 IR-ON": [], "3 IR-off": []}

    try:
        for i, frame in enumerate(cam):
            luma = float(np.frombuffer(bytes(frame), dtype=np.uint8)[0::2].mean())
            if i < PER_PHASE:
                phases["1 IR-off"].append(luma)
                if i == PER_PHASE - 1:  # turn emitter ON
                    ir_thread = threading.Thread(
                        target=ir_emitter_worker, args=(stop, ir_count))
                    ir_thread.start()
            elif i < 2 * PER_PHASE:
                if i >= PER_PHASE + 3:  # skip a few frames for IR stream to spin up
                    phases["2 IR-ON"].append(luma)
                if i == 2 * PER_PHASE - 1:  # turn emitter OFF
                    stop.set()
                    ir_thread.join()
            elif i < 3 * PER_PHASE:
                if i >= 2 * PER_PHASE + 3:
                    phases["3 IR-off"].append(luma)
            else:
                break
    finally:
        if ir_thread is not None and ir_thread.is_alive():
            stop.set()
            ir_thread.join()
        set_control(cam, CID_AUTO_EXPOSURE, 3)  # restore auto
        set_control(cam, CID_GAIN, 64)
        cam.close()

    print(f"\n(IR emitter streamed {ir_count[0]} frames during phase 2)\n")
    for label, lumas in phases.items():
        if lumas:
            stats(label, lumas)
    base = np.mean(phases["1 IR-off"] + phases["3 IR-off"])
    onm = np.mean(phases["2 IR-ON"])
    print(f"\n  IR-on minus baseline = {onm - base:+.3f} luma "
          f"({(onm - base) / max(base, 1e-6) * 100:+.1f}%)")
    print("  -> ~0 means robust IR rejection; a clear positive lift means IR leakage.")


if __name__ == "__main__":
    main()
