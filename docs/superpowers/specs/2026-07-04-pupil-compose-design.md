# Pupil → Jetpack Compose (design)

_2026-07-04. Approved design for rewriting the Pupil Android app's UI layer in Jetpack Compose
(Material 3 Expressive), plus the prerequisite toolchain upgrade and the home-dir cleanup that
falls out of it. The app's **behaviour** is unchanged — see the original spec
`2026-07-03-pupil-ble-als-design.md` and `android/README.md`. This spec covers only the UI
re-platforming and the toolchain/environment work._

## 1. Goal & scope

Move Pupil fully to Compose, retiring the Views/XML/AppCompat + PreferenceFragmentCompat +
Material Components (MDC) stack — which Google put into maintenance mode at I/O 2026 (Android is
now Compose-first). The non-UI layer is untouched: the BLE sensor-broadcaster behaviour, the
BTHome wire format, the Python receiver, and the cross-language golden-vector contract all stay
exactly as validated on 2026-07-03.

**Success criterion:** the app builds on the current toolchain (AGP 9.2 / Gradle 9.6.1 / Kotlin
2.3.x / compileSdk 36), runs as a single adaptive Compose screen that is functionally identical
to the Views version, adapts to the Find N6's folded (single column) and unfolded (two-pane)
displays, and installs + broadcasts correctly on-device. The `~/.local/opt` hand-installed
toolchain is gone; the JDK is a dnf package in the `dev` toolbox.

**Out of scope:** any change to sensor/BLE behaviour, the freshness contract, the Python side,
the app icon (adaptive + monochrome, kept as-is), and the deadband model (parked separately).
Hinge/tabletop posture awareness is explicitly excluded — width-class adaptation is the whole
win for a utility this simple (YAGNI).

## 2. Toolchain upgrade + home-dir cleanup

The upgrade is a **major migration** (AGP 8.7 → 9.2 crosses AGP 9.0's breaking changes), and it
is also the cleanup — because the only reason the JDK was hand-installed dissolves at Gradle 9.

Target version set (from web research, mid-2026):

| Component | Target | Note |
|---|---|---|
| AGP | 9.2.0 | latest stable |
| Gradle wrapper | 9.6.1 | min for AGP 9.2 is 9.4.1 |
| Kotlin | 2.3.21 | via AGP **built-in Kotlin** — remove `id("org.jetbrains.kotlin.android")` |
| compileSdk / targetSdk | 36 / 36 | Android 16; API 37 too fresh |
| build-tools | 36.0.0 | AGP 9.2 minimum |
| JDK (daemon) | dnf `java-25-openjdk-devel` | **already installed** in the `dev` toolbox (LTS); Gradle 9.x runs on it |

Migration gotchas to handle (AGP 9.0 default flips):
- Built-in Kotlin on by default → drop the Kotlin plugin declaration; Kotlin version rides with AGP.
- `kotlinOptions {}` → `compilerOptions {}`.
- `targetSdk` now defaults to `compileSdk` if unset → set it explicitly (36).
- New DSL on by default → ensure any plugin usage is AGP-9-compatible (we use none exotic).
- R8 flips only matter if minify is enabled (it isn't for debug).
- **Drop `com.google.android.material`** (MDC/Views) entirely — replaced by `androidx.compose.material3`.

Cleanup sequence (after a green build on 36):
1. Install `platforms;android-36` + `build-tools;36.0.0` via sdkmanager (uses the toolbox dnf java-25).
2. Bump wrapper → 9.6.1 (via the existing `./gradlew`), then build.gradle.kts to AGP 9.2 etc.
3. Verify build + JUnit tests green on 36.
4. Remove `~/.local/opt/jdk-21`, `~/.local/opt/gradle-9.6.1`, their symlinks, and `~/.local/bin/gradle`
   (`~/.local/opt` ends empty). Drop the `JAVA_HOME=~/.local/opt/jdk-21` prefix from all invocations
   and from `dev.sh android` — `./gradlew` uses the toolbox dnf java-25.
5. Remove old `build-tools;35.0.0` and `platforms;android-35` from the SDK.

**Kept, deliberately:** `~/Android/Sdk` (platform SDK — adb, build-tools, android.jar; not
distro-packaged, sdkmanager-managed, `$HOME` chosen so it survives toolbox rebuilds; pruned to
platform-36 + build-tools-36), `~/.gradle` (the Maven/dependency cache — the NuGet-cache
equivalent, refills), `~/.android` (adb keys + wireless-debugging pairing).

## 3. Module structure

Package `io.github.genneth.pupil`.

- **Unchanged:** `BthomeEncoder.kt` + `BthomeEncoderTest.kt`; the BTHome format; `contract/bthome-golden.json`
  and the entire Python receiver.
- **Lightly changed:** `PupilService.kt` — keeps the sensor-acquisition ladder, `AdvertisingSet`
  management, coalescing send, heartbeat, and lifecycle fixes. Two changes only: (a) it writes UI
  state into a `MutableStateFlow` (§4) instead of `@Volatile` fields; (b) it receives its config as
  Intent extras at start (§4) rather than reading SharedPreferences.
- **Deleted:** `SettingsActivity.kt`; all `res/layout/*.xml`; `res/xml/prefs.xml`; the preference
  string-arrays in `res/values/strings.xml`.
- **Reduced:** `res/values/themes.xml` is rewritten from the MDC `Theme.Material3Expressive.*`
  theme to a minimal non-MDC window/splash theme (see "Kept resources" below) — all real theming
  moves into `PupilTheme.kt`.
- **New (Compose):**
  - `MainActivity.kt` — thin host: `enableEdgeToEdge()`, `setContent { PupilTheme { PupilApp(...) } }`,
    computes `WindowSizeClass`.
  - `PupilTheme.kt` — Material 3 `dynamicColorScheme` (Material You) with a Bluetooth-blue fallback;
    typography (Display for the hero number).
  - `PupilViewModel.kt` — bridges `PupilState.state` (StateFlow) and `SettingsRepository` to the UI;
    holds permission/dialog UI state; survives fold-induced config changes.
  - `PupilScreen.kt` — the adaptive one-screen UI (§5).
  - `SettingsRepository.kt` — Jetpack DataStore (Preferences) wrapper (§4).
- **Kept resources:** the adaptive launcher icon (`ic_launcher_foreground`, `ic_launcher_monochrome`,
  mipmaps, `pupil_navy`/`bluetooth_blue` colours) and `ic_stat_pupil` (notification). The **minimal
  XML window/splash theme** (§ "Reduced" above) remains for the Activity window pre-draw — Compose
  still needs one — parented on a base platform/androidx theme, not MDC.

## 4. State & data flow

- **Service → UI (StateFlow):** `PupilState` becomes a process-level holder:
  ```
  data class PupilUiState(val running: Boolean, val lux: Float?, val packetId: Int, val sensorRung: String)
  object PupilState {
      private val _state = MutableStateFlow(PupilUiState(false, null, 0, "not started"))
      val state: StateFlow<PupilUiState> = _state.asStateFlow()
      fun update(transform: (PupilUiState) -> PupilUiState) { _state.update(transform) }
  }
  ```
  The service calls `PupilState.update { ... }`; the ViewModel exposes `PupilState.state`; the
  composable uses `collectAsStateWithLifecycle()`. No service binding, no polling, no stale frames;
  fold/unfold recreation simply re-collects.
- **Settings (DataStore):** `SettingsRepository` wraps `DataStore<Preferences>`:
  ```
  data class PupilSettings(val intervalMs: Int, val txPower: TxPower, val deadbandPct: Int, val heartbeatS: Int)
  val settings: Flow<PupilSettings>   // with defaults 400 / LOW / 5 / 10
  suspend fun setInterval(...); setTxPower(...); setDeadband(...); setHeartbeat(...)
  ```
  `TxPower` is an enum with an `AdvertisingSetParameters` mapping. The UI reads/writes reactively.
- **UI → Service (Intent extras):** on Start, the ViewModel snapshots current `PupilSettings` and
  passes the four values as Intent extras to `PupilService`. The service reads primitives from the
  Intent — no DataStore dependency, no blocking reads, one-way flow. All four knobs now apply
  uniformly on next service start (the Views version live-reloaded the heartbeat mid-run from
  SharedPreferences; that special case is dropped for a consistent one-way model).

## 5. The one adaptive screen

`PupilScreen` renders the same content in both layouts; only the arrangement differs, keyed on
`WindowSizeClass.widthSizeClass`:

- **Compact (folded cover display):** a single scrolling `Column` — hero lux + status, Start/Stop,
  the four settings controls, battery-exemption, sensor report.
- **Expanded (unfolded inner display):** a two-pane `Row` — live readout (hero lux + status +
  Start/Stop) on the left, settings + battery-exemption + sensor report on the right.
- **Medium:** treated as compact (single column, centred with a max content width).

Details:
- **Material 3 Expressive** via the Compose BOM + `androidx.compose.material3`. Hero lux in a Display
  text style with tabular figures; status in a body/label style with `onSurfaceVariant`.
- **Dynamic colour** via `dynamicColorScheme(context)` (Material You), Bluetooth-blue seed fallback.
- **Settings controls:** inline M3 (exposed-dropdown menus or segmented buttons) writing to DataStore;
  the four knobs (interval, TX power, deadband, heartbeat) with the current labels, incl. the
  heartbeat "needs receiver --stale-after ≥N" warnings.
- **Permissions:** `rememberLauncherForActivityResult(RequestMultiplePermissions())`; rationale and
  battery-exemption as composable `AlertDialog`s (explain-first); graceful refused state (no
  settings deep-link). **Predictive back** via `BackHandler` where relevant; manifest
  `enableOnBackInvokedCallback` stays.
- **Edge-to-edge:** `enableEdgeToEdge()` + `Modifier.safeDrawingPadding()` (Compose handles insets;
  no manual listener).

## 6. Testing

- **Keep** `BthomeEncoderTest` — the cross-language golden-vector contract, untouched.
- **Add** a `SettingsRepository` unit test (defaults + `TxPower` enum↔key mapping) using DataStore's
  test scope.
- **No Compose UI-test instrumentation** for a one-screen app — a deliberate scope choice, not an
  omission. The adaptive layout and visuals are verified on-device (build → wireless-adb install →
  eyeball folded + unfolded), the same acceptance rhythm as the original build.
- The Python receiver tests and `bthome-golden.json` are untouched and must stay green
  (`./dev.sh check`).

## 7. Risks

| Risk | Mitigation |
|---|---|
| AGP 8.7→9.2 major migration breaks the build | Incremental: bump wrapper → AGP → Compose deps with a build-verify loop; the Upgrade Assistant's changes are small for a minimal module. |
| Built-in Kotlin / new-DSL surprises | Follow the documented default-flips (§2); escape hatches exist (`android.builtInKotlin=false`, `android.newDsl=false`) if needed. |
| Foldable layout only testable on-device | On-device acceptance covers it; `WindowSizeClass` also previewable in Android Studio if needed. |
| Losing the golden-vector contract in the churn | `BthomeEncoder` + its test are explicitly untouched; `./dev.sh check` gates the Python side. |

## 8. Key sources

- AGP/Gradle/Kotlin versions: research 2026-07-04 (AGP 9.2 notes, Gradle compatibility matrix).
- Compose adaptive layouts: `androidx.compose.material3.windowsizeclass` / `androidx.window`.
- Compose-first / MDC maintenance-mode: Android Developers Blog, I/O 2026.
