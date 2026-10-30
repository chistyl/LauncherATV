plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tw.chistyl.dangbei"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.chistyl.dangbei"
        minSdk = 23
        targetSdk = 28
        versionCode = 53
        versionName = "5.4.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
