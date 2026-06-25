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

- **Live GNOME brightness test PASSED (2026-06-25) — the full loop works.** With the daemon
  running, covering the camera (lux→7) dropped the panel backlight 240→145/400 (60%→36%); uncovering
  / bright light (lux→19–188) raised it back to ~240. So GNOME's *native* auto-brightness moves the
  real `intel_backlight` in response to the webcam: **camera → virtual ALS → iio-sensor-proxy →
  gsd-power → backlight**, end to end, prompt (~2 s), no feedback-loop runaway. (Needed a nudge —
  toggling `ambient-enabled` — to make gsd-power claim the freshly-appeared sensor.) Caveat: the
  swing was only ~36–60% (placeholder lux mapping squeezed through gsd's curve) → calibration next.

- **Whole downstream stack decoded from source (2026-06-25)** → [BRIGHTNESS-MATH.md](./BRIGHTNESS-MATH.md).
  gsd-power: self-normalizing **linear** law, `brightness ∝ L/Lanchor` capped at `L=1.5·Lanchor`,
  EMA **τ ≈ 1.6 s** (not ~10 s), re-anchors on startup / idle-dim / any user brightness change.
  gnome-shell: auto target `T` and the manual slider `S` **combine** as `clamp(T + S − 0.5, 0, 1)`,
  written **linearly** to raw backlight units. This nails both live-test surprises: the slider doesn't
  move because it shows the *bias* `S` (auto slides the panel around it); the range "shifted" on
  key-press because changing `S` makes gsd re-anchor. Two design facts fall out: **only L-ratios
  matter (absolute lux is meaningless)**, and **the law's tight linear cap means we must feed it a
  compressive/log-like signal**, not linear-in-luminance.

- **Pixel reduction + dynamic range probed & decided (2026-06-25)** — `scripts/probe_pixels.py`,
  results in [FINDINGS.md](./FINDINGS.md). Reduction = **`logmean`** (log-average / geometric-mean
  luma): robust to bright spots and already the log-compressed signal gsd wants. Exposure brackets
  (dim room well-exposed at exp 5000–10000; flashlight at 500–1000, clips ≥2000) prove **a single
  fixed exposure can't span dark→bright → auto-ranging is required.**

- **Camera sensitivity / operating point + comfort anchor (2026-06-25).** A *typical lights-on evening*
  needs ~**max exposure (10000) at gain 64** (`logmean ≈ −1.5`); the camera is insensitive → indoor
  lives near the top of the exposure range, ranging headroom is almost all at the **bright** end, and
  below a lit room we floor fast (lights-off → minimum, fine). Running near max exposure (~1 fps, 1 s
  integration) **averages out mains flicker** → steady-scene jitter is tiny (±0.02 `logmean`). The old
  default exp 1500 floors a normal room → bias the default high, range *down*. User comfort anchor:
  lit-evening ↔ **~16% (raw 63/400)** (set via the brightness keys — which notably **bypass mutter**:
  they moved sysfs but not mutter's `Backlight` property; see FINDINGS).

- **Brightness-ownership dig + clean-release rehearsal (2026-06-25).** Reconfirmed Tier-1 hard: in
  GNOME 50 **mutter owns the panel** via `org.gnome.Mutter.DisplayConfig` (`SetBacklight`);
  `/sys/class/backlight` is decoupled and external writes don't stick. Found the **auto-mode lifecycle
  footgun** — a sensor vanishing (vs a clean `ReleaseLight`) leaves gnome-shell stuck in auto mode,
  bricking manual brightness. Rehearsed the fix (claim a live sensor → disable ambient → clean release
  → `-1`). Detail in [BRIGHTNESS-MATH.md](./BRIGHTNESS-MATH.md) §5.

## ⏳ Next

1. **Camera output pipeline (priority; reduction + ranging decided, build parked by user).** Decided:
   report `logmean`; drop the `MAX_LUX` "lux" framing (ratios only); auto-range exposure(+gain) and
   fold the settings back in for a wide-range EV proxy `∝ γ·logmean − log(exposure·gain)`. **Parked
   until ready:** building the auto-ranging loop. **Still pending data:** a daytime/window bright
   anchor (night-time flashlight proved the mechanism, not a calibrated top), and a
   dim→normal→bright `watch` walk to confirm the EV proxy is monotonic across conditions.
2. **Deployment:** a udev rule for `/dev/uhid` so the daemon runs as a `systemd --user` service
   (currently needs sudo), plus the unit bound to the graphical session — and decide how to get
   gsd-power to claim the sensor at startup (the `ambient-enabled` toggle, or appearing before login).
3. **Power: re-measure rigorously** (see Open questions): interleaved on/off cycles on a genuinely
   idle system, or an external USB meter.
4. **Revisit the parked trade-offs:** open/close vs continuous (LED blink vs steady), camera
   contention / `EBUSY` back-off, adaptive cadence.

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
- **luma → reported-value mapping.** Compressive/log-like (see BRIGHTNESS-MATH §3); relative/adaptive,
  anchored at dark/bright; absolute lux not required. Needs a tuning session.
- **Screen-feedback loop.** The camera partly sees the screen lighting the user → risk of runaway.
  gsd's EMA (τ ≈ **1.6 s**, milder than the ~10 s we assumed) only partly damps it; likely need to
  subtract a model of our own backlight's contribution to luma (we can read current backlight from sysfs).
- **GET_REPORT poll vs. buffered/triggered path.** iio-sensor-proxy currently reads via a
  synchronous input-report poll (we serve current lux there). Confirm this is robust, or whether to
  also feed the trigger/buffer.
- **Session lifecycle.** Behaviour at the login screen, on lock, and with no active session (camera
  uaccess is session-scoped; the daemon should pause cleanly).
- **Clean-release lifecycle (deployment-critical).** iris must cause gsd to `ReleaseLight` *before* its
  uhid sensor disappears, or stopping mid-session bricks manual brightness until a shell restart
  (BRIGHTNESS-MATH §5; rehearsed 2026-06-25). Logout is safe. Design the unit's stop path; maybe report
  upstream that gsd should send `-1` on sensor-gone, not only on `ReleaseLight`.
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
