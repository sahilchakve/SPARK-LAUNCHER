plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.friday.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.friday.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity:1.9.0")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
