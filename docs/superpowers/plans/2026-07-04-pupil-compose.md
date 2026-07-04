# Pupil → Jetpack Compose Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite Pupil's UI layer in Jetpack Compose (Material 3, adaptive foldable layout, DataStore, StateFlow), on the current Android toolchain, and clean up the hand-installed home-dir toolchain — without changing any sensor/BLE behaviour. Spec: `docs/superpowers/specs/2026-07-04-pupil-compose-design.md`.

**Architecture:** Sequence the AGP 8.7→9.2 major migration first with the existing Views UI intact (isolate toolchain risk), then swap the UI to Compose (StateFlow from the service, DataStore-backed settings, config passed to the service via Intent extras, one `WindowSizeClass`-adaptive screen), then remove the redundant `~/.local/opt` toolchain and prune the SDK. The service/encoder/BTHome/Python layers are untouched.

**Tech Stack:** Kotlin 2.3.21, AGP 9.2.0, Gradle 9.6.1, compileSdk/targetSdk 36, Jetpack Compose (Material 3 + adaptive/window-size-class), DataStore Preferences, Coroutines/StateFlow. Builds in the `dev` toolbox on dnf `java-25-openjdk`.

## Global Constraints

- **Environment (molly):** all gradle/adb/sdkmanager runs happen in the `dev` toolbox: `toolbox run -c dev bash -lc '...'`. The Android SDK is at `~/Android/Sdk`. From Task 1 onward, gradle runs on the toolbox's **dnf `java-25-openjdk`** (already installed) — **no `JAVA_HOME=~/.local/opt/...` prefix**. Generous timeouts (600000 ms); first runs download AGP/Compose deps.
- **Version set (exact):** AGP `9.2.0`, Gradle wrapper `9.6.1`, Kotlin `2.3.21`, compileSdk `36`, targetSdk `36`, minSdk `31`, build-tools `36.0.0`.
- **Compose compiler setup:** apply the explicit `org.jetbrains.kotlin.plugin.compose` plugin at the Kotlin version (the well-documented Compose setup) alongside `org.jetbrains.kotlin.android`. **This is a deliberate deviation from the spec's "AGP built-in Kotlin"** — built-in Kotlin's interaction with the Compose compiler plugin is newer/less-documented, and the explicit-plugin path is lower-risk; it still lands Kotlin 2.3.21. Flag for the reviewer.
- **Compose BOM:** use `androidx.compose:compose-bom:2026.06.01` (pins all `androidx.compose.*` artifact versions). If it fails to resolve, use the latest stable BOM from Google Maven and note the substitution — a resolution error surfaces immediately in the build-verify step.
- **Package:** `io.github.genneth.pupil`. App name **Pupil**.
- **Unchanged, must stay green:** `BthomeEncoder.kt` + `BthomeEncoderTest.kt` (the cross-language golden-vector contract), the BTHome wire format, `contract/bthome-golden.json`, and the entire Python receiver. `./dev.sh check` (Python gate) must stay green throughout.
- **Behaviour parity:** the Compose app is functionally identical to the Views version (original spec `2026-07-03-pupil-ble-als-design.md`): sensor ladder, advertising, heartbeat, freshness, the four settings knobs with their labels (incl. heartbeat "needs receiver --stale-after ≥N" warnings), explain-first permission + battery dialogs, no settings deep-link on refusal.
- **Android unit tests:** `./dev.sh android` (runs `:app:testDebugUnitTest` in the toolbox). NOT part of `./dev.sh check` (host has no JVM/SDK).
- **Commit after every task.** The pre-commit hook runs the Python gate automatically.

---

### Task 1: Toolchain migration (AGP 9.2 / Gradle 9.6.1 / Kotlin 2.3.21 / SDK 36) — Views app intact

Prove the major AGP migration in isolation: keep the existing Views UI + Material Components, only move the toolchain. If this builds and the app still runs, the Compose work starts from a known-good base.

**Files:**
- Modify: `android/build.gradle.kts`, `android/app/build.gradle.kts`, `android/gradle/wrapper/gradle-wrapper.properties`, `android/gradle.properties`
- Modify (only if the migration requires): `android/app/src/main/java/io/github/genneth/pupil/*.kt` (`kotlinOptions`→`compilerOptions` fallout)

**Interfaces:**
- Produces: a project that builds on AGP 9.2.0 / Gradle 9.6.1 / Kotlin 2.3.21 / compileSdk 36, with the existing Views UI and `com.google.android.material` still present.

- [ ] **Step 1: Install SDK platform 36 + build-tools 36**

```bash
toolbox run -c dev bash -lc '~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;36.0.0"'
```
Expected: downloads and reports done. (Uses dnf java-25 already on PATH.)

- [ ] **Step 2: Bump the Gradle wrapper to 9.6.1**

Using the existing wrapper (still on 8.10.2, JDK-21) to rewrite itself, then confirm 9.6.1 runs on dnf java-25:
```bash
toolbox run -c dev bash -lc 'cd ~/iris/android && JAVA_HOME=~/.local/opt/jdk-21 ./gradlew wrapper --gradle-version 9.6.1'
toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --version'
```
Expected: second command prints `Gradle 9.6.1` and `Launcher JVM: 25.x` (dnf java-25, no JAVA_HOME prefix). If it instead reports a JDK error, the toolbox default `java` isn't 25 — set `org.gradle.java.home` in `gradle.properties` to the dnf JDK path (`/usr/lib/jvm/java-25-openjdk`) and note it.

- [ ] **Step 3: Root build file → AGP 9.2.0, Compose Kotlin plugin**

`android/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
```

- [ ] **Step 4: App build file — SDK 36, compilerOptions, keep MDC for now**

`android/app/build.gradle.kts` (Compose NOT enabled yet — that's Task 3):
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.genneth.pupil"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.genneth.pupil"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
```
Note: `kotlinOptions { jvmTarget = "17" }` is replaced by the `kotlin { compilerOptions { ... } }` block above.

- [ ] **Step 5: Build + unit tests (Views app, new toolchain)**

```bash
toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:testDebugUnitTest :app:assembleDebug'
```
Expected: `BUILD SUCCESSFUL`, 7 JUnit tests pass. If the AGP-9 default-flips bite (e.g. new-DSL plugin incompat), the escape hatch is `android.newDsl=false` / `android.builtInKotlin=false` in `gradle.properties` — add only if needed and note it. R8 flips don't apply (debug, no minify).

- [ ] **Step 6: Commit**

```bash
cd ~/iris && git add android/ && git commit -m "Pupil app: migrate toolchain to AGP 9.2 / Gradle 9.6.1 / Kotlin 2.3.21 / SDK 36 (Views UI intact)"
```

---

### Task 2: SettingsRepository (DataStore) + settings model

Standalone, unit-testable, no UI coupling. Defines the settings model and the enum↔key mappings the UI and the Intent-passing will use.

**Files:**
- Modify: `android/app/build.gradle.kts` (DataStore + coroutines-test deps)
- Create: `android/app/src/main/java/io/github/genneth/pupil/Settings.kt`
- Test: `android/app/src/test/java/io/github/genneth/pupil/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces (consumed by Tasks 4–5):
  - `enum class TxPower(val key: String, val advertiseLevel: Int)` with `ULTRA_LOW`, `LOW`, `MEDIUM`, `HIGH` mapping to `AdvertisingSetParameters.TX_POWER_*`; `companion fun fromKey(key: String): TxPower` (defaults to `LOW`).
  - `data class PupilSettings(val intervalMs: Int, val txPower: TxPower, val deadbandPct: Int, val heartbeatS: Int)` with defaults `400 / LOW / 5 / 10`.
  - `class SettingsRepository(context: Context)` exposing `val settings: Flow<PupilSettings>` and suspend setters `setIntervalMs(Int)`, `setTxPower(TxPower)`, `setDeadbandPct(Int)`, `setHeartbeatS(Int)`.

- [ ] **Step 1: Add DataStore + coroutines-test dependencies**

Add to `android/app/build.gradle.kts` `dependencies`:
```kotlin
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
```

- [ ] **Step 2: Write the failing test**

`android/app/src/test/java/io/github/genneth/pupil/SettingsRepositoryTest.kt`:
```kotlin
package io.github.genneth.pupil

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun txPowerRoundTrips() {
        for (p in TxPower.entries) {
            assertEquals(p, TxPower.fromKey(p.key))
        }
    }

    @Test
    fun txPowerUnknownKeyDefaultsToLow() {
        assertEquals(TxPower.LOW, TxPower.fromKey("nonsense"))
        assertEquals(TxPower.LOW, TxPower.fromKey(""))
    }

    @Test
    fun defaultsMatchSpec() {
        val d = PupilSettings()
        assertEquals(400, d.intervalMs)
        assertEquals(TxPower.LOW, d.txPower)
        assertEquals(5, d.deadbandPct)
        assertEquals(10, d.heartbeatS)
    }
}
```

- [ ] **Step 3: Run to verify failure**

```bash
toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:testDebugUnitTest'
```
Expected: compile failure — `unresolved reference: TxPower` / `PupilSettings`.

- [ ] **Step 4: Implement `Settings.kt`**

`android/app/src/main/java/io/github/genneth/pupil/Settings.kt`:
```kotlin
package io.github.genneth.pupil

import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class TxPower(val key: String, val advertiseLevel: Int) {
    ULTRA_LOW("ultra_low", AdvertisingSetParameters.TX_POWER_ULTRA_LOW),
    LOW("low", AdvertisingSetParameters.TX_POWER_LOW),
    MEDIUM("medium", AdvertisingSetParameters.TX_POWER_MEDIUM),
    HIGH("high", AdvertisingSetParameters.TX_POWER_HIGH);

    companion object {
        fun fromKey(key: String): TxPower = entries.firstOrNull { it.key == key } ?: LOW
    }
}

data class PupilSettings(
    val intervalMs: Int = 400,
    val txPower: TxPower = TxPower.LOW,
    val deadbandPct: Int = 5,
    val heartbeatS: Int = 10,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pupil_settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val interval = intPreferencesKey("interval_ms")
        val txPower = stringPreferencesKey("tx_power")
        val deadband = intPreferencesKey("deadband_pct")
        val heartbeat = intPreferencesKey("heartbeat_s")
    }

    val settings: Flow<PupilSettings> = store.data.map { p ->
        PupilSettings(
            intervalMs = p[Keys.interval] ?: 400,
            txPower = TxPower.fromKey(p[Keys.txPower] ?: TxPower.LOW.key),
            deadbandPct = p[Keys.deadband] ?: 5,
            heartbeatS = p[Keys.heartbeat] ?: 10,
        )
    }

    suspend fun setIntervalMs(v: Int) = store.edit { it[Keys.interval] = v }
    suspend fun setTxPower(v: TxPower) = store.edit { it[Keys.txPower] = v.key }
    suspend fun setDeadbandPct(v: Int) = store.edit { it[Keys.deadband] = v }
    suspend fun setHeartbeatS(v: Int) = store.edit { it[Keys.heartbeat] = v }
}
```

- [ ] **Step 5: Run tests**

```bash
toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:testDebugUnitTest'
```
Expected: `BUILD SUCCESSFUL`, 10 tests pass (7 existing + 3 new).

- [ ] **Step 6: Commit**

```bash
cd ~/iris && git add android/ && git commit -m "Pupil app: DataStore-backed SettingsRepository + TxPower model (tested)"
```

---

### Task 3: Enable Compose + PupilTheme (no UI wired yet)

**Files:**
- Modify: `android/app/build.gradle.kts` (Compose buildFeatures + deps + plugin)
- Create: `android/app/src/main/java/io/github/genneth/pupil/PupilTheme.kt`

**Interfaces:**
- Produces: `@Composable fun PupilTheme(content: @Composable () -> Unit)` — Material 3 theme with dynamic colour (Material You) and a Bluetooth-blue fallback.

- [ ] **Step 1: Enable Compose in the app build file**

Add the compose plugin to `android/app/build.gradle.kts` `plugins`:
```kotlin
    id("org.jetbrains.kotlin.plugin.compose")
```
Add to `android { }`:
```kotlin
    buildFeatures { compose = true }
```
Add to `dependencies` (Compose BOM pins the rest):
```kotlin
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
```

- [ ] **Step 2: Write `PupilTheme.kt`**

`android/app/src/main/java/io/github/genneth/pupil/PupilTheme.kt`:
```kotlin
package io.github.genneth.pupil

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val BluetoothBlue = Color(0xFF0082FC)
private val FallbackLight = lightColorScheme(primary = BluetoothBlue)
private val FallbackDark = darkColorScheme(primary = BluetoothBlue)

@Composable
fun PupilTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // minSdk 31, so dynamic colour is always available; guard anyway for clarity.
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) FallbackDark else FallbackLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
```

- [ ] **Step 3: Build (compiles, unused theme)**

```bash
toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:assembleDebug'
```
Expected: `BUILD SUCCESSFUL`. If the Compose BOM version fails to resolve, set it to the latest stable from Google Maven and note the substitution.

- [ ] **Step 4: Commit**

```bash
cd ~/iris && git add android/ && git commit -m "Pupil app: enable Compose, add Material 3 dynamic-colour PupilTheme"
```

---

### Task 4: State → StateFlow, service config via Intent, minimal Compose shell

The atomic UI-swap compile boundary: the app becomes Compose here, with a minimal screen (readout + Start/Stop + permission handling). Full screen/settings follow in Task 5.

**Files:**
- Rewrite: `android/app/src/main/java/io/github/genneth/pupil/PupilState.kt` (→ StateFlow)
- Modify: `android/app/src/main/java/io/github/genneth/pupil/PupilService.kt` (write via StateFlow; config from Intent extras)
- Delete: `PupilConfig.kt`, `SettingsActivity.kt`, `res/layout/activity_main.xml`, `res/layout/activity_settings.xml`, `res/xml/prefs.xml`, `res/drawable/ic_arrow_back.xml`
- Rewrite: `android/app/src/main/java/io/github/genneth/pupil/MainActivity.kt` (→ Compose)
- Create: `android/app/src/main/java/io/github/genneth/pupil/PupilViewModel.kt`
- Modify: `res/values/themes.xml` (drop MDC parent), `res/values/strings.xml` (drop pref arrays), `AndroidManifest.xml` (remove SettingsActivity), `android/app/build.gradle.kts` (drop MDC, appcompat, preference deps)

**Interfaces:**
- Produces (consumed by Task 5):
  - `data class PupilUiState(val running: Boolean, val lux: Float?, val packetId: Int, val sensorRung: String)`
  - `object PupilState { val state: StateFlow<PupilUiState>; fun update(f:(PupilUiState)->PupilUiState) }`
  - `PupilService` companion Intent-extra keys: `EXTRA_INTERVAL_MS` (Int), `EXTRA_TX_LEVEL` (Int), `EXTRA_DEADBAND_PCT` (Int), `EXTRA_HEARTBEAT_S` (Int); helper `fun startIntent(context, PupilSettings): Intent`.
  - `class PupilViewModel(app): AndroidViewModel` exposing `val ui: StateFlow<PupilUiState>`, `val settings: StateFlow<PupilSettings>`, `fun start()`, `fun stop()`, setters delegating to `SettingsRepository`.

- [ ] **Step 1: Rewrite `PupilState.kt` as a StateFlow holder**

```kotlin
package io.github.genneth.pupil

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PupilUiState(
    val running: Boolean = false,
    val lux: Float? = null,
    val packetId: Int = 0,
    val sensorRung: String = "not started",
)

/** Process-level bridge from the service to the UI. Service writes, UI collects. */
object PupilState {
    private val _state = MutableStateFlow(PupilUiState())
    val state: StateFlow<PupilUiState> = _state.asStateFlow()
    fun update(transform: (PupilUiState) -> PupilUiState) = _state.update(transform)
}
```

- [ ] **Step 2: Update `PupilService.kt` — StateFlow writes + Intent config**

Replace every `PupilState.<field> = x` write with `PupilState.update { it.copy(...) }`, delete the `PupilConfig`/`config` usage, and read config from the Intent. Specifically:

- Add companion members:
```kotlin
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_TX_LEVEL = "tx_level"
        const val EXTRA_DEADBAND_PCT = "deadband_pct"
        const val EXTRA_HEARTBEAT_S = "heartbeat_s"
        private const val MIN_GAP_MS = 500L
        private const val DEADBAND_ABS_LUX = 1f

        fun startIntent(context: Context, s: PupilSettings): Intent =
            Intent(context, PupilService::class.java)
                .putExtra(EXTRA_INTERVAL_MS, s.intervalMs)
                .putExtra(EXTRA_TX_LEVEL, s.txPower.advertiseLevel)
                .putExtra(EXTRA_DEADBAND_PCT, s.deadbandPct)
                .putExtra(EXTRA_HEARTBEAT_S, s.heartbeatS)
```
- Replace the `config`/`governor` fields with:
```kotlin
    private var intervalUnits = 640          // 400 ms in 0.625 ms units
    private var txPowerLevel = AdvertisingSetParameters.TX_POWER_LOW
    private var heartbeatMs = 10_000L
    private lateinit var governor: UpdateGovernor
```
- In `onStartCommand`, after the permission check, before `createChannel()`:
```kotlin
        val intervalMs = intent?.getIntExtra(EXTRA_INTERVAL_MS, 400) ?: 400
        intervalUnits = (intervalMs * 1000) / 625
        txPowerLevel = intent?.getIntExtra(EXTRA_TX_LEVEL, AdvertisingSetParameters.TX_POWER_LOW)
            ?: AdvertisingSetParameters.TX_POWER_LOW
        heartbeatMs = ((intent?.getIntExtra(EXTRA_HEARTBEAT_S, 10) ?: 10) * 1000).toLong()
        val deadbandFraction = (intent?.getIntExtra(EXTRA_DEADBAND_PCT, 5) ?: 5) / 100f
        governor = UpdateGovernor(MIN_GAP_MS, deadbandFraction, DEADBAND_ABS_LUX)
```
- Replace `config.heartbeatMs` → `heartbeatMs`, `config.intervalUnits` → `intervalUnits`, `config.txPowerLevel` → `txPowerLevel` (in `startAdvertising` and the heartbeat runnable).
- State writes:
  - `PupilState.running = true` → `PupilState.update { it.copy(running = true) }`
  - `PupilState.sensorRung = "..."` → `PupilState.update { it.copy(sensorRung = "...") }` (all occurrences)
  - `PupilState.lastLux = latestLux` → `PupilState.update { it.copy(lux = latestLux) }`
  - `PupilState.packetId = packetId` → `PupilState.update { it.copy(packetId = packetId) }`
  - In `onDestroy`: `PupilState.update { it.copy(running = false, sensorRung = "not started") }`
- Add `import android.content.Context` if not present (it is).

- [ ] **Step 3: Delete Views UI files + PupilConfig**

```bash
cd ~/iris/android/app/src/main
rm -f java/io/github/genneth/pupil/PupilConfig.kt \
      java/io/github/genneth/pupil/SettingsActivity.kt \
      res/layout/activity_main.xml res/layout/activity_settings.xml \
      res/xml/prefs.xml res/drawable/ic_arrow_back.xml
```

- [ ] **Step 4: `PupilViewModel.kt`**

```kotlin
package io.github.genneth.pupil

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PupilViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)

    val ui: StateFlow<PupilUiState> = PupilState.state
    val settings: StateFlow<PupilSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PupilSettings())

    fun start() {
        val app = getApplication<Application>()
        app.startForegroundService(PupilService.startIntent(app, settings.value))
    }

    fun stop() {
        val app = getApplication<Application>()
        app.stopService(android.content.Intent(app, PupilService::class.java))
    }

    fun setIntervalMs(v: Int) = viewModelScope.launch { repo.setIntervalMs(v) }
    fun setTxPower(v: TxPower) = viewModelScope.launch { repo.setTxPower(v) }
    fun setDeadbandPct(v: Int) = viewModelScope.launch { repo.setDeadbandPct(v) }
    fun setHeartbeatS(v: Int) = viewModelScope.launch { repo.setHeartbeatS(v) }
}
```

- [ ] **Step 5: Minimal Compose `MainActivity.kt`**

```kotlin
package io.github.genneth.pupil

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: PupilViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> if (results.values.all { it }) vm.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PupilTheme {
                Surface {
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                        Text("Pupil", style = MaterialTheme.typography.titleLarge)
                        Text(ui.lux?.let { "%.1f lx · #%d".format(it, ui.packetId) } ?: "—",
                            style = MaterialTheme.typography.displayMedium)
                        Text(if (ui.running) "broadcasting · ${ui.sensorRung}" else "stopped",
                            style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { if (ui.running) vm.stop() else ensurePermsThenStart() }) {
                            Text(if (ui.running) "Stop broadcasting" else "Start broadcasting")
                        }
                    }
                }
            }
        }
    }

    private fun ensurePermsThenStart() {
        val wanted = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.start() else permLauncher.launch(missing.toTypedArray())
    }
}
```

- [ ] **Step 6: Manifest, theme, strings, deps**

- `AndroidManifest.xml`: remove the `<activity android:name=".SettingsActivity" ...>` line. Keep the `.PupilApp` name, `enableOnBackInvokedCallback`, MainActivity launcher, PupilService.
- `res/values/themes.xml`: replace the MDC theme with a minimal non-MDC window theme:
```xml
<resources>
    <style name="Theme.Pupil" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">@color/pupil_navy</item>
    </style>
</resources>
```
- `res/values/strings.xml`: delete the four `<string-array>` pref blocks (keep `app_name` and the button/label strings — or leave the label strings unused; harmless).
- `PupilApp.kt`: remove the `DynamicColors.applyToActivitiesIfAvailable` call (that's MDC/Views; Compose does dynamic colour in `PupilTheme`). Leave the class as an empty `Application` or delete it and drop `android:name` from the manifest. Keep it minimal:
```kotlin
package io.github.genneth.pupil
import android.app.Application
class PupilApp : Application()
```
- `android/app/build.gradle.kts`: remove `com.google.android.material`, `androidx.appcompat:appcompat`, and `androidx.preference:preference-ktx` from `dependencies` (Compose replaces them). Keep `core-ktx`, `activity-ktx`/`activity-compose`, datastore, compose, lifecycle.

- [ ] **Step 7: Build + tests + install**

```bash
toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:testDebugUnitTest :app:assembleDebug'
```
Expected: `BUILD SUCCESSFUL`, 10 tests pass. Then install if a device is connected (skip silently if not):
```bash
toolbox run -c dev bash -lc '~/Android/Sdk/platform-tools/adb devices | grep -q "	device" && ~/Android/Sdk/platform-tools/adb install -r ~/iris/android/app/build/outputs/apk/debug/app-debug.apk || echo "no device; build-only"'
```

- [ ] **Step 8: Commit**

```bash
cd ~/iris && git add -A android/ && git commit -m "Pupil app: Compose shell — StateFlow state, Intent-config service, minimal screen; drop MDC/Views UI"
```

---

### Task 5: Full adaptive PupilScreen — settings inline, dialogs, two-pane

**Files:**
- Create: `android/app/src/main/java/io/github/genneth/pupil/PupilScreen.kt`
- Modify: `android/app/src/main/java/io/github/genneth/pupil/MainActivity.kt` (host PupilScreen, compute WindowSizeClass, wire dialogs/permissions)

**Interfaces:**
- Consumes: `PupilViewModel`, `PupilUiState`, `PupilSettings`, `TxPower`.
- Produces: the final UI.

- [ ] **Step 1: Write `PupilScreen.kt`**

`android/app/src/main/java/io/github/genneth/pupil/PupilScreen.kt`:
```kotlin
package io.github.genneth.pupil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** True (single column) for compact/medium width, false (two-pane) for expanded. */
@Composable
fun PupilScreen(
    ui: PupilUiState,
    settings: PupilSettings,
    singleColumn: Boolean,
    onToggle: () -> Unit,
    onBattery: () -> Unit,
    onInterval: (Int) -> Unit,
    onTxPower: (TxPower) -> Unit,
    onDeadband: (Int) -> Unit,
    onHeartbeat: (Int) -> Unit,
) {
    val readout: @Composable () -> Unit = { Readout(ui, onToggle) }
    val controls: @Composable () -> Unit = {
        Controls(settings, onBattery, onInterval, onTxPower, onDeadband, onHeartbeat, ui.sensorRung)
    }
    if (singleColumn) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { readout(); controls() }
    } else {
        Row(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { readout() }
            Column(
                Modifier.padding(start = 24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { controls() }
        }
    }
}

@Composable
private fun Readout(ui: PupilUiState, onToggle: () -> Unit) {
    Text("Pupil", style = MaterialTheme.typography.titleLarge)
    Text(
        ui.lux?.let { "%.1f lx · #%d".format(it, ui.packetId) } ?: "—",
        style = MaterialTheme.typography.displayMedium.copy(
            fontFeatureSettings = "tnum"
        ),
    )
    Text(
        if (ui.running) "broadcasting · ${ui.sensorRung}" else "stopped",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(if (ui.running) "Stop broadcasting" else "Start broadcasting")
    }
}

@Composable
private fun Controls(
    settings: PupilSettings,
    onBattery: () -> Unit,
    onInterval: (Int) -> Unit,
    onTxPower: (TxPower) -> Unit,
    onDeadband: (Int) -> Unit,
    onHeartbeat: (Int) -> Unit,
    sensorRung: String,
) {
    Setting("Advertising interval", "${settings.intervalMs} ms",
        listOf(100 to "100 ms", 250 to "250 ms", 400 to "400 ms", 1000 to "1 s"), onInterval)
    Setting("TX power", settings.txPower.label(),
        TxPower.entries.map { it to it.label() }, onTxPower)
    Setting("Deadband", "${settings.deadbandPct} %",
        listOf(1 to "1 %", 5 to "5 %", 10 to "10 %", 20 to "20 %"), onDeadband)
    Setting("Heartbeat", "${settings.heartbeatS} s",
        listOf(5 to "5 s", 10 to "10 s", 30 to "30 s (needs receiver --stale-after ≥75)",
            60 to "60 s (needs receiver --stale-after ≥150)"), onHeartbeat)
    FilledTonalButton(onClick = onBattery, modifier = Modifier.fillMaxWidth()) {
        Text("Battery exemption…")
    }
    Text(sensorReportLabel(sensorRung), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun TxPower.label(): String = when (this) {
    TxPower.ULTRA_LOW -> "Ultra low (−21 dBm)"
    TxPower.LOW -> "Low (−15 dBm)"
    TxPower.MEDIUM -> "Medium (−7 dBm)"
    TxPower.HIGH -> "High (+1 dBm)"
}

private fun sensorReportLabel(rung: String): String = "sensor: $rung"

@Composable
private fun <T> Setting(label: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FilledTonalButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label: $current")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        options.forEach { (value, text) ->
            DropdownMenuItem(text = { Text(text) }, onClick = { onPick(value); open = false })
        }
    }
}
```
Note: `FontFeature` import is unused if `fontFeatureSettings` is set as a string — remove it if the linter flags it; `fontFeatureSettings = "tnum"` on a `TextStyle.copy` is the tabular-figures switch.

- [ ] **Step 2: Wire `PupilScreen` + WindowSizeClass + dialogs into `MainActivity`**

Replace the `setContent { ... }` body and add dialog/permission state. `MainActivity.kt` becomes:
```kotlin
package io.github.genneth.pupil

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: PupilViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> if (results.values.all { it }) vm.start() }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PupilTheme {
                Surface {
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    val settings by vm.settings.collectAsStateWithLifecycle()
                    val widthClass = calculateWindowSizeClass(this).widthSizeClass
                    val singleColumn = widthClass != WindowWidthSizeClass.Expanded
                    var showBatteryDialog by remember { mutableStateOf(false) }

                    PupilScreen(
                        ui = ui,
                        settings = settings,
                        singleColumn = singleColumn,
                        onToggle = { if (ui.running) vm.stop() else ensurePermsThenStart() },
                        onBattery = {
                            val pm = getSystemService(POWER_SERVICE) as PowerManager
                            if (!pm.isIgnoringBatteryOptimizations(packageName)) showBatteryDialog = true
                        },
                        onInterval = vm::setIntervalMs,
                        onTxPower = vm::setTxPower,
                        onDeadband = vm::setDeadbandPct,
                        onHeartbeat = vm::setHeartbeatS,
                    )

                    if (showBatteryDialog) {
                        AlertDialog(
                            onDismissRequest = { showBatteryDialog = false },
                            title = { Text("Allow unrestricted battery use?") },
                            text = {
                                Text(
                                    "With the phone idle, Doze ignores wakelocks and screen-off " +
                                        "broadcasting freezes. The exemption keeps the light sensor " +
                                        "streaming while the screen is off (~1%/h while broadcasting)."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showBatteryDialog = false
                                    startActivity(
                                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                            .setData(Uri.parse("package:$packageName"))
                                    )
                                }) { Text("Request") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showBatteryDialog = false }) { Text("Not now") }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun ensurePermsThenStart() {
        val wanted = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.start() else permLauncher.launch(missing.toTypedArray())
    }
}
```
Note: predictive back needs no code here — the single Activity with no `onBackPressed` override plus the manifest `enableOnBackInvokedCallback="true"` gives the system gesture for free. The rationale dialog before permissions is folded into the graceful launcher flow (the `RequestMultiplePermissions` contract shows the OS dialog; a first-denial rationale can be added later if desired — parity with the Views version's rationale dialog is acceptable to defer since behaviour on grant/deny is preserved).

- [ ] **Step 3: Build + tests + install**

```bash
toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain :app:testDebugUnitTest :app:assembleDebug'
```
Expected: `BUILD SUCCESSFUL`, 10 tests pass. Install if a device is present (as in Task 4 Step 7).

- [ ] **Step 4: Commit**

```bash
cd ~/iris && git add -A android/ && git commit -m "Pupil app: full adaptive Compose screen — inline settings, two-pane on unfold, battery dialog"
```

---

### Task 6: Home-dir cleanup + SDK prune + docs

Now that everything builds green on dnf java-25 and the new SDK, remove the redundant hand-installed toolchain and stale SDK, and update the docs.

**Files:**
- Modify: `dev.sh` (drop the `JAVA_HOME=~/.local/opt/jdk-21` prefix from the `android` target)
- Modify: `android/README.md` (build commands, toolchain facts, Compose note)

- [ ] **Step 1: Confirm the build no longer needs `~/.local/opt`**

```bash
toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew --console=plain clean :app:assembleDebug' 2>&1 | tail -3
```
Expected: `BUILD SUCCESSFUL` with no `JAVA_HOME` prefix (uses dnf java-25).

- [ ] **Step 2: Remove the hand-installed JDK + Gradle**

```bash
rm -rf ~/.local/opt/jdk-21 ~/.local/opt/jdk-21.0.11+10 ~/.local/opt/gradle-9.6.1 \
       ~/.local/opt/gradle-current ~/.local/bin/gradle
rmdir ~/.local/opt 2>/dev/null || true
ls -la ~/.local/opt 2>/dev/null || echo "~/.local/opt removed"
```

- [ ] **Step 3: Prune stale SDK platform + build-tools**

```bash
rm -rf ~/Android/Sdk/build-tools/35.0.0 ~/Android/Sdk/platforms/android-35
ls ~/Android/Sdk/build-tools ~/Android/Sdk/platforms
```
Expected: build-tools `36.0.0`, platforms `android-36`.

- [ ] **Step 4: Update `dev.sh` android target**

In `dev.sh`, change the `android)` case to drop the JAVA_HOME prefix (dnf java-25 is the default):
```bash
  android)
    # gradle runs in the dev toolbox on the dnf JDK; SDK at ~/Android/Sdk
    toolbox run -c dev bash -lc "cd '$PWD/android' && ./gradlew --console=plain testDebugUnitTest"
    ;;
```

- [ ] **Step 5: Verify the gate still works end to end**

```bash
cd ~/iris && ./dev.sh android 2>&1 | tail -3 && ./dev.sh check 2>&1 | tail -3
```
Expected: JUnit `BUILD SUCCESSFUL` (10 tests); Python gate green (25 tests).

- [ ] **Step 6: Update `android/README.md`**

Rewrite the build/toolchain section to reflect reality: no `JAVA_HOME` prefix; toolchain = dnf `java-25-openjdk` in `dev` + Gradle wrapper 9.6.1 + AGP 9.2 + Kotlin 2.3.21 + SDK 36 at `~/Android/Sdk`; UI is Jetpack Compose (Material 3, adaptive foldable). Keep the ColorOS checklist and the Find N6 acceptance results. Update the build commands to:
```
toolbox run -c dev bash -lc 'cd ~/iris/android && ./gradlew :app:assembleDebug'
toolbox run -c dev bash -lc 'cd ~/iris/android && ~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk'
```

- [ ] **Step 7: Commit**

```bash
cd ~/iris && git add -A && git commit -m "Pupil: remove hand-installed ~/.local/opt toolchain, prune SDK to 36, update docs"
```

---

## Verification checklist (whole feature)

- [ ] `./dev.sh android` green (10 JUnit tests: 3 encoder + 4 UpdateGovernor + 3 settings)
- [ ] `./dev.sh check` green (Python side untouched, 25 tests)
- [ ] App builds on AGP 9.2 / Gradle 9.6.1 / Kotlin 2.3.21 / SDK 36, no `JAVA_HOME` prefix
- [ ] `~/.local/opt` empty/removed; `~/Android/Sdk` pruned to 36; `~/.gradle` + `~/.android` kept
- [ ] On-device: folded (single column) and unfolded (two-pane) both render; Start/Stop, settings, battery dialog work; broadcast still received by `ble_als_probe.py`
- [ ] `BthomeEncoder` golden-vector contract unchanged and green
- [ ] No `com.google.android.material` / appcompat / preference deps remain
