// Kotlin comes in through the Compose plugin, not through AGP and not through a
// standalone install. AGP 9.3.1 declares kotlin-gradle-plugin 2.2.10 at runtime,
// but `org.jetbrains.kotlin.plugin.compose` pulls kotlin-gradle-plugins-bom and
// upgrades the whole graph to its own version — verified with `gradlew
// buildEnvironment`, which resolves `kotlin-gradle-plugin:2.2.10 -> 2.3.20`.
// So the Compose plugin version below IS the effective Kotlin version, and the
// buildscript classpath override this file used to carry (forcing KGP 2.3.21)
// was both unnecessary and a version skew against the Compose compiler.
//
// Versions are allocated by almon, not chosen here: ~/almon/conformance/intent.py
// -> ANDROID_TOOLCHAIN, enforced by conformance/test_android_toolchain.py. Keep
// them declared in this file — almon never rewrites them at build time, so this
// repo stays reproducible off molly.
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.android.compose.screenshot") version "0.0.1-alpha16" apply false
}
