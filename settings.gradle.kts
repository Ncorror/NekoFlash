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

rootProject.name = "NekoFlash"

include(":app")
include(":core:model")
include(":core:diagnostics")
include(":core:operation")
include(":usb:api")
include(":usb:android")
