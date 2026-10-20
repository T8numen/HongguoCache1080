plugins {
    id("com.android.application")
}

android {
    namespace = "com.t8numen.hongguocache1080"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.t8numen.hongguocache1080"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "1.8.7"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
