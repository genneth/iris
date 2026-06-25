# iris — v1 experimental findings

> Empirical results from the v1 sensing experiments (2026-06-24), run host-side
> with `uv` + `linuxpy` (pure-Python V4L2) + numpy. Reproduce via `scripts/`.
>
> **Note:** the *sensing* findings below still hold. The *sink* discussion (how to apply the
> result) is superseded — the design later pivoted from writing brightness to presenting a
> virtual ambient-light sensor and letting GNOME drive. See [DESIGN.md](./DESIGN.md) / [STATUS.md](./STATUS.md).

## TL;DR

1. The design's **preferred "exposure-metadata" strategy is dead on this hardware** —
   the camera firmware does not expose live auto-chosen exposure/gain.
   → **Primary signal is frame-mean luma under *manual* exposure** instead.
2. The IR camera runs a **Windows-Hello flood-emitter strobe** (illuminated/ambient
   frame pairs at 15 fps). Ambient-IR = keep the emitter-OFF frames. Night-vision
   falls out for free.
3. The **RGB sensor robustly rejects IR** (a flood that saturates the IR sensor
   shifts RGB luma by −0.04). So RGB luma is a clean *visible*-light signal.
4. Consequence (see "Scope"): brightness-only control needs **only RGB luma** —
   no IR sensor, no colour-temperature path.

## Environment / tooling

- **Camera access**: the `uaccess` ACL grants `user:genneth:rw-` on `/dev/video0`
  (RGB) and `/dev/video2` (IR) — no root, no `video` group. Works host-side and
  inside the `dev` toolbox (ACL preserved; root/video map to `nobody`).
- **Stay on the host for v1.** Pure-Python `linuxpy` + numpy, no system C deps.
- **Rust (v2)**: host has **no `cc`/gcc** and **no gnu CRT objects**, so gnu/proc-macro
  artifacts can't link host-side. `rust-lld` is the default linker (Rust 1.90+),
  and a *leaf* static-musl binary links host-side, but anything with proc-macros
  (zbus/serde) must build in the `dev` toolbox (gcc 16.1.1). Deploy target is a
  static-musl binary as a `systemd --user` service bound to `graphical-session.target`.
  Shared `$HOME` means switching host↔toolbox costs nothing.
- Components installed: `rust-analyzer`, `clippy`, `rustfmt`, `rust-src`.

## Capture

| Sensor | Node | Formats | Negotiated (req. 160×120) | Notes |
|---|---|---|---|---|
| RGB | `/dev/video0` | YUYV 4:2:2, MJPEG | **320×180** YUYV | Y at even bytes; **studio range, black=16** |
| IR  | `/dev/video2` | GREY 8-bit, MJPEG | **640×360** GREY, **15 fps** | single plane = intensity |

- No JPEG decode needed (YUYV + GREY are raw). Camera snaps to nearest supported size.
- **Very dark static scenes → byte-identical frames** (camera temporal denoising):
  mean luma reads exactly constant, zero variance. Mean-luma is a poor metric in
  the dark; prefer spatial/averaged methods or ensure scene variation.

## Controls (`scripts/controls.py`)

- **RGB is control-rich**: Brightness, Gain (0–128), Auto Exposure (menu; 1=manual,
  3=aperture-priority), Exposure Time Absolute (50–10000, inactive while AE auto),
  Auto White Balance (on by default), White Balance Temperature (inactive while auto-WB).
- **IR is control-bare**: only ROI, read-only Privacy, and class nodes. **No exposure/
  gain/brightness** → no metadata strategy possible for IR; IR illuminance = frame-mean only.
- **linuxpy gotcha**: its high-level `Controls` dict aborts the whole enumeration on
  an unmodeled control type (`263` = ROI rectangle) — one bad control hides all of
  them. Workaround: low-level `iter_read_controls` / `get_control(fd,id)` /
  `set_control(fd,id,val)`. The Rust `v4l` crate may have the same enum gap.

## Sensing strategy (the key pivot)

- **Exposure-metadata strategy — UNAVAILABLE.** Streaming in auto (aperture-priority),
  luma swung 75→89 while `exposure_absolute`/`gain` stayed pinned at defaults (166/64).
  The firmware adjusts AE internally but never writes the live values back to V4L2.
  → DESIGN §6 strategy 2 / §8 ("likely the better signal") does not apply here.
- **Manual frame-mean — WORKS.** AE→manual makes exposure/gain settable; luma responds
  (clearly to gain, to exposure at the high end in dim light). This is the primary
  signal: lock manual exposure+gain, read mean luma (minus the 16 black level).
- AE settling after stream-on is ~3–4 s (avoided entirely by using manual mode).

## IR emitter strobe (`scripts/ir_strobe.py`, `ir_video.py`)

- The IR camera streams **illuminated/ambient frame pairs at 15 fps** — this is
  documented Windows-Hello behaviour, not a quirk (confirmed vs Microsoft's driver spec).
- Even frames: **emitter ON** — saturated central flood blob (max 255), useless for ambient.
- Odd frames: **emitter OFF** — pure ambient IR. In this LED-lit room that is **≈0**
  (mean 0.00), consistent with LEDs being IR-poor (DESIGN §7).
- Real strobe, not a buffering artifact: monotonic sequence numbers + steady 67 ms
  spacing on both phases.
- Emitter fires **only while the IR camera is streaming** (no warm-up; on from frame 0).
  Brief sampling bursts → emitter only blips.
- **Answers §10-Q4**: read ambient IR by discarding emitter-ON frames (classify by
  max/saturation). No emitter control exists to disable it (would need fragile vendor
  UVC extension-unit hacks à la `linux-enable-ir-emitter`); not needed.
- Bonus: the emitter-ON frames are **active-IR night vision** (640×360, ~7.5 fps).

## RGB IR-rejection (`scripts/ir_reject_spatial.py`)

- Test: average RGB frames with emitter OFF vs ON, subtract (constant room light cancels).
- Result: mean Δ = **−0.04 luma**; difference image is near-black with only thin
  motion-edge speckle, **no central face-blob**. Simultaneously the RGB frame shows the
  face as a dark silhouette while the IR frame shows it floodlit.
- **Conclusion: RGB IR rejection is robust.** RGB luma is a clean *visible*-light
  illuminance proxy, uncontaminated by IR → light-type-robust brightness calibration.

## Scope decision (pending confirmation)

Brightness-only (drop colour-temperature output) is under consideration. If adopted, it
cascades further than just the output:

- Removes CCT estimation (McCamy/white-balance handling) and the entire v3 colour-temp
  Wayland-gamma sink (the hardest future piece).
- Combined with robust IR rejection, **removes the IR sensor from the core path** — the
  IR channel's job was colour/daylight discrimination, which only mattered for colour.
- Pipeline collapses to: **RGB frame → mean luma (−16) → smoothing → brightness %**.
- Trade-off to weigh: this converges toward `clight`'s feature set (the prior art the
  design set out to *exceed*), shedding the IR/colour novelty that motivated "IRis".

## Calibration (open — to design)

- No absolute lux needed; want a monotonic, comfortable luma→brightness% mapping.
- Given limited control over room light, prefer **adaptive/relative** calibration over a
  one-shot fitted curve: fix manual exposure/gain so the room range sits mid-scale, use a
  perceptual response curve + gsd-power-style EMA (~10 s), anchor with the few conditions
  one can create (covered lens / dark room = dark anchor; near a window = bright anchor).
- **Feedback-loop caveat**: a screen-facing camera measures its *own* screen light
  reflected off the user (we saw the face lit as a screen-lit silhouette). Brighter screen
  → higher measured "ambient" → risk of runaway. The brightness loop must damp this
  (heavy smoothing, dead-band, or subtract a model of our own current brightness).

## §10 research-question scorecard

| Q | Status |
|---|---|
| Q1 frame-mean tracks ambient monotonically? | Auto AE normalises (bad); **manual AE responds** — full ambient-range validation pending a light-varying session |
| Q2 exposure+gain metadata cleaner? | **No — unavailable** (firmware doesn't expose live AE) |
| Q3 RGB(±IR) separate warm/cool, sun/LED? | Moot if brightness-only; ambient IR≈0 under LED as predicted; daylight test pending |
| Q4 IR without emitter pollution? | **Yes — keep emitter-OFF frames** |
| Q5 noise/lag, sample rate + smoothing? | AE settle ~3–4 s (manual avoids); smoothing TBD (~10 s) |
| Q6 privacy-LED blink, minimise? | Emitter only while streaming; RGB privacy-LED behaviour needs user observation |
| Q7 daemon footprint? | Not yet measured; architecture decided (static-musl `systemd --user`) |
