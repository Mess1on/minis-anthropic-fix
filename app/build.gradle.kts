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
        versionCode = 5
        versionName = "5.0.0"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("minisa.jks")
            storePassword = "minisa2026"
            keyAlias = "minisa"
            keyPassword = "minisa2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
