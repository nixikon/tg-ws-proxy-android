pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://chaquo.com/maven")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://chaquo.com/maven")
    }
}

rootProject.name = "TgWsProxy"
include(":app")
