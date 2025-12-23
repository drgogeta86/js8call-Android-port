// Example app-level build.gradle.kts for integrating JS8Call native library

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.js8call.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.js8call.example"
        minSdk = 26  // Android 8.0 (required for Oboe)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Specify ABIs to build
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Configure CMake for native library
    externalNativeBuild {
        cmake {
            path = file("../../CMakeLists.txt")  // Path to adapter CMakeLists.txt
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Configure prefab for Oboe dependency
    buildFeatures {
        prefab = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Oboe for audio (via prefab)
    implementation("com.google.oboe:oboe:1.8.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
