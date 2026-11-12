pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "SteamShelf"
include(":core", ":server")

// The Android app needs the Android SDK. The server's Docker build passes -Psteamshelf.serverOnly=true to leave it out.
if (providers.gradleProperty("steamshelf.serverOnly").orNull != "true") {
    include(":app")
}
