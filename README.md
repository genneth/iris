# iris

Use this laptop's webcam as an **ambient-light sensor** — it has no real ALS — so the desktop can
auto-adjust screen brightness. iris does **not** control brightness itself: it presents the camera
as a standard ambient-light sensor (a virtual HID ALS), and GNOME's existing auto-brightness does
the rest. Brightness only (no colour temperature).

**IR**is — heritage name: the infrared-sensor exploration ([FINDINGS.md](./FINDINGS.md)) is what
taught us the camera's behaviour. The shipping design uses the RGB sensor.

**Status (2026-06-25):** **MVP working** — the iris daemon holds the webcam streaming and feeds real
camera-derived lux to a virtual ambient-light sensor; `monitor-sensor` reports live ambient lux
through `iio-sensor-proxy`. Not yet wired through to GNOME's *brightness response* (next step), and
it runs via `sudo` pending a `/dev/uhid` udev rule. See **[STATUS.md](./STATUS.md)**.

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
