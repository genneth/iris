# iris

Auto-adjust this laptop's screen brightness from a real ambient-light measurement, working
*with* GNOME rather than fighting it. This laptop has no built-in ALS, so iris gets the light
level from **the phone** (calibrated lux over BLE) and drives GNOME's backlight through its own
auto-brightness sink. Brightness only (no colour temperature).

## The eye — three components

| Component | What it is | Where |
|---|---|---|
| **pupil** | The aperture/sensor: an Android app that broadcasts the phone's ambient-light reading as BTHome BLE adverts, plus the receive-side decode + freshness/RSSI library. | [`android/`](./android), [`python/src/pupil/`](./python/src/pupil), [`contract/`](./contract) |
| **iris** | The muscle that regulates light: the shipping `systemd --user` daemon. Receives pupil's lux, maps it through an absolute log-lux curve, and drives GNOME via `SetAutoBrightnessTarget`. | [`python/src/iris/`](./python/src/iris), [`python/deploy/`](./python/deploy), [`docs/iris/`](./docs/iris) |
| **retina** | The imaging surface (experimental, parked): the webcam-as-ALS path — camera → scene-brightness → virtual HID ALS so GNOME reads the webcam as a sensor. Superseded by the phone path; kept for provenance. | [`python/src/retina/`](./python/src/retina), [`docs/retina/`](./docs/retina) |

**Status (2026-07-04):** **iris ships and is validated on-device** (Find N6 → laptop): the phone's
lux drives the real backlight through GNOME's own auto-brightness, reverting to the manual slider
when the phone leaves range. **pupil** is built and on-device-verified (see `android/README.md`).
**retina** is parked — the webcam path worked end-to-end but the phone is a better sensor (calibrated
lux, no privacy-LED, no video-call contention, no screen-feedback loop).

## Run iris (the daemon)

iris reads pupil's BLE lux and drives GNOME's backlight — no camera, no uhid, no root. Design:
[`docs/iris/`](./docs/iris) and `docs/superpowers/specs/2026-07-04-reflex-phone-als-brightness-design.md`
(codename "Reflex" — the pupillary light reflex).

**Foreground** (needs the phone broadcasting pupil nearby):

```sh
cd python && uv run python -m iris          # Ctrl-C releases to manual and exits cleanly
IRIS_DEBUG=1 uv run python -m iris          # + per-reading lux->target and panel readout (for calibration)
```

**As a `systemd --user` service** (auto-starts with the graphical session):

```sh
mkdir -p ~/.config/systemd/user
cp python/deploy/iris.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now iris.service
journalctl --user -u iris -f        # follow
systemctl --user stop iris.service  # releases to manual (-1) via ExecStopPost
```

The unit assumes the checkout at `~/iris` with the uv venv at `python/.venv`; edit `ExecStart=` if
elsewhere. **Installation/management of software on this machine is [almon](../almon)'s domain** — see
[`docs/iris/PACKAGING.md`](./docs/iris/PACKAGING.md) for how iris intends to hand almon a clean artifact.

### Calibration

Optional `~/.config/iris/config.toml` over code defaults (all keys optional). A worked, room-tuned
example ships at [`python/deploy/config.example.toml`](./python/deploy/config.example.toml):

```toml
[curve]
# A single straight log-lux line, clamped to caps at both ends (the "physical model"):
# floor for dark, ceil for bright. 100% is anchored at DIRECT SUN (~70k lx), so the gentle
# slope keeps it dim indoors (100 lx -> 20%, 150 -> 25%) and only pegs 100% in real sun.
anchors = [[25, 0.03], [70000, 1.0]]   # [lux, target] pairs, piecewise-linear in log10(lux)
floor = 0.03
ceil = 1.0

[tracker]
min_rssi = -75.0     # admit above +5, drop below -5 (hysteresis) — the proximity gate
stale_after = 25.0   # seconds of silence -> STALE -> release to manual
dead_after = 30.0    # seconds with no BLE advert at all -> SCANNER_DEAD (recreate scanner)

[ease]
tau = 1.5            # seconds; how fast the panel eases toward the curve target
push_hz = 10.0       # D-Bus push rate while driving
epsilon = 0.002      # skip a push below this |delta target|
```

To calibrate to your room: run with `IRIS_DEBUG=1`, keep the phone broadcasting beside the laptop,
set the GNOME slider until a given light looks comfortable, and read the `(lux, panel%)` pair — those
become `[curve] anchors`. Collinear anchors in log-lux give a single straight "physical" line.

**On-device acceptance — PASSED (2026-07-04, Find N6 + molly, cloudy day):** engage with no jump
(0.5-neutral); tracks dark→floor (backlight 111→4/400) and up with light; manual slider bias persists
live (`clamp(T + S − 0.5)`, no re-anchor); phone silent → `stale` at ~25 s → `-1` → manual; clean
SIGTERM release; RSSI proximity gate admits at the desk and drops beyond ~1 m. Caveat (pupil-side, not
iris): when the phone's screen sleeps, ColorOS pauses pupil and the feed goes `stale` → iris reverts to
manual; keep the screen on / "Allow background activity" for continuous driving.

## Layout

- **`python/`** — uv project. `src/pupil` (receive lib), `src/iris` (the daemon), `src/retina`
  (parked camera experiment); `tests/`, `scripts/{pupil,iris,retina}/` (a sorted lab notebook),
  `deploy/` (service unit + example config).
- **`android/`** — the pupil app (Jetpack Compose; see its own README).
- **`contract/`** — `bthome-golden.json`, the cross-language pupil wire-format contract (android
  encoder ↔ python decoder).
- **`rust/`** — a stub for an eventual low-footprint rewrite of the iris daemon (see STATUS; not
  started, not clearly necessary).
- **`docs/`** — `iris/` (brightness-stack analysis, packaging), `retina/` (camera sensing findings),
  `superpowers/` (specs + plans). **`dev.sh`**, **`hooks/`** — task runner + pre-commit gate.

## Development

```sh
cd python && uv sync     # set up the env (host-side; no toolbox needed)
./dev.sh setup           # enable the git pre-commit hook (once per clone)
./dev.sh check           # full gate: ruff + mypy + pytest + cargo fmt/clippy (runs on every commit)
./dev.sh fmt             # auto-format
```

## Docs

- **[docs/iris/](./docs/iris)** — [`BRIGHTNESS-MATH.md`](./docs/iris/BRIGHTNESS-MATH.md) (the exact
  gsd-power + gnome-shell arithmetic iris drives) and [`PACKAGING.md`](./docs/iris/PACKAGING.md).
- **[docs/retina/](./docs/retina)** — [`DESIGN.md`](./docs/retina/DESIGN.md) (the webcam-as-ALS
  architecture), [`FINDINGS.md`](./docs/retina/FINDINGS.md) (sensing experiments),
  [`DESIGN-v1.md`](./docs/retina/DESIGN-v1.md) (original pre-pivot brief).
- **[STATUS.md](./STATUS.md)** — what's done, what's next, open questions.
- **[docs/superpowers/](./docs/superpowers)** — the design specs and implementation plans.

*Heritage name:* the "IR" in **IR**is is the infrared-sensor exploration ([docs/retina/FINDINGS.md](./docs/retina/FINDINGS.md))
that taught us the camera's behaviour before the phone became the sensor.
