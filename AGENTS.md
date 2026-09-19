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

- **Software rendering only.** `scripts/android-loop` leases Almon's shared emulator;
  it cannot select a host GPU or launch a project-owned AVD.
- **Toolchain versions are allocated by almon**, not chosen here. Follow
  `~/almon/conformance/intent.py` → `ANDROID_TOOLCHAIN`, including the paired
  Kotlin and Compose pins. Change all consumers together through Almon.
- **`~/.gradle/gradle.properties` overrides the project file** for host-owned JVM,
  worker and caching policy; see the shared skill before changing these settings.
- **One emulator at a time.** Preserve the controller's lease token and pass it
  on every device lookup and release. Never bypass a missing or superseded token.
  The shared skill owns the memory policy and recovery procedure.

Verify Android changes with `~/almon/conformance/check -k android` — it must be green.

## The closed loop

```bash
android/scripts/android-loop up [folded|unfolded]   # shared software-rendered emulator
android/scripts/android-loop --help
./dev.sh android                                    # project entry point
```

## The daemon side

`python/iris.py` is a **single-file PEP 723 script** — run it with `uv run`, never the host
`python3`. Its deployed unit is byte-pinned by almon conformance to `python/deploy/iris.service`,
so if you change the unit, expect `~/almon/conformance/check` to hold you to it.

Host and OS questions belong to `~/almon/`; see `~/almon/layers/iris.md` for how this project is
deployed on molly.
