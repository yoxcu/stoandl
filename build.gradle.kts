plugins {
    kotlin("jvm") version "2.3.10"
    application
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

    // Ktor HTTP client engine for JVM (libpebble3 uses Ktor but only adds engine in jvmTest)
    implementation("io.ktor:ktor-client-cio:3.4.0")

    // DBus — listen to org.freedesktop.Notifications on the session bus
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.2")

    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("co.touchlab:kermit:2.0.8")

    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

// JVM flags required for the BecomeMonitor no-reply fix (see DbusNotificationMonitor.kt):
// we replace the IMessageWriter in TransportConnection with a no-op via reflection so
// dbus-java's auto-reply doesn't cause the daemon to close the monitor connection.
// For the fat JAR, pass these flags to `java` manually:
//   java --add-opens=org.freedesktop.dbus/org.freedesktop.dbus.connections.base=ALL-UNNAMED \
//        --add-opens=org.freedesktop.dbus/org.freedesktop.dbus.connections.transports=ALL-UNNAMED \
//        -jar stoandl.jar
val monitorOpenFlags = listOf(
    "--add-opens=org.freedesktop.dbus/org.freedesktop.dbus.connections.base=ALL-UNNAMED",
    "--add-opens=org.freedesktop.dbus/org.freedesktop.dbus.connections.transports=ALL-UNNAMED",
)

application {
    mainClass.set("de.yoxcu.stoandl.MainKt")
    applicationDefaultJvmArgs = monitorOpenFlags
}

// Fat JAR for deployment to postmarketOS
tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "de.yoxcu.stoandl.MainKt"
    }
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else if (it.name.endsWith(".jar")) zipTree(it) else null }.filterNotNull() })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
