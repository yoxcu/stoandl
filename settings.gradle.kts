rootProject.name = "stoandl"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io")
    }
}

includeBuild("libs/libpebble3") {
    dependencySubstitution {
        substitute(module("com.coredevices:libpebble3"))
            .using(project(":libpebble3"))
    }
}
