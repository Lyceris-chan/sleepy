import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing material is supplied from outside the repository, and is absent by default.
// `keystore.properties` at the repository root points at the keystore—it is gitignored, and its
// four keys are the ones AGP uses:
//
//     storeFile=release.jks
//     storePassword=...
//     keyAlias=...
//     keyPassword=...
//
// Without it—on a fresh checkout, in CI, for anyone who only builds debug—the release
// build is left unsigned rather than signed with a key everyone has.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// The release version is read from the changelog rather than written here as well. CHANGELOG.md is
// the record of which version this project is at—releases are cut from it, and its newest
// released heading is the newest version that exists—so a build that read the number from
// anywhere else can report a version the changelog does not mention. The `Unreleased` heading is
// deliberately skipped: it is a version that cannot be installed.
//
// It is read through `providers.fileContents` rather than through `File.readText`, because only
// the former is a *tracked* configuration input. A bare read happens inside the configuration
// action and leaves no record of itself, so with the configuration cache on—where the action is
// skipped entirely and an earlier entry is reused—cutting a release by adding a heading here
// keeps stamping the previous version onto every build until something unrelated invalidates the
// entry. That produces a wrong version number in a build that still succeeds, which is the failure
// this indirection exists to prevent. `fileContents` resolves through a value source, so Gradle
// records the bytes it read and reconfigures when they differ.
val changelogText: Provider<String> =
    providers.fileContents(rootProject.layout.projectDirectory.file("CHANGELOG.md")).asText
val changelogVersion: Provider<String> = changelogText.map { text ->
    Regex("^## \\[(\\d+\\.\\d+\\.\\d+)\\]", RegexOption.MULTILINE)
        .find(text)
        ?.groupValues?.get(1)
        ?: throw GradleException(
            "CHANGELOG.md has no released version heading (## [x.y.z]) to build as"
        )
}

// 1.4.0 becomes 10400. Android orders installs by this number and not by the name, so two releases
// that share one do not replace each other on install, even when their version names differ.
val changelogVersionCode: Provider<Int> = changelogVersion.map { version ->
    version.split(".").let { (major, minor, patch) ->
        major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    }
}

android {
    namespace = "dev.sleepy.app"
    // OkHttp 5.5 requires callers to compile against API 37 or newer.
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.sleepy.app"
        minSdk = 26
        targetSdk = 37
        // Resolved here rather than at the point the provider is built: the value is a plain
        // number in the manifest by the time a build runs, and asking for it during configuration
        // is what pulls the changelog in as a configuration input.
        versionCode = changelogVersionCode.get()
        versionName = changelogVersion.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // Created only when the properties are there. A `release` config holding empty strings
        // builds an APK that fails at signing; not having one leaves the artifact unsigned, which
        // matches the fact that no key was supplied.
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Not the debug key. The debug key is a published keystore every Android SDK
            // installs, so a release APK signed with it installs like any other release and is
            // signed by a key anyone can use to make a "newer" build of this app. Without
            // keystore.properties the APK is left unsigned and is named
            // `app-release-unsigned.apk`, which records that. Releases are re-signed where they
            // are published (see .github/workflows/release.yml), so nothing downstream depends
            // on a signing key being in this repository or on a developer's machine.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

}

// The merge test runs the real Discord splits—a 96 MB base and a 74 MB ABI split—end to end,
// and that is deliberate: it exercises the same peak memory the device reaches. The heap is capped
// at what a phone grants an app with `largeHeap`, so a repack that holds the whole archive, or the
// libraries it is merging, fails here instead of on someone's phone.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "512m"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.register<Copy>("copySources") {
    from(rootDir) {
        include("sources.json")
    }
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") {
    dependsOn("copySources")
}

dependencies {
    // Compose BOM
    implementation(platform(libs.compose.bom))
    // ui-tooling and ui-tooling-preview are deliberately absent: they exist for `@Preview`
    // composables and there are none in this app, so they were two artifacts on the compile
    // classpath—and ui-tooling on the debug APK—that nothing referenced. Add them back
    // with the first preview that needs them.
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    // AndroidX & Kotlin
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    // lifecycle-viewmodel-compose for `viewModel()`; lifecycle-runtime-compose (which adds
    // `collectAsStateWithLifecycle`) was declared and not called—this app collects its
    // flows with `collectAsState`. activity-compose brings lifecycle-runtime itself in
    // transitively, so removing the declaration removes nothing the app uses.
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.adaptive)
    implementation(libs.coroutines.android)

    // APK Tooling
    implementation(libs.smali)
    implementation(libs.baksmali)
    implementation(libs.apksig)

    // Network
    implementation(libs.okhttp)

    // Testing
    testImplementation("junit:junit:4.13.2")
}
