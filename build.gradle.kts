// AGP 9 compiles Kotlin itself, so the org.jetbrains.kotlin.android plugin is gone. KGP
// still has to be on the classpath to pin the Kotlin version: built-in Kotlin ships with
// KGP 2.2.10, and a newer Kotlin is selected by putting it on the buildscript classpath.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
