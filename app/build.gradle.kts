plugins {
    id("com.android.application")
}

android {
    namespace = "com.kk333616.earphonessearch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kk333616.earphonessearch"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
