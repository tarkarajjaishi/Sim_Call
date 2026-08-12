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

    signingConfigs {
        // Override the auto-generated debug key with a committed keystore so every
        // build shares one signature. That lets `install -r` / a Downloads update
        // replace the app in place, keeping permissions, the accessibility grant
        // and config -- instead of the uninstall-per-build churn a random CI key
        // causes. It's a debug key for a personal sideload; the password is the
        // stock Android debug value on purpose.
        getByName("debug") {
            storeFile = file("simbridge.jks")
            storePassword = "android"
            keyAlias = "simbridge"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
