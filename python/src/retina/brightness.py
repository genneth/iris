"""Turn a camera luma plane into an ambient-light estimate.

We don't need absolute lux: gsd-power applies its own response curve and smoothing
to whatever the sensor reports, so iris only needs a *monotonic, robust, plausible*
value. `frame_brightness` returns a robust 0..1 scene brightness (trimmed mean of
luma, so a few blown-out pixels don't dominate); `brightness_to_lux` maps that onto
a lux-ish scale for the virtual sensor. Calibration of that mapping is a tuning
open-question (see STATUS) — monotonicity is what matters today.
"""

import numpy as np
import numpy.typing as npt

# YUYV is studio-range: Y runs 16 (black) .. 235 (white).
BLACK = 16.0
WHITE = 235.0

# Placeholder lux span for the virtual sensor (gsd-power supplies the real curve).
MAX_LUX = 1000


def frame_brightness(luma: npt.NDArray[np.uint8]) -> float:
    """Robust scene brightness in 0..1 from a luma plane (outlier-trimmed mean)."""
    a = luma.astype(np.float64).ravel()
    lo, hi = (float(x) for x in np.percentile(a, [2.0, 98.0]))
    mean = float(np.clip(a, lo, hi).mean()) if hi > lo else float(a.mean())
    return float(np.clip((mean - BLACK) / (WHITE - BLACK), 0.0, 1.0))


def brightness_to_lux(brightness: float) -> int:
    """Map 0..1 brightness to a (monotonic, plausible) lux value for the virtual ALS."""
    return round(max(0.0, min(1.0, brightness)) * MAX_LUX)
