plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vishucraft.game"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vishucraft.game"
        minSdk = 24
        targetSdk = 36
        // Every GitHub build gets a higher version number, so Android sees it as an update.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + build
        versionName = "1.$build"
    }

    signingConfigs {
        // One fixed signing key for the APK on the download link (debug and release, on any computer). Android only
        // installs an update over an app signed with the same key; the default debug key differs on every machine.
        create("dhruvvishu") {
            storeFile = file("dhruvvishu.keystore")
            storePassword = "dhruvvishu"
            keyAlias = "dhruvvishu"
            keyPassword = "dhruvvishu"
        }
        // The private Google Play upload key, given to the build through GitHub secrets (never stored in the project).
        val upload = System.getenv("PLAY_UPLOAD_KEYSTORE")
        if (upload != null && file(upload).exists()) create("playUpload") {
            storeFile = file(upload)
            storePassword = System.getenv("PLAY_UPLOAD_STORE_PASSWORD")
            keyAlias = System.getenv("PLAY_UPLOAD_KEY_ALIAS")
            keyPassword = System.getenv("PLAY_UPLOAD_KEY_PASSWORD")
        }
    }

    // Two versions of the app:
    //  direct - the APK on the download link (Android TV, phones); updates itself from that link.
    //  play   - the app bundle for Google Play; Google Play does the updating, so it has no updater and does not
    //           ask for permission to install apps.
    flavorDimensions += "store"
    productFlavors {
        create("direct") {
            dimension = "store"
            signingConfig = signingConfigs.getByName("dhruvvishu")
        }
        create("play") {
            dimension = "store"
            signingConfig = signingConfigs.findByName("playUpload") ?: signingConfigs.getByName("dhruvvishu")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dhruvvishu")
        }
        release {
            // Off: the Claude SDK reads its JSON classes by reflection, which code shrinking would break.
            isMinifyEnabled = false
            // Signed with the flavor's key (see productFlavors).
        }
    }

    compileOptions {
        // The Claude SDK uses java.time, which older Android versions (7.x) only get through desugaring.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/**", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // Claude (Anthropic) API: writes, changes, explains and fixes Arduino code.
    implementation("com.anthropic:anthropic-java:3.3.0")
    // QR codes, to copy a sketch from the TV to a phone or computer.
    implementation("com.google.zxing:core:3.5.3")
}
