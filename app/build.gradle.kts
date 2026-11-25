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
        versionCode = 6
        versionName = "6.0.0"
    }

    // 本地放了 app/minisa.jks 就用它签名（自用升级不用卸载）；
    // 公开仓库不含密钥，回落到 debug 签名。
    val hasFixedKey = file("minisa.jks").exists()
    signingConfigs {
        if (hasFixedKey) {
            create("fixed") {
                storeFile = file("minisa.jks")
                storePassword = System.getenv("MINISA_STORE_PASS") ?: "minisa2026"
                keyAlias = System.getenv("MINISA_KEY_ALIAS") ?: "minisa"
                keyPassword = System.getenv("MINISA_KEY_PASS") ?: "minisa2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasFixedKey) {
                signingConfigs.getByName("fixed")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Modern API 的声明文件在 META-INF/xposed/ 下，必须打进去
            merges += "META-INF/xposed/*"
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
