pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // mavenLocal() FIRST so the locally-published chencang-core-android AAR resolves
        // during dev. CI publishes to ~/.m2 prior to building this project.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "chencang-android"
include(":app", ":shared", ":design")
