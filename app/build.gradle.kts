plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kiaashkan.editnote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kiaashkan.editnote"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

dependencies {
    // Intentionally dependency-free: the Android framework alone keeps
    // EditNote tiny, fully offline and free of third-party trackers/ads.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
}
