# iris — packaging & deployment (for almon)

> **Ownership.** Installation and lifecycle management of software on this machine is
> [**almon**](../../../almon)'s domain (`~/almon`) — see its `AGENTS.md`. This repo does **not**
> install anything; it aims to hand almon a *clean, self-contained artifact* + a unit file + an
> example config, with no build step required on the immutable host. This note sketches what that
> artifact should be and why.

## What iris is, deployment-wise

- A **`systemd --user`** daemon bound to `graphical-session.target`. **No root, no udev, no system
  packages** — it talks to the session bus (`org.gnome.Shell.Brightness`) and scans BLE via BlueZ.
- **Runtime deps: just `bleak` + `dbus-fast`** (`dbus-fast` rides in transitively with `bleak`).
  Everything else — the `iris` daemon (~460 lines: curve, config, controller, sink) and the `pupil`
  receive lib (~140 lines) — is our own pure Python. No compiled extensions.
- **Config:** optional `~/.config/iris/config.toml` (user-owned; `deploy/config.example.toml` is the
  starting point). Nothing to install for config.
- **Unit:** [`../../python/deploy/iris.service`](../../python/deploy/iris.service) — `ExecStart` runs
  the daemon; `ExecStopPost` sends `SetAutoBrightnessTarget(-1)` so brightness always returns to
  manual on stop/crash.

## "Could it just be one file?" — yes, and it matters

The daemon's real dependency surface is tiny, so it is a genuine candidate for a **single PEP 723
script**: one `iris.py` with inline metadata that `uv` resolves and runs, no venv to manage.

```python
# /// script
# requires-python = ">=3.13"
# dependencies = ["bleak>=0.23", "dbus-fast>=2.24"]
# ///
```

Then `uv run iris.py` **Just Works** (uv builds an ephemeral, cached env). The honest catch: the
source is ~600 lines across 6 small, individually unit-tested modules (`iris/*` + `pupil`); collapsing
them into one file trades that structure and test granularity for a single dropped artifact. So it's
attractive for *deployment* but not how we want to *develop*.

## The three packaging paths (in order of recommendation)

1. **`uv tool install` from the repo/wheel — recommended today.** Keeps the split, tested source;
   gives almon a one-liner and a clean `iris` command (a `[project.scripts] iris = "iris.daemon:run"`
   entry point is wired in `pyproject.toml`). uv owns the isolated env + deps.
   ```sh
   uv tool install --from git+file://$HOME/iris#subdirectory=python iris   # or from a built wheel
   # ExecStart=%h/.local/bin/iris   (uv puts the console script there)
   ```
   Update = `uv tool upgrade iris`. No host compiler, no venv babysitting, structure + tests intact.

2. **PEP 723 single file** — the simplest possible drop-in: almon copies one `iris.py` to
   `~/.local/bin/` and the unit runs `ExecStart=uv run %h/.local/bin/iris.py`. Cost: needs a small
   **bundle step** (the split modules concatenated/zipapp'd) with drift risk vs the real source, so
   only worth it if path 1's uv-managed env is unwanted. Feasible precisely because deps are just two.

3. **Static Rust binary — future, cleanest of all.** If iris is rewritten in Rust (STATUS: parked,
   not clearly necessary), the artifact becomes a **single ~5 MB static-musl binary** — no
   interpreter, no env, no uv. almon drops the binary + unit and is done. This is the strongest
   argument for the rewrite; until then, path 1 is the pragmatic clean handoff.

## What almon needs from us (the clean contract)

- A **versioned artifact** buildable **in the `dev` toolbox**, not on the host: a wheel (`uv build`)
  or a tagged checkout — path 1 consumes either.
- The **unit file** (`deploy/iris.service`) and **example config** (`deploy/config.example.toml`),
  both already in-repo.
- **No host build step, no root, no system packages** — user-scoped (`uv tool` / `~/.local`,
  `systemctl --user`). This fits molly's model (Flatpaks are for GUI apps; iris is a headless
  session daemon, so it's a user-scoped tool + unit, not a Flatpak).
- A one-line **uninstall**: `uv tool uninstall iris` + remove the unit; brightness is already back to
  manual via `ExecStopPost`.
