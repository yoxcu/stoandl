package de.yoxcu.stoandl

/** The build version, baked in from `git describe` by Gradle (see build.gradle.kts → version.txt). */
object BuildInfo {
    val version: String by lazy {
        BuildInfo::class.java.getResourceAsStream("/de/yoxcu/stoandl/version.txt")
            ?.bufferedReader()?.use { it.readText().trim() }
            ?.takeIf { it.isNotEmpty() } ?: "unknown"
    }
}
