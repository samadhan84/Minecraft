pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Declared here rather than in the root build so the desktop build does not need the Android plugin.
    plugins {
        id("com.android.application") version "8.11.1"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.teavm") version "0.15.0"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "DhruvVishu"
// "-PdesktopOnly" builds just the Windows / desktop version (no Android SDK needed).
if (!providers.gradleProperty("desktopOnly").isPresent && !providers.gradleProperty("webOnly").isPresent) include(":app")
// The desktop module is built separately (Windows workflow) so the Android build does not load it.
if (providers.gradleProperty("desktop").isPresent || providers.gradleProperty("desktopOnly").isPresent) include(":desktop")
// "-PwebOnly" builds the browser version (iPhone, iPad and any computer) with TeaVM.
if (providers.gradleProperty("webOnly").isPresent) include(":web")
