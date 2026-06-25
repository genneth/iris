"""Robustly dump V4L2 controls + current values, bypassing linuxpy's high-level
Controls dict (which aborts the whole enumeration on an unmodeled control type,
e.g. type 263 on this UVC camera).

Run: uv run python scripts/controls.py [/dev/videoN ...]
"""

import sys

from linuxpy.video.device import (
    ControlFlag,
    ControlType,
    Device,
    get_control,
    iter_read_controls,
)


def type_name(t: int) -> str:
    try:
        return ControlType(t).name
    except ValueError:
        return f"UNKNOWN({t})"


def dump(path: str) -> None:
    print(f"\n{'=' * 78}\n{path}\n{'=' * 78}")
    cam = Device(path)
    cam.open()
    with cam:
        for c in iter_read_controls(cam):
            flags = ControlFlag(c.flags)
            name = c.name.decode(errors="replace")
            tname = type_name(c.type)

            # Value is only meaningfully readable for simple scalar controls.
            readable = (
                ControlFlag.HAS_PAYLOAD not in flags
                and ControlFlag.DISABLED not in flags
                and ControlFlag.WRITE_ONLY not in flags
                and c.type
                in (
                    ControlType.INTEGER,
                    ControlType.BOOLEAN,
                    ControlType.MENU,
                    ControlType.INTEGER_MENU,
                    ControlType.U8,
                    ControlType.U16,
                    ControlType.U32,
                )
            )
            if readable:
                try:
                    val = get_control(cam, c.id)
                except Exception as e:  # noqa: BLE001
                    val = f"<err {e!r}>"
            else:
                val = "—"

            flag_str = ",".join(f.name.lower() for f in ControlFlag if f in flags) or "-"
            print(
                f"  0x{c.id:08x} {name:34s} {tname:12s} "
                f"min={c.minimum:<6} max={c.maximum:<8} step={c.step:<5} "
                f"def={c.default_value:<8} val={val!s:<8} [{flag_str}]"
            )


if __name__ == "__main__":
    for p in sys.argv[1:] or ["/dev/video0", "/dev/video2"]:
        dump(p)
