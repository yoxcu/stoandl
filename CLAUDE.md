# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**stoandl** is a headless Pebble smartwatch companion daemon for Linux. It bridges the D-Bus session bus (desktop notifications) to a Pebble watch over BLE via libpebble3/BlueZ. No UI. Runs as a systemd user service.

## Commands

```sh
./gradlew run          # run locally (includes required --add-opens JVM flags)
./gradlew jar          # build fat JAR → build/libs/stoandl-<version>.jar
```

To run the fat JAR manually, the `--add-opens` flags are required:
```sh
java \
  --add-opens=org.freedesktop.dbus/org.freedesktop.dbus.connections.base=ALL-UNNAMED \
  --add-opens=org.freedesktop.dbus/org.freedesktop.dbus.connections.transports=ALL-UNNAMED \
  -jar build/libs/stoandl-*.jar
```

There are no tests in this project.

## Architecture

```
Desktop apps
    │  Notify() on org.freedesktop.Notifications (D-Bus session bus)
    ▼
DbusNotificationMonitor   ← passive BecomeMonitor copy (does NOT intercept)
    │  Flow<IncomingNotification>  (MutableSharedFlow, buffer 64)
    ▼
PebbleIntegration / DbusNotificationListenerConnection
    │  buildTimelineNotification → libPebble.sendNotification()
    ▼
libpebble3 (composite build submodule: libs/libpebble3, micropebble branch)
    ▼
BlueZ GATT server (reversedPPoG=false: phone acts as BLE peripheral)
    ▼
Pebble watch over BLE/PPoG
```

**Key design points:**

- `monitorNotifications()` (`DbusNotificationMonitor.kt`) uses `DBusMonitoring.BecomeMonitor` — the notification is a *passive copy*; the original still reaches the system notification daemon (dunst, mako, etc.). After `BecomeMonitor` succeeds, the writer on `TransportConnection` is replaced with a no-op via reflection to prevent dbus-java's auto-reply from closing the monitor connection.

- `PebbleIntegration.kt` initializes Koin (libpebble3's DI), then overrides two bindings: `NotificationListenerConnection` (swapped for `DbusNotificationListenerConnection` which bridges the D-Bus `Flow`) and `BleConfigFlow` (pinned to `reversedPPoG=false` so no persisted Java Preferences can override it).

- `KermitSlf4jWriter` bridges libpebble3's Kermit logger into SLF4J/Logback. Tag names are cleaned (strips `/{...}` and `-{...}` device-path suffixes) before creating loggers.

- Logs go to `/tmp/stoandl.log` (rolling, 5 MB × 3) and stdout. Log levels are tuned in `logback.xml` — libpebble3 internals are suppressed to INFO/WARN to reduce noise.

## libpebble3 submodule

The dependency is a patched fork (`yoxcu/libpebble3`, branch `stoandl`) included as a git submodule at `libs/libpebble3`. It is wired via Gradle composite build in `settings.gradle.kts` — no Maven publish needed. After cloning, run `git submodule update --init --recursive`.

The fork adds: BlueZ GATT server, PPoG handshake fixes for Linux BLE, BlueZ pairing/bonding, and strips Android/iOS targets to speed up JVM builds.

## Deployment (postmarketOS / systemd user service)

```sh
sudo install -Dm644 build/libs/stoandl-*.jar /usr/lib/stoandl/stoandl.jar
sudo install -Dm644 packaging/stoandl.service /usr/lib/systemd/user/stoandl.service
systemctl --user daemon-reload
systemctl --user enable --now stoandl
```

The service unit sets `DBUS_SESSION_BUS_ADDRESS=unix:path=%t/bus` so it finds the user session bus without a graphical login.
