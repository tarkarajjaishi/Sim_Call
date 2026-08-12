plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tarka.simbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tarka.simbridge"
        minSdk = 26          // Android 8; endCall() is guarded for <28 at runtime
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded, so sign release with the debug key too -- otherwise the
            // APK is unsigned and Android refuses to install it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party dependencies: HttpURLConnection and org.json ship with Android.
dependencies { }
