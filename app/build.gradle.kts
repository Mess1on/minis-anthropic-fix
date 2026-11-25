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
        versionCode = 1
        versionName = "1.0.0"
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
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    compileOnly(files("libs/api-82.jar"))
}
