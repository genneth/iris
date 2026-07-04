"""Enumerate frame sizes + frame intervals (fps) for the RGB camera, to find the
lowest-power streaming config (smallest size, lowest fps). Run: uv run python scripts/probe_fps.py"""

from linuxpy.video.device import (
    Device,
    iter_read_frame_intervals,
    iter_read_frame_sizes,
)

cam = Device("/dev/video0")
cam.open()
with cam:
    for fmt in cam.info.formats:
        pf = fmt.pixel_format
        print(f"== {pf.name} ({fmt.description}) ==")
        for fs in iter_read_frame_sizes(cam, pf):
            info = fs.info
            w = getattr(info, "width", None)
            h = getattr(info, "height", None)
            try:
                ivs = list(iter_read_frame_intervals(cam, pf, w, h))
            except Exception as e:  # noqa: BLE001
                ivs = [f"<{e!r}>"]
            print(f"  {w}x{h}: {ivs}")
