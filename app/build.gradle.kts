plugins {
    id("com.android.application")
}

android {
    namespace = "com.ktwo.minisa"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ktwo.minisa"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Modern API 的入口清单在 META-INF/xposed/ 下，必须打进 APK
        resources {
            merges += "META-INF/xposed/*"
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
