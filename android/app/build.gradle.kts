plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.genneth.pupil"
    // Stays at 35: compileSdk 36 needs the android-36 platform + a newer AGP than the
    // pinned 8.7.3 (and Gradle bump) — a toolchain chore worth its own pass. Everything
    // here (M3 Expressive, dynamic colour, edge-to-edge, predictive back) works at 35;
    // targetSdk 36 only matters for Play, which this sideloaded app isn't on.
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.genneth.pupil"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
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
