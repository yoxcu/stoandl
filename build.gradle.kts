plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "de.yoxcu.stoandl"

// Version comes from `git describe`: the exact tag (e.g. 0.1.0) when built on a release tag with a
// clean tree, otherwise <tag>-<commits>-g<hash> with a `-dirty` suffix for uncommitted changes
// (falls back to a bare commit hash when there are no tags yet).
val projectVersion: String = run {
    try {
        val proc = ProcessBuilder("git", "describe", "--tags", "--always", "--dirty")
            .directory(projectDir).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        if (proc.exitValue() == 0 && text.isNotEmpty()) text.removePrefix("v") else "0.1.0-nogit"
    } catch (e: Exception) {
        "0.1.0-nogit"
    }
}
version = projectVersion

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("com.coredevices:libpebble3:micropebble")

    // Koin DI (needed because libpebble3 exposes it as implementation, not api)
    implementation("io.insert-koin:koin-core:4.1.1")

    // Ktor HTTP client engine for JVM (libpebble3 uses Ktor but only adds engine in jvmTest).
    // Also used by the weather sync to query Open-Meteo.
    implementation("io.ktor:ktor-client-cio:3.4.0")

    // JSON parsing for the Open-Meteo weather response (version matches libpebble3's catalog).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // iCalendar parsing + RRULE recurrence expansion for calendar sync (pure-JVM, BSD-3, java.time).
    implementation("org.mnode.ical4j:ical4j:4.1.1")

    // DBus — listen to org.freedesktop.Notifications on the session bus
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.2")

    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("co.touchlab:kermit:2.0.8")

    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

// Bake the version into a classpath resource so the daemon/CLI can report it (`stoandl version`).
val generateVersionFile by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/version")
    inputs.property("version", projectVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("de/yoxcu/stoandl/version.txt").asFile
        file.parentFile.mkdirs()
        file.writeText(projectVersion)
    }
}

sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/version"))
tasks.named("processResources") { dependsOn(generateVersionFile) }

// Generate the language-pack catalog resource from the libpebble3 submodule's own manifest, so our
// bundled copy can never drift from the fork. The official Core app embeds the catalog as a
// triple-quoted JSON string (`LanguagePacksJson`) in LanguagePackRepository.kt — which lives in the
// Compose `pebble` module stoandl doesn't depend on — so we extract that string verbatim at build
// time into a classpath resource (read by de.yoxcu.stoandl.language.LanguagePackCatalog). Bumping the
// submodule automatically refreshes the catalog; there's no hand-maintained copy to forget.
val generateLanguagePackCatalog by tasks.registering {
    val source = layout.projectDirectory.file(
        "libs/libpebble3/pebble/src/commonMain/kotlin/coredevices/pebble/services/LanguagePackRepository.kt"
    )
    val outputDir = layout.buildDirectory.dir("generated/language")
    inputs.file(source)
    outputs.dir(outputDir)
    doLast {
        val src = source.asFile
        require(src.isFile) {
            "Language-pack source not found: ${src.path}\nRun: git submodule update --init --recursive"
        }
        val text = src.readText()
        val marker = "LanguagePacksJson = \"\"\""
        val start = text.indexOf(marker)
        require(start >= 0) { "`$marker` not found in ${src.name} — has the fork's catalog moved or been renamed?" }
        val from = start + marker.length
        val end = text.indexOf("\"\"\"", from)
        require(end >= 0) { "closing triple-quote for LanguagePacksJson not found in ${src.name}" }
        // `.trimIndent()` mirrors what the Kotlin source itself does at runtime (`""".trimIndent()`).
        val json = text.substring(from, end).trimIndent().trim()
        require(json.startsWith("{") && json.contains("\"languages\"")) {
            "Extracted language-pack JSON doesn't look right (no \"languages\" array) — check ${src.name}"
        }
        val out = outputDir.get().file("language-packs.json").asFile
        out.parentFile.mkdirs()
        out.writeText(json + "\n")
        logger.lifecycle("Generated language-packs.json (${json.length} chars) from ${src.name}")
    }
}

sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/language"))
tasks.named("processResources") { dependsOn(generateLanguagePackCatalog) }

// Note: the BecomeMonitor no-reply fix (DbusNotificationMonitor.kt) reflects into dbus-java
// internals, but needs no `--add-opens`: the fat JAR runs on the classpath, where dbus-java is in
// the unnamed module (no strong encapsulation). Passing those flags only triggered
// "WARNING: Unknown module: org.freedesktop.dbus specified to --add-opens" — see packaging/.
application {
    mainClass.set("de.yoxcu.stoandl.MainKt")
}

// Fat JAR for deployment to postmarketOS.
// Shadow plugin merges META-INF/services/* files so GraalVM Truffle language discovery works.
tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "de.yoxcu.stoandl.MainKt"
    }
    mergeServiceFiles()
}
