# Pupil — the phone as a BLE ambient-light sensor

iris's star pupil: broadcasts the phone's ambient-light sensor as BTHome v2 BLE
adverts (non-connectable; receivable by `python/scripts/ble_als_probe.py`, Home
Assistant, or anything BTHome-aware). Specs:
`docs/superpowers/specs/2026-07-03-pupil-ble-als-design.md` (behaviour) and
`docs/superpowers/specs/2026-07-04-pupil-compose-design.md` (Compose UI).

The UI is **Jetpack Compose** (Material 3, dynamic colour, one adaptive screen —
single column folded, two-pane on the Find N6's unfolded inner display).

## Build & install

    toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew :app:assembleDebug'
    toolbox run -c dev bash -lc 'cd ~/iris/android && ~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk'

Toolchain (all in the `dev` toolbox): AGP 9.2.0 / Gradle wrapper 9.6.1 / Kotlin
2.3.21 (AGP built-in) / compileSdk+targetSdk 36. Runs on the toolbox's dnf
`java-25-openjdk` — no `JAVA_HOME` prefix. Android SDK at `~/Android/Sdk`
(platforms;android-36, build-tools;36.0.0). Unit tests: `./dev.sh android`.

`./dev.sh android` is the local Android gate: generated/property unit tests, Android lint, and
deterministic Compose screenshots at the Find N6's folded and unfolded dimensions. It runs in
`dev`; the Python/Rust gate remains `./dev.sh check`.

## Foldable development loop

Pupil treats the Find N6's cover and inner displays as two first-class compositions: Compact
windows use the folded single-column instrument; Medium and Expanded windows use the unfolded
two-pane workspace. This deliberately handles the inner display reporting roughly 814–873 dp as
system bars and posture change.

The project-local loop uses a disposable API 36 AVD under `target/`, selects it by both
`emulator-*` serial and AVD name, and can emulate both widths without touching a connected phone.
It uses the same proven rendering path as Shamoji and Hartley: NVIDIA host rendering inside QEMU's
`-no-window` headless binary. The emulator uses Xwayland only as its EGL bridge—this installed build
has no native Wayland headless renderer—but Qt never creates a window, so nothing opens or flashes
in the desktop session. `start software` retains the established SwiftShader compatibility fallback.

    android/scripts/android-loop up
    android/scripts/android-loop capture folded-ready
    android/scripts/android-loop unfolded
    android/scripts/android-loop capture unfolded-ready
    android/scripts/android-loop test-ui
    android/scripts/android-loop stop

The emulator closes the build, interaction, adaptive-layout and release-shrinker loop. It cannot
prove the real Find N6's ALS, BLE controller, Doze or ColorOS behaviour; the screen-off torch test
below remains the hardware acceptance gate.

## Release signing

Official builds use Pupil's alias in Almon's shared, backed-up Android signing keystore. Local
credentials are in the git-ignored, mode-0600 `keystore.properties`; a checkout without them still
produces an unsigned release, but cannot produce an official APK. Pupil's permanent certificate is:

    654746ec3c14cac3498052ac5cf7d7a8a02b87a85cdcd96cd9a19d938b99c6e5

Build and export version 0.3.0 with:

    android/scripts/android-loop verify-release

The command removes any stale signed output before building, then refuses the result unless it is
non-debuggable, version code 3, signed by exactly that certificate, and carries a v3 signature. A
successful run exports `android/dist/pupil-0.3.0-release.apk` plus its SHA-256 file. To exercise the
shrunk artifact on the disposable emulator, use `install-release`.

The Find N6 was migrated from the historical debug-key build to the permanent key with 0.2.0 (one
explicit uninstall, now done). Later versions update in place over it. The emulator loop never
automates or targets this physical-phone migration.

## ColorOS survival checklist (do all of these once)

1. In-app: tap **Battery exemption…** and allow (also keeps wakelocks honoured in Doze).
2. Settings → Battery → App battery management → Pupil: **Allow auto-launch**,
   **Allow foreground activity**, **Allow background activity**, **Optimize
   battery use → off**.
3. Battery → Advanced settings: **Sleep stand-by optimization → off**.
4. Recents → long-press Pupil's card → **Lock** (stops ColorOS silently
   reverting the exemption).
5. Decline any "high background power consumption" prompt about Pupil.

## Find N6 acceptance results (2026-07-03, ColorOS 16)

- **Sensor report:** NO wakeup `TYPE_LIGHT` variant (`getDefaultSensor(TYPE_LIGHT, true)` =
  null); default ALS = "OPLUS Fusion Light Sensor Next Gen" (vendor OPLUS, maxRange 65535 lx,
  fifoMax 0, isWakeUp false) → **rung 2: non-wakeup + partial wakelock**. A wakeup lux sensor
  exists but only as vendor type `qti.sensor.lux_aod` (AOD), not reachable via `TYPE_LIGHT`.
- **First-sighting smoke test: PASS** — adverts decoded by the probe within seconds of Start;
  lux tracked cover/uncover live. (Advert deliberately carries NO Flags AD element and NO
  local name — Android can't set Flags on a non-connectable legacy advertising set; HA/BlueZ
  PASSIVE-mode interop therefore unverified — see spec §3.)
- **Screen-off torch test: PASS, with one load-bearing caveat.** With only the battery
  exemption (checklist item 1), ColorOS's app freezer suspended Pupil **~30 s after lock**:
  the controller kept repeating the last advert (packet id frozen) and the receiver correctly
  reported `stale`; the app resumed instantly on unlock. After granting **Allow background
  activity** (item 2), a full 11-minute locked run stayed `fresh` throughout (heartbeats
  continuous, packet ids advancing), and a screen-off raise-to-light registered (22 → 94 lx and
  back) — so the non-wakeup ALS genuinely streams with the display off on this device. Items
  3–5 were NOT needed for an 11-minute run; revisit if longer idle periods (sleep-standby
  hours) freeze it again.
- **RSSI walk @ TX low (−15 dBm):** desk −64…−75 dBm · across room −86…−93 · pocket at
  distance / next room / stairs −92…−98 (a few packets still audible) · back at desk
  re-admitted instantly at −65. → **chosen --min-rssi: −75 (the default; admit −70 / drop −80
  hysteresis needed no tuning).**
- **TX power note:** ULTRA_LOW (−21 dBm) was tried and rejected — desk RSSI sat at −72…−76
  with fades to −92, i.e. *below the admit bar*, so after any deep fade the phone could never
  re-admit at desk range. LOW is the shipping default.
