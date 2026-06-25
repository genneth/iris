# iris

Use this laptop's webcam as an **ambient-light sensor** — it has no real ALS — so the desktop can
auto-adjust screen brightness. iris does **not** control brightness itself: it presents the camera
as a standard ambient-light sensor (a virtual HID ALS), and GNOME's existing auto-brightness does
the rest. Brightness only (no colour temperature).

**IR**is — the name is heritage: the infrared-sensor exploration ([FINDINGS.md](./FINDINGS.md)) is
what taught us the camera's behaviour. The shipping design uses the RGB sensor.

**Status (2026-06-25):** the architecture is **validated end-to-end** — a virtual ambient-light
sensor fed from userspace is detected by `iio-sensor-proxy` and reports our values up to GNOME. The
real camera→lux daemon is **not yet built**. See **[STATUS.md](./STATUS.md)**.

## Docs

- **[DESIGN.md](./DESIGN.md)** — the architecture ("webcam as a virtual ambient-light sensor").
- **[STATUS.md](./STATUS.md)** — what's done, what's next, open questions.
- **[FINDINGS.md](./FINDINGS.md)** — empirical results from the sensing experiments.
- **[DESIGN-v1.md](./DESIGN-v1.md)** — the original pre-pivot brief, kept for provenance.

## Layout

- `scripts/` — the experiments that validated the design ([scripts/README.md](./scripts/README.md)).
- `src/iris/` — the daemon package (stub; to be built).

## Running the experiments

```sh
uv sync
uv run python scripts/uhid_als_spike.py validate     # parse the virtual-sensor descriptor (no root)
sudo .venv/bin/python scripts/uhid_als_spike.py       # create the virtual ALS (needs /dev/uhid)
```
