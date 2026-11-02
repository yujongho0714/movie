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
        versionCode = 5
        versionName = "1.3"
    }

    // 매번 같은 서명으로 만들어서 새 버전이 기존 앱 위에 바로 업데이트되게 함
    signingConfigs {
        getByName("debug") {
            storeFile = file("jungdo-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
}
