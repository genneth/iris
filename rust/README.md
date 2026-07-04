# iris (rust) — deployment build

The eventual low-footprint, shrink-wrapped daemon. The Python project in [`../python`](../python)
is the reference/MVP; this crate will port the validated camera→lux + virtual-HID-ALS logic into a
small static binary deployed as a `systemd --user` service.

**Status:** stub — not started. See [`../STATUS.md`](../STATUS.md).

## Build note (immutable host)

`cargo fmt`, `cargo clippy`, and `cargo check` run fine **on the host** (no linking step), so the
commit-time gates work everywhere. `cargo build` needs a C linker driver, which the immutable host
lacks — build the actual binary inside the `dev` toolbox (`toolbox run -c dev cargo build`), as a
static-musl artifact. See [`../DESIGN.md`](../docs/retina/DESIGN.md) §7.
