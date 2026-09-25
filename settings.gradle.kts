pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Declared here rather than in the root build so the desktop build does not need the Android plugin.
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "VishuCraft"
// "-PdesktopOnly" builds just the Windows / desktop version (no Android SDK needed).
if (!providers.gradleProperty("desktopOnly").isPresent) include(":app")
// The desktop module is built separately (Windows workflow) so the Android build does not load it.
if (providers.gradleProperty("desktop").isPresent || providers.gradleProperty("desktopOnly").isPresent) include(":desktop")
