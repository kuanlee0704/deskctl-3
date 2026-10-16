plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tw.deskctl"
    compileSdk = 34
    defaultConfig {
        applicationId = "tw.deskctl"
        minSdk = 26
        targetSdk = 34
        versionCode = 31
        versionName = "1.12.0"
    }
    // 固定的除錯簽章: 每次 GitHub Actions 打包出來的 APK 簽章相同, 才能直接「覆蓋安裝」並保留設定
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media:media:1.7.0")
}
