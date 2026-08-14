pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "decentralized-messaging"

include(":mesh-protocol")
include(":mesh-crypto")
include(":mesh-crypto-android")
include(":mesh-engine")
include(":mesh-simulator")
include(":android-probe")
