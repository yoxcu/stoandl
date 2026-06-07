plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "de.yoxcu.stoandl"
version = "0.1.0"

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

    // DBus — listen to org.freedesktop.Notifications on the session bus
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.2")

    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("co.touchlab:kermit:2.0.8")

    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

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
