# iris

Use this laptop's webcam as an **ambient-light sensor** — it has no real ALS — so the desktop can
auto-adjust screen brightness. iris does **not** control brightness itself: it presents the camera
as a standard ambient-light sensor (a virtual HID ALS), and GNOME's existing auto-brightness does
the rest. Brightness only (no colour temperature).

**IR**is — heritage name: the infrared-sensor exploration ([FINDINGS.md](./FINDINGS.md)) is what
taught us the camera's behaviour. The shipping design uses the RGB sensor.

**Status (2026-07-03):** **MVP working, full loop validated** — the iris daemon turns the webcam
into a virtual ambient-light sensor, and GNOME's native auto-brightness moves the real backlight in
response (covering the lens dims the screen; bright light raises it). Runs via `sudo` pending a
`/dev/uhid` udev rule; tuning the lux→brightness range is the next refinement — and the sink
decision has been **reopened** after validating a much simpler direct-to-GNOME variant
(`SetAutoBrightnessTarget`; DESIGN §2 update). See **[STATUS.md](./STATUS.md)**.

## Docs

- **[DESIGN.md](./DESIGN.md)** — the architecture ("webcam as a virtual ambient-light sensor").
- **[STATUS.md](./STATUS.md)** — what's done, what's next, open questions.
- **[FINDINGS.md](./FINDINGS.md)** — empirical results from the sensing experiments.
- **[DESIGN-v1.md](./DESIGN-v1.md)** — the original pre-pivot brief, kept for provenance.

## Layout

- **`python/`** — the MVP / iteration implementation (uv project: `src/iris/`, `scripts/`, `tests/`).
- **`rust/`** — the eventual low-footprint, shrink-wrapped deployment build (cargo; currently a stub).
- **`dev.sh`**, **`hooks/`** — the dev task runner and the git pre-commit gate. Shared docs at the root.

## Development

```sh
cd python && uv sync     # set up the Python env (host-side; no toolbox needed)
./dev.sh setup           # enable the git pre-commit hook (once per clone)
./dev.sh check           # the full gate: ruff + mypy + pytest + cargo fmt/clippy (runs on every commit)
./dev.sh fmt             # auto-format python + rust
```

## Running the experiments

```sh
cd python
uv run python scripts/uhid_als_spike.py validate    # parse the virtual-sensor descriptor (no root)
sudo .venv/bin/python scripts/uhid_als_spike.py      # create the virtual ALS (needs /dev/uhid)
```

See [`python/scripts/README.md`](./python/scripts/README.md) for the full experiment index.

## Run Reflex (phone-ALS, direct sink)

Reflex is the shipping daemon: Pupil (the Android app, `android/`) broadcasts calibrated lux over
BLE; iris reads it, maps it through an absolute log-lux curve, and drives GNOME's backlight via
`org.gnome.Shell.Brightness.SetAutoBrightnessTarget` — no camera, no uhid, no root. Design:
DESIGN.md §2 (Tier-1b), `docs/superpowers/specs/2026-07-04-reflex-phone-als-brightness-design.md`.

**Run in the foreground** (needs the phone broadcasting Pupil nearby):

```sh
cd python && uv run python -m iris
```

Ctrl-C releases the backlight to manual and exits cleanly.

**Install as a `systemd --user` service** (auto-starts with the graphical session):

```sh
mkdir -p ~/.config/systemd/user
cp python/deploy/iris.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now iris.service
journalctl --user -u iris -f       # follow the logs
systemctl --user stop iris.service # releases to manual (-1) via ExecStopPost, belt-and-braces
```

The unit assumes the checkout lives at `~/iris` with the uv venv at `python/.venv`; edit
`ExecStart=` in the copied unit file if yours lives elsewhere.

**Calibration** — optional `~/.config/iris/config.toml` (all keys optional; see
`iris/config.py` for the code defaults). A worked, room-tuned example ships at
[`python/deploy/config.example.toml`](./python/deploy/config.example.toml):

```toml
[curve]
# A single straight log-lux line, clamped to caps at both ends (the "physical model"):
# floor for dark, ceil for bright. 100% is anchored at DIRECT SUN (~70k lx), so the
# gentle slope keeps it dim-leaning indoors (100 lx -> 23%) and only pegs to 100% if
# the sensor catches real sun; indoor-by-window tops out ~58-77%.
anchors = [[18, 0.03], [70000, 1.0]]  # [lux, target] pairs, piecewise-linear in log10(lux)
floor = 0.03
ceil = 1.0

[tracker]
min_rssi = -75.0     # admit above +5, drop below -5 (hysteresis)
stale_after = 25.0   # seconds of silence -> STALE -> release to manual
dead_after = 30.0    # seconds with no BLE advert at all -> SCANNER_DEAD

[ease]
tau = 1.5            # seconds; how fast the panel eases toward the curve target
push_hz = 10.0        # D-Bus push rate while driving
epsilon = 0.002       # skip a push below this |delta target|
```

**Calibrating to your room** — run with `IRIS_DEBUG=1 python -m iris`: it logs each phone
reading as `recv lux=… -> curve target=…` and a periodic `panel sysfs=…% applied_target=…`.
Keep the phone broadcasting beside the laptop, set the GNOME slider until a given light looks
comfortable, and read the `(lux, panel%)` pair — those become your `[curve] anchors`. Collinear
anchors in log-lux give a single straight "physical" line; add points to shape it.

**Live-test result (spec §8 acceptance) — PASSED on-device (2026-07-04, Find N6 + molly, cloudy day):**

- **Engage, no jump:** phone brought near → `never-seen → fresh`; panel held at the slider, then
  eased to the curve target (no jump), confirming the 0.5-neutral engage.
- **Tracks light:** covering the phone drove the backlight to the floor (**111 → 4/400**); light
  raised it back. Curve mapping confirmed live from the debug readout (e.g. 131 lx → 0.37,
  306 lx → 0.56 on the default curve).
- **Manual bias persists (no re-anchor):** dragging the GNOME slider bottom→middle moved the panel
  4 → 201/400 while the auto target held at 0.556 — `clamp(T + S − 0.5)` exactly as designed.
- **Dropout → manual:** phone silent/away → `fresh → stale` at ~25 s → `SetAutoBrightnessTarget(-1)`,
  and the manual slider took over. Re-engaged on the phone's return.
- **Clean stop:** SIGINT/SIGTERM → `released to manual` (validated repeatedly).
- **RSSI proximity gate (by design):** admitted at the desk (−65…−74 dBm); at the window edge (−80)
  and beyond it drops out and reverts to manual — the low-TX gate working as intended.
- **Watchdog:** a bottom-rail false-positive was found and fixed (suppress the `#4432?` warning when
  the panel is legitimately pinned at a rail).
- **Caveat (Pupil-side, not Reflex):** when the phone's screen sleeps, ColorOS pauses Pupil and the
  feed goes `stale` → Reflex reverts to manual; keep the screen on / "Allow background activity" for
  continuous driving.

The shipped `config.example.toml` is a clamped log-lux line (physical model, floor+ceil caps),
tuned live to one room on a cloudy day, with 100% anchored at direct sun so it stays dim-leaning
indoors; the sub-20-lux floor is worth re-checking in true darkness.
