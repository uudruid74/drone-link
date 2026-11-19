plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dronelink.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dronelink.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "0.3.4"  // version-bump law (Evan 2026-11-18): 25Hz stream runs while bound (daemon shape); connect() no early-return
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    testImplementation("junit:junit:4.13.2")
    // org.json is built into the Android runtime; this mirrors it for JVM unit tests.
    testImplementation("org.json:json:20240303")
}
