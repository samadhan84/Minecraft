plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// The desktop (Windows) version shares the game engine with the Android app: world, blocks, mobs, redstone,
// crafting, saving, Wi-Fi and the OpenGL renderer. Only the window, input, menus and sound are desktop-specific.
val lwjglVersion = "3.3.4"
val lwjglNatives = listOf("natives-windows", "natives-linux", "natives-macos")

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    for (m in listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl")) {
        implementation("org.lwjgl:$m")
        for (n in lwjglNatives) runtimeOnly("org.lwjgl:$m::$n")
    }
    testImplementation(kotlin("test"))
}

// Compile for Java 17 with whichever JDK (17 or newer) runs Gradle.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

sourceSets {
    main {
        kotlin {
            srcDir("../app/src/main/java")
            // Android-only parts (views, activities, Android audio) are replaced by desktop code.
            exclude(
                "com/vishucraft/game/ui/**",
                "com/vishucraft/game/GameActivity.kt",
                "com/vishucraft/game/MenuActivity.kt",
                "com/vishucraft/game/CrashReporter.kt",
                "com/vishucraft/game/audio/Sounds.kt",
                "com/vishucraft/game/render/GameRenderer.kt",
            )
        }
    }
}

application {
    mainClass.set("com.vishucraft.desktop.MainKt")
    applicationName = "VishuCraft"
}

tasks.jar {
    manifest { attributes("Main-Class" to "com.vishucraft.desktop.MainKt") }
}
