plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vishucraft.game"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vishucraft.game"
        minSdk = 24
        targetSdk = 35
        // Every GitHub build gets a higher version number, so Android sees it as an update.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + build
        versionName = "1.$build"
    }

    // One fixed signing key for every build (debug and release, on any computer). Android only installs an
    // update over an app signed with the same key; the default debug key is different on every build machine.
    signingConfigs {
        create("dhruvvishu") {
            storeFile = file("dhruvvishu.keystore")
            storePassword = "dhruvvishu"
            keyAlias = "dhruvvishu"
            keyPassword = "dhruvvishu"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dhruvvishu")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("dhruvvishu")
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
