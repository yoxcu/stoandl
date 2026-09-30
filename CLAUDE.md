# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**stoandl** is a headless Pebble smartwatch companion daemon for Linux. It bridges the D-Bus session bus (desktop notifications) to a Pebble watch over BLE via libpebble3/BlueZ. No UI. Runs as a systemd user service.

## Commands

```sh
./gradlew run          # run locally
./gradlew shadowJar    # build fat JAR → build/libs/stoandl-<version>-all.jar
java -jar build/libs/stoandl-*.jar   # run the fat JAR manually
./gradlew test                              # daemon unit tests
./gradlew :libpebble3:libpebble3:jvmTest    # the fork's JVM tests (included build)
```

Gradle 8.14.4 runs on JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`); Kotlin 2.4.10 compiles against a
JDK 25 toolchain, and the daemon needs JDK 25 at runtime (the Classic transport uses `java.lang.foreign`).
The outer dependency pins in `build.gradle.kts` (Kotlin, Koin, Ktor, kotlinx, kermit) follow the fork's
`gradle/libs.versions.toml`; move them together.

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

Most behaviour needs a watch and a session bus and is tested by hand from [TESTING.md](TESTING.md). The unit
tests cover pure logic whose mistakes are silent: the settings schema against the config parser, the
analytics-heartbeat layouts, firmware release selection and the downgrade handoff, iCal all-day dates and
watch-pref ranges (daemon, `src/test`); PPoG, the Koin graph, PKJS on GraalJS and notification catch-up
(fork jvmTest).

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
libpebble3 (composite build submodule: libs/libpebble3, stoandl-bump branch)
    ▼
BlueZ GATT server (forward PPoG: phone acts as BLE peripheral)
    ▼
Pebble watch over BLE/PPoG
```

**Key design points:**

- `monitorNotifications()` (`DbusNotificationMonitor.kt`) uses `DBusMonitoring.BecomeMonitor` — the notification is a *passive copy*; the original still reaches the system notification daemon (dunst, mako, etc.). After `BecomeMonitor` succeeds, the writer on `TransportConnection` is replaced with a no-op via reflection to prevent dbus-java's auto-reply from closing the monitor connection.

- `PebbleIntegration.kt` initializes Koin (libpebble3's DI), then loads an override module. It swaps `NotificationListenerConnection` for `DbusNotificationListenerConnection` (bridges the D-Bus `Flow`), replaces libpebble3's no-op JVM platform bindings (notification actions, music, calendar, call log, geolocation, time changes, platform flags), and pins four config flows to one host-built `LibPebbleConfig` so no persisted Java Preferences (`LibPebbleConfigHolder` loads storage over the host default) can override them: `LibPebbleConfigFlow`/`BleConfigFlow` (forward PPoG, `legacyReversedPPoG=false` + `useReversedPpogV2=false`; upstream `PebbleBle` picks the transport from `LibPebbleConfigFlow`), `WatchConfigFlow` (`lanDevConnection=true`) and `NotificationConfigFlow` (`missedNotificationCatchUpMs` from `notification.catch_up_minutes`, the fork's notification catch-up after a disconnect).

- `KermitSlf4jWriter` bridges libpebble3's Kermit logger into SLF4J/Logback. Tag names are cleaned: strips `/{...}` and `-{...}` device-path suffixes, and also plain app-name suffixes (`RhinoJsRunner-Hooky` → `RhinoJsRunner`) so logback entries match without knowing the app name.

- `SqliteNative.prepare()` (called in `main()` before `PebbleIntegration.init()`) must run before anything opens `libpebble3.db`. androidx's bundled `libsqliteJni.so` is built for glibc and imports `__isnan`; on musl the JVM's `RTLD_LAZY` load leaves it unresolved and the first REAL value in any SQL SIGSEGVs the JVM. On musl it `dlopen`s an embedded libc-free shim (`tools/isnan-shim/`, rebuilt with its `build.sh` into `src/main/resources/de/yoxcu/stoandl/natives/`) `RTLD_NOW|RTLD_GLOBAL` via FFM — `System.load` is `RTLD_LOCAL` and won't do (from the cache, else a throwaway copy in `java.io.tmpdir`). It also extracts `libsqliteJni.so` once to `~/.cache/stoandl/native/`, `System.load`s it, and only then points the driver there (`androidx.sqlite.driver.bundled.path`) instead of a new `/tmp/androidx_sqliteJni*.tmp` per start — once that property is set the driver never falls back, and loading it first from the same class loader makes the driver's own load a no-op (its `JNI_OnLoad` only registers natives on the `*Kt` facades, which have no static initialisers). Re-check all of this when bumping androidx `sqlite-bundled` (`readelf --dyn-syms` on its `natives/linux_*/libsqliteJni.so` for new glibc-only imports; `javap -c -p` on its `NativeLibraryLoader` for the property).

- `power/` makes the daemon suspend-aware for phones that deep-sleep (see [docs/deep-sleep.md](docs/deep-sleep.md)): `SleepGuard` holds a logind *delay* lock (a `systemd-inhibit … cat` child — dbus-java's native transport can't receive logind's fd) and drains pending watch traffic (libpebble3 `WatchLinkActivity`) on `PrepareForSleep(true)`; periodic work uses `delayWallClock()` (monotonic `delay()` stops while suspended) and re-checks on `SleepGuard.resumed`. Never use a *block* lock for routine work — it makes a one-shot `systemctl suspend` fail.

- Logs go to `/tmp/stoandl.log` (rolling, 5 MB × 3) and stdout. Default level is INFO: startup, scan, watch connected, notifications, PKJS lifecycle. Set `STOANDL_LOG=DEBUG` (env var or `-DSTOANDL_LOG=DEBUG` JVM flag) for full BLE/protocol packet traces.

## libpebble3 submodule

The dependency is a patched fork of upstream [`coredevices/libpebble3`](https://github.com/coredevices/libpebble3) (`yoxcu/libpebble3`), included as a git submodule at `libs/libpebble3`. It tracks `coredevices/master` directly and is wired via Gradle composite build in `settings.gradle.kts` — no Maven publish needed. After cloning, run `git submodule update --init --recursive`.

The submodule follows fork branch **`stoandl-bump`**: the fork rebased as a linear history onto upstream `e6b5138e`, plus the deep-sleep commits. It replaces the old branch `stoandl` (base `e4180ffc`) once it passes the hardware pass in TESTING §5.33; then `.gitmodules` goes back to `stoandl`. Push the fork branch, and the `gui` submodule's branch, **before** pushing a stoandl commit that records them: CI builds every push to `main` and every tag (`git submodule update --init --depth 1 libs/libpebble3`), and the APKBUILD downloads the recorded commit's archive from `yoxcu/libpebble3`, so a gitlink GitHub doesn't have yet breaks the build. [FIRMWARE-GAPS.md §3](FIRMWARE-GAPS.md) is the runbook for the next bump.

The fork adds: a pure-BlueZ D-Bus BLE backend + GATT server, a Bluetooth Classic (RFCOMM/SPP) transport for classic-era watches, PPoG handshake/reconnect fixes for Linux BLE, BlueZ pairing/bonding, and a GraalJS PKJS runtime for watchapp companion JS. It builds the JVM target. The Android-SDK-only modules sit behind one gate in its `settings.gradle.kts`: an SDK (`ANDROID_HOME` or `sdk.dir`), a Gradle at or above AGP's minimum (9.5 for AGP 9.3.1), and a standalone build, so they are never on inside stoandl's composite. The iOS targets are kept (they're load-bearing for the Room codegen).

## PKJS (PebbleKit JS)

Watchapps can ship a `pkjs/index.js` companion script. libpebble3 runs it in **GraalJS** (GraalVM JS, 25.0.x) via `GraalJsRunner`. The JS bridge initialises when the watch connects and the app is launched; look for `Pebble JS Bridge initialized.` in the log.

GraalJS is a full, spec-compliant ECMAScript engine — modern JS (classes, `for...of`, default/rest params, computed keys, etc.) all work. No Rhino-style syntax workarounds are needed.

> **Note:** PKJS originally ran on Mozilla Rhino 1.7.15 and was migrated to GraalJS. The trigger was Clay's `tosource()`, which runs a 60-alternative regex on every object key — pathologically slow on Rhino's NFA, fine on GraalJS's TruffleRegex DFA.

**GraalJS gotchas (build/runtime, not syntax):**
- The fat JAR **must** merge `META-INF/services/` (Shadow plugin `mergeServiceFiles()`). A plain `DuplicatesStrategy.EXCLUDE` silently drops the TruffleRegex service registration → `No language for id regex found` at runtime.
- The fat JAR **must** keep `Multi-Release: true` in its manifest. GraalVM polyglot 25.x ships Multi-Release JARs (`META-INF/versions/{9,21}/…`); Shadow preserves the versioned classes but drops the manifest attribute, so the JVM ignores them and Truffle throws `InternalError: Truffle could not be initialized because Multi-Release classes are not configured correctly … Multi-Release … has been lost` at `Context.build()` (PKJS never inits). Fix: `manifest { attributes["Multi-Release"] = "true" }` in `tasks.shadowJar`.
- Don't set `js.esversion` as a `GraalJsRunner` option — it isn't a valid GraalJS option and throws.
- When building JS strings to `eval` (e.g. injecting an XHR response body), JSON-encode the value (`Json.encodeToString(...)`) — don't hand-escape. Unescaped `\n`/`\r`/control chars cause a silent `PolyglotException` and the JS callbacks never fire.

libpebble3's `GraalJsRunnerTest` (jvmTest, `:libpebble3:libpebble3:jvmTest`) runs PKJS offline on GraalJS with the real shims: bridge init, the host-access allow-list, AppMessage/config round trips, intercepted and reused XHRs. Add a case there for a shim change; still confirm on hardware via `Pebble JS Bridge initialized.` and the script's `console.log` output in the log.

## Deployment (postmarketOS / systemd user service)

```sh
sudo install -Dm644 build/libs/stoandl-*.jar /usr/lib/stoandl/stoandl.jar
sudo install -Dm644 packaging/stoandl.service /usr/lib/systemd/user/stoandl.service
systemctl --user daemon-reload
systemctl --user enable --now stoandl
```

The service unit sets `DBUS_SESSION_BUS_ADDRESS=unix:path=%t/bus` so it finds the user session bus without a graphical login.

## GUI / D-Bus contract

The daemon's public control surface — for the CLI and the GUI (the `gui/` submodule, which ships a
Kirigami and a GTK front-end) — is the single D-Bus interface `de.yoxcu.stoandl.Control` (session bus,
name `de.yoxcu.stoandl`, path `/de/yoxcu/stoandl`), defined in `dbus/StoandlControl.kt` and implemented
by `StoandlControlImpl` in `PebbleIntegration.kt`. The **methods are the source of truth**; seven
signals (`WatchesChanged`, `FirmwareProgress`, `LockerChanged`, `LanguageProgress`, `CalendarsChanged`,
`ExtensionsChanged`, `ExtensionStateChanged`) are a reactive layer on top, and there are no properties.
The daemon isn't D-Bus-activated, so a client can miss a signal: it re-reads the method when the name
appears and keeps a slow fallback poll. Long-running ops (pair, firmware flash, language install)
report progress via polled status strings (`PairStatus`/`FirmwareStatus`/`LanguageStatus`), which the
CLI uses. `backup`, `restore` and `support` are **CLI-local** (no D-Bus method). The GUI settings page
renders whatever `GetConfigSchema` advertises (`config/ConfigSchema.kt`), so a new config key needs a
schema row, not GUI code.

See [docs/dbus-interface.md](docs/dbus-interface.md) for the full method catalog (D-Bus signatures,
CLI mapping, tab-separated record layouts, status-string conventions) **and** the GUI gap analysis
per screen (Watch, Apps & Faces, Plugins, Sync, System). Keep that doc in sync when you change the
interface.
