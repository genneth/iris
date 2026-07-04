# iris (rust) — deployment build

A stub for an eventual low-footprint rewrite of the **iris daemon** (`python/iris.py`) as a small
static-musl binary deployed as a `systemd --user` service. The port would be BLE scan (`btleplug`)
+ the D-Bus brightness sink (`zbus`); the pure core (curve/config/controller) is trivial to carry
over. The pull is packaging cleanliness — a single ~5 MB binary is the cleanest possible artifact to
hand almon (no `uv`, no interpreter). See the "Rewrite iris in Rust?" note in
[`../STATUS.md`](../STATUS.md) and [`../docs/iris/PACKAGING.md`](../docs/iris/PACKAGING.md).

**Status:** stub — not started, and not clearly necessary (the single-file Python + `uv run` deploys
cleanly today). Park until footprint or packaging actually bites.

## Build note (immutable host)

`cargo fmt`, `cargo clippy`, and `cargo check` run fine **on the host** (no linking step), so the
commit-time gates work everywhere. `cargo build` needs a C linker driver, which the immutable host
lacks — build the actual binary inside the `dev` toolbox (`toolbox run -c dev cargo build`), as a
static-musl artifact.
