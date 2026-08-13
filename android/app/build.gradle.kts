import java.util.Properties

/**
 * Release signing credentials, or null when this checkout has none.
 *
 * The signing key is the app's permanent identity: Android rejects any update whose
 * certificate differs from the installed app, so re-keying means an uninstall. The
 * keystore is one PKCS12 container shared with shamoji and hartley, holding a separate
 * key pair per alias; it lives outside every repo and is backed up to Drive. See
 * `~/almon/layers/android-signing.md`.
 *
 * Absent credentials are **not** an error: the build still produces
 * `app-release-unsigned.apk`, so anyone can clone this repo and build it.
 */
val releaseSigning: Map<String, String>? = run {
    fun value(key: String, env: String): String? =
        providers.environmentVariable(env).orNull
            ?: rootProject.file("keystore.properties")
                .takeIf { it.isFile }
                ?.let { file -> Properties().apply { file.inputStream().use(::load) }.getProperty(key) }

    val store = value("storeFile", "PUPIL_KEYSTORE_FILE") ?: return@run null
    val storePassword = value("storePassword", "PUPIL_KEYSTORE_PASSWORD") ?: return@run null
    val alias = value("keyAlias", "PUPIL_KEY_ALIAS") ?: return@run null
    val keyPassword = value("keyPassword", "PUPIL_KEY_PASSWORD") ?: return@run null
    // A path in the properties file that does not exist is a misconfiguration worth
    // failing on, not something to silently fall back from: it would otherwise produce
    // an unsigned APK that looks like a successful build.
    require(file(store).isFile) { "keystore.properties points at a missing keystore: $store" }
    mapOf(
        "storeFile" to store,
        "storePassword" to storePassword,
        "keyAlias" to alias,
        "keyPassword" to keyPassword,
    )
}

plugins {
    id("com.android.application")
    // AGP 9.2 built-in Kotlin drives Kotlin compilation; no org.jetbrains.kotlin.android here.
    // The Compose compiler plugin is required whenever buildFeatures.compose is enabled
    // (AGP 9.2 does not bundle a Compose compiler even under built-in Kotlin), but it does
    // not require org.jetbrains.kotlin.android to be applied alongside it.
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.compose.screenshot")
}

android {
    namespace = "io.github.genneth.pupil"
    compileSdk = 37
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        applicationId = "io.github.genneth.pupil"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseSigning?.let { credentials ->
            create("release") {
                storeFile = file(credentials.getValue("storeFile"))
                storePassword = credentials.getValue("storePassword")
                keyAlias = credentials.getValue("keyAlias")
                keyPassword = credentials.getValue("keyPassword")
                // v3 must be switched on explicitly, and matters beyond compatibility: it
                // carries the proof-of-rotation lineage, which has to be present in the
                // *installed* APK for a future key change to avoid an uninstall.
                //
                // AGP derives the rest of the scheme set from minSdk — at minSdk >= 28 v3
                // supersedes v2, so the built APK carries a v3 block and no v2 one. That
                // is correct here (minSdk 31). Don't "fix" it by adding enableV2Signing:
                // verified on a clean, config-cache-free build that it does not force a
                // v2 block back in.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Null when this checkout has no credentials, which leaves the release APK
            // unsigned rather than failing the build.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha16")
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
}
