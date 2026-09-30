plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every CI build gets a higher versionCode, so each new APK installs over the last one.
val ciBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Release signing: the keystore is never committed. CI decodes it from the
// KEYSTORE_BASE64 secret; locally, point these env vars at your .jks file.
val keystorePath: String? = System.getenv("SIGNING_STORE_FILE")
val keystorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")

android {
    namespace = "com.ghostcleaner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ghostcleaner"
        minSdk = 26
        targetSdk = 34
        versionCode = ciBuildNumber
        versionName = "1.0.$ciBuildNumber"
    }

    signingConfigs {
        if (keystorePath != null && keystorePassword != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "ghostcleaner"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Slide-out sidebar
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
}
