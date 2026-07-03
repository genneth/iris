# Pupil — the phone as a BLE ambient-light sensor

iris's star pupil: broadcasts the phone's ambient-light sensor as BTHome v2 BLE
adverts (non-connectable; receivable by `python/scripts/ble_als_probe.py`, Home
Assistant, or anything BTHome-aware). Spec:
`docs/superpowers/specs/2026-07-03-pupil-ble-als-design.md`.

## Build & install

    toolbox run -c dev bash -lc 'cd ~/iris/android && JAVA_HOME=~/.local/opt/jdk-21 ./gradlew :app:assembleDebug'
    toolbox run -c dev bash -lc '~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk'

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

## Find N6 acceptance results (2026-07-__)

- Sensor report: <record: wakeup ALS present? name? fifoMax? rung used>
- Screen-off torch test: <record: PASS / killed / frozen-value>
- RSSI walk @ TX low (−15 dBm): desk ___ dBm · sofa ___ · next room ___ ·
  pocket ___ → chosen --min-rssi: ___
