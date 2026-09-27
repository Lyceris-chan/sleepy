import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.sleepy.app"
    // OkHttp 5.5 requires callers to compile against API 37 or newer.
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.sleepy.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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
            signingConfig = signingConfigs.getByName("debug")
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

// The merge test walks the real Discord splits — a 96 MB base and a 74 MB ABI split — end to
// end, and that is the point of it: it exercises the same peak the device hits. The heap is
// capped at what a phone grants an app with `largeHeap` so a repack that quietly holds the
// whole archive, or the libraries it is merging, fails here instead of on someone's phone.
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
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX & Kotlin
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.adaptive)
    implementation(libs.datastore)
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
