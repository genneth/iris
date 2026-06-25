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

## ⏳ Next (build)

1. **`src/iris/` package:** `sense` (camera → lux), `device` (the virtual HID ALS, lifted from the
   spike), and a daemon loop. Promote the validated logic out of `scripts/`.
2. **Camera → lux:** manual exposure + histogram/IQR brightness (per clight); calibrate to a
   plausible, monotonic lux scale (gsd-power supplies the curve, so this need not be absolute).
3. **Daemon behaviour:** sample every 5–15 s (adaptive); open → grab 1 frame → close; `EBUSY`
   back-off so video calls win the camera.
4. **Deployment:** a udev rule for `/dev/uhid` (→ run as a user service, no root) + a `systemd --user`
   unit bound to the graphical session.
5. **Live integration test:** enable GNOME "Automatic Brightness" and confirm the backlight actually
   tracks the camera (the one end-to-end test we haven't run — we've proven up to SensorProxy, not
   the brightness response itself).

## ❓ Open questions / considerations

- **Privacy-LED blinking (parked).** Open/close blinks the camera LED every few seconds; some find
  that worse than a steady light. Adaptive cadence reduces frequency; no clean fix exists. Revisit
  once the mechanics work.
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
