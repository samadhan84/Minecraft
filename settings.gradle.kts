pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
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
// The desktop module is opt-in ("-Pdesktop" or "-PdesktopOnly") while it is being built.
if (providers.gradleProperty("desktop").isPresent || providers.gradleProperty("desktopOnly").isPresent) include(":desktop")
