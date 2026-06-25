# scripts — experiments

The experiments that validated the iris design. Run with `uv run python scripts/<name>.py`
(the uhid one needs root — see below). These are a lab notebook, not the product; the real
daemon will live in `src/iris/`.

## Hardware / capture probing
- `probe.py` — enumerate V4L2 formats & frame sizes for the RGB (`/dev/video0`) and IR (`/dev/video2`) cameras.
- `controls.py` — robust dump of V4L2 controls + current values (works around linuxpy's control-enum crash).
- `capture_test.py` — first end-to-end capture (RGB YUYV → luma; IR GREY → mean).

## Sensing strategy
- `meta_stream.py` — shows the camera does **not** expose live auto-exposure/gain → the "metadata" strategy is dead.
- `manual_test.py` — manual exposure/gain sweep; luma responds → **manual frame-mean luma** is the signal.
- `liveness.py` — confirm frames are fresh (sequence # + byte hash), not duplicates.
- `measure_sample.py` — per-sample cost: ~10 ms CPU, ~0.6 s window, luma stable on frame 0 under manual exposure.

## IR investigation (drove the brightness-only / RGB-only decision)
- `ir_strobe.py` — characterises the Windows-Hello IR-emitter strobe (illuminated/ambient frame pairs @ 15 fps).
- `ir_heatmap.py` — ASCII heatmap of an emitter-ON vs ambient-OFF IR frame.
- `ir_video.py` — saves IR night-vision still + GIFs.
- `ir_rejection.py`, `ir_reject_spatial.py` — confirm the RGB sensor **robustly rejects IR** (so RGB luma is clean visible light).

## Architecture validation (the key one)
- `uhid_als_spike.py` — presents a **virtual HID ambient-light sensor** via `/dev/uhid`. Proves
  webcam → virtual ALS → `hid-sensor-als` → IIO → `iio-sensor-proxy` → GNOME works end-to-end
  (`monitor-sensor` reports the pushed lux). See `DESIGN.md` §"The virtual-ALS recipe".
  - `uv run python scripts/uhid_als_spike.py validate` — parse/inspect the descriptor (no root).
  - `sudo .venv/bin/python scripts/uhid_als_spike.py [seconds]` — create the device and push a lux ramp.
