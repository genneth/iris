# iris — status

_Updated 2026-06-25. Architecture validated; daemon not yet built._

## ✅ Done

- **v1 sensing experiments** (host-side Python + `linuxpy` + numpy; see [FINDINGS.md](./FINDINGS.md)):
  - Hardware confirmed (RGB `/dev/video0` YUYV; IR `/dev/video2` GREY); camera opens via the
    session `uaccess` ACL — no root, no `video` group.
  - The "read auto-exposure/gain metadata" strategy is **dead** on this camera → the usable signal
    is **mean luma under manual exposure**.
  - The RGB sensor **robustly rejects IR** → RGB luma is a clean visible-light signal (IR cam not needed).
- **Scope locked: brightness only** (colour temperature dropped); single RGB sensor.
- **Build vs. reuse clight: build.** clight writes brightness from a root daemon; iris is a sensor.
  We crib clight's brightness-*estimate* algorithm, not its architecture.
- **Sink architecture: pivoted Tier-2 → Tier-1.** Confirmed GNOME doesn't observe external
  brightness writes (stale slider, hotkeys jump) → iris-as-writer fights GNOME. Chose iris-as-sensor.
- **uhid virtual ALS: VALIDATED END-TO-END.** `scripts/uhid_als_spike.py` presents a virtual HID
  ambient-light sensor; `monitor-sensor` reports our exact pushed lux through
  `iio-sensor-proxy`/`net.hadess.SensorProxy`. The hard part (the HID descriptor) is solved — recipe
  in [DESIGN.md](./DESIGN.md) §3.
- **Operational profile measured:** ~10 ms CPU/sample, ~0.6 s capture window, luma stable on the
  first frame under manual exposure; target cadence 5–15 s; open/close per sample.
- **Repo structure + dev gates in place:** `python/` (uv, MVP) + `rust/` (cargo, deployment stub)
  split; `./dev.sh` runs ruff + mypy + pytest + rustfmt + clippy, enforced by a `hooks/pre-commit`
  gate (pure bash — the host has no `make`).

- **MVP daemon built & validated end-to-end (2026-06-25).** `python/src/iris/` (`brightness`,
  `camera`, `virtual_als`, `daemon`) holds the webcam streaming and feeds real camera-derived lux to
  the virtual ALS; `monitor-sensor` reports live ambient lux (~46–56 in the dim test room) through
  iio-sensor-proxy. Power-tuned for continuous streaming: smallest size 320×180 + manual exposure +
  auto-WB off. (fps is firmware-locked at 30, so the continuous-stream draw is at its floor; the big
  lever — duty-cycling — is deliberately parked for this MVP: steady LED, camera held open.)

## ⏳ Next

1. **Live GNOME brightness test — the one end-to-end step left:** enable GNOME "Automatic Brightness"
   and confirm the *backlight* actually tracks the camera (we've proven up to SensorProxy, not the
   brightness response itself).
2. **luma → lux calibration:** tune the mapping (today a placeholder linear 0..1000 lux) so
   gsd-power's curve feels natural; address the screen-feedback loop.
3. **Deployment:** a udev rule for `/dev/uhid` so the daemon runs as a `systemd --user` service
   (it currently needs sudo), plus the unit bound to the graphical session.
4. **Power: re-measure rigorously.** A first battery-gauge pass put the webcam at **~1 W** (very
   rough — see Open questions). Redo properly: many interleaved on/off cycles on a *genuinely idle*
   system, averaged to statistical significance — or an external USB power meter — before trusting it.
5. **Revisit the parked trade-offs** once the above works: open/close vs continuous (LED blink vs
   steady), camera contention / `EBUSY` back-off, adaptive cadence.

## ❓ Open questions / considerations

- **Privacy-LED blinking (parked).** Open/close blinks the camera LED every few seconds; some find
  that worse than a steady light. Adaptive cadence reduces frequency; no clean fix exists. Revisit
  once the mechanics work.
- **Power draw of continuous streaming — _preliminary, low confidence_ (2026-06-25).** One
  battery-gauge session (`BAT0 current×voltage` while discharging, `scripts/measure_power.py`,
  ~25 s averages) put the webcam at **~1 W** (~0.7–1.3 W depending on drift-correction), ≈3–5% of
  the ~24 W idle draw. **Treat as order-of-magnitude only** — the totality of observations was
  messy: the off-baseline drifted ~1.1 W between two reads; a repeat was contaminated (mean 38 W,
  a 97 W spike) and the baseline later climbed to ~30 W ±3 W — i.e. the desktop was *not* quiet
  (background / dGPU activity swamps a sub-watt signal). No sensor instruments the camera's USB
  rail directly: RAPL/turbostat see only the CPU package (the streaming host-side cost was *below*
  the ±0.6 W package noise floor), and only total-system power (battery, discharging) captures the
  camera — noisily. **To trust a figure:** many interleaved on/off cycles on a genuinely idle
  system for statistical significance, or an external USB power meter. Matters mainly for the
  duty-cycling vs continuous trade-off (the ~1 W is the most duty-cycling could reclaim).
- **luma → lux calibration.** What mapping makes gsd-power's curve feel natural? Relative/adaptive,
  anchored at dark/bright; absolute lux not required. Needs a tuning session.
- **Screen-feedback loop.** The camera partly sees the screen lighting the user → risk of runaway.
  gsd's ~10 s EMA dampens it; may still need to subtract a model of our own current brightness.
- **GET_REPORT poll vs. buffered/triggered path.** iio-sensor-proxy currently reads via a
  synchronous input-report poll (we serve current lux there). Confirm this is robust, or whether to
  also feed the trigger/buffer.
- **Session lifecycle.** Behaviour at the login screen, on lock, and with no active session (camera
  uaccess is session-scoped; the daemon should pause cleanly).
- **Final language.** Python is validated and fine on footprint (~30–50 MB). Revisit Rust (~5 MB)
  only if a resident daemon's footprint becomes a concern.
- **Daylight dynamic range.** Confirm the lux scale spans real bright conditions (pending a daylight test).
- **Multi-monitor / external displays.** Out of scope for now (internal panel only).

## How to reproduce the validation

```sh
cd python && uv sync
uv run python scripts/uhid_als_spike.py validate         # descriptor parse, no root
sudo .venv/bin/python scripts/uhid_als_spike.py 45 &      # create virtual ALS, push a lux ramp
timeout -s KILL 16 monitor-sensor                         # should print the ramp as "Light changed"
```
