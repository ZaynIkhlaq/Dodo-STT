plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing comes from the environment (GitHub Actions secrets) so nothing secret lives in the repo.
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.zaynikhlaq.dodostt"
    compileSdk = 35

    defaultConfig {
        // The app is called Vodo, but this id and the class names stay: Android keys the
        // accessibility grant and the update chain to them, and changing either would mean
        // re-granting everything and a fresh install.
        applicationId = "com.zaynikhlaq.dodostt"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        if (signingStoreFile != null && signingPassword != null) {
            create("release") {
                storeFile = file(signingStoreFile)
                storeType = "pkcs12"
                storePassword = signingPassword
                keyAlias = "dodostt"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
