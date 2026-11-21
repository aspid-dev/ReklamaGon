plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vc = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "local.phonediag"
    compileSdk = 34
    defaultConfig {
        applicationId = "local.phonediag"
        minSdk = 26
        targetSdk = 28
        versionCode = vc
        versionName = "1.0.$vc"
    }
    signingConfigs {
        create("rel") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null)
                signingConfigs.getByName("rel") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
