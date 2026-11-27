plugins {
    // Kotlin, Ktor, kotlinx, kermit and Koin come from the fork's catalog (libs/libpebble3/gradle/
    // libs.versions.toml, `forkLibs` in settings.gradle.kts): the daemon compiles as a friend of the
    // fork's jvm module (below), so it must build with the same compiler and libraries.
    alias(forkLibs.plugins.kotlin.jvm)
    alias(forkLibs.plugins.kotlin.serialization)
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
    // JDK 25 toolchain (auto-detected). The Gradle 8.14 daemon stays on JDK 21 — only the compiled
    // bytecode and the daemon/CLI runtime need JDK 25 (libpebble3's BT Classic transport uses the
    // finalized java.lang.foreign API). Bumping Gradle is intentionally avoided (see CLAUDE.md).
    jvmToolchain(25)
}

dependencies {
    // The version here is irrelevant: settings.gradle.kts substitutes this module with the
    // libs/libpebble3 composite build, so the coordinate is never resolved against a repository.
    implementation("com.coredevices:libpebble3:composite")

    // Compile-only, and ONLY so the compiler can resolve the supertypes of fork classes the daemon uses:
    // upstream's public PKJSApp (LibPebble's currentPKJSSession) is a KoinComponent. The daemon never uses
    // Koin itself (its door into the graph is the fork's JvmHost / createJvmLibPebble); `noKoinInDaemon`
    // fails `test` and `shadowJar` on any `org.koin.` use in src/, imported or fully qualified. Runtime Koin
    // is libpebble3's own.
    compileOnly(forkLibs.koin.core)

    // Ktor HTTP client engine for the daemon's own clients (the weather sync's Open-Meteo queries, …);
    // libpebble3 keeps its CIO engine an implementation dependency.
    implementation(forkLibs.ktor.cio)

    // JSON parsing for the Open-Meteo weather response.
    implementation(forkLibs.serialization)

    // iCalendar parsing + RRULE recurrence expansion for calendar sync (pure-JVM, BSD-3, java.time).
    implementation("org.mnode.ical4j:ical4j:4.1.1")

    // DBus — listen to org.freedesktop.Notifications on the session bus
    implementation(forkLibs.kotlinx.io.core)

    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.0")

    implementation(forkLibs.coroutines)

    implementation(forkLibs.kermit)

    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Test-only. Most daemon behaviour needs a watch and a session bus and is tested by hand
    // (TESTING.md). The unit tests cover pure logic whose mistakes are silent: the settings surface (the
    // GUI config schema, its validation and its agreement with the config parser, which could corrupt
    // stoandl.conf or persist a value the daemon ignores), the analytics-heartbeat layouts (a wrong
    // entry shifts every value after it), firmware release selection and the downgrade handoff (driven
    // through fake watches; its hardware test risks the watch), iCal all-day dates across host zones,
    // and the watch-pref ranges. `gradle test` is not part of `shadowJar`, so nothing ships.
    testImplementation(kotlin("test"))
}

// The daemon (main and test) compiles as a FRIEND of libpebble3's jvm module, so it sees upstream's and
// the fork's `internal` declarations (upstream made most of its API internal under explicitApi). Where
// upstream offers a public API the daemon uses it; internals only where nothing public exists. The
// daemon has no Koin access: the fork's io.rebble.libpebblecommon.host.JvmHost / createJvmLibPebble is
// its one door into the graph. The filter matches the composite's libpebble3 jvm output (a jar today,
// a classes dir would match too); matching nothing fails the compile here, not with a wall of
// "Cannot access ...: it is internal" errors.
val libpebble3Friend: Provider<FileCollection> = configurations.compileClasspath.map { cp ->
    cp.filter { it.invariantSeparatorsPath.contains("/libs/libpebble3/libpebble3/build/") }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    friendPaths.from(libpebble3Friend) // appends: KGP's own test->main friend path stays
    val friends = libpebble3Friend
    doFirst {
        check(!friends.get().isEmpty) {
            "No libpebble3 jvm output on the compile classpath to make a friend of (expected a path under " +
                "libs/libpebble3/libpebble3/build/): did the composite's output move?"
        }
    }
}

// The guard behind the compile-only Koin above: the daemon reaches libpebble3's graph only through
// JvmLibPebble's typed doors, never through Koin.
val noKoinInDaemon by tasks.registering {
    val sources = fileTree("src") { include("**/*.kt") }
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("sources")
    doLast {
        // Any org.koin. reference on a code line, imported or fully qualified (`org.koin.core.Koin` in a type
        // or a call): Koin is on the compile classpath, so only this keeps it out. Comment lines don't count.
        val koinUse = Regex("""^(?!\s*(//|\*|/\*)).*\borg\.koin\.""", RegexOption.MULTILINE)
        val hits = sources.filter { koinUse.containsMatchIn(it.readText()) }.map { it.relativeTo(projectDir) }
        check(hits.isEmpty()) { "The daemon must not use Koin (use JvmLibPebble's doors); org.koin in: ${hits.joinToString()}" }
    }
}

tasks.test {
    dependsOn(noKoinInDaemon)
    useJUnitPlatform()
    // The address order main() sets, so the tests' sockets resolve and bind as the daemon's do.
    systemProperty("java.net.preferIPv6Addresses", "system")
    // Shipped files the tests compare against (conf.example, the GUI mock's goldens) live outside the test
    // sources: without them as inputs an edit to one leaves `test` UP-TO-DATE and the mismatch unseen.
    inputs.files("packaging/stoandl.conf.example", fileTree("gui/tools") { include("*.tsv") })
        .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("goldens")
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

// The language-pack catalog (src/main/resources/language-packs.json, read by
// de.yoxcu.stoandl.language.LanguagePackCatalog) is a frozen, checked-in copy: upstream deleted the
// literal it used to be extracted from (7cf80230, packs moved to a server manifest), and fetching that
// manifest would be new egress. Its `_origin` key names the source commit.

// Note: the BecomeMonitor no-reply fix (DbusNotificationMonitor.kt) reflects into dbus-java
// internals, but needs no `--add-opens`: the fat JAR runs on the classpath, where dbus-java is in
// the unnamed module (no strong encapsulation). Passing those flags only triggered
// "WARNING: Unknown module: org.freedesktop.dbus specified to --add-opens" — see packaging/.
application {
    mainClass.set("de.yoxcu.stoandl.MainKt")
    // libpebble3's BT Classic transport reaches libc via java.lang.foreign; on JDK 25 native access
    // for unnamed-module code is restricted and warns without this. NEVER --enable-preview (FFM is a
    // final API on 22+; --enable-preview would fail on a release JDK). Only for `gradlew run`, which
    // runs from the classpath: the packaged launchers run the fat JAR, whose manifest grants it.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Fat JAR for deployment to postmarketOS.
// Shadow plugin merges META-INF/services/* files so GraalVM Truffle language discovery works.
tasks.shadowJar {
    dependsOn(noKoinInDaemon)
    manifest {
        attributes["Main-Class"] = "de.yoxcu.stoandl.MainKt"
        // GraalVM polyglot 25.x ships Multi-Release JARs (META-INF/versions/{9,21}/…). Shadow keeps
        // the versioned classes but drops the manifest attribute, so the JVM ignores them and
        // Truffle's CheckMultiReleaseSupport throws "Multi-Release … lost" at Context.build() (PKJS
        // init). Re-declaring it makes the JVM load the versioned Truffle classes again.
        attributes["Multi-Release"] = "true"
        // The manifest form of --enable-native-access=ALL-UNNAMED (JDK 22+, honoured for `java -jar`): the
        // FFM code (Classic transport, SqliteNative) and the SQLite driver's JNI load run without
        // restricted-method warnings. Every launcher (service, OpenRC, stoandl-ctl) relies on it.
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
    mergeServiceFiles()
    // Same sources, same JAR: no build timestamps in the entries, a fixed entry order.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    // Natives the daemon can never load, about 21 MB of the JAR:
    // - androidx sqlite-bundled's macOS and Windows libraries (Linux only).
    // - kable's btleplug backend: its native library (every platform, Linux too) and JNA, which only it
    //   uses. stoandl drives BLE over BlueZ D-Bus and never builds a kable peripheral or scanner, and
    //   kable's JVM `Bluetooth.availability` is a constant flow; the Classic transport uses FFM.
    // - Truffle's attach library for macOS and Windows.
    exclude(
        "natives/osx_*/**", "natives/windows_*/**",
        "darwin-*/libbtleplug_ffi.*", "linux-*/libbtleplug_ffi.so", "win32-*/btleplug_ffi.dll",
        "com/sun/jna/**",
        "META-INF/resources/engine/libtruffleattach/darwin/**",
        "META-INF/resources/engine/libtruffleattach/windows/**",
    )
}
