# iris — agent guide

Auto-brightness for this laptop from a real ambient-light measurement taken by the phone.
`README.md` is the orientation; this file is the "before you touch anything" note.

| Component | What | Where |
|---|---|---|
| **pupil** | Android app broadcasting phone lux as BTHome BLE adverts | `android/` |
| **iris** | the shipping `systemd --user` daemon, one PEP 723 file | `python/iris.py` |
| **retina** | webcam-as-ALS path, parked, kept for provenance | `python/src/retina/` |

## Android work: read molly's host-wide rules first

**Before building, testing or starting an emulator for pupil, read
`~/almon/tools/molly-android-dev/SKILL.md`.** Claude and Codex sessions get it automatically as the
`molly-android-dev` skill; other agents should open the file.

It is not optional background. The rules it carries have already been learned the hard way on this
machine, and they bind this repo:

- **Never let a headless emulator take the host GPU.** Pupil's launcher used to default to
  `-gpu host`; the identical default in a sibling project deadlocked the GPU the compositor draws
  on and cost a hard reset on 2026-08-13. `scripts/android-loop` now defaults to software, and
  conformance fails if that regresses.
- **Toolchain versions are allocated by almon**, not chosen here. Gradle, AGP, the Compose plugin
  and `compileSdk` must match `~/almon/conformance/intent.py` → `ANDROID_TOOLCHAIN`. Do not bump
  one project alone — that is how this repo ended up on its own Gradle version, forking a second
  3 GB daemon and skipping a shared build cache.
- **`~/.gradle/gradle.properties` overrides `android/gradle.properties`** for `jvmargs`,
  `caching`, `workers.max` and `daemon.idletimeout`. Editing those here has no effect on molly.
  The header in that file says so.
- **Kotlin comes from the Compose plugin, not AGP.** Never add a `buildscript` classpath override
  for `kotlin-gradle-plugin`; this repo carried one until 2026-08-13 and it skewed KGP against the
  Compose compiler rather than pinning anything.
- **One emulator at a time.** `user-1000.slice` is capped at 24 G and an emulator peaks at 6–9 GB.

Verify Android changes with `~/almon/conformance/check -k android` — it must be green.

## The closed loop

```bash
android/scripts/android-loop up [software|host]     # software is the default, and should stay so
android/scripts/android-loop --help
./dev.sh android                                    # project entry point
```

## The daemon side

`python/iris.py` is a **single-file PEP 723 script** — run it with `uv run`, never the host
`python3`. Its deployed unit is byte-pinned by almon conformance to `python/deploy/iris.service`,
so if you change the unit, expect `~/almon/conformance/check` to hold you to it.

Host and OS questions belong to `~/almon/`; see `~/almon/layers/iris.md` for how this project is
deployed on molly.
