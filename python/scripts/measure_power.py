"""Sample total system power from the battery gauge (only meaningful while discharging).

Usage: uv run python scripts/measure_power.py [seconds]
Power = |current_now| * voltage_now (sysfs reports uA and uV).
"""

import statistics
import sys
import time

BAT = "/sys/class/power_supply/BAT0"


def _read(name: str) -> int:
    with open(f"{BAT}/{name}") as f:
        return int(f.read().strip())


def power_w() -> float:
    return abs(_read("current_now")) * _read("voltage_now") / 1e12


def main() -> None:
    dur = float(sys.argv[1]) if len(sys.argv) > 1 else 20.0
    with open(f"{BAT}/status") as f:
        status = f.read().strip()
    samples = []
    t0 = time.monotonic()
    while time.monotonic() - t0 < dur:
        samples.append(power_w())
        time.sleep(0.5)
    print(
        f"status={status}  n={len(samples)}  mean={statistics.mean(samples):.2f} W  "
        f"min={min(samples):.2f}  max={max(samples):.2f}  stdev={statistics.pstdev(samples):.2f}"
    )


if __name__ == "__main__":
    main()
