plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hand.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hand.bridge.nativepreview"
        minSdk = 31
        targetSdk = 34
        // CI run number, so every published APK installs as an update over the previous one.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.3.0-native-preview"
    }

    // One permanent key (from CI secrets) keeps updates installable. Without it the build falls
    // back to the debug key, which differs per CI machine, so each update needs an uninstall first.
    val releaseKeystore = System.getenv("HB_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("HB_STORE_PASSWORD")
                keyAlias = System.getenv("HB_KEY_ALIAS")
                keyPassword = System.getenv("HB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Own the band locally; no Android broadcasts in the native input path.
dependencies {
    implementation(project(":band"))
}
