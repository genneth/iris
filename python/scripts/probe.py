"""Enumerate V4L2 capabilities for the iris cameras: formats, frame sizes, controls.

Run: uv run python scripts/probe.py
"""

import sys

from linuxpy.video.device import Device


def probe(path: str) -> None:
    print(f"\n{'=' * 70}\n{path}\n{'=' * 70}")
    try:
        cam = Device(path)
        cam.open()
    except Exception as e:  # noqa: BLE001
        print(f"  !! cannot open: {e!r}")
        return

    with cam:
        info = cam.info
        print(f"  card    : {info.card}")
        print(f"  driver  : {info.driver}")
        print(f"  bus     : {info.bus_info}")

        print("\n  -- formats --")
        try:
            for fmt in info.formats:
                print(f"    {fmt.pixel_format.name:12s} {fmt.description}")
        except Exception as e:  # noqa: BLE001
            print(f"    (format enum failed: {e!r})")

        print("\n  -- frame sizes --")
        try:
            for fs in info.frame_sizes:
                print(f"    {fs.pixel_format.name:12s} {fs.width}x{fs.height}")
        except Exception as e:  # noqa: BLE001
            print(f"    (frame size enum failed: {e!r})")

        print("\n  -- controls --")
        try:
            for ctrl in cam.controls.values():
                bits = [f"id=0x{ctrl.id:08x}", f"type={ctrl.type.name.lower()}"]
                for attr in ("minimum", "maximum", "step", "default_value"):
                    if hasattr(ctrl, attr):
                        bits.append(f"{attr}={getattr(ctrl, attr)}")
                try:
                    val = ctrl.value
                except Exception as e:  # noqa: BLE001
                    val = f"<read err {e!r}>"
                bits.append(f"value={val}")
                print(f"    {ctrl.config_name:32s} {' '.join(bits)}")
        except Exception as e:  # noqa: BLE001
            print(f"    (control enum failed: {e!r})")


if __name__ == "__main__":
    paths = sys.argv[1:] or ["/dev/video0", "/dev/video2"]
    for p in paths:
        probe(p)
