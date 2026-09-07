// Toolchain versions are allocated by almon, not chosen here: ~/almon/conformance/intent.py
// -> ANDROID_TOOLCHAIN, enforced by conformance/test_android_toolchain.py. Keep them
// declared in this file — almon never rewrites them at build time, so this repo stays
// reproducible off molly.
//
// Kotlin under AGP 9's built-in Kotlin: AGP bundles an older kotlin-gradle-plugin (KGP) and
// Google's documented way to run a newer one is an explicit classpath pin (see
// developer.android.com/build/releases/agp-9-0-0-release-notes). The Compose compiler plugin
// is versioned WITH Kotlin, so the pin below and the Compose plugin version MUST be equal —
// a mismatch skews KGP against the Compose compiler (pupil carried exactly that until
// 2026-08-13). Conformance asserts equality; move both together.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.android.compose.screenshot") version "0.0.1-alpha16" apply false
}
