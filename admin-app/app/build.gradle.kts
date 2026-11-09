import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.thetrendlab.admin"
    compileSdk = 35
    signingConfigs {
        create("release") {
            val encoded = System.getenv("ANDROID_KEYSTORE_BASE64")
            if (!encoded.isNullOrBlank()) {
                val keyFile = file("$rootDir/admin-release.jks")
                keyFile.writeBytes(Base64.getDecoder().decode(encoded.replace("\\s".toRegex(), "")))
                storeFile = keyFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    defaultConfig {
        applicationId = "com.thetrendlab.adminapp2026"
        minSdk = 23
        targetSdk = 34
        versionCode = 11
        versionName = "1.0.10"
    }
    buildTypes { release { signingConfig = signingConfigs.getByName("release"); isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}