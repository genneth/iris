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
