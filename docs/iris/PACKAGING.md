# iris — packaging & deployment (for almon)

> **Ownership.** Installation and lifecycle management of software on this machine is
> [**almon**](../../../almon)'s domain (`~/almon`) — see its `AGENTS.md`. This repo does **not**
> install anything; it hands almon a *single self-contained file* + a unit + an example config,
> with no build step and no interpreter/venv to manage on the immutable host.

## What iris is, deployment-wise

- **One self-contained file: [`python/iris.py`](../../python/iris.py)** (~580 lines). A PEP 723
  script with inline dependency metadata — `bleak` + `dbus-fast` (dbus-fast rides in with bleak).
  Everything else (BTHome decode, presence tracker, curve, config, controller, D-Bus sink, async
  daemon) is in the one file. No compiled extensions.
- Runs as a **`systemd --user`** service bound to `graphical-session.target`. **No root, no udev, no
  system packages** — session bus (`org.gnome.Shell.Brightness`) + BlueZ.
- **Config:** optional `~/.config/iris/config.toml` (user-owned; `deploy/config.example.toml` is the
  starting point). Nothing to install.
- **Unit:** [`../../python/deploy/iris.service`](../../python/deploy/iris.service) —
  `ExecStart=uv run --script …/iris.py`; `ExecStopPost` sends `SetAutoBrightnessTarget(-1)` so
  brightness always returns to manual on stop/crash.

## How it deploys: `uv run --script`

```sh
uv run --script ~/iris/python/iris.py
```

`uv` reads the inline `# /// script` metadata, resolves `bleak` + `dbus-fast` into an **isolated,
cached** environment, and runs — **no venv to create, no `pip install`, no project checkout needed
beyond the one file**. Verified standalone (copied out of the repo, run with `--script`, resolved
deps and drove brightness). This is the whole artifact: copy `iris.py` anywhere + point the unit at
it. First run pays a one-time resolve; subsequent runs hit uv's cache.

Why a single file and not an installed package: iris's real dependency surface is just two libraries,
and a self-contained script can't import a local helper package anyway — so the decode/tracker/curve/
controller/sink all live in the one file. It reads top-to-bottom in clearly-marked sections; ~580
lines is not excessive for a daemon, and the pure functions stay unit-tested (tests `import iris` via
pytest's pythonpath; `run()` is guarded so import is side-effect-free).

## What almon needs from us (the clean contract)

- **The file:** `python/iris.py` (versioned with the repo). That's the artifact — no `uv build`, no
  wheel, no compile step.
- **The unit + example config:** `deploy/iris.service`, `deploy/config.example.toml` (both in-repo).
- **On the host:** `uv` on the user PATH and `systemctl --user`. Nothing system-level, no root.
- **Install:** drop/point at `iris.py`, copy the unit, `systemctl --user enable --now iris.service`.
  **Update:** `git pull` (the file is versioned) — uv re-resolves only if the inline deps changed.
  **Uninstall:** `systemctl --user disable --now iris.service` + remove the unit; brightness is
  already back to manual via `ExecStopPost`. uv's script cache can be pruned with `uv cache prune`.

## Later: a static Rust binary (STATUS)

If iris is rewritten in Rust (parked — not clearly necessary; see STATUS), the artifact becomes a
**single ~5 MB static-musl binary** — no `uv`, no interpreter, no resolve step. That's the only step
cleaner than today's single script, and the main argument for the rewrite. Until then, `iris.py` +
`uv run --script` is the clean handoff.
