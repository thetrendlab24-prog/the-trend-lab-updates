plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.thetrendlab.admin"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thetrendlab.adminapp2026"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
