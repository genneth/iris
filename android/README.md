# Pupil — the phone as a BLE ambient-light sensor

iris's star pupil: broadcasts the phone's ambient-light sensor as BTHome v2 BLE
adverts (non-connectable; receivable by `python/scripts/ble_als_probe.py`, Home
Assistant, or anything BTHome-aware). Spec:
`docs/superpowers/specs/2026-07-03-pupil-ble-als-design.md`.

## Build & install

    toolbox run -c dev bash -lc 'cd ~/iris/android && JAVA_HOME=~/.local/opt/jdk-21 ./gradlew :app:assembleDebug'
    toolbox run -c dev bash -lc 'cd ~/iris/android && ~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk'

Toolchain: gradle 9.6.1 bootstrap (~/.local/bin), wrapper Gradle 8.10.2, Temurin JDK 21 at ~/.local/opt/jdk-21, SDK at ~/Android/Sdk (platforms;android-35, build-tools;35.0.0). Unit tests: `./dev.sh android`.

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
