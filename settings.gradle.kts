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
rootProject.name = "HandBridge"
include(":app")

// Experimental direct Meta Neural Band engine from pinned Rokid Lumen sources.
// Run scripts/sync-lumen.sh before opening this preview branch in Android Studio.
include(":band")
project(":band").projectDir = file("third_party/lumen/band")
