"""Probe how to reduce webcam pixels to one ambient-light number — and the camera's range.

Two questions, answered with data rather than a guess:

  1. Pixel -> scalar. The YUYV Y channel is *gamma-encoded* (Y' ~ luminance^(1/2.2)), so a
     plain mean averages in a non-linear space and is biased by scene contrast (it
     over-weights dark regions). We log several candidate reductions per frame so the
     choice is empirical:
       mean_g  - arithmetic mean of Y' (the current frame_brightness; gamma space)
       trim_g  - 2..98% trimmed mean of Y' (current robust variant)
       lin     - mean of gamma-decoded luminance  (true average relative luminance)
       geo     - geometric-mean luminance = exp(gamma * mean(log Y'_norm))
       logmean - mean(log Y'_norm), gamma-free, EV-like
     `logmean`/`geo` are the principled pick: they are the scene's *log-average*
     luminance, which is exactly the log-compressed signal gsd-power wants (BRIGHTNESS-MATH
     §3) and is far less swayed by a bright lamp or dark jumper in frame than a raw mean.

  2. Dynamic range. Does one fixed exposure span real lighting, or clip (mass piling at
     Y=235) / floor (mass at Y=16)? 'watch' logs clip%/floor% over time while you move
     through conditions; 'bracket' sweeps exposure at one held scene to show how many
     extra stops an auto-ranging loop would buy (exposure clamps to 50..10000 here).

Usage:
  uv run python scripts/probe_pixels.py watch [--duration S] [--interval S] [--exposure N]
  uv run python scripts/probe_pixels.py bracket [--gain N]
"""

import argparse
import time

import numpy as np
import numpy.typing as npt
from linuxpy.video.device import BufferType, Device, get_control, set_control

WIDTH, HEIGHT = 320, 180
BLACK, WHITE = 16.0, 235.0
GAMMA = 2.2  # approx encode gamma for decoding Y' -> relative luminance (good enough for a proxy)

# V4L2 control IDs (mirror iris.camera).
CID_GAIN = 0x00980913
CID_AUTO_WHITE_BALANCE = 0x0098090C
CID_AUTO_EXPOSURE = 0x009A0901  # 1 = manual, 3 = auto
CID_EXPOSURE_ABS = 0x009A0902

EXPOSURE_SWEEP = (50, 100, 200, 500, 1000, 2000, 5000, 10000)
SETTLE_FRAMES = 15  # frames to discard after an exposure change before it takes effect


def frame_stats(luma: npt.NDArray[np.uint8]) -> dict[str, float]:
    """Candidate pixel->scalar reductions plus clip/floor fractions for one luma plane."""
    a = luma.astype(np.float64).ravel()
    n = a.size
    norm = np.clip((a - BLACK) / (WHITE - BLACK), 0.0, 1.0)
    eps = 1.0 / (WHITE - BLACK)  # one code value, so log() never sees 0
    logn = np.log(np.clip(norm, eps, 1.0))
    lo, hi = (float(x) for x in np.percentile(a, [2.0, 98.0]))
    trimmed = float(np.clip(a, lo, hi).mean()) if hi > lo else float(a.mean())
    p10, p50, p90 = (float(x) for x in np.percentile(a, [10.0, 50.0, 90.0]))
    return {
        "clip": float(np.count_nonzero(luma >= 234) / n),
        "floor": float(np.count_nonzero(luma <= 17) / n),
        "mean_g": float(norm.mean()),
        "trim_g": float((trimmed - BLACK) / (WHITE - BLACK)),
        "lin": float((norm**GAMMA).mean()),
        "geo": float(np.exp(GAMMA * logn.mean())),
        "logmean": float(logn.mean()),
        "p10": p10,
        "p50": p50,
        "p90": p90,
    }


HEADER = (
    f"{'t':>5} {'exp':>6} {'clip%':>6} {'flr%':>6} "
    f"{'mean_g':>7} {'trim_g':>7} {'lin':>7} {'geo':>7} {'logmn':>7} "
    f"{'p10':>4} {'p50':>4} {'p90':>4}"
)


def fmt_row(t: float, exp: int, s: dict[str, float]) -> str:
    return (
        f"{t:5.0f} {exp:6d} {s['clip'] * 100:6.1f} {s['floor'] * 100:6.1f} "
        f"{s['mean_g']:7.4f} {s['trim_g']:7.4f} {s['lin']:7.4f} {s['geo']:7.4f} "
        f"{s['logmean']:7.3f} {s['p10']:4.0f} {s['p50']:4.0f} {s['p90']:4.0f}"
    )


def configure(dev: Device, exposure: int, gain: int) -> None:
    dev.set_format(BufferType.VIDEO_CAPTURE, WIDTH, HEIGHT, pixel_format="YUYV")
    set_control(dev, CID_AUTO_EXPOSURE, 1)
    set_control(dev, CID_AUTO_WHITE_BALANCE, 0)
    set_control(dev, CID_GAIN, gain)
    set_control(dev, CID_EXPOSURE_ABS, exposure)


def restore(dev: Device) -> None:
    set_control(dev, CID_AUTO_EXPOSURE, 3)
    set_control(dev, CID_AUTO_WHITE_BALANCE, 1)


def luma_of(frame: object) -> npt.NDArray[np.uint8]:
    buf: npt.NDArray[np.uint8] = np.frombuffer(bytes(frame), dtype=np.uint8)
    return buf[0::2].reshape(HEIGHT, WIDTH)  # YUYV: Y at even byte offsets


def watch(args: argparse.Namespace) -> None:
    dev = Device(args.device)
    dev.open()
    hists: list[npt.NDArray[np.int64]] = []
    meta: list[tuple[float, int, int]] = []
    try:
        configure(dev, args.exposure, args.gain)
        exp = int(get_control(dev, CID_EXPOSURE_ABS))
        print(f"# watch exposure={exp} gain={int(get_control(dev, CID_GAIN))} {WIDTH}x{HEIGHT}")
        print(HEADER)
        t0 = time.monotonic()
        last = -1e9
        for frame in dev:
            now = time.monotonic()
            if now - t0 >= args.duration:
                break
            if now - last < args.interval:
                continue
            last = now
            luma = luma_of(frame)
            print(fmt_row(now - t0, exp, frame_stats(luma)), flush=True)
            if args.save:
                hists.append(np.bincount(luma.ravel(), minlength=256))
                meta.append((now - t0, exp, args.gain))
    finally:
        restore(dev)
        dev.close()
    if args.save and hists:
        np.savez(args.save, hist=np.array(hists), meta=np.array(meta))
        print(f"# saved {len(hists)} histograms -> {args.save}")


def bracket(args: argparse.Namespace) -> None:
    dev = Device(args.device)
    dev.open()
    it = None
    try:
        configure(dev, EXPOSURE_SWEEP[0], args.gain)
        gain = int(get_control(dev, CID_GAIN))
        print(f"# bracket gain={gain} {WIDTH}x{HEIGHT}; hold the scene still")
        print(HEADER)
        it = iter(dev)
        for req in EXPOSURE_SWEEP:
            set_control(dev, CID_EXPOSURE_ABS, req)
            exp = int(get_control(dev, CID_EXPOSURE_ABS))
            for _ in range(SETTLE_FRAMES):
                next(it)
            print(fmt_row(0, exp, frame_stats(luma_of(next(it)))), flush=True)
    finally:
        if it is not None:
            it.close()  # stop streaming before close, else close() blocks
        restore(dev)
        dev.close()


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--device", default="/dev/video0")
    sub = p.add_subparsers(dest="mode", required=True)

    w = sub.add_parser("watch", help="log scalars over time as you move through conditions")
    w.add_argument("--duration", type=float, default=120.0)
    w.add_argument("--interval", type=float, default=2.0)
    w.add_argument("--exposure", type=int, default=1500)
    w.add_argument("--gain", type=int, default=64)
    w.add_argument("--save", default=None, help="optional .npz dump of per-sample histograms")
    w.set_defaults(func=watch)

    b = sub.add_parser("bracket", help="sweep exposure at a held scene to measure ranging headroom")
    b.add_argument("--gain", type=int, default=64)
    b.set_defaults(func=bracket)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
