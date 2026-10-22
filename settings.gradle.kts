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

rootProject.name = "Pantavi"
include(":app")
// Pantavi Pro key app: private, left out of the published GPL source (scripts/export_source.sh).
if (file("prokey").exists()) include(":prokey")
