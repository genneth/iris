# iris — design & launch-pad brief

> Use the camera on this laptop as an ambient-light (and colour) sensor, since it
> has no real ambient light sensor, and drive screen brightness (and eventually
> colour temperature) from it.
>
> The name: **IR**is — contains **IR** (the infrared sensor we exploit), an *iris*
> is a camera aperture, and the biological iris is the body's own
> ambient-light-driven brightness controller. Three puns, one concept.

**Status:** design approved 2026-06-24, not yet implemented. This document is the
handoff brief for the agent that picks it up — it embeds the hardware survey, the
verified GNOME integration points, V4L2 specifics, candidate libraries, and
copy-ready snippets so you start with code to crib, not a blank page.

---

## 1. Motivation

This is a Razer Blade 16 (RZ09-0510). It has **no ambient light sensor** — verified:

- `/sys/bus/iio` does not exist (no IIO subsystem instance); no `ACPI0008` ACPI ALS device.
- No `iio` / `acpi_als` / `hid_sensor_hub` kernel modules loaded; nothing in `dmesg`.
- `iio-sensor-proxy` (3.8) *is* installed (GNOME pulls it in) but has no devices to expose.
- `monitor-sensor` just hangs "Waiting for iio-sensor-proxy to appear".

`clight` already solves *plain* webcam→backlight on Linux, so we are **not** rebuilding
that. The point of `iris` is the **sensing experiment**: get a better/richer signal out
of the camera than naive frame-brightness — specifically a colour-temperature estimate
and a daylight-vs-artificial-light discriminator using the **IR** sensor — and only then
wire it to the screen.

## 2. Hardware map (this machine)

| Thing | Value |
|---|---|
| Model | Razer Blade 16, `RZ09-0510` |
| Desktop | GNOME Shell 50.2, **Wayland** |
| Camera | Luxvisions Innotech UVC, USB `30c9:00cb`, **dual-sensor** |
| `/dev/video0`, `/dev/video1` | **RGB** colour camera ("Integrated Camera ... C") — capture + metadata nodes |
| `/dev/video2`, `/dev/video3` | **IR** camera ("Integrated Camera ... I"), the Windows-Hello face-unlock sensor |
| Backlights | `/sys/class/backlight/intel_backlight` and `nvidia_0` |
| ALS | none |

Camera device permissions are `crw-rw----+ root video` with an ACL (`+`) — `systemd-logind`'s
`uaccess` udev rule grants the **active session user** access, so no root and no `video`-group
membership is needed to open the camera from a normal user session.

> ⚠️ **IR illuminator caveat.** The IR camera is paired with an IR LED emitter for face
> unlock. To measure *ambient* IR the emitter must be **off** (otherwise we measure our own
> reflected light). Whether/how the emitter activates on plain capture, and how to keep it
> off, is an open research question for v1 (it may only fire under a specific capture mode).

## 3. Architecture: `sense` → `map` → `sink`

Keep the sensing core independent of the output sink. This is what lets us swap outputs and,
later, port just the hot module to Rust.

- **`sense`** — capture frames (RGB `/dev/video0`, IR `/dev/video2`) → a light/colour signal:
  relative illuminance + colour-temperature estimate + IR/visible ratio.
- **`map`** — signal → policy outputs: brightness % (and, later, a colour-temp target).
  Owns smoothing and the response curve.
- **`sink`** — pluggable output backends. None in v1 (we only log/plot). For v2+, see §5.

## 4. Integration tier: **Tier 2** (chosen)

We do the sensing and the mapping ourselves; we let **GNOME apply** the result. Rationale:

- GNOME's `gsd-power` is the only thing in the GNOME ecosystem that does ambient→brightness
  translation, and it consumes a **single lux scalar** — it cannot consume our colour signal.
  "Letting GNOME translate" (Tier 1) would throw away the whole novelty.
- Tier 1 also drags us into either a **kernel IIO module** (akmod/dkms + secure-boot signing +
  reboots on an immutable Silverblue host — fragile, `almon` territory) or a **root system
  daemon** owning `net.hadess.SensorProxy`.
- Tier 2 is **pure user-space** (fits the molly user-scoped tier: no root, no `rpm-ostree`, no
  reboots), borrows GNOME for the fiddly hardware write (it handles the dual intel+nvidia
  backlight) and the UI slider, and leaves colour temperature entirely ours to drive.

Rejected alternatives, for the record:
- **Tier 1** ("be the sensor", let GNOME translate) — discards colour, needs kernel module or root.
- **Tier 3** (write `/sys/class/backlight/...` directly, like `clight`) — Tier 2 dominates it
  (no permissions/dual-backlight pain, keeps GNOME slider in sync).

## 5. GNOME / D-Bus integration research (for v2+)

### 5a. Output sink — set brightness via GNOME (Tier 2, no root)

GNOME exposes a settable brightness on the **session** bus. Writing it makes GNOME do the
hardware write (both backlights) and updates the Settings slider. This is the v2 brightness sink.

```bash
# READ current brightness (int32 percent, -1 if unsupported)
gdbus call --session \
  --dest org.gnome.SettingsDaemon.Power \
  --object-path /org/gnome/SettingsDaemon/Power \
  --method org.freedesktop.DBus.Properties.Get \
  org.gnome.SettingsDaemon.Power.Screen Brightness

# SET brightness to 50%
gdbus call --session \
  --dest org.gnome.SettingsDaemon.Power \
  --object-path /org/gnome/SettingsDaemon/Power \
  --method org.freedesktop.DBus.Properties.Set \
  org.gnome.SettingsDaemon.Power.Screen Brightness "<int32 50>"
```

Interface `org.gnome.SettingsDaemon.Power.Screen` also has `StepUp`/`StepDown` methods.
Ensure GNOME's own auto-brightness is off (it will be — no sensor) so nothing fights us.

### 5b. Colour-temperature sink (v3)

There is **no GNOME mechanism that consumes a sensor-derived colour temperature** (Night
Light sets warmth by *time/location*, not from a sensor). So drive it ourselves on Wayland via
the `wlr-gamma-control-unstable-v1` protocol — i.e. the same path `gammastep`/`wlsunset` use.
Investigate whether GNOME's Mutter implements `wlr-gamma-control` or whether we need a
different route; this is a v3 open question.

### 5c. Tier-1 path, if ever wanted (reference only)

`gsd-power` reads ambient light **exclusively** from `net.hadess.SensorProxy` on the **system**
bus: properties `HasAmbientLight`, `LightLevel`, `LightLevelUnit`; methods `ClaimLight()` /
`ReleaseLight()`. `iio-sensor-proxy` has no userspace-feed input, so a future Tier-1 sink would
have to *impersonate* that bus name (system daemon; mask the stock proxy, which exposes nothing
here). `gsd-power` then runs its own **exponential moving average** curve (see
`plugins/power/gsd-power-manager.c`, constants `GSD_AMBIENT_TIME_CONSTANT` ≈ 10 s and
`GSD_AMBIENT_SMOOTH`) — worth mirroring its ~10 s smoothing in our own `map` so our response
feels native.

## 6. V4L2 capture notes

`v4l-utils` is **not** installed on the immutable host — enumerate controls from inside the
`dev` toolbox (`toolbox run -c dev v4l2-ctl -d /dev/video0 --list-ctrls-menus`) or via the
Python capture lib. Two capture strategies to build and compare in v1:

1. **Frame-mean luma** — grab a low-res frame, average it. Simple, but fights the camera's
   auto-exposure/auto-gain (the camera normalises brightness, hiding the very thing we measure).
2. **Exposure-metadata** — lock auto-exposure to manual (or read the AE state) and read back the
   camera's own `exposure_time_absolute` + `gain`. When the camera cranks gain/exposure up, the
   room is dark. This is near-zero cost (reading two integers) and likely the *better* signal.

Relevant controls (names vary by driver; modern UVC uses the first of each pair):
`auto_exposure` (`exposure_auto`), `exposure_time_absolute` (`exposure_absolute`), `gain`,
`brightness`, `white_balance_automatic` / `white_balance_temperature`. Request a **low
resolution** capture format (e.g. 160×120) — we want an aggregate, not detail.

## 7. Sensing / estimation math (starting points — validate empirically)

- **Relative illuminance:** from frame-mean luma and/or from `exposure × gain` (a brighter
  scene → the AE picks shorter exposure / lower gain). Calibrate to a rough lux scale against
  known conditions; absolute lux is not required for brightness control.
- **Colour temperature (CCT):** average RGB → linearise (undo sRGB gamma) → XYZ (sRGB matrix) →
  CIE xy chromaticity → **McCamy's approximation**:
  `n = (x − 0.3320) / (0.1858 − y)`,
  `CCT = 449·n³ + 3525·n² + 6823.3·n + 5520.33`.
  A crude `R/B`-ratio proxy is fine for relative warmth if McCamy proves noisy.
- **Daylight vs artificial discriminator:** ratio of **IR-channel** intensity to visible
  intensity. Sunlight is IR-rich, LEDs IR-poor, incandescent IR-rich. Combine with CCT to
  disambiguate (daylight = cool + high IR; incandescent = warm + high IR; LED = IR-poor).

## 8. Language strategy

- **v1 playground → Python.** numpy/OpenCV/matplotlib make the sensing + calibration math fast
  to iterate, and matches the house style for research projects (`monet`, `pacioli`,
  `branched-flow`). The playground is an interactive/logging tool, **not** a resident daemon, so
  its footprint is irrelevant. CPU is a non-issue regardless of language: sampling every ~2–5 s,
  a downscaled frame, a mean reduction → well under 0.1% CPU.
- **v2 daemon → Rust, as tight as possible.** The eventual always-on daemon is where footprint
  matters (Python+numpy+OpenCV idle RSS ≈ 100–200 MB; an equivalent Rust daemon ≈ 3–10 MB).
  Rust: `zbus` for the GNOME D-Bus sink, a `v4l`/`v4l2` crate for capture. The `sense`/`map`/`sink`
  split keeps this port localised — the validated Python algorithm becomes the spec for the Rust
  `sense` module.

### Candidate libraries

- **Python (v1):** capture — OpenCV (`cv2.VideoCapture`) or `linuxpy`/`v4l2py` for raw V4L2
  control access; arrays — `numpy`; plotting — `matplotlib`; D-Bus (v2 if staying Python) —
  `dasbus` or `jeepney`/`pydbus`. Package with `uv` + `pyproject.toml`.
- **Rust (v2):** `v4l` (capture), `zbus` (D-Bus), `image`/`ndarray` (frame math), `serde` (config).

## 9. v1 scope — the sensing playground

A measurement/validation tool. **No brightness control yet** — prove the signal is real, stable,
and meaningful first. Modules (still `sense`-shaped; `map`/`sink` are stubs/logging in v1):

- **`capture`** — open `/dev/video0` (RGB) and `/dev/video2` (IR); low-res frames; both capture
  strategies from §6 (frame-mean and exposure-metadata) selectable.
- **`estimate`** — relative lux, CCT, and IR/visible ratio per §7.
- **`record`** — live terminal readout; timestamped CSV/JSON log; optional matplotlib plots of
  lux & CCT over time.
- **`calibrate`** — capture under known conditions (dark room / daylight / lamp) to characterise
  the raw→meaningful mapping, plus noise and lag.

Sketch CLI: `iris-sense --device /dev/video0 --ir /dev/video2 --interval 2 --strategy metadata --log run.csv --plot`.

## 10. Research questions v1 must answer

1. Does frame-mean track ambient light **monotonically** despite auto-exposure, or must we go manual?
2. Is the **exposure+gain metadata** signal cleaner/faster/less noisy than frame-mean?
3. Can **RGB (±IR)** separate warm vs cool light, and sunlight vs LED?
4. Can the **IR channel** be read without the IR emitter polluting it? (See §2 caveat.)
5. How **noisy/laggy** is it — what sample rate + smoothing does a comfortable response need?
6. Does sampling **blink the privacy LED**, and can we minimise it?
7. **Daemon footprint estimate** — measure a minimal resident sampler to confirm the Rust-vs-Python
   call for v2.

These outputs (validated pipeline, chosen capture strategy, rough calibration, footprint) directly
seed the v2 daemon.

## 11. Privacy / UX / power notes (mostly v2 concerns)

- Opening the camera may light the **privacy LED**; users may find a constantly-lit LED alarming.
- The camera **can't be shared** while another app (video call) holds it — the daemon must detect
  contention and **back off gracefully**, not error or fight.
- Keep wakeups infrequent (sample every few seconds, not a tight loop) for battery.

## 12. Copy-ready snippets

**Minimal OpenCV capture + mean (Python, frame-mean strategy):**

```python
import cv2, numpy as np
cap = cv2.VideoCapture("/dev/video0", cv2.CAP_V4L2)
cap.set(cv2.CAP_PROP_FRAME_WIDTH, 160)
cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 120)
# To compare manual exposure: cap.set(cv2.CAP_PROP_AUTO_EXPOSURE, 1)  # 1=manual on many UVC drivers
ok, frame = cap.read()                       # BGR uint8
luma = (frame * [0.114, 0.587, 0.299]).sum(axis=2).mean()   # BGR weights -> luma
b, g, r = frame.reshape(-1, 3).mean(axis=0)  # per-channel means for CCT
cap.release()
```

> Note: OpenCV's exposure/auto-exposure property mapping is notoriously driver-specific.
> If it misbehaves, use a raw-V4L2 lib (`linuxpy`/`v4l2py`) to set controls by their real names.

**Set GNOME brightness (the v2 sink):** see §5a.

**Read raw V4L2 exposure/gain (preferred metadata strategy)** — enumerate the exact control
names first (`v4l2-ctl --list-ctrls` in the `dev` toolbox), then read `exposure_time_absolute`
and `gain`. These two integers *are* the light signal under fixed manual exposure.

## 13. References

- iio-sensor-proxy `net.hadess.SensorProxy` interface:
  https://hadess.pages.freedesktop.org/iio-sensor-proxy/gdbus-net.hadess.SensorProxy.html
- gsd-power EMA brightness algorithm (source):
  https://github.com/GNOME/gnome-settings-daemon/blob/master/plugins/power/gsd-power-manager.c
- Setting `Power.Screen.Brightness` via D-Bus (ArchWiki Backlight):
  https://wiki.archlinux.org/title/Backlight
- IR corneal glint / Purkinje imaging (background on the IR-sensor angle):
  https://pmc.ncbi.nlm.nih.gov/articles/PMC10166114/
- `clight` (prior art for webcam→backlight, the thing we deliberately go beyond):
  https://github.com/FedeDP/Clight
