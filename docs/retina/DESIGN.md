# iris — design (validated architecture)

> Use this laptop's RGB webcam as an **ambient-light sensor** (it has no real ALS) so GNOME can
> auto-adjust screen brightness. iris is **only a sensor**: it presents the camera as a virtual
> HID ambient-light sensor, and GNOME's existing stack does the brightness curve, smoothing, the
> Settings/quick-settings UI, and the hardware write. **Brightness only** (no colour temperature).

**Status (2026-06-25):** **MVP built; full loop validated** (§4) — the daemon (`python/src/iris/`)
feeds real camera lux to the virtual ALS, and GNOME's native auto-brightness moves the real backlight
in response (covering the lens dims the panel; bright light raises it). Runs via sudo pending a
`/dev/uhid` udev rule; lux→brightness range needs tuning (see STATUS). This supersedes the earlier
"iris writes brightness" (Tier-2) approach — see §2. **(2026-07-03: sink decision reopened — a
simpler direct `SetAutoBrightnessTarget` variant is now validated; see the §2 update.)**
Provenance: original brief [DESIGN-v1.md](./DESIGN-v1.md); empirical results [FINDINGS.md](./FINDINGS.md);
progress + open questions [STATUS.md](../../STATUS.md).

## 1. The shape

```
webcam ─▶ [iris: frame → lux] ─▶ virtual HID ALS (/dev/uhid)
                                   └▶ kernel hid-sensor-hub + hid-sensor-als
                                       └▶ /sys/bus/iio illuminance device
                                           └▶ iio-sensor-proxy ─▶ net.hadess.SensorProxy
                                               └▶ gsd-power (normalize + ~1.6s EMA) ─▶ gnome-shell ─▶ backlight
```

iris's entire job is the **first arrow**: turn a downscaled camera frame into a believable lux
value and publish it as a standard ambient-light sensor. Everything after the virtual sensor is
**stock kernel + GNOME**. The payoff: brightness, smoothing, the Settings slider, the brightness
hotkeys, and idle-dim all stay native and coherent, because iris is *not* a second writer of
brightness — it never fights GNOME.

## 2. Why this shape (and what we rejected)

We started with **Tier 2** — iris computes brightness and writes it (via mutter `SetBacklight` or
logind `SetBrightness`). Phase-1 testing killed it: **GNOME does not observe external brightness
writes.** Setting the backlight underneath GNOME leaves its quick-settings slider stale and makes
the brightness hotkeys jump to a cached value — iris and GNOME end up as two uncoordinated writers
(see STATUS / git history for the experiments). There is no user-space API to write brightness
*through* GNOME's own state (gsd-power's D-Bus object is keyboard-backlight only).

So we flipped the relationship to **Tier 1**: iris is the *sensor*; GNOME owns brightness. The one
real cost is a small system-level enabler (a udev rule for `/dev/uhid`, §7) — acceptable, and the
result is zero-fighting native integration. (Rejected Tier-1 variants: a custom kernel IIO module —
fragile on an immutable host; impersonating `net.hadess.SensorProxy` — simpler code but makes iris
a privileged system daemon. The uhid virtual HID ALS keeps iris a user-space service and the system
stock; only a udev rule is added.)

**Update (2026-07-03) — a second Tier-1 variant is validated and the sink decision is reopened.**
`org.gnome.Shell.Brightness.SetAutoBrightnessTarget` — the very call gsd-power makes — is a
*published, sender-unrestricted* session-bus API, driven live from an unprivileged process
(FINDINGS 2026-07-03). Calling it directly ("**Tier 1b**") would replace the entire
uhid→kernel→iio-sensor-proxy→gsd chain with one D-Bus call: no root/udev, iris owns the
curve+smoothing (escaping gsd's hardcoded 1.5× linear law and re-anchor quirks, BRIGHTNESS-MATH §3),
shutdown/crash recovery is `SetAutoBrightnessTarget(-1)`, and the hotkeys/slider are untouched —
they integrate inside gnome-shell, downstream of the target, identically for both variants. Costs:
no native Settings "Automatic Brightness" toggle, and coupling to a newer shell API (function
breaks loudly on API change, vs. the uhid path's behaviour drifting silently with gsd internals).
It is still not Tier 2 — there remains exactly one brightness writer, the shell. Decision:
STATUS Next №1; arithmetic: BRIGHTNESS-MATH §6.

**Decided (2026-07-04): Tier-1b is the sink, and it's built.** For the phone-ALS mode ("Reflex" —
Pupil's BLE lux replacing the webcam as the light source) iris drives
`SetAutoBrightnessTarget`/`-1` directly from `python/src/iris/{shell_brightness,controller,
daemon}.py`; the uhid virtual-ALS path (§3 below) is retained for the webcam sensor but not wired
into the shipping entry point (`python -m iris`, STATUS "Sink decided and Reflex built"). Nothing
in §3's descriptor recipe changes — it's parked, not obsoleted.

## 3. The virtual-ALS recipe (validated — the hard-won part)

Present a HID ambient-light sensor on `/dev/uhid`; the kernel's stock `hid-sensor-hub` +
`hid-sensor-als` turn it into a real `/sys/bus/iio` illuminance device. Reference implementation:
[`scripts/uhid_als_spike.py`](../../python/scripts/retina/uhid_als_spike.py). The descriptor details that actually
matter (each cost an iteration to find):

- **Single `Collection(Physical)` carrying the ALS usage** (`0x200041`). Wrapping it in an
  Application collection that *also* had the ALS usage made hid-sensor-hub create two cells with
  split collection-index ranges → neither matched the fields → probe failed.
- **A Report ID is mandatory** (Windows tolerates its absence; Linux hid-sensor-hub does not).
- **A Power State feature property (`0x200319`) is mandatory** — `hid_sensor_parse_common_attributes`
  does a `get_feature` on it and returns `-EINVAL` if it's missing (the `0xffffffff` error). Report
  Interval and Reporting State are also included (the latter is how reporting gets enabled).
- **`get_report` must serve the right report by type:** on an *input*-type poll, return the input
  report `[report_id, state, event, illuminance_u32]` with the **current lux**; on a *feature*-type
  get, return the feature report `[report_id, interval_u32, reporting_state, power_state]`. iio-sensor-proxy
  reads the value via a synchronous input-report `GET_REPORT` poll, so getting this wrong yields a
  garbage value (we briefly saw lux = `16842752` = our feature bytes mis-parsed).
- Field matching in hid-sensor-hub requires `field->physical|application == sensor_usage` **and**
  `field->logical|usage[0] == attr_usage` **and** `usage[0].collection_index ∈ [start,end)` — the
  single-Physical-collection structure is what makes all three line up.

## 4. Validation (what we proved)

With the spike running and pushing a lux ramp, `monitor-sensor` reports it verbatim through the
real stack:

```
+++ iio-sensor-proxy appeared
=== Has ambient light sensor (unit: lux)
    Light changed: 750 → 850 → 950 → … → 2250 (lux)     # exactly our pushed ramp
```

i.e. **webcam-fed lux reaches `net.hadess.SensorProxy`** — the same source `gsd-power` consumes for
"Automatic Brightness". Stock kernel modules, no kernel module of our own, no bus impersonation.

## 5. Sensing: camera → lux

The signal (from FINDINGS): **mean luma under *manual* exposure** (the camera doesn't expose live
auto-exposure metadata; auto-exposure normalises the scene away). Per sample: lock AE→manual at a
tuned exposure/gain, grab one low-res YUYV frame, reduce to brightness. Crib clight's robust
estimator (40-bin histogram + interquartile-range outlier rejection on the Y plane) rather than a
naive mean; subtract the studio-range black level (Y=16). Then map luma→**a plausible lux scale**.

Crucially, **gsd-power + gnome-shell own the response curve and smoothing**, so iris does *not* build
a brightness curve, dead-band, or transition logic — it only needs a sane, monotonic mapping. The
downstream math is now fully decoded — see **[BRIGHTNESS-MATH.md](../iris/BRIGHTNESS-MATH.md)**. The two
load-bearing facts: gsd's law is **self-normalizing and linear** (`brightness ∝ L/Lanchor`, capped at
`L = 1.5·Lanchor`), so **only ratios matter — absolute "lux" is meaningless**; and because it is
linear with a tight cap, a *linear-in-luminance* signal gives a uselessly narrow window — iris should
report a **compressive (log-like)** function of scene luminance. Calibration is therefore
relative/adaptive (no absolute lux), anchored at the few conditions one can create (covered lens =
dark; near a window = bright). EMA τ ≈ **1.6 s** (not the ~10 s assumed earlier), so the
screen-feedback loop (camera sees the screen lighting the user) is only mildly damped — a real
consideration (§STATUS, BRIGHTNESS-MATH §3).

## 6. Operational profile (measured)

- **Sample every 5–15 s** (adaptive: faster when light is moving, slower when stable). gsd's EMA is
  τ ≈ 1.6 s — its `α = 1/(1 + τ/Δt)` adapts to *our* update interval, so a slow cadence still tracks
  (each reading just carries more weight); faster sampling mainly sharpens transient response, traded
  against camera-on time/power. Revisit cadence alongside the feedback-loop work.
- **Open/close the camera per sample**, not held open. One sample = open → set manual exposure →
  grab 1 frame → close ≈ **0.6 s** (luma is stable on frame 0 under manual exposure; ~0.55 s of that
  is UVC stream-startup) at **~10 ms CPU**. At a 5–15 s cadence the camera is live ~4–12% of the time.
- **Yield to real camera users:** on `EBUSY` (a video call holds the camera), skip and back off
  (exponential, up to ~60 s); iris goes silent and gsd holds the last brightness.
- **Power:** dominated by camera-on time; open/close keeps the average to ~0.05–0.2 W. Holding the
  camera open continuously would be ~0.5–1.5 W sustained — avoided.
- **Hardware longevity:** a non-issue (CMOS sensor, no moving parts; the privacy LED is an LED).
- **Privacy LED:** open/close makes it blink every few seconds — the one genuine UX wart, parked
  for now (§STATUS). Holding it open for a steady LED reintroduces the power/contention costs.

## 7. Deployment

- **A udev rule granting `/dev/uhid` access** (system, one-time — `almon` territory) so the daemon
  runs as a **`systemd --user` service** (no root), tied to the graphical session (camera access is
  session-scoped via the uaccess ACL). *(Security note, 2026-07-03: `/dev/uhid` lets any session
  process create arbitrary HID devices — including keyboards, i.e. session-wide keystroke injection
  bypassing Wayland input isolation. Acceptable on this box; if ever shipping, split privilege — a
  tiny system service owns uhid and takes lux over a socket. The Tier-1b sink (§2) needs none of this.)*
- `iio-sensor-proxy` is already installed and needs **no changes** — it auto-detects the new IIO
  device. The user enables GNOME's "Automatic Brightness" toggle once (it appears when a sensor exists).
- **Clean-release lifecycle (defused 2026-07-03; keep the polite path).** gsd only leaves auto mode on
  a `ReleaseLight` issued *while the sensor is still present* (see
  [BRIGHTNESS-MATH.md](../iris/BRIGHTNESS-MATH.md) §5) — so iris's shutdown should still trigger that release
  **before** the uhid device vanishes. But the failure is no longer catastrophic: any session process
  can send `SetAutoBrightnessTarget(-1)` directly to clear stuck auto mode (validated live;
  BRIGHTNESS-MATH §6) — put it in the unit's `ExecStopPost=`. Logout is safe (session-inactive
  auto-releases). (Upstream nicety still worth reporting: gsd ought to send `-1` on sensor-gone, not
  only on `ReleaseLight`.)
- **Language: Python** for now (validated; `linuxpy` + `hid-tools`, pure wheels, runs entirely on
  the host — no toolbox/gcc needed). Resident footprint ~30–50 MB is fine here; revisit Rust (~5 MB)
  only if it ever matters.

## 8. Versus clight (prior art)

clight solves webcam→backlight too, but by **writing brightness directly** from a root system
daemon (`clightd` → sysfs/DDC). iris instead **only senses** and lets GNOME drive — no root daemon,
native UI/hotkey coherence, and it would default to the *wrong* camera on this dual-sensor laptop
(clight prefers the higher `/dev/videoN`, i.e. the IR cam). We crib clight's brightness-*estimate*
algorithm (§5); we do not adopt its architecture. (Full comparison in git history / earlier revisions.)

Also **wluma** (https://github.com/maximbaz/wluma, Wayland): uses the webcam as an ALS when no real
sensor exists, and — most relevantly — handles the screen-feedback loop by **capturing the screen
contents** and learning from the user's manual adjustments, rather than modelling backlight alone.
Its architecture is Tier-2 (writes the backlight itself; would fight GNOME, and its capture path is
wlroots-only, dead on Mutter) — rejected for the same reasons as clight — but its feedback-loop
treatment is the strongest prior art for the screen-feedback open question (STATUS).

## 9. References

- HID Sensors usage tables (page 0x20) — `hidtools/data/0020_sensor.hut`; HUTRR39b.
- Linux HID-sensor framework: `Documentation/hid/hid-sensor.rst`; `drivers/hid/hid-sensor-hub.c`,
  `drivers/iio/common/hid-sensors/hid-sensor-attributes.c`.
- `iio-sensor-proxy` (`net.hadess.SensorProxy`): https://gitlab.freedesktop.org/hadess/iio-sensor-proxy
- `hid-tools` (uhid plumbing used by the spike): https://gitlab.freedesktop.org/libevdev/hid-tools
- Downstream brightness arithmetic (gsd-power + gnome-shell), decoded from source: [BRIGHTNESS-MATH.md](../iris/BRIGHTNESS-MATH.md).
  gsd-power `plugins/power/gsd-power-manager.c` (`iio_proxy_changed`); gnome-shell `js/misc/brightnessManager.js` (`_sync`).
