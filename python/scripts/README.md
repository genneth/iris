# scripts — experiments

A lab notebook, not the product. Sorted by component; run with
`uv run python scripts/<component>/<name>.py` (the uhid one needs root — see below).

## `retina/` — the webcam-as-ALS exploration (parked)

**Hardware / capture probing**
- `probe.py` — enumerate V4L2 formats & frame sizes for the RGB (`/dev/video0`) and IR (`/dev/video2`) cameras.
- `controls.py` — robust dump of V4L2 controls + current values (works around linuxpy's control-enum crash).
- `capture_test.py` — first end-to-end capture (RGB YUYV → luma; IR GREY → mean).

**Sensing strategy**
- `meta_stream.py` — shows the camera does **not** expose live auto-exposure/gain → the "metadata" strategy is dead.
- `manual_test.py` — manual exposure/gain sweep; luma responds → **manual frame-mean luma** is the signal.
- `liveness.py` — confirm frames are fresh (sequence # + byte hash), not duplicates.
- `measure_sample.py` — per-sample cost: ~10 ms CPU, ~0.6 s window, luma stable on frame 0 under manual exposure.
- `measure_power.py` — battery-gauge estimate of the camera's continuous-stream draw (~1 W, low confidence).
- `probe_pixels.py` — pixel→scalar reduction + dynamic-range probe. `bracket` sweeps exposure
  (50..10000, in 100 µs units) at a held scene to show ranging headroom; `watch` logs candidate
  scalars (gamma mean / linearised mean / log-average) and clip/floor% over time across conditions.
  Informs the calibration in [BRIGHTNESS-MATH.md](../../../docs/iris/BRIGHTNESS-MATH.md).
- `probe_fps.py` — measured frame cadence.

**IR investigation (drove the brightness-only / RGB-only decision)**
- `ir_strobe.py` — characterises the Windows-Hello IR-emitter strobe (illuminated/ambient frame pairs @ 15 fps).
- `ir_heatmap.py` — ASCII heatmap of an emitter-ON vs ambient-OFF IR frame.
- `ir_video.py` — saves IR night-vision still + GIFs.
- `ir_rejection.py`, `ir_reject_spatial.py` — confirm the RGB sensor **robustly rejects IR** (so RGB luma is clean visible light).

**Architecture validation (the key one)**
- `uhid_als_spike.py` — presents a **virtual HID ambient-light sensor** via `/dev/uhid`. Proves
  webcam → virtual ALS → `hid-sensor-als` → IIO → `iio-sensor-proxy` → GNOME works end-to-end
  (`monitor-sensor` reports the pushed lux). See [`docs/retina/DESIGN.md`](../../../docs/retina/DESIGN.md) §"The virtual-ALS recipe".
  - `uv run python scripts/retina/uhid_als_spike.py validate` — parse/inspect the descriptor (no root).
  - `sudo .venv/bin/python scripts/retina/uhid_als_spike.py [seconds]` — create the device and push a lux ramp.

## `pupil/` — the phone-BLE receiver

- `ble_als_probe.py` — live pupil/BTHome advert probe (lux, RSSI, freshness state; `--csv` for the room walk).

## `iris/` — the brightness daemon

- `watch_brightness.py` — watch the panel backlight (and virtual-ALS lux, when present) together, live.
