// AGP 9.2 built-in Kotlin: no explicit org.jetbrains.kotlin.android plugin needed
// (AGP applies Kotlin support itself). AGP 9.2.0's runtime KGP dependency defaults
// to 2.3.10; override to the pinned 2.3.21 per the migration plan's exact version set.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "9.2.0" apply false
}
