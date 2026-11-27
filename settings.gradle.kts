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
    // The fork's catalog is the one source of the pins the daemon shares with libpebble3 (Kotlin, Ktor,
    // kotlinx, kermit, Koin): build.gradle.kts reads them as `forkLibs.*`, so a bump moves them together.
    versionCatalogs {
        create("forkLibs") { from(files("libs/libpebble3/gradle/libs.versions.toml")) }
    }
}

includeBuild("libs/libpebble3") {
    dependencySubstitution {
        substitute(module("com.coredevices:libpebble3"))
            .using(project(":libpebble3"))
    }
}
