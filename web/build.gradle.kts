plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.teavm")
}

// The browser version (for iPhone and iPad through Safari, and any computer): the same game engine as the
// Android and desktop versions, compiled to JavaScript by TeaVM, drawing with WebGL.
dependencies {
    implementation(teavm.libs.jsoApis)
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

sourceSets {
    main {
        kotlin {
            srcDir("../app/src/main/java")
            // Menus, inventory screens and the 2D toolkit, shared with the desktop version.
            srcDir("../client")
            exclude(
                "com/vishucraft/game/ui/**",
                "com/vishucraft/game/GameActivity.kt",
                "com/vishucraft/game/MenuActivity.kt",
                "com/vishucraft/game/Updater.kt",
                "com/vishucraft/game/CrashReporter.kt",
                "com/vishucraft/game/audio/Sounds.kt",
                "com/vishucraft/game/render/GameRenderer.kt",
            )
        }
        // Pure-Java stand-ins for Android's Matrix and Bitmap, shared with the desktop version.
        java { srcDir("../shims") }
    }
}

teavm {
    js {
        mainClass = "com.vishucraft.web.WebMainKt"
        targetFileName = "game.js"
        obfuscated = providers.gradleProperty("webDebug").isPresent.not()
        sourceMap = providers.gradleProperty("webDebug").isPresent
    }
}
