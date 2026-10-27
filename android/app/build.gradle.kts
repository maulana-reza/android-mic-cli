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
        versionCode = 1
        versionName = "0.1.0"
    }

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
