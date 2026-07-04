# Reflex — the phone as iris's ambient-light sensor (design)

_2026-07-04. Approved design for closing the loop from **Pupil** (the phone's calibrated lux, already
broadcasting over BLE) to the laptop's screen brightness, with the phone as the **sole** ambient-light
source and iris driving GNOME via the **direct sink**. Behaviour background: the Pupil spec
`2026-07-03-pupil-ble-als-design.md`; the downstream arithmetic `BRIGHTNESS-MATH.md` (esp. §6, the
direct sink); the sink decision this closes is STATUS Next №1._

## 1. Goal & scope

Turn the phone into a *bona fide* ambient-light sensor for the laptop: Pupil broadcasts calibrated lux
over BLE, and the laptop-side **iris daemon** — repurposed for this (codename **Reflex**) — receives it
and drives the screen backlight so brightness tracks the room. (Pupil senses light; Reflex is the
display's response — the *pupillary light reflex*.) This **reuses the existing `iris` daemon and its
service as the local script**, rather than standing up a parallel service: `daemon.py` is repurposed
from webcam orchestration to the phone controller.

**Two decisions are settled** (this session):

1. **Phone is the sole ALS. The webcam is not involved.** This collapses the webcam-era complexity:
   no V4L2 exclusivity (video calls unaffected), no privacy-LED blink, no `logmean`/exposure-ranging,
   and — decisively — **no screen-feedback runaway**, because the phone cannot see the laptop's own
   screen. One calibrated source, absolute lux.
2. **Direct sink** (`org.gnome.Shell.Brightness.SetAutoBrightnessTarget`, validated live —
   BRIGHTNESS-MATH §6), **not** the uhid→gsd path. Rationale below (§2).

**Success criterion:** with Pupil broadcasting and Reflex running, the real panel backlight tracks the
phone's lux through a stable, absolute curve iris owns; bringing the phone near the laptop enables
auto-brightness and taking it away reverts cleanly to manual; the fn-keys/slider keep working as a
persistent bias; `./dev.sh check` stays green.

**Out of scope:** the webcam sensing modules (`camera.py`, `virtual_als.py`, the uhid recipe) — left in
the tree unused, available if webcam mode is ever revived, but not part of Reflex; any change to Pupil
(the phone app) or the BTHome wire format; multi-monitor/external displays (internal panel only);
colour temperature. (`daemon.py` itself is *repurposed*, not left untouched — see §7.)

## 2. Why the direct sink for a *calibrated* sensor

The anchoring problem (BRIGHTNESS-MATH §1/§3) is the crux, and for a calibrated ALS it is decisive.
gsd divides every reading by a **self-set `Lanchor`** and re-anchors on startup, idle-dim toggle, and
**every hotkey/slider touch** — so only `L/Lanchor` drives brightness, the absolute scale is discarded,
and the anchor is whatever the ambient happened to be at the last keypress.

For the webcam (an uncalibrated luma proxy) that is fine — relative-with-re-anchor is all it ever had.
But the phone's *entire advantage* is absolute calibration (50 lx is 50 lx), and gsd deliberately
throws exactly that away, then rescales the mapping on every interaction. Feeding a calibrated sensor
into gsd spends its one advantage to buy nothing.

The direct sink escapes it: **iris owns the `lux → target` curve**, so a given room brightness always
produces the same backlight — one absolute, stable mapping, no rescale, no drift. Costs, accepted:
no native Settings "Automatic Brightness" toggle (Reflex owns on/off itself), and coupling to the
published-but-newer shell API (it breaks loudly on API change rather than drifting silently — the
better failure mode). There is still exactly one brightness writer: the shell.

## 3. Architecture

The existing `iris` daemon, repurposed — the same `python -m iris` entry point and a single `iris`
`systemd --user` service bound to `graphical-session.target`, **not** a parallel service. **No camera,
no uhid, no iio-sensor-proxy, no root, no udev** — a plain session service. `asyncio` throughout (one
event loop shared by BLE and D-Bus).

```
Pupil (phone) ─BTHome BLE adverts─▶ [Reflex]
  bleak active scan ─▶ decode_bthome_illuminance ─▶ PupilTracker (RSSI gate + freshness)
     │ latest accepted lux                              │ TrackerState
     ▼                                                  ▼
  BrightnessCurve (lux→target, log-lux)         state machine (DRIVING / RELEASED)
     │ target ∈ [floor, ceil]                           │
     └────────────▶ easing loop (~10 Hz, τ≈1.5 s) ──────┴─▶ SetAutoBrightnessTarget(T)  (or −1)
                                                                 └─▶ gnome-shell: clamp(T + S − 0.5) ─▶ mutter ─▶ backlight
```

**Reuses wholesale:** `iris/pupil.py` — `decode_bthome_illuminance`, `PupilTracker`/`TrackerConfig`/
`TrackerState`, `PupilReading` — and the `bleak` active-scan pattern from `scripts/ble_als_probe.py`.
**New code only** is the curve, the D-Bus sink, and the controller (§7).

**Why the easing loop exists.** The direct sink bypasses gsd-power's EMA (BRIGHTNESS-MATH §1) — the
shell's `_sync` applies a pushed target *immediately*, so one lux jump = one backlight jump. Reflex
therefore owns smoothing: a ~10 Hz loop eases the *applied* `T` toward the *target* `T` with a time
constant τ≈1.5 s (gsd used ≈1.6 s and it felt right), giving smooth ramps and absorbing the burst of
adverts Pupil emits when light is changing. D-Bus calls are capped at ~10 Hz and skipped when the
applied `T` is within an epsilon of the target (no spamming a settled panel).

## 4. The curve & calibration — the payoff of owning it

Reflex publishes an **absolute, stable, user-tunable** `lux → brightness` mapping, perceptual (roughly
linear in `log10 lux`), defined as a **small table of `(lux, percent)` anchors interpolated in
log-lux space** and clamped to `[floor, ceil]` (never fully black, never blinding). Transparent and
adjustable in a config file — not a fitted black box.

```
target(lux) = clamp( piecewise_linear_in_log10( max(lux, ε), anchors ), floor, ceil )
```

Defaults (starting point, refined by the calibration walk below):

| anchor | lux (phone) | brightness % |
|---|---|---|
| dark room / covered | ~1 | floor (≈8%) |
| dim evening | ~10 | ~12% |
| lit evening (known comfort anchor, FINDINGS) | _measure_ | 16% |
| bright room / daytime | ~300 | ~55% |
| near a sunlit window | ~2000+ | ceil (≈100%) |

**Calibration is trivial because the ALS *is* the calibration device.** Carry the phone through dark →
dim → lit-evening → bright-window, note the comfortable backlight at each, drop the pairs into the
config. This is exactly the "monotonicity walk + bright anchor" STATUS №2 had pending — it collapses
into one short session. We already hold one pair — lit-evening ↔ 16% (raw 63/400) — and only need the
phone's lux reading in that room to pin it. `ε` (a small lux floor, ~0.5) keeps `log10` finite and maps
true darkness to `floor`.

`lux = 0` **while packets are still fresh** is *real darkness* (phone face-down/covered) → drive to
`floor`. It is **not** a dropout — only the *absence* of packets is (§5). The freshness contract gives
us that distinction for free.

## 5. Lifecycle — state machine on `TrackerState`

Reflex has two sink states plus a scanner-health watchdog:

| Reflex state | Entered when | Behaviour |
|---|---|---|
| **RELEASED** (initial) | `NEVER_SEEN`; or on entry from DRIVING when the phone goes silent | Not asserting any target. Manual slider/keys fully in control. |
| **DRIVING** | `TrackerState.FRESH` | Easing loop pushes `SetAutoBrightnessTarget(T)` from the curve. |

Transitions:
- `FRESH` → **DRIVING**: start the easing loop (ramps smoothly from the current panel value — no jump).
- `STALE` **or** `SCANNER_DEAD` while DRIVING → **RELEASED**: send `SetAutoBrightnessTarget(-1)` **once**,
  stop the loop. `PupilTracker`'s `STALE` (25 s, i.e. ≥2 missed 10 s heartbeats) means the phone is
  genuinely gone, so this is the "phone away" trigger.
- back to `FRESH` → **DRIVING** again.
- **shutdown** while DRIVING → send `-1` (also wired as `ExecStopPost=` — belt and braces,
  BRIGHTNESS-MATH §5/§6). Shutdown while RELEASED → nothing (already manual).

**This is the confirmed UX: revert-to-manual on dropout**, which turns the low-TX RSSI gate into a
feature — *auto-brightness is on when the phone is near the laptop; take it away and brightness reverts
to my manual control* — matching Pupil's "naturally stops being seen unless it's right next to the
laptop" intent. (Rejected alternative: freeze at last brightness, which would keep asserting the stale
target and lock out the manual slider until the phone returned.)

**Scanner-health watchdog.** `SCANNER_DEAD` (no adverts from *anyone* for 30 s) means Reflex's own BLE
scan broke, not just that the phone left. On `SCANNER_DEAD`, release **and** tear down + recreate the
`bleak` scanner (calling `PupilTracker.reset_scanner`), so a wedged adapter self-heals.

## 6. Manual override — falls out for free, no anchoring

Reflex **never subscribes to `BrightnessChanged`** (no re-anchoring — that is the whole point). So a
slider or fn-key nudge only changes the shell's manual bias `S`, and every subsequent `T` push combines
as `clamp(T + S − 0.5)` (BRIGHTNESS-MATH §2). The user's bias therefore **persists as a stable offset**
("Reflex runs a touch dim for me") until they move it again — the exact inverse of the anchoring
problem, obtained by *not* doing what gsd does. fn-keys are kernel-handled but shell-observed
(`syncWithBacklight`), so they move `S` and integrate the same way (FINDINGS 2026-07-03).

## 7. Module structure

Reuse the `iris` package flat (matching its existing layout — `brightness.py`, `camera.py`,
`daemon.py`, `pupil.py`, `virtual_als.py`), no new subpackage. Python (validated footprint; drops
straight onto `pupil.py`; `bleak` already vendors `dbus-fast`, which we reuse for the sink — no new
runtime dep of substance).

- **Unchanged / reused:** `iris/pupil.py` and its tests; the `bleak` scan idiom from
  `scripts/ble_als_probe.py`.
- **Repurposed:** `iris/daemon.py` — from webcam→virtual-ALS orchestration to the **phone controller**:
  the `asyncio` BLE scan task feeding `PupilTracker`, the ~10 Hz easing loop, and the DRIVING/RELEASED
  state machine. Time and the sink are injectable for testing (the tracker already takes `now`
  explicitly). Reached via the existing `python -m iris` entry (`iris/__main__.py`).
- **New modules (flat in `iris/`):**
  - `iris/curve.py` — `BrightnessCurve(anchors, floor, ceil, eps)` with `target(lux: float) -> float`.
    Pure, fully unit-tested.
  - `iris/shell_brightness.py` — `ShellBrightness` over `dbus-fast` (session bus,
    `org.gnome.Shell` / `/org/gnome/Shell/Brightness`): `async set_target(t: float)`,
    `async release()` (`t = -1.0`). Plus a best-effort `read_backlight_sysfs()` helper for the
    watchdog (§9) — reads `/sys/class/backlight/intel_backlight`, **never** the D-Bus `Backlight`
    property (it goes stale — FINDINGS bug 2).
  - `iris/config.py` — load `~/.config/iris/config.toml` (stdlib `tomllib`) over code defaults:
    anchor table, `floor`/`ceil`/`eps`, easing `tau`, D-Bus rate cap, and `TrackerConfig` overrides.
- **Deployment artifact:** `deploy/iris.service` — the single `systemd --user` unit,
  `PartOf=graphical-session.target`, `ExecStart=` running `python -m iris` (or the `iris` console
  script), `ExecStopPost=` sending `-1` via `gdbus`.

## 8. Testing

- **Keep** `pupil.py`'s decoder/tracker tests untouched (they gate the receive logic Reflex depends on).
- **Curve** (`curve.py`): monotonic non-decreasing in lux; clamps to `[floor, ceil]`; hits each anchor
  exactly; correct log-lux interpolation between anchors; `lux ≤ eps` → `floor`.
- **Controller** (`daemon.py`): with a fake clock, a fake sink, and synthetic `PupilReading`s —
  `FRESH` drives and eases toward the curve target; `FRESH→STALE` calls `release()` **exactly once**
  and stops pushing; `STALE→FRESH` resumes; `SCANNER_DEAD` releases and triggers scanner recreation;
  shutdown-while-driving releases. Assert the D-Bus rate cap and settled-epsilon skip.
- **Sink** (`shell_brightness.py`): thin enough to cover in the live test rather than mocking
  `dbus-fast` in units.
- **Live integration** (the existing acceptance rhythm, like `uhid_als_spike.py validate`): run Reflex
  against the real shell — a phone lux ramp moves the panel smoothly; removing the phone reverts to
  manual (the slider works again); a slider nudge persists as a bias across subsequent updates.
- `./dev.sh check` (ruff + mypy + pytest + rust gates) must stay green.

## 9. Hardening & risks

| Risk / hazard | Mitigation |
|---|---|
| **mutter bug #4432** (lid cycle re-binds backlight to phantom `nvidia_0`) silently kills auto-brightness | Watchdog: after a target push, sample `intel_backlight` sysfs; if `T` moved materially but sysfs didn't over a few seconds (or the `Backlight` property reads `max 100`), log a clear warning naming #4432. Best-effort, non-fatal. |
| Stale D-Bus `Backlight` property (bug 2) | Read sysfs for current state, never the property (baked into `sink.py`). |
| Shell API (`SetAutoBrightnessTarget`) changes across GNOME versions | Fails loudly (accepted trade-off, §2); surfaced as an error, not silent drift. |
| Phone brought near mid-session → sudden auto engage | Easing loop ramps from the current panel value — smooth, not a jump (§3/§5). |
| Continuous laptop-side BLE active scan cost | Small; the RSSI gate + Pupil's low TX mean Reflex only *acts* when the phone is near, but the scan itself runs whenever the service is up (accepted). |
| Crash leaves auto mode asserted | Defused: `-1` from any process clears it; `ExecStopPost=` and the shutdown handler both send it (BRIGHTNESS-MATH §5/§6). |

## 10. Key sources

- Direct sink validation + arithmetic: `BRIGHTNESS-MATH.md` §6 (and §1–§3 for the anchoring law, §5 for
  the release lifecycle).
- fn-key / slider integration (`clamp(T + S − 0.5)`, `syncWithBacklight`): `BRIGHTNESS-MATH.md` §2,
  `FINDINGS.md` 2026-07-03.
- Receive logic being reused: `iris/pupil.py`; Pupil behaviour spec
  `docs/superpowers/specs/2026-07-03-pupil-ble-als-design.md`.
- D-Bus contract: `/usr/share/dbus-1/interfaces/org.gnome.Shell.Brightness.xml`
  (`org.gnome.Shell` / `/org/gnome/Shell/Brightness`).
</content>
</invoke>
