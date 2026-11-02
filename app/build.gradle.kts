plugins {
    id("com.android.application")
}

android {
    namespace = "kr.jungdo.video"
    compileSdk = 34

    defaultConfig {
        applicationId = "kr.jungdo.video"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
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
}

dependencies {
    implementation("androidx.core:core:1.12.0")
}
