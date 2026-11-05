plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.thetrendlab.customer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thetrendlab.customer"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
}
