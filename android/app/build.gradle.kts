import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
}

android {
    namespace = "com.example.mirror"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.maulanareza.mirror"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    // standard: screen and mic, installs like any app. control: also replays the PC's clicks and keys
    // through an accessibility service, which Play Protect only lets in through adb.
    flavorDimensions += "control"
    productFlavors {
        create("standard") { dimension = "control" }
        create("control") { dimension = "control" }
    }
    buildTypes.getByName("debug").applicationIdSuffix = ".debug"  // installs next to the release one

    // Release key: keystore.properties next to settings.gradle.kts (storeFile, storePassword, keyAlias,
    // keyPassword), kept out of git. Without it assembleRelease still builds, unsigned.
    val keystore = rootProject.file("keystore.properties")
    if (keystore.exists()) {
        val key = Properties().apply { keystore.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = file(key.getProperty("storeFile"))
            storePassword = key.getProperty("storePassword")
            keyAlias = key.getProperty("keyAlias")
            keyPassword = key.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")  // Google's scanner, no camera permission
}
