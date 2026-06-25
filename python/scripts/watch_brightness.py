"""Watch the virtual ALS lux and the real panel backlight together.

While the iris daemon is running and you vary the room light, this shows whether GNOME's
auto-brightness actually moves intel_backlight in response to the camera.
Usage: uv run python scripts/watch_brightness.py [seconds]
"""

import sys
import time

IIO = "/sys/bus/iio/devices/iio:device0/in_illuminance_raw"
BL = "/sys/class/backlight/intel_backlight"


def rd(path: str) -> str:
    try:
        with open(path) as f:
            return f.read().strip()
    except OSError:
        return "?"


def main() -> None:
    dur = float(sys.argv[1]) if len(sys.argv) > 1 else 60.0
    mx = rd(f"{BL}/max_brightness")
    print(f"max_brightness={mx}")
    print(f"{'t(s)':>5} {'lux':>6} {'backlight':>9} {'pct':>4}")
    t0 = time.monotonic()
    while time.monotonic() - t0 < dur:
        lux, b = rd(IIO), rd(f"{BL}/brightness")
        pct = (
            f"{round(int(b) / int(mx) * 100)}" if b.isdigit() and mx.isdigit() and int(mx) else "?"
        )
        print(f"{time.monotonic() - t0:5.0f} {lux:>6} {b:>9} {pct:>4}", flush=True)
        time.sleep(2)


if __name__ == "__main__":
    main()
