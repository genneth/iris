# iris — status

_Updated 2026-07-03. MVP validated end-to-end; sink decision reopened (see Next №1)._

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

- **Design review + live brightness-stack session (2026-07-03).** Full-project review plus a live
  incident investigation. Key outcomes (detail: FINDINGS 2026-07-03, BRIGHTNESS-MATH §6):
  - **Direct sink validated ("Tier-1b"):** `org.gnome.Shell.Brightness.SetAutoBrightnessTarget` is a
    *published, sender-unrestricted* session-bus API — unprivileged calls drove the real panel and
    `-1` cleanly restored manual mode, repeatedly. Candidate replacement for the whole
    uhid→kernel→iio-sensor-proxy→gsd chain: no root/udev, iris owns curve+smoothing, clean `-1`
    shutdown, hotkeys/slider unaffected. Sink decision reopened → Next №1.
  - **Stuck-auto-mode footgun defused:** `-1` can be sent by *any* session process, so a crashed
    iris no longer bricks brightness until a shell restart — one `gdbus` call recovers (either sink).
  - **fn-key mechanism resolved:** the kernel ACPI handler (`video.brightness_switch_enabled=Y`)
    steps the backlight directly (~10% steps); GNOME *observes* the change (`syncWithBacklight`):
    slider `S` tracks it and `BrightnessChanged` fires → hotkeys re-anchor and coexist with *any*
    auto-brightness source. The 2026-06-25 "keys bypass mutter" was right about writes, incomplete
    about observation.
  - **Two mutter hotplug bugs found** (lid close→open): (1) backlight re-bound to the dGPU's
    phantom `nvidia_0` — all GNOME brightness writes void until *another* lid cycle (DPMS/udev pokes
    don't fix; tell: `Backlight` property `max 100` not `400`); (2) the D-Bus `Backlight` property
    goes stale (hardware moves, property frozen) → read sysfs, never the property. Upstream: (1) is
    known — mutter #4432, draft fix !4746 pending; (2) appears unreported (links in FINDINGS).
  - **Calibration correction:** publish a **power law of luminance** (`L ∝ lum^β`), never a
    log-domain EV (negative → u32-clamped to 0 → gsd's `L>0` gate discards it; ratios of logs aren't
    shift-invariant). BRIGHTNESS-MATH §3.
  - **Phone-ALS plan adopted:** use the phone's calibrated lux sensor for the missing bright anchor
    + monotonicity walk (Termux `termux-sensor`, no app needed); optional later: a BTHome-over-BLE
    broadcaster as an opportunistic live sensor (laptop BT adapter confirmed ready).
  - **MVP code debts flagged:** `daemon.py`'s `finally: als.destroy()` triggers the §5 footgun today
    (now recoverable via `-1`); continuous streaming **blocks video calls** (V4L2 exclusivity) —
    duty-cycling/`EBUSY` back-off is functional, not just power; uhid `dispatch()` is frame-paced and
    breaks at 1 fps exposures; stale defaults/comments (exp 1500, "~10 s EMA" in daemon.py).

## ⏳ Next

1. **Decide the sink (new, 2026-07-03):** uhid virtual ALS (current, validated) **vs** direct
   `SetAutoBrightnessTarget` (validated 2026-07-03 — no root/udev, iris owns the curve+smoothing
   and escapes gsd's hardcoded 1.5× law, `-1` on stop, hotkeys unaffected; costs: no native Settings
   toggle, coupling to a newer shell API). The review leans direct — the prototype is small since
   sensing is unchanged. DESIGN §2 update, BRIGHTNESS-MATH §6.
2. **Camera output pipeline (reduction + ranging decided, build parked by user).** Decided:
   reduce with `logmean`; drop the `MAX_LUX` "lux" framing (ratios only); auto-range exposure(+gain)
   into an EV `= γ·logmean − log(exposure·gain)` — but **publish `L ∝ exp(β·EV)`** (a power law of
   luminance), never the raw log-domain EV (BRIGHTNESS-MATH §3; applies to the uhid sink — the
   direct sink owns the curve and can use EV natively). **Still pending data:** a daytime/window
   bright anchor and a dim→normal→bright monotonicity walk — use the **phone ALS** as ground truth
   for both (Termux `termux-sensor -s light`; see Open questions).
3. **Deployment** (shape depends on №1): uhid path needs the `/dev/uhid` udev rule (mind the
   keystroke-injection security note, DESIGN §7) + the gsd claim nudge at startup; the direct path
   is a plain `systemd --user` unit with `ExecStopPost` sending `-1`.
4. **Power: re-measure rigorously** (see Open questions): interleaved on/off cycles on a genuinely
   idle system, or an external USB meter.
5. **Revisit the parked trade-offs:** open/close vs continuous (LED blink vs steady) — noting
   continuous streaming **blocks video calls** (V4L2 streaming is exclusive), so duty-cycling +
   `EBUSY` back-off is a functional requirement, not just power; adaptive cadence.

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
- **Clean-release lifecycle (defused 2026-07-03, keep the polite path).** iris should still cause gsd
  to `ReleaseLight` *before* its uhid sensor disappears (BRIGHTNESS-MATH §5) — but the failure mode is
  no longer catastrophic: **any session process can send `SetAutoBrightnessTarget(-1)` directly** to
  clear stuck auto mode (validated live), so a crash costs one `gdbus` call / `ExecStopPost=`, not a
  shell restart. Upstream nicety still stands (gsd should send `-1` on sensor-gone).
- **Mutter backlight wedging after lid events (found 2026-07-03).** A lid cycle can re-bind mutter's
  backlight to the phantom `nvidia_0` device, silently killing all GNOME brightness writes (and hence
  auto-brightness, either sink) until another lid cycle. iris should watchdog "my target changed but
  `intel_backlight` sysfs didn't" (or the `Backlight`-property `max 100` tell) and warn the user.
  Also: **never read the D-Bus `Backlight` property for current state** (it goes stale) — read sysfs.
- **Phone ALS (adopted for calibration; optional live source).** The phone's calibrated lux sensor
  supplies the missing bright anchor + monotonicity ground truth via Termux (`termux-sensor -s light`,
  streamed over ssh/HTTP). Possible follow-on: a small Android app broadcasting BTHome-v2 BLE adverts
  (service data `0xFCD2`, illuminance object `0x05`), read host-side via BlueZ/bleak — but only as an
  *opportunistic* boost (pocket/face-down ⇒ lux 0): gate on freshness/plausibility, webcam remains
  the foundation. (Assumes Android; iOS exposes no public ALS API.) → **Building: Pupil** (spec docs/superpowers/specs/2026-07-03-pupil-ble-als-design.md, plan docs/superpowers/plans/2026-07-03-pupil-ble-als.md).
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
