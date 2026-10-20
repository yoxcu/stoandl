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
> Nor does any launcher pass `--enable-native-access`: the fat JAR's manifest grants it
> (`Enable-Native-Access: ALL-UNNAMED`). Only `gradlew run` keeps it in `applicationDefaultJvmArgs`.

Install and control (requires systemd service running):
```sh
./install.sh                          # build jar (no `clean`: it drops only old fat jars), install service, restart
./install.sh --remote user@host       # build locally, copy + install on remote via SSH (an rsync delta into
                                      # the remote ~/.cache/stoandl/install when both ends have rsync)
./install.sh --remote user@host -d    # same, with debug logging drop-in
stoandl status                            # daemon, watch, services and watch tasks at a glance
stoandl apps install app.pbw              # sideload a .pbw onto the connected watch
```

Most behaviour needs a watch and a session bus and is tested by hand from [TESTING.md](TESTING.md). The unit
tests cover pure logic whose mistakes are silent: the settings schema against the config parser and its layout,
help, requires and egress rules (egress rows default off), `stoandl.conf.example` against the defaults, the GUI
mock's generated fixtures (config schema, watch-pref text), the watch-pref text table (sections, help, requires)
and reset, config reload (changed keys, the file watch), the daemon-get "used when" sentence, the
analytics-heartbeat layouts, firmware release selection, the downgrade handoff and the check cache (TTL, shared lookup, gzip, ETag 304), iCal all-day dates,
calendar source health, watch-pref ranges, the dialer-app match, the vCard folder walk, the notification monitor's reply matching, the notification layout (app-name placement, the per-app override file, body markup to plain text), the global mute, the call monitor's signal order, the Quiet Time schedule rule, the config-page server's `data:` decoding, close rewrite and Save round trip and its plugin-page bridge, the process runner's deadline, the slow D-Bus call warning, the CLI's D-Bus error mapping and exit codes, the app-icon `pbw-cache` listing, desktop→watch dismissal and the route table, the libpebble3 config projection, MPRIS seek, the image pipeline (decode, EXIF, crop/scale, the notification picture store, album art), voice (Speex frames → decoder → backend → watch result, WAV, Ogg demux, the two-file model store, Canary's language rule and output clean-up), text to speech (engine argv and speed, the voice by language, the watch-speaker encoder: low-pass, ADPCM blocks and a known vector, Speak/synthesize busy, the resident Piper's process lifecycle), the watch tasks and their user-dirs paths, the pairing-window decisions (the Classic step, the discovery predicate, new-watch resolution, goal restore) and the wrist follower's relink grace, extension-served plugins, the extension directory install and `stoandl ext config` (table, value checks, payload), plugin secrets, phone state and permission wording, the OAuth connectors, the PKJS weather interceptors and the weather city search (daemon, `src/test`); PPoG, the Koin graph, WatchManager's single connection slot, PKJS on GraalJS (sessions sharing one engine) and notification catch-up
(fork jvmTest). The assistant extension's Go tests also cover its speech (speak gating, Ask stopping the pump,
pacing, packing and the PLAY start, keepAwake renewal, the vendored speaker receiver).

## Architecture

```
Desktop apps
    │  Notify() on org.freedesktop.Notifications, AddNotification() on the portal
    │  backend (Plasma >= 6.7 Flatpaks) or org.gtk.Notifications (GNOME)  (D-Bus session bus)
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

- `monitorNotifications()` (`DbusNotificationMonitor.kt`) uses `DBusMonitoring.BecomeMonitor` — the notification is a *passive copy*; the original still reaches the system notification daemon (dunst, mako, etc.). After `BecomeMonitor` succeeds, the writer on `TransportConnection` is replaced with a no-op via reflection to prevent dbus-java's auto-reply from closing the monitor connection. It also watches portal/GTK `AddNotification`, the server's `NotificationClosed` and `NameOwnerChanged` for its name. The server rules name `org.freedesktop.Notifications` itself, so the bus follows its owner (login, restarts) and the monitor never rebuilds for it; pending `Notify` calls are keyed by (caller, serial). The `NameOwnerChanged` rule must have no `sender=` key: dbus-broker never delivers a sender-narrowed `arg0` rule to a monitor (dbus-daemon does, so test on dbus-broker). Wrist actions go back through `DesktopNotifOwner` (`pebble/DesktopNotifOwner.kt`): Dismiss via `CloseNotification` (GNOME Shell's own object as fallback) or the backend's `RemoveNotification`; Reply and app actions via Plasma's `org.kde.NotificationManager` (`InvokeReply` needs the plasma-workspace patch and is probed by introspection) — never `InvokeAction("inline-reply")`, which KNotifications turns into an empty reply.

- `PebbleIntegration.kt` initializes Koin (libpebble3's DI), then loads an override module. It swaps `NotificationListenerConnection` for `DbusNotificationListenerConnection` (bridges the D-Bus `Flow`), replaces libpebble3's no-op JVM platform bindings (notification actions, music, calendar, call log, geolocation, time changes, platform flags), and pins four config flows to one host-built `LibPebbleConfig` so no persisted Java Preferences (`LibPebbleConfigHolder` loads storage over the host default) can override them: `LibPebbleConfigFlow`/`BleConfigFlow` (forward PPoG, `legacyReversedPPoG=false` + `useReversedPpogV2=false`; upstream `PebbleBle` picks the transport from `LibPebbleConfigFlow`), `WatchConfigFlow` (`lanDevConnection=true`) and `NotificationConfigFlow` (`missedNotificationCatchUpMs` from `notification.catch_up_minutes`, the fork's notification catch-up after a disconnect).

- `KermitSlf4jWriter` bridges libpebble3's Kermit logger into SLF4J/Logback. Tag names are cleaned: strips `/{...}` and `-{...}` device-path suffixes, and also plain app-name suffixes (`RhinoJsRunner-Hooky` → `RhinoJsRunner`) so logback entries match without knowing the app name.

- `SqliteNative.prepare()` (called in `main()` before `PebbleIntegration.init()`) must run before anything opens `libpebble3.db`. androidx's bundled `libsqliteJni.so` is built for glibc and imports `__isnan`; on musl the JVM's `RTLD_LAZY` load leaves it unresolved and the first REAL value in any SQL SIGSEGVs the JVM. On musl it `dlopen`s an embedded libc-free shim (`tools/isnan-shim/`, rebuilt with its `build.sh` into `src/main/resources/de/yoxcu/stoandl/natives/`) `RTLD_NOW|RTLD_GLOBAL` via FFM — `System.load` is `RTLD_LOCAL` and won't do (from the cache, else a throwaway copy in `java.io.tmpdir`). It also extracts `libsqliteJni.so` once to `~/.cache/stoandl/native/`, `System.load`s it, and only then points the driver there (`androidx.sqlite.driver.bundled.path`) instead of a new `/tmp/androidx_sqliteJni*.tmp` per start — once that property is set the driver never falls back, and loading it first from the same class loader makes the driver's own load a no-op (its `JNI_OnLoad` only registers natives on the `*Kt` facades, which have no static initialisers). Re-check all of this when bumping androidx `sqlite-bundled` (`readelf --dyn-syms` on its `natives/linux_*/libsqliteJni.so` for new glibc-only imports; `javap -c -p` on its `NativeLibraryLoader` for the property).

- `power/` makes the daemon suspend-aware for phones that deep-sleep (see [docs/deep-sleep.md](docs/deep-sleep.md)): `SleepGuard` holds a logind *delay* lock (a `systemd-inhibit … cat` child — dbus-java's native transport can't receive logind's fd) and drains pending watch traffic (libpebble3 `WatchLinkActivity`) on `PrepareForSleep(true)`; periodic work uses `delayWallClock()` (monotonic `delay()` stops while suspended) and re-checks on `SleepGuard.resumed`. Never use a *block* lock for routine work — it makes a one-shot `systemctl suspend` fail. `QuietTimeLink` (`power.quiet_time_link_off`) pauses the link during the watch's scheduled Quiet Time through the fork's in-memory `WatchConnector.holdDisconnected()`; `stoandl watch disconnect` (`pebble/ManualDisconnect.kt`) is a second hold on the same call. Never pause with `requestDisconnection()`, which persists `connectGoal=false` across restarts. The display state (`power/ScreenState.kt` `DisplayState`) is one shared flow, sampled only while a follower (the datalog pause, the Quiet Time link pause) collects it; it emits every sample, repeats included, without replay, so a follower re-syncs on the first sample after a resume. Long-lived D-Bus listeners suspend in `WatchedBus.awaitDisconnect()` (`util/DbusConnections.kt`) instead of polling `isConnected`: an idle daemon makes no periodic wakeups while awake.

- Startup takes two bus names (`Main.kt`, `PebbleIntegration.init()`): the private `de.yoxcu.stoandl.Instance` first thing, the single-instance guard `SqliteNative.prepare()` relies on (a second daemon exits there before touching anything shared), and the public `de.yoxcu.stoandl` last, once Control is exported and everything it reports has started, so a client can call as soon as it appears. The service connection (`openServiceBus()`) runs 12 method-call threads. `stoandl.conf` is watched (`config/ConfigReload.kt`): 500 ms after an edit by hand, `restore` or another process, `reloadConfig()` applies what changed since the last applied version, the same path the daemon's own writes take (`persistConfig`), and announces each key with `ConfigChanged`.

- Pairing a watch (`pebble/PairingWindow.kt` and the pair monitor in `PebbleIntegration.kt`) has ONE owner of the connect goal per watch: the Classic handler acts only on watches new to stoandl, inside a pairing window, once per window (nothing when Bluetooth is off or the bond is unreadable); known watches reconnect only through libpebble3's `connectGoal` and the wrist follower. A window resolves only on a watch that wasn't known when it opened (a `Repair`'s watch once it has been seen without a link) and on decline or timeout gives the goal back to the watch that held it. The follower (`connection.autoswitch`) gives a watch that just dropped after a resume, or was torn down by this host, 90 s to re-link instead of 20 s.

- Text to speech is `voice/tts/` next to `voice/` (speech to text): `TtsService` runs one synthesis at a time for D-Bus `Speak` (plays on this device) and an extension's `synthesize` (audio back for the watch speaker), and `WatchSpeakerEncoding` encodes the watch-speaker blocks. `ResidentPiper` keeps libpiper's `piper_exe` loaded, one process per voice, fed `--json-input` lines; a sentence is done when its `output_file` exists and is no longer open in `/proc/<pid>/fd` (piper prints the path, but its stdout is block-buffered on a pipe, so never wait for that line). Any other Piper stays one process per sentence. The block format is binding for that encoder and the watch receiver `examples/extensions/watchlib/stoandl_speaker.c`: [docs/speaker-stream.md](docs/speaker-stream.md). Engines, players and volume tools are found with one PATH probe, `onPath` in `util/Process.kt`.

- The daemon logs to `/tmp/stoandl.log` (rolling, 5 MB × 3; tmpfs, so no flash writes) and stdout (`logback.xml`). Default level is INFO: startup, scan, watch connected, notifications, PKJS lifecycle. Set `STOANDL_LOG=DEBUG` (env var or `-DSTOANDL_LOG=DEBUG` JVM flag) for the decisions behind them (sleep guard, connect/drop reasons, catch-up, health sync); it covers all of `de.yoxcu.stoandl` (no per-package overrides) and libpebble3's connection tags. `STOANDL_LOG=TRACE` adds a line per BLE/protocol packet (libpebble3's Kermit `v` = TRACE: keep per-packet lines there, not at `d`). The CLI never uses `logback.xml`: for any command line `Main.kt` selects `logback-cli.xml` (stderr only, WARN unless `STOANDL_LOG`) before the first logger is touched (Main's logger is lazy), so CLI calls never create or roll the daemon's log. The CLI's JVM starts from a CDS archive it writes on its first run after an install (`~/.cache/stoandl/cli-<size>-<mtime>.jsa`; `cli-*.nocds` marks a JDK or disk that can't, and the CLI then runs without it until the next install).

## libpebble3 submodule

The dependency is a patched fork of upstream [`coredevices/libpebble3`](https://github.com/coredevices/libpebble3) (`yoxcu/libpebble3`), included as a git submodule at `libs/libpebble3`. It tracks `coredevices/master` directly and is wired via Gradle composite build in `settings.gradle.kts` — no Maven publish needed. After cloning, run `git submodule update --init --recursive`.

The submodule follows fork branch **`stoandl-bump`**: the fork rebased as a linear history onto upstream `ec2156cc` (2026-10-15; the plugin and permission series), plus the deep-sleep commits and the QoL wave's three (`b0863aea` shared GraalJS engine, `2679eb8b` disconnect ends an arming BLE attempt, `3d39201d` `classicBondState`). The previous base `e6b5138e` passed TESTING §5.33 by daily use; its tip is tagged `stoandl-bump-e6b5138e` as the bisect baseline. The branch replaces the old `stoandl` (base `e4180ffc`) once the re-bump's hardware rows (TESTING §5.44) pass; then `.gitmodules` goes back to `stoandl`. Push the fork branch, and the `gui` submodule's branch, **before** pushing a stoandl commit that records them: CI builds every push to `main` and every tag (`git submodule update --init --depth 1 libs/libpebble3`), and the APKBUILD downloads the recorded commit's archive from `yoxcu/libpebble3`, so a gitlink GitHub doesn't have yet breaks the build. [FIRMWARE-GAPS.md §3](FIRMWARE-GAPS.md) is the runbook for the next bump.

The fork adds: a pure-BlueZ D-Bus BLE backend + GATT server, a Bluetooth Classic (RFCOMM/SPP) transport for classic-era watches, PPoG handshake/reconnect fixes for Linux BLE (`BluezGattConnector.disconnect()` ends an attempt that is still arming, with `NotAnError_DisconnectRequested`: WatchManager's single connection slot depends on it), BlueZ pairing/bonding (`classicBondState()` says when BlueZ can't tell), and a GraalJS PKJS runtime for watchapp companion JS. It builds the JVM target. The Android-SDK-only modules sit behind one gate in its `settings.gradle.kts`: an SDK (`ANDROID_HOME` or `sdk.dir`), a Gradle at or above AGP's minimum (9.5 for AGP 9.3.1), and a standalone build, so they are never on inside stoandl's composite. The iOS targets are kept (they're load-bearing for the Room codegen).

## PKJS (PebbleKit JS)

Watchapps can ship a `pkjs/index.js` companion script. libpebble3 runs it in **GraalJS** (GraalVM JS, 25.0.x) via `GraalJsRunner`. The JS bridge initialises when the watch connects and the app is launched; look for `Pebble JS Bridge initialized.` in the log. Every context comes from one process-wide engine per HostAccess policy (`GraalEngines.contextBuilder(hostAccess)`, fork `jvmMain/.../js/GraalEngines.kt`; PKJS and plugins get one each), and the PKJS shims are `Source`s parsed once per engine, so a session starts in ~15–25 ms instead of ~50 and Truffle's per-session "fallback runtime" warning is gone.

GraalJS is a full, spec-compliant ECMAScript engine — modern JS (classes, `for...of`, default/rest params, computed keys, etc.) all work. No Rhino-style syntax workarounds are needed.

> **Note:** PKJS originally ran on Mozilla Rhino 1.7.15 and was migrated to GraalJS. The trigger was Clay's `tosource()`, which runs a 60-alternative regex on every object key — pathologically slow on Rhino's NFA, fine on GraalJS's TruffleRegex DFA.

**GraalJS gotchas (build/runtime, not syntax):**
- The fat JAR **must** merge `META-INF/services/` (Shadow plugin `mergeServiceFiles()`). A plain `DuplicatesStrategy.EXCLUDE` silently drops the TruffleRegex service registration → `No language for id regex found` at runtime.
- The fat JAR **must** keep `Multi-Release: true` in its manifest. GraalVM polyglot 25.x ships Multi-Release JARs (`META-INF/versions/{9,21}/…`); Shadow preserves the versioned classes but drops the manifest attribute, so the JVM ignores them and Truffle throws `InternalError: Truffle could not be initialized because Multi-Release classes are not configured correctly … Multi-Release … has been lost` at `Context.build()` (PKJS never inits). Fix: `manifest { attributes["Multi-Release"] = "true" }` in `tasks.shadowJar`.
- Don't set `js.esversion` as a `GraalJsRunner` option — it isn't a valid GraalJS option and throws.
- Contexts on one `Engine` must share its HostAccess: Truffle throws `Found different host access configuration for a context with a shared engine`. Get contexts from `GraalEngines.contextBuilder`, never `Context.newBuilder` directly.
- When building JS strings to `eval` (e.g. injecting an XHR response body), JSON-encode the value (`Json.encodeToString(...)`) — don't hand-escape. Unescaped `\n`/`\r`/control chars cause a silent `PolyglotException` and the JS callbacks never fire.

libpebble3's `GraalJsRunnerTest` (jvmTest, `:libpebble3:libpebble3:jvmTest`) runs PKJS offline on GraalJS with the real shims: bridge init, the host-access allow-list, AppMessage/config round trips, intercepted and reused XHRs, sessions sharing the engine. Add a case there for a shim change; still confirm on hardware via `Pebble JS Bridge initialized.` and the script's `console.log` output in the log.

## Deployment (postmarketOS / systemd user service)

```sh
sudo install -Dm644 build/libs/stoandl-*.jar /usr/lib/stoandl/stoandl.jar
sudo install -Dm644 packaging/stoandl.service /usr/lib/systemd/user/stoandl.service
systemctl --user daemon-reload
systemctl --user enable --now stoandl
```

The service unit sets `DBUS_SESSION_BUS_ADDRESS=unix:path=%t/bus` so it finds the user session bus without a graphical login.

## GUI / D-Bus contract

The daemon's public control surface — for the CLI and the GUI (the `gui/` submodule, a Kirigami
front-end) — is the single D-Bus interface `de.yoxcu.stoandl.Control` (session bus,
name `de.yoxcu.stoandl`, path `/de/yoxcu/stoandl`), defined in `dbus/StoandlControl.kt` and implemented
by `StoandlControlImpl` in `PebbleIntegration.kt`. The **methods are the source of truth**; eleven
signals (`WatchesChanged`, `FirmwareProgress`, `LockerChanged`, `LanguageProgress`, `CalendarsChanged`, `PluginsChanged`, `PermissionRequestsChanged`,
`ExtensionsChanged`, `ExtensionStateChanged`, `ConfigChanged`, `TaskChanged`) are a reactive layer on top, and there are no properties.
The daemon isn't D-Bus-activated, so a client can miss a signal: it re-reads the method when the name
appears and keeps a slow fallback poll. Long-running ops (pair, firmware flash, language install, the watch
tasks: sideload, screenshot, logs, core dump) report progress via polled status strings
(`PairStatus`/`FirmwareStatus`/`LanguageStatus`/`TaskStatus`), which the CLI uses. `backup`, `restore` and
`support` are **CLI-local** (no D-Bus method). The CLI exits 0 done, 1 failed, 2 usage, 3 daemon unreachable
(`ctlError` in `Main.kt`). The GUI's settings pages (one per schema `page` column) render whatever
`GetConfigSchema` advertises (`config/ConfigSchema.kt`), so a new config key needs a schema row, not GUI code:
its page and section, a one-sentence `desc` (at most 90 characters), `help` for what the description leaves
out, `requires` only for a hard gate (the row does nothing otherwise), and `egress` naming every host it makes
stoandl contact (such a row must default off; `ConfigSchemaTest` enforces it), plus its line in
`packaging/stoandl.conf.example` at its default. Then regenerate the GUI mock's fixture
`gui/tools/config-schema.tsv` (and `watch-pref-text.tsv` after a watch-pref text change) with
`STOANDL_UPDATE_GOLDEN=1 ./gradlew test --tests '*ConfigSchemaTest*' --tests '*WatchPrefTextTest*'` (in the
container through `stoandl-check-locked.sh`, then copy them from `.cache/stoandl-build/gui/tools/`), commit them
in gui and bump the gitlink.

See [docs/dbus-interface.md](docs/dbus-interface.md) for the full method catalog (D-Bus signatures,
CLI mapping, tab-separated record layouts, status-string conventions, exit codes) **and** the GUI gap analysis
per screen (Watch, Apps & Faces, Plugins, Settings, System). Keep that doc in sync when you change the
interface.
