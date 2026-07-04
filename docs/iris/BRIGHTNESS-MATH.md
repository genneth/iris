# iris — the downstream brightness arithmetic

> What happens to iris's reported value *after* it leaves the virtual sensor — the exact math in
> `gsd-power` and `gnome-shell` that turns a light reading into a backlight level. Read from source
> (versions installed on this box): **gnome-settings-daemon 50.1**, **gnome-shell 50.2**. This is the
> ground truth behind the calibration plan (STATUS) and explains the live-test surprises (§4).

## Notation

| symbol | meaning |
|---|---|
| `L` | the value iris publishes — the iio `LightLevel` property. gsd's own comment: *"latest results, which do not have to be Lux."* Units are irrelevant (see §3). |
| `Lanchor` | the `L` observed at the **last (re)normalization** |
| `T` | the auto-brightness target gsd sends the shell, a double in `[0,1]` |
| `S` | the user's **manual brightness slider** for a monitor, `∈ [0,1]` |
| `Bmin,Bmax,B` | the hardware backlight's min / max / current raw value |

## 1. gsd-power — reading → auto target `T`

`plugins/power/gsd-power-manager.c : iio_proxy_changed()` (line ~2945). Runs on every iio update.
Gated on: shell `HasBrightnessControl` true, gsetting `ambient-enabled` true, `HasAmbientLight` true,
and `L > 0` — otherwise it returns and does nothing.

```
# (re)normalize, only when ambient_norm_required:
Lanchor   = L
norm      = L * 1.5                       # GSD_AMBIENT_NORMALIZE_CONSTANT = 1.5
if acc <= 0:  acc = 100 / 1.5  = 66.67    # seed so the first reading -> 66.7%

# every reading:
inst      = clamp(100 * L / norm, 0, 100)        #  = clamp(66.67 * L/Lanchor, 0, 100)
alpha     = 1 / (1 + tau/dt)                      # dt = time since last reading
acc       = alpha*inst + (1-alpha)*acc            # EMA
# throttled to <= once per 0.1 s:
SetAutoBrightnessTarget(acc / 100)                #  T = acc/100  in [0,1]
```

Constants (all compile-time `#define`s — **no gsettings, not user-tunable without patching gsd**):

| constant | value | meaning |
|---|---|---|
| `GSD_AMBIENT_NORMALIZE_CONSTANT` | `1.5` | the reading at normalization → 66.7% target; 100% needs `L ≥ 1.5·Lanchor` |
| `GSD_AMBIENT_BANDWIDTH_HZ` | `0.1` | EMA bandwidth |
| `GSD_AMBIENT_TIME_CONSTANT` `τ` | `1e6/(2π·0.1) µs ≈ 1.592 s` | EMA time constant — **~1.6 s, not ~10 s** (earlier docs were wrong) |
| `GSD_AMBIENT_SEND_UPDATE_INTERVAL` | `0.1 s` | max rate targets are pushed to the shell |

**Renormalization (`ambient_norm_required = TRUE`) triggers** — each re-anchors `Lanchor` to the
*current* reading:
- **startup** (`gsd_power_manager_initable_init`, ~line 3140);
- **idle-dim turned on or off** (after `SetDimming` succeeds, ~line 1278);
- **any user brightness change** — gnome-shell emits the `BrightnessChanged` signal →
  `on_brightness_changed_by_user` (~line 3057). *This is the slider/hotkey → re-anchor path.*

## 2. gnome-shell — auto target `T` + manual slider `S` → backlight `B`

`js/misc/brightnessManager.js : _sync()` (the `org.gnome.Shell.Brightness` D-Bus service;
`SetAutoBrightnessTarget` sets `this._abTarget`, then `_sync()` runs). Per monitor with a backlight:

```js
// the manual slider biases the auto target by ±0.5 around its midpoint:
target = (abTarget >= 0) ? clamp(abTarget + scale.value - 0.5, 0, 1)   // auto active
                         : scale.value;                                // auto off
// idle-dim clips the top:
final  = min(target, dimmingEnabled ? idleBrightness/100 : 1.0);       // idle-brightness default 30 -> 0.30
// write, LINEAR in raw backlight units (no gamma / perceptual curve):
backlight.brightness = Bmin + (Bmax - Bmin) * final;
```

Notes that matter:
- **The manual slider is a ±0.5 additive bias on the auto target, not an absolute set.** With `S = 0.5`
  the panel follows `T` exactly; `S = 1.0` adds +0.5 (everything brighter, saturating); `S = 0` subtracts 0.5.
- The slider has **≤ 20 discrete steps** (`SCALE_VALUE_N_STEPS`, fewer if the panel has fewer raw
  steps). The auto target `T` is **continuous**.
- The shell's *own* backlight writes do **not** move the slider widget — it only re-reads the
  hardware on *external* changes (`syncWithBacklight`). So **auto-brightness slides the panel around a
  stationary slider.**
- `HasBrightnessControl` is true iff some logical monitor has an active backlight.

## 3. Net transfer function

Settled (EMA converged), auto on, slider at `S`, anchored at `Lanchor`:

```
B/Bmax  ≈  clamp(  min(0.6667 · L/Lanchor, 1.0)  +  S − 0.5 ,  0, 1 )      (then ·(Bmax−Bmin)+Bmin)
```

Consequences for iris:
1. **Absolute scale is irrelevant** — gsd divides by its own self-set `Lanchor`. Only the *ratio*
   `L/Lanchor` drives brightness. Reporting "lux × 1000" is meaningless; we shape a unitless ratio.
   *(So `MAX_LUX` should go.)*
2. **The law is linear with a hard cap at `L = 1.5·Lanchor`.** Feeding it a signal that is *linear in
   scene luminance* gives a uselessly narrow window: only luminance `∈ [0, 1.5·Lanchor]` maps to
   `0–100%` — a ~2.25× span, while real rooms vary 10–1000×. A **compressive (log-like) transform**
   `L = f(luminance)` is needed to spread the realistic range across `[0, 1.5·Lanchor]`.
   **Concretely (2026-07-03): the transform must be a *power law*, `L = C·lum^β`** — then
   `L/Lanchor = (lum/lum_anchor)^β`: shift-invariant, always positive, and `β` sets the span
   (100% reached at `1.5^(1/β)×` the anchor: β=0.25 → 5×, β=0.1 → 57×). Do **not** publish a
   log-domain EV directly: typical indoor `logmean ≈ −1.5` is *negative* → u32-clamps to 0 → the
   `L > 0` gate discards it; and ratios of logs depend on the arbitrary log offset. Pick `C` so the
   indoor anchor sits ≳10³ in integer units (u32 quantization). (Trade-off: small β flattens the
   dim-side response — this law cannot span both directions fully; the direct sink of §6 escapes it
   by owning the curve.)
3. **The manual slider is the user's only tuning knob**, and touching it re-anchors. Intended UX: park
   it near the middle to give auto the full range; nudge it when you disagree (that re-anchors).
4. **Not tunable in gsd.** The `1.5`, the linear law, and the bandwidth are hardcoded. The only runtime
   switch is `ambient-enabled`.

## 4. Why this explains the live test

Observed (STATUS): covering the lens dropped the panel to ~36%, bright light raised it to ~60%; the
**slider didn't move**; pressing the brightness keys made **"the whole range shift."**

- **Slider didn't move** — *by design.* The slider shows the user's bias `S`; auto-brightness moves the
  panel around it. The widget only tracks *external* backlight changes, not gsd-driven ones.
- **Range shifted on key-press** — pressing the keys changes `S` → shell emits `BrightnessChanged` →
  gsd re-anchors (`Lanchor ← current L`). The whole `L→brightness` mapping rescales around the new baseline.
  *(Resolved 2026-07-03: the keys are **kernel-handled** (ACPI `video.brightness_switch_enabled=Y`)
  and write the backlight directly — but the shell **observes** the external change
  (`syncWithBacklight`): the slider `S` tracks it and `BrightnessChanged` fires, so key-presses do
  re-anchor after all. The frozen-`Backlight`-property observation was mutter's hotplug staleness
  bug, not the key path. See §6 / FINDINGS 2026-07-03.)*
- **Only ~36–60% swing** — the reachable auto window is `[S−0.5, S+0.5]` clamped, and our reported `L`
  spanned too small a *ratio* (placeholder linear mapping), compounded by a ~2 s test against the ~1.6 s
  EMA (barely one time-constant — it had not settled).

## 5. How gsd enters/leaves auto mode — and the lifecycle footgun

Auto-brightness is a *mode* in gnome-shell, gated by gsd's claim of the sensor:

- **Enter:** when `iio_proxy_should_claim_light` holds (has brightness control + session active +
  screen not blanked + `ambient-enabled`), gsd `ClaimLight`s and starts sending
  `SetAutoBrightnessTarget(T ≥ 0)`; gnome-shell's `_abTarget` goes ≥ 0 and it applies
  `clamp(T + S − 0.5)` on every sync (§2).
- **Leave:** gsd sends `SetAutoBrightnessTarget(-1)` **only** from `light_released_cb` — i.e. only on
  a clean `ReleaseLight`. And `iio_proxy_claim_light(FALSE)` **returns early if `iio_proxy == NULL`**
  (gsd-power-manager.c:1358).

**The footgun:** if the sensor *vanishes* (iris stops / uhid device gone) rather than being cleanly
released, `iio_proxy` is NULL, so even toggling `ambient-enabled` off can't issue `ReleaseLight` → gsd
never sends `-1` → gnome-shell stays stuck in auto mode with a stale `_abTarget`, re-asserting
brightness over every manual write. With a high stale `abTarget`, `clamp(abTarget + S − 0.5)` makes the
**bottom of the range unreachable** (observed: a hard floor ~60%; sysfs *and* `SetBacklight` writes
reverting). Only a clean release or a shell restart clears it. (This also explains the project's early
"fn keys feel like some other state" — auto mode was silently engaged.)
**(Update 2026-07-03: defused —** `SetAutoBrightnessTarget` is callable by *any* session-bus peer
(§6), so sending `-1` directly clears stuck auto mode without gsd and without a shell restart —
validated live. The clean-release choreography below remains the polite path; a crash now costs one
`gdbus` call, e.g. an `ExecStopPost=` in the unit.)**

**Clean-release order (rehearsed 2026-06-25):** the release must happen *while the sensor is still
present*. Sequence: sensor live → flip `should_claim` false (e.g. disable `ambient-enabled`) → gsd
`ReleaseLight` → `-1` → auto mode cleared → *then* remove the sensor. Done in that order, manual control
returned (`SetBacklight 45` then held; before, it snapped back to 243). → iris shutdown requirement,
DESIGN §7.

**Mutter owns the panel; sysfs is decoupled.** In GNOME 50 the panel backlight is driven by mutter via
`org.gnome.Mutter.DisplayConfig` — the `Backlight` property + `SetBacklight(serial, connector, value)`
(the `serial` is the *backlight* serial from the property, not `GetCurrentState`'s config serial). The
legacy `/sys/class/backlight/intel_backlight` node is decoupled: writing it doesn't steer what mutter
drives (seen: sysfs 243 while mutter 43). And `SetBacklight` from outside is overridden whenever auto
mode is engaged. Net: **no durable external way to set screen brightness in GNOME 50 outside the
desktop's own controls** — the reconfirmation of iris's sensor-only (Tier-1) design.

## 6. Direct control & live-fire addenda (2026-07-03)

**`SetAutoBrightnessTarget` is publicly callable.** `org.gnome.Shell.Brightness` is a published
contract (`/usr/share/dbus-1/interfaces/org.gnome.Shell.Brightness.xml` — "lets the auto brightness
system tell the shell what the ideal relative brightness [0,1] is") and gnome-shell does **not**
restrict the sender. Validated live from an unprivileged process: `SetAutoBrightnessTarget 0.9`
drove the panel 62→122 raw, `0.75` drove 101→200, and `-1` restored manual mode (panel returns to
the slider value `S`) every time. Consequences:

- **A "Tier-1b" sink exists:** iris can *be* the auto-brightness source — one D-Bus call replaces
  uhid → kernel HID/IIO → iio-sensor-proxy → gsd, with iris owning the `EV → T` curve and smoothing
  (no §1 constants, no §3 contortions, re-anchoring done deliberately by subscribing to
  `BrightnessChanged` on the same interface). The `clamp(T + S − 0.5)` slider combination lives in
  the shell (§2), so the slider/hotkey UX is identical either way. Decision: STATUS Next №1.
- **The §5 footgun is defused:** stuck auto mode (vanished sensor, crashed daemon) is cleared by
  *any* process sending `-1` — no shell restart.

**fn keys: kernel-handled, then observed.** `video.brightness_switch_enabled=Y` → the ACPI video
handler steps `intel_backlight` directly (uniform ~10% = 40/400 steps in a 300 ms trace — not the
shell's ≤20-step slider). The shell then *observes* the external change (`syncWithBacklight`): `S`
tracks it (releasing auto mode landed exactly on the key-set raw value) and `BrightnessChanged`
fires → §1's re-anchor path works for key presses, and the hotkeys coexist with *any* target source.

**Mutter hotplug fragility (two bugs, 2026-07-03).** A lid close→open re-created `intel_backlight`,
after which:
1. **Wrong-device binding:** mutter re-bound eDP-1's backlight to the dGPU's phantom `nvidia_0`
   (min 1/max 100, controls nothing) — every GNOME brightness write went nowhere until *another*
   lid cycle re-bound it (DPMS off/on and `udevadm trigger` on drm/backlight did **not**; re-login is
   the deterministic fallback). **Tell:** the `Backlight` property reads `max 100` instead of `400`.
2. **Stale property mirror:** post-rebind, shell-internal writes move the hardware while the D-Bus
   `Backlight` property stays frozen (property 62 throughout a sysfs 101→200→101 round-trip).
   ⇒ **read sysfs for current state, never the property.**

iris hardening (either sink): watchdog "target changed but `intel_backlight` didn't move" (or the
max-100 tell) and warn — auto-brightness dies silently when bug 1 strikes. Evidence and timeline:
FINDINGS 2026-07-03. Upstream: bug 1 is [mutter #4432](https://gitlab.gnome.org/GNOME/mutter/-/issues/4432)
(open) with draft fix [!4746](https://gitlab.gnome.org/GNOME/mutter/-/merge_requests/4746); bug 2
appears unreported (links in FINDINGS).

## 7. Source references

- `gnome-settings-daemon` 50.1 — `plugins/power/gsd-power-manager.c`: `iio_proxy_changed()` (~2945),
  `shell_brightness_set_auto_target()` (~1251), `on_brightness_changed_by_user()` (~3051), constants (~76–100).
- `gnome-shell` 50.2 — `js/misc/brightnessManager.js`: `_sync()` (~186), `MonitorBrightnessScale`
  (~332); D-Bus contract `data/dbus-interfaces/org.gnome.Shell.Brightness.xml`.
- Auto-mode lifecycle: `iio_proxy_claim_light()` / `light_released_cb` / `iio_proxy_should_claim_light()`
  (gsd-power-manager.c ~1321–1392; the `iio_proxy == NULL` early-return at 1358). Panel ownership:
  mutter `org.gnome.Mutter.DisplayConfig` `Backlight` / `SetBacklight`.
