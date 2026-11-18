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
        // NewPipe Extractor（JitPack，无 Maven Central 坐标）
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MoCast"
include(":app")
