# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**stoandl** is a headless Pebble smartwatch companion daemon for Linux. It bridges the D-Bus session bus (desktop notifications) to a Pebble watch over BLE via libpebble3/BlueZ. No UI. Runs as a systemd user service.

## Commands

```sh
./gradlew run          # run locally
./gradlew shadowJar    # build fat JAR → build/libs/stoandl-<version>-all.jar
java -jar build/libs/stoandl-*.jar   # run the fat JAR manually
```

> Note: no `--add-opens` flags are needed. The BecomeMonitor fix reflects into dbus-java internals,
> but the fat JAR runs on the classpath where dbus-java is in the unnamed module (no encapsulation).
> Passing `--add-opens=org.freedesktop.dbus/...` only triggers a harmless
> `WARNING: Unknown module: org.freedesktop.dbus` (no such named module), so it was dropped everywhere.

Install and control (requires systemd service running):
```sh
./install.sh                          # build jar, install service, restart
./install.sh --remote user@host       # build locally, scp + install on remote via SSH
./install.sh --remote user@host -d    # same, with debug logging drop-in
stoandl apps install app.pbw              # sideload a .pbw onto the connected watch
```

There are no automated tests in this project.

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
libpebble3 (composite build submodule: libs/libpebble3, stoandl branch)
    ▼
BlueZ GATT server (reversedPPoG=false: phone acts as BLE peripheral)
    ▼
Pebble watch over BLE/PPoG
```

**Key design points:**

- `monitorNotifications()` (`DbusNotificationMonitor.kt`) uses `DBusMonitoring.BecomeMonitor` — the notification is a *passive copy*; the original still reaches the system notification daemon (dunst, mako, etc.). After `BecomeMonitor` succeeds, the writer on `TransportConnection` is replaced with a no-op via reflection to prevent dbus-java's auto-reply from closing the monitor connection.

- `PebbleIntegration.kt` initializes Koin (libpebble3's DI), then overrides two bindings: `NotificationListenerConnection` (swapped for `DbusNotificationListenerConnection` which bridges the D-Bus `Flow`) and `BleConfigFlow` (pinned to `reversedPPoG=false` so no persisted Java Preferences can override it).

- `KermitSlf4jWriter` bridges libpebble3's Kermit logger into SLF4J/Logback. Tag names are cleaned: strips `/{...}` and `-{...}` device-path suffixes, and also plain app-name suffixes (`RhinoJsRunner-Hooky` → `RhinoJsRunner`) so logback entries match without knowing the app name.

- Logs go to `/tmp/stoandl.log` (rolling, 5 MB × 3) and stdout. Default level is INFO: startup, scan, watch connected, notifications, PKJS lifecycle. Set `STOANDL_LOG=DEBUG` (env var or `-DSTOANDL_LOG=DEBUG` JVM flag) for full BLE/protocol packet traces.

## libpebble3 submodule

The dependency is a patched fork of upstream [`coredevices/libpebble3`](https://github.com/coredevices/libpebble3) (`yoxcu/libpebble3`, branch `stoandl`), included as a git submodule at `libs/libpebble3`. It tracks `coredevices/master` directly and is wired via Gradle composite build in `settings.gradle.kts` — no Maven publish needed. After cloning, run `git submodule update --init --recursive`.

The fork adds: a pure-BlueZ D-Bus BLE backend + GATT server, a Bluetooth Classic (RFCOMM/SPP) transport for classic-era watches, PPoG handshake/reconnect fixes for Linux BLE, BlueZ pairing/bonding, and a GraalJS PKJS runtime for watchapp companion JS. It builds the JVM target; the Android-SDK-only modules are gated behind `ANDROID_HOME`, and the iOS targets are kept (they're load-bearing for the Room codegen).

## PKJS (PebbleKit JS)

Watchapps can ship a `pkjs/index.js` companion script. libpebble3 runs it in **GraalJS** (GraalVM JS, 24.2.x) via `GraalJsRunner`. The JS bridge initialises when the watch connects and the app is launched; look for `Pebble JS Bridge initialized.` in the log.

GraalJS is a full, spec-compliant ECMAScript engine — modern JS (classes, `for...of`, default/rest params, computed keys, etc.) all work. No Rhino-style syntax workarounds are needed.

> **Note:** PKJS originally ran on Mozilla Rhino 1.7.15 and was migrated to GraalJS. The trigger was Clay's `tosource()`, which runs a 60-alternative regex on every object key — pathologically slow on Rhino's NFA, fine on GraalJS's TruffleRegex DFA.

**GraalJS gotchas (build/runtime, not syntax):**
- The fat JAR **must** merge `META-INF/services/` (Shadow plugin `mergeServiceFiles()`). A plain `DuplicatesStrategy.EXCLUDE` silently drops the TruffleRegex service registration → `No language for id regex found` at runtime.
- The fat JAR **must** keep `Multi-Release: true` in its manifest. GraalVM polyglot 25.x ships Multi-Release JARs (`META-INF/versions/{9,21}/…`); Shadow preserves the versioned classes but drops the manifest attribute, so the JVM ignores them and Truffle throws `InternalError: Truffle could not be initialized because Multi-Release classes are not configured correctly … Multi-Release … has been lost` at `Context.build()` (PKJS never inits). Fix: `manifest { attributes["Multi-Release"] = "true" }` in `tasks.shadowJar`.
- Don't set `js.esversion` as a `GraalJsRunner` option — it isn't a valid GraalJS option and throws.
- When building JS strings to `eval` (e.g. injecting an XHR response body), JSON-encode the value (`Json.encodeToString(...)`) — don't hand-escape. Unescaped `\n`/`\r`/control chars cause a silent `PolyglotException` and the JS callbacks never fire.

There is no offline syntax harness (the GraalJS language jars aren't in the Gradle module cache); verify PKJS changes by running the daemon and watching the log for `Pebble JS Bridge initialized.` and the script's `console.log` output.

## Deployment (postmarketOS / systemd user service)

```sh
sudo install -Dm644 build/libs/stoandl-*.jar /usr/lib/stoandl/stoandl.jar
sudo install -Dm644 packaging/stoandl.service /usr/lib/systemd/user/stoandl.service
systemctl --user daemon-reload
systemctl --user enable --now stoandl
```

The service unit sets `DBUS_SESSION_BUS_ADDRESS=unix:path=%t/bus` so it finds the user session bus without a graphical login.

## GUI / D-Bus contract

The daemon's public control surface — for the CLI and any future GUI (e.g. a Kirigami front-end) —
is the single D-Bus interface `de.yoxcu.stoandl.Control` (session bus, name `de.yoxcu.stoandl`,
path `/de/yoxcu/stoandl`), defined in `dbus/StoandlControl.kt` and implemented by
`StoandlControlImpl` in `PebbleIntegration.kt`. It is **methods only — zero signals, zero
properties** — so clients poll; long-running ops (pair, firmware flash, language install) report
progress via polled status strings (`PairStatus`/`FirmwareStatus`/`LanguageStatus`). `backup`,
`restore` and `support` are **CLI-local** (no D-Bus method).

See [docs/dbus-interface.md](docs/dbus-interface.md) for the full method catalog (D-Bus signatures,
CLI mapping, tab-separated record layouts, status-string conventions) **and** the GUI gap analysis
per planned screen (Watch, Apps & Faces, Plugins, Sync, System) — i.e. the signals/fields/methods
to add for a reactive GUI. Keep that doc in sync when you change the interface.
