import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.thetrendlab.customer"
    compileSdk = 35

    signingConfigs {
        create("release") {
            val encodedStore = System.getenv("ANDROID_KEYSTORE_BASE64")
            if (!encodedStore.isNullOrBlank()) {
                val keyFile = file("$rootDir/thetrendlab-release.jks")
                keyFile.writeBytes(Base64.getDecoder().decode(encodedStore.replace("\\s".toRegex(), "")))
                storeFile = keyFile
            }
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    dependencies {
        implementation("androidx.core:core-ktx:1.15.0")
        implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    defaultConfig {
        applicationId = "com.thetrendlab.customer"
        minSdk = 23
        targetSdk = 35
        versionCode = 38
        versionName = "2.12.5"
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
}
