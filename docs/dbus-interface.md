# D-Bus interface

The stoandl daemon exposes a single control interface on the **session bus**. The `stoandl` CLI
(`stoandl-ctl` → the fat JAR in `ctl` mode) is just a client of it; a GUI would be another. This
document is the contract between the daemon and any out-of-process front-end.

> **Source of truth.** This was extracted from the Kotlin source — the interface declaration in
> [`StoandlControl.kt`](../src/main/kotlin/de/yoxcu/stoandl/dbus/StoandlControl.kt), its
> implementation `StoandlControlImpl` in
> [`PebbleIntegration.kt`](../src/main/kotlin/de/yoxcu/stoandl/pebble/PebbleIntegration.kt) (exported
> at `PebbleIntegration.kt:1244`, impl from `:1685`), and the CLI dispatch in
> [`Main.kt`](../src/main/kotlin/de/yoxcu/stoandl/Main.kt). The live daemon was **not running** when
> this was written (no BLE radio / systemd in the build sandbox), so it was not introspected. To
> reconcile against a live daemon:
>
> ```sh
> busctl --user list | grep -i stoandl                       # find the name
> busctl --user introspect de.yoxcu.stoandl /de/yoxcu/stoandl # the control object
> gdbus introspect --session --dest de.yoxcu.stoandl --object-path /de/yoxcu/stoandl
> ```
>
> A live introspection should show the 71 methods below **plus 6 signals** (`WatchesChanged`,
> `FirmwareProgress`, `LockerChanged`, `LanguageProgress`, `ExtensionsChanged`, `ExtensionStateChanged`)
> and no properties.

## Service summary

| | |
|---|---|
| **Bus** | session bus |
| **Bus name** | `de.yoxcu.stoandl` |
| **Object path** | `/de/yoxcu/stoandl` |
| **Interface** | `de.yoxcu.stoandl.Control` |
| **Methods** | 71 |
| **Signals** | **6** — `WatchesChanged`, `FirmwareProgress`, `LockerChanged`, `LanguageProgress`, `ExtensionsChanged`, `ExtensionStateChanged` (reactive layer on top of the poll methods) |
| **Properties** | **0** |
| **Activation** | **not** D-Bus-activated — a systemd **user** service ([`packaging/stoandl.service`](../packaging/stoandl.service); also OpenRC via `packaging/stoandl.openrc`). The daemon calls `requestBusName("de.yoxcu.stoandl")` at startup (`Main.kt:69`) and `releaseBusName` on shutdown (`Main.kt:90`). There is no `dbus-1/services/*.service` activation file — a caller that finds the name unowned must start/`enable` the service itself. |

The session connection is `DBusConnectionBuilder.forSessionBus().withShared(false).build()`
(`util/DbusConnections.kt`). The systemd unit sets
`Environment=DBUS_SESSION_BUS_ADDRESS=unix:path=%t/bus` so the headless daemon reaches the user
session bus with no graphical login.

### Six signals augment polling; no properties

`de.yoxcu.stoandl.Control` now emits **six D-Bus signals** as a reactive layer **on top of** the
poll methods — they do not replace them:

| Signal | D-Bus sig | Meaning | Client reaction |
|---|---|---|---|
| `WatchesChanged` | *(none)* | a known watch's set / connection-state / battery / transport changed | re-call `ListWatches` |
| `FirmwareProgress` | `sis` | firmware-flash `phase` + `percent` (0–100 while `inprogress`, else −1) + `detail` | drive the progress UI directly; every phase change and % tick |
| `LockerChanged` | *(none)* | the locker (installed apps/faces) or the active watchface changed — incl. from the watch or another client | re-call `ListApps` |
| `LanguageProgress` | `sis` | language-pack install `phase` + `percent` (0–100 while `installing`, else −1) + `detail` | drive the progress UI directly; every phase change and % tick |
| `ExtensionsChanged` | *(none)* | an extension's installed/enabled/running state changed (enable/disable/restart/install/uninstall — incl. from the CLI) | re-call `ExtList` |
| `ExtensionStateChanged` | `ss` | per-extension runtime transition: `name` + `state` (`ready` / `exited` (restarting) / `quarantined`) — the unsolicited crash/quarantine the poke can't catch | show the live state (a `quarantined` ext otherwise polls as `running`) |

The pokes carry no payload; the typed signals are `FirmwareProgress`/`LanguageProgress` (the live %, which a
poke would lose) and `ExtensionStateChanged` (the per-extension state a `quarantined` ext can't be polled
for — its ExtList row still reads `running`). **The methods stay the source of truth** — a signal says
*something changed*, the client re-reads the authoritative method. Because the daemon is **not**
D-Bus-activated (above), a client that starts late or after a daemon restart can **miss** a signal, so
clients also (a) re-sync by calling the method once when the bus name appears and (b) keep a slow
fallback poll. Long-running ops still also expose *polled status strings*
([Long-running operations](#long-running-operations)) for the CLI, which does not subscribe.

Daemon side: the signals are nested `DBusSignal` subclasses on `StoandlControl`
([`StoandlControl.kt`](../src/main/kotlin/de/yoxcu/stoandl/dbus/StoandlControl.kt)), emitted with
`serviceConn.sendMessage(...)` from collectors in `PebbleIntegration.startSignalEmitters()` (watch
state ← `libPebble.watches`; firmware ← `FirmwareControl.statusFlow()`'s inner progress flow; locker ←
`getAllLockerUuids()` + `activeWatchface`).

The only other object stoandl exports on any bus is an internal **BlueZ pairing agent**
(`org.bluez.Agent1` at `/io/stoandl/agent`, on the **system** bus, from
[`BluezPairingAgent.kt`](../src/main/kotlin/de/yoxcu/stoandl/pebble/BluezPairingAgent.kt)). It is
registered with `org.bluez.AgentManager1` for headless auto-confirm pairing and is **not** part of
the public control API — callers never invoke it; BlueZ does.

### Type signatures

Only four types appear across the 71 methods (the `FirmwareProgress` signal adds a fifth, `i`):

| Kotlin | D-Bus sig | Plain language |
|---|---|---|
| `String` | `s` | string |
| `Boolean` | `b` | boolean |
| `Int` | `i` | signed 32-bit int (only the `FirmwareProgress` signal's `percent`) |
| `List<String>` | `as` | array of strings (one per record; fields tab-separated) |
| `Unit` / no return | *(empty)* | no out-arg (only `WebviewClose`) |

There are no struct, dict, variant, or object-path types on the control interface.

### Status-string convention

Most methods return a single `String` shaped as **`kind:message`** — a status token, a colon, then
a human/payload tail. The CLI splits on the first `:` (`splitStatus`/`handleStatusResponse` in
`Main.kt`). Common kinds: `ok`, `error`, `notready` (libPebble not up / no watch), `notfound`,
`ambiguous`, plus method-specific ones (`pending`, `timeout`, `disabled`, `idle`, `inprogress`,
`reboot`, `failed`, `done`, `uptodate`, `noasset`, `busy`, `none`, `unknown`). When a payload has
multiple fields they are **tab-separated** in the tail (e.g. `ok:<name>\t<level>`).

## Methods

In-args and out-args are given as D-Bus signatures; see the per-group notes for the field layout of
tab-separated payloads. "CLI" is the `stoandl` subcommand that calls each method.

### Daemon / meta

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `Version` | `() → s` | Daemon version (from `git describe` at build time). | `version` (soft — falls back to the CLI's embedded version if the daemon is down) |

### Watch (`stoandl watch`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `BluetoothStatus` | `() → s` | Whether host Bluetooth is on/usable: `ok:on` / `ok:off`. Tracked from libpebble3's adapter state **and** `org.bluez.GattManager1` presence (so it catches rfkill/airplane-mode, which leave `Powered=true`). The daemon already detects and logs every transition; this method just exposes the state for polling. | *(GUI)* |
| `ListWatches` | `() → as` | Known watches, one record each: `name\tstate\tbattery`. | `watch list` (also bare `watch`) |
| `Battery` | `() → s` | Active watch's battery: `ok:<name>\t<level>` (0–100), `unknown:<name>`, or `notready:`. | `watch battery` |
| `Connect` | `(s) → s` | Connect/switch to a known watch by name (exact-then-unique-substring); hands it the single connection slot. | `watch connect <name>` |
| `Pair` | `() → s` | Open a ~2-min pairing window; returns `ok:` immediately, poll `PairStatus`. | `watch pair` |
| `PairStatus` | `() → s` | Pairing outcome: `pending:<msg>` / `confirm:<code>` (numeric comparison awaiting `ConfirmPairing`) / `ok:` / `error:` / `timeout:`. | (polled by `watch pair`/`watch repair`; the CLI prompts y/N on `confirm:`) |
| `ConfirmPairing` | `(b) → s` | Accept/decline the `confirm:<code>` numeric comparison (verify it matches the watch first). `ok:accepted`/`ok:declined`, or `error:No pairing confirmation pending`. Gates client-initiated `Pair`/`Repair` only; the notification re-pair path auto-accepts. | (CLI y/N prompt during `watch pair`/`repair`) |
| `Repair` | `(s) → s` | Forget one known watch (bond + Trusted intent) and reopen the pairing window; multi-watch-safe. Poll `PairStatus`. | `watch repair <name>` |
| `Unpair` | `(s) → s` | Forget watch(es): empty = blanket (all), name = single (exact-then-substring). libpebble `forget()` + BlueZ `RemoveDevice`. | `watch unpair [name]` |
| `FindWatch` | `() → b` | Ring the watch continuously (a "Find My Watch" call screen) until a button is pressed. `false` = not ready. | `watch find` |
| `WatchDetails` | `() → s` | Structured details for the connected watch: `ok:name\tcode\tmodel\tplatform\ttransport\tfirmware\tserial\tbattery\tlastSync`, or `notready:`. | *(GUI; no standalone verb — see `support`/`watch list`)* |
| `SetWatchNickname` | `(s,s) → s` | Rename a known watch `(query, nickname)` (exact-then-unique-substring; nickname non-empty). `ok:`/`notfound:`/`error:`/`notready:`. | *(GUI; no verb yet)* |

`ListWatches` record: `name \t state \t battery \t transport` — `state` ∈ `connected` | `connecting` |
`disconnected`; `battery` is the 0–100 level for a connected watch, else empty; `transport` is
`ble`/`classic` for a connected watch, else empty.

`WatchDetails` record (after `ok:`): `name \t code \t model \t platform \t transport \t firmware \t
serial \t battery \t lastSync`. `code` is the BLE-advert-name suffix (may be empty); `model` is the
full `WatchColor.uiDescription` (e.g. "Pebble Time Steel - Black"); `platform` is the watch-type name
(e.g. `BASALT`); `transport` is the human label "Bluetooth LE"/"Bluetooth Classic"; `lastSync` is a
relative age of the last connection.

### Apps & watchfaces (`stoandl apps`, `stoandl config`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `ListApps` | `() → as` | Locker contents (apps + faces), one record each (see below). | `apps list` |
| `GetAppIcon` | `(s) → s` | The menu icon of the installed app/face `<uuid>`, extracted **locally** from its cached `.pbw` (no network/appstore) and written as a PNG under `<configDir>/icons/<uuid>.png` (cached after first call). `ok:<abs png path>` / `none:` (no cached `.pbw`, no declared `menuIcon`, or undecodable) / `notready:` / `error:<msg>`. Fetched lazily per-row by the GUI (kept off `ListApps`). | *(GUI only)* |
| `LaunchApp` | `(s) → s` | Launch app/face by UUID or name. `ok:`/`notfound:`/`ambiguous:`/`notready:`/`error:`. | `apps launch <name\|uuid>` |
| `RemoveApp` | `(s) → s` | Uninstall app/face from the locker (system apps refused). | `apps remove <name\|uuid>` |
| `SideloadApp` | `(s) → s` | Install a local `.pbw` (absolute daemon-side path). | `apps install <path.pbw>` (aliases `sideload`, `add`) |
| `OpenConfig` | `(s) → s` | Config (Clay/PKJS) URL for a running app; empty string if none. The CLI proxies it over a local HTTP server. | `config [app]` |
| `WebviewClose` | `(s) → ` *(void)* | Hand the saved settings JSON back to the running PKJS app after the config webview closes. | `config [app]` |

`ListApps` record: `uuid \t type \t order \t flags \t title \t developer`, where `flags` is a
comma-joined subset of **`{active, sideloaded, config, system, synced}`** (`active` = current
watchface, `config` = has a Clay/PKJS config page, `system` = built-in, `sideloaded` = installed from
a `.pbw`, `synced` = present on the watch — system apps are always synced).

### Watch settings (`stoandl settings`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `ListWatchPrefs` | `() → as` | Watch advanced settings, one record each (see below). | `settings` / `settings list [filter]` |
| `SetWatchPref` | `(s,s) → s` | Set setting `<id>` to `<value>` (parsed per the pref's type). | `settings set <id> <value>` |

`ListWatchPrefs` record: `id \t type \t current \t default \t allowed \t flags \t name \t
description` (`flags` carries `debug` for advanced settings). `type` ∈ **`bool`** (`current`/`allowed`
= `true|false`), **`number`** (`current` is the value with its unit appended, e.g. `3000 ms`; `allowed`
is `min..max[ unit]`), **`enum`** (`current` + `allowed` are **display names** like `Standard - Low`,
not the Kotlin constant — round-trip-safe, `SetWatchPref` accepts either), **`quicklaunch`** (`current`
is an app name / `off` / a raw uuid; `allowed` is the literal `off|<app name or uuid>` — pick the target
by app name), and **`color`** (`current` is `0xRRGGBB`; `allowed` is `RRGGBB|<preset name>|…`).
**The `allowed` option list is pipe-(`|`)-separated** for `enum`/`quicklaunch`/`color` (a display name
can contain a comma), so split on `|`, not `,`. `SetWatchPref` parses the value per type and accepts an
enum display-name-or-constant, a color preset-name-or-hex, and a quick-launch app-name-or-uuid-or-`off`.

### Notifications (per-app + filters) (`stoandl notif`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `NotifList` | `() → as` | Per-app notification store, one record each (see below). | `notif list` (also bare `notif`) |
| `NotifSetMute` | `(s,s) → s` | Set mute for the app matching `<query>`; spec = `always`/`never`/`weekdays`/`weekends` or a duration (`30m`/`1h`/`2d`). | `notif mute <app> [spec]` / `notif unmute <app>` (sends `never`) |
| `NotifSetMuteAll` | `(s) → s` | Apply a mute spec to every tracked app. | `notif mute-all [spec]` / `notif unmute-all` |
| `NotifSetStyle` | `(s,s,s,s) → s` | Per-app styling `(query, color, icon, vibe)` applied host-side; per value, empty = unchanged, `default` = reset. | `notif style <app> [--color] [--icon] [--vibe]` |
| `NotifListFilters` | `() → as` | Global notification filters, one record each: `pattern\taction` (`action` ∈ `allow`/`block`). | `notif filter list` |
| `NotifAddFilter` | `(s,s) → s` | Add a global filter `(pattern, action)`. `pattern` is a Java regex (inline flags like `(?i)` work), matched against appName + title + body; `action` `allow`/`block` (anything not `allow` ⇒ `block`). `ok:added <action> filter` / `error:empty pattern` / `error:invalid regex …`. Applied **live** (no restart). | `notif filter add <regex> [allow\|block]` |
| `NotifRemoveFilter` | `(s) → s` | Remove a global filter by exact `pattern`. `ok:filter removed` / `notfound:`. Live. | `notif filter remove <regex>` |

`NotifList` record: `name \t muteLabel \t color \t icon \t vibe \t lastNotifiedEpochSeconds`
(`muteLabel` ∈ `never`/`always`/`weekdays`/`weekends` or `muted-until <instant>`). *(The interface
KDoc lists only 3 fields; the implementation in `NotificationAppsControl.kt:34` emits all 6 —
the KDoc is stale.)*

**Notification filters** are a host-side **global** allow/block list enforced in `WatchNotifier.push()`
(the single send choke point). `allow` is a whitelist that **bypasses** the block filters, the master
forwarding switch (`notification.forward`), *and* per-app mute; `block` drops the notification. Filters
are stored in a dedicated file `<configDir>/notification-filters` (one `action⇥pattern` line each) and
are **live-mutable** — `NotifAddFilter`/`NotifRemoveFilter` take effect immediately, no restart.

### Sync — service on/off + force-sync triggers (`stoandl sync`/`weather`/`calendar`/`health`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `SetSyncEnabled` | `(s,b) → s` | Turn a sync service on/off at **runtime** (no daemon restart). `service` ∈ {notifications, weather, calendar, music, health, dnd}. Persists the backing config key **and** starts/stops the live service. `dnd` maps the boolean to its 4-way mode (`true` → `both`, `false` → `off`; the direction is still set via `SetConfig dnd.sync`). `ok:<service> enabled\|disabled` / `notfound:` (unknown service) / `error:`. | `sync enable <service>` / `sync disable <service>` |
| `SyncWeather` | `() → s` | Fetch weather now and push to the watch. `error:` if weather isn't enabled. | `weather` |
| `SyncCalendar` | `() → s` | Re-read calendar sources → update timeline pins. `error:` if calendar isn't enabled. | `calendar sync` |
| `SyncHealth` | `() → s` | Request fresh health/activity data from the watch and re-project the export. | `health sync` |
| `HeartRate` | `() → s` | Latest stored heart-rate reading: `ok:<bpm>\t<epochSec>`, `none:` (no sample yet), or `notready:`. Read-only DB lookup (not a live GATT stream); fills from the connect-time health sync. | `health hr` |
| `ListCalendars` | `() → as` | Synced calendars: `id \t name \t enabled\|disabled`. | `calendar list` (also bare `calendar`) |
| `SetCalendarEnabled` | `(s,b) → s` | Enable/disable a single calendar by id or name substring. | `calendar enable\|disable <id\|name>` |
| `GetHealthSummary` | `() → s` | Today's health summary (21 tab fields; see below) from the synced DB, or `notready:`. | *(GUI; CLI `health` reads the export files directly)* |
| `GetHealthSeries` | `(s) → as` | Health chart series: `steps` (7 `label\tvalue` rows, last 7 days), `sleep` (last night's light/deep timeline, `startFraction\twidthFraction\tisDeep` rows; `[]` when no session), or `heart` (24 `hour\tbpm` rows today; `[]` when HR unavailable). | *(GUI)* |
| `GetSyncStatus` | `() → as` | Per-feature **live** runtime status, one row each: `service\tenabled\tavailable\tlastSync` for {notifications, weather, calendar, music, health, dnd}. Reflects the running state (after any `SetSyncEnabled`), not just the startup config. `available` is `false` for weather/calendar when no source is configured (the GUI greys the toggle then); `lastSync` is `live`/`off`/`no source` (and the dnd mode string when dnd is on). | *(GUI)* |

`GetHealthSummary` record (after `ok:`, 21 fields): `stepsToday \t stepGoal \t distanceKm \t kcal \t
activeMin \t stepWeekAvg \t stepTrendPct \t sleepTotalMin \t sleepDeepMin \t sleepLightMin \t
sleepBedtime \t sleepWakeup \t sleepTypicalMin \t sleepAvgMin \t sleepTrendPct \t restingHr \t
currentHr \t hrMin \t hrMax \t hrAvailable(yes\|no) \t lastSync`. (`stepGoal` is a fixed default — no
watch-synced goal; Pebble models only light/deep, so REM is dropped and `sleepLightMin` = total −
deep; `sleepBedtime`/`sleepWakeup` are epoch seconds of last night's first-asleep / last-awake, `0`
when there's no session; `sleepTypicalMin` is the 30-day average; trends are week-over-week %.)

`GetHealthSeries("sleep")` rows: `startFraction \t widthFraction \t isDeep(0\|1)` — one per sleep
interval, as fractions of an 18 h window (6 PM yesterday → noon today). Light intervals come first,
deep last, so a client drawing them in order paints deep on top of light; awake gaps are uncovered
space. (Steps/heart rows keep the `label \t value` shape.)

There is **no** force-sync for music or notifications. `GetSyncStatus` exposes the per-service
enabled/available **read** and `SetSyncEnabled` is the runtime master on/off — services start/stop
**live**, with no daemon restart (the config key is persisted *and* the live service is started or
stopped). `lastSync` is a state label (`live`/`off`/`no source`, plus the dnd mode when dnd is on),
not yet a tracked timestamp.

### Firmware (`stoandl firmware`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `CheckFirmware` | `() → s` | Check the matching source (GitHub for Core, cohorts.rebble.io for classic) for a newer build: `ok:<board>\t<current>\t<latest>\t<asset>\t<yes\|no>\t<source>\t<changelogUrl>` (the 7th field, the PebbleOS changelog page, is a constant present on both `ok:` branches), or `noasset:`/`disabled:`/`notready:`/`error:`. | `firmware check` |
| `UpdateFirmware` | `() → s` | Download newer firmware and start flashing. `ok:<board>\t<current>\t<latest>\t<asset>` once started; `uptodate:`/`noasset:`/`busy:`/`disabled:`/`notready:`/`error:`. Poll `FirmwareStatus`. | `firmware update` |
| `SideloadFirmware` | `(s) → s` | Flash a local `.pbz` (absolute daemon-side path), async. Poll `FirmwareStatus`. | `firmware sideload <file.pbz>` / `firmware <file.pbz>` |
| `FirmwareStatus` | `() → s` | Flash state: `idle:` / `downloading:<asset>` / `waiting:` / `inprogress:<percent>` / `reboot:` (success) / `failed:<reason>` / `notready:`. | `firmware status` (also polled during update/sideload) |

### Language packs (`stoandl language`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `ListLanguages` | `() → as` | Catalog packs for the watch's board, one record each (see below). | `language list` (also bare `language`; soft — falls back to the offline bundled catalog) |
| `InstallLanguage` | `(s) → s` | Auto-pick (locale/name/id; blank = system locale), download and install. `ok:<displayName>` once started; `notfound:`/`disabled:`/`notready:`/`error:`. Poll `LanguageStatus`. | `language install <locale\|name\|id>` |
| `SideloadLanguage` | `(s) → s` | Install a local `.pbl` (absolute daemon-side path), async. Poll `LanguageStatus`. | `language sideload <file.pbl>` (alias `add`) |
| `LanguageStatus` | `() → s` | Install state: `idle:` / `downloading:<name>` / `installing:<percent>` / `done:<name>` / `failed:<reason>` / `notready:`. | `language status` (also polled during install/sideload) |

`ListLanguages` record: `id \t isoLocal \t displayName \t installed(yes\|no) \t source(rebble\|github)`.

### Diagnostics (`stoandl screenshot`/`logs`/`support`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `TakeScreenshot` | `(s) → s` | Capture the screen to a PNG at the absolute daemon-side path. `ok:<path>\t<width>\t<height>` / `notready:` / `error:`. Blocks ~seconds. | `screenshot [path]` |
| `GatherLogs` | `(s) → s` | Dump watch firmware logs to a text file at the absolute path. `ok:<path>` / `notready:` / `error:`. Blocks ~seconds. | `logs [path]`; also `support` |
| `GetCoreDump` | `(s) → s` | Fetch an existing coredump to the absolute path. `ok:<path>` / `none:` / `notready:` / `error:`. | `support --coredump` (no standalone verb) |
| `WatchInfoText` | `() → s` | Watch metadata as a human-readable text block (model/fw/board/serial/language/battery/capabilities). `ok:<text>` / `notready:`. Returned **inline** (no file). | `support` (no standalone verb) |

> `screenshot`/`logs`/`coredump` write to a **daemon-side filesystem path** and return that path —
> fine for a co-located CLI, a problem for a remote/sandboxed GUI (see gaps). `WatchInfoText` is the
> exception: it returns content inline.

### Reset (`stoandl reset`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `ResetIntoRecovery` | `() → s` | Reboot the watch into recovery (PRF) firmware. Fire-and-forget. | `reset recovery` / `reset prf` |
| `FactoryReset` | `() → s` | Wipe the watch to out-of-box state and reboot. Fire-and-forget; **destructive** (CLI/GUI owns the confirmation). | `reset factory [--yes]` |

Both are fire-and-forget: one RESET packet is sent, the link drops, and there is **no** completion
ack — `ok:` means "queued", not "done".

### Developer connection (`stoandl developer`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `StartDevConnection` | `() → s` | Start the LAN WebSocket server (port 9000) bridging the Pebble SDK/CloudPebble to the watch. `ok:9000` / `notready:` / `error:`. **Binds `0.0.0.0`, no auth.** | `developer start` |
| `StopDevConnection` | `() → s` | Stop the developer server. | `developer stop` |
| `DevConnectionStatus` | `() → s` | `ok:active` / `ok:inactive` / `notready:`. | `developer status` |

### Extensions / plugins (`stoandl ext`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `ExtList` | `() → as` | Installed/enabled extensions: `name \t installed\|missing \t enabled\|disabled \t running\|stopped \t config \t description` (config ∈ {none, schema} — `schema` = the manifest declares a `configSchema`; `url` backend not implemented). | `ext list` / `ext status` (also bare `ext`) |
| `ExtInstall` | `(s) → s` | Install from an archive (`.tar.gz`/`.tgz`/`.tar`/`.zip`, absolute daemon-side path): extract, sideload bundled `.pbw`, enable, hotplug-start. | `ext install <archive>` |
| `ExtUninstall` | `(s,b) → s` | Stop, drop from `extensions.enabled`, delete files; `keepConfig` retains the `config` file for a later reinstall. | `ext uninstall <name>` (aliases `remove`; `--keep-config`/`--delete-config`) |
| `ExtEnable` | `(s) → s` | Add to `extensions.enabled` and hotplug-start. | `ext enable <name>` |
| `ExtDisable` | `(s) → s` | Remove from `extensions.enabled` and stop (files kept). | `ext disable <name>` |
| `ExtRestart` | `(s) → s` | Restart the extension's process. | `ext restart <name>` |
| `ExtConfigSchema` | `(s) → s` | The extension's typed config schema (manifest `configSchema`) as a JSON array: `ok:<json-array>` of `{key,type,label,secret?,options?}`, `none:`, or `notfound:`. | *(GUI)* |
| `ExtOpenConfig` | `(s) → s` | The `url` config backend (a hosted settings page the GUI opens in a browser). Not implemented — stoandl has no embedded HTTP server — so it never returns a URL: `error:` for a schema-backed extension (use `ExtConfigSchema`), `none:`, or `notfound:`. Present for contract parity. | *(GUI)* |
| `ExtGetConfig` | `(s) → s` | The extension's current settings (its `config` file) as a JSON object of string values: `ok:<json>` (or `ok:{}`), or `notfound:`. | *(GUI)* |
| `ExtSetConfig` | `(s,s) → s` | Merge a JSON object of key→value into the extension's `config` file (atomic, comment- and unchanged-key-preserving), then restart it if running. `ok:`/`notfound:`/`error:`. | *(GUI)* |

### Daemon config (`stoandl.conf` over D-Bus)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `GetConfigSchema` | `() → as` | Schema for the curated GUI-exposed config keys, one row each: `key\ttype\tlabel\toptions\tdesc` (type ∈ {toggle, combo}; options is a CSV for combos). | *(GUI)* |
| `GetConfig` | `() → as` | Current values of those keys, one `key\tvalue` row each (combo value = an options label; toggle = `true`/`false`). Read from the **live** config store (reloaded on every `SetConfig`/`SetSyncEnabled` write), so a value just written is reflected. | *(GUI)* |
| `SetConfig` | `(s,s) → s` | Set one curated key `(key, value)` — value is a toggle's `true`/`false` or a combo's option label; mapped to the raw `stoandl.conf` token and upserted atomically. `ok:<key> = <token>`, `notfound:` (unknown key), or `error:` (bad value / IO). Applied **live**: the write persists to `stoandl.conf`, reloads the config store, and re-reconciles the affected subsystem — **no daemon restart**. | *(GUI)* |

The curated key set lives in `config/ConfigSchema.kt` (a small, hand-maintained subset, not the full
config surface). `SetConfig` shares the atomic `key = value` writer + lock (`util/ConfFile.kt`) with the
extension-config writers. The running daemon holds the config in a live store (`config/ConfigStore.kt`,
an `AtomicReference`); every `SetConfig`/`SetSyncEnabled` write persists to `stoandl.conf`, reloads the
store, and re-reconciles the affected subsystem, so changes take effect **without a restart**. (A
**hand-edit** of `stoandl.conf` is *not* picked up live — it still needs a daemon restart.) The
per-service runtime master on/off (notifications, weather, calendar, music, health, dnd) is
`SetSyncEnabled`, which also persists its backing key.

### Debug (`stoandl fakecall`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `FakeCallRing` | `(s,s) → b` | Inject a synthetic incoming (ringing) call `(name, number)` so the watch shows the native call screen. `false` = not ready. | `fakecall ring [name] [number]` |
| `FakeCallEnd` | `() → b` | Clear the current synthetic call. | `fakecall end` |

## Tab-separated record formats (the `as` returns)

| Method | Fields |
|---|---|
| `ListWatches` | `name` · `state`(connected/connecting/disconnected) · `battery`(0–100 or empty) · `transport`(ble/classic or empty) |
| `ListApps` | `uuid` · `type` · `order` · `flags`(⊆ active,sideloaded,config,system,synced) · `title` · `developer` |
| `ListWatchPrefs` | `id` · `type` · `current` · `default` · `allowed` · `flags` · `name` · `description` |
| `ListCalendars` | `id` · `name` · `enabled`/`disabled` |
| `ListLanguages` | `id` · `isoLocal` · `displayName` · `installed`(yes/no) · `source`(rebble/github) |
| `NotifList` | `name` · `muteLabel` · `color` · `icon` · `vibe` · `lastNotifiedEpochSeconds` |
| `NotifListFilters` | `pattern` · `action`(allow/block) |
| `ExtList` | `name` · `installed`/`missing` · `enabled`/`disabled` · `running`/`stopped` · `config`(none/schema) · `description` |
| `GetHealthSeries` | steps/heart: `label`/`hour` · `value` (empty value = no data) · sleep: `startFraction` · `widthFraction` · `isDeep`(0/1) |
| `GetSyncStatus` | `service` · `enabled`/`disabled` · `available`/`unavailable` · `lastSync` |
| `GetConfig` | `key` · `value` |
| `GetConfigSchema` | `key` · `type`(toggle/combo) · `label` · `options`(CSV) · `desc` |

## Long-running operations

Three operations run asynchronously on the daemon and report progress via a **polled status
string** — there is no progress signal. The CLI's poll loops document the cadence and terminal
states:

| Operation | Start method | Polled method | Cadence | Timeout | Terminal states |
|---|---|---|---|---|---|
| **Pair / Repair** | `Pair()` / `Repair(name)` | `PairStatus()` | 1.5 s | 145 s | `ok:` (paired), `error:`, `timeout:`; `pending:<msg>` continues; `confirm:<code>` → answer with `ConfirmPairing(b)` (≤60 s or it declines) |
| **Firmware flash** | `UpdateFirmware()` / `SideloadFirmware(path)` | `FirmwareStatus()` | 0.8 s | 600 s | `reboot:` or post-activity `notready:` = success; `failed:` = failure |
| **Language install** | `InstallLanguage(query)` / `SideloadLanguage(path)` | `LanguageStatus()` | 0.6 s | 180 s | `done:` = success; `failed:` = failure; post-activity `notready:` = disconnected |

For firmware/language, the start method returns `ok:` (kicked off) and the *snapshot* status is read
repeatedly. A successful flash/install ends with the watch rebooting, so the link drops and the poll
loop treats a `notready:` *after* it has seen activity as success. (Language install skips one stale
sticky `done:`/`idle:`/`failed:` on the first poll — it can be the previous install's terminal value
before the new kickoff propagates.)

## CLI subcommands that bypass the daemon

Not every `stoandl` subcommand talks to D-Bus. These read local files or generate output entirely
in-process (no daemon needed), which is relevant to a GUI deciding what it can do while the daemon
is down — and a reminder that **backup/restore are not daemon capabilities**:

- `backup [out]` / `restore <in> [--force]` — **pure CLI-local `tar` over `~/.config/stoandl`**. They
  only probe `org.freedesktop.DBus.NameHasOwner` to *warn* (backup) or *refuse* (restore) when the
  daemon is up; they never call `de.yoxcu.stoandl.Control`. **There is no `Backup`/`Restore` method.**
- `support [out.tar.gz]` — CLI-local assembly: calls `WatchInfoText`/`GatherLogs`/`GetCoreDump` for
  the watch pieces, then reads `/tmp/stoandl*.log` and `stoandl.conf` (with secret redaction) off the
  host and tars it. **There is no `SupportBundle` method.**
- `calendar dump <file.ics|url>` — parses + expands recurrence in-process.
- `datalog list|dump|tail` — reads `~/.config/stoandl/datalog/**/*.ndjson` directly.
- `health` / `health [days]` / `health activities` / `health dump` — read
  `~/.config/stoandl/health/*.ndjson` directly (`health sync` and `health hr` call the daemon).
- `notif styles` — generated offline from the `TimelineColor`/`TimelineIcon` enums + vibe presets.
- `version` and `language list` use a *soft* connection: they call the daemon if reachable and
  degrade to embedded/offline output otherwise.

---

## GUI gap analysis

A planned Kirigami GUI has five screens. For each, this lists the existing control members that
satisfy it and the **gaps** — data or actions it needs that the daemon does not expose over D-Bus.
The recurring theme *was* **no signals or properties**, so every "live update" need was a gap — now
partly closed: the daemon emits `WatchesChanged`/`FirmwareProgress`/`LockerChanged` (above), so the
watch, firmware-progress and locker screens update reactively (with polling kept as the fallback).
Remaining gaps are values the daemon already computes but drops from a return or never surfaces.

A `feasibility` note marks each gap as **wiring-only** (the daemon/libpebble3 already computes it —
just expose it), **needs bookkeeping** (a small new field/timestamp), or **design work** (lifecycle
or egress concerns).

> **Batches A–D landed (data-path + write-path + lifecycle gaps below now resolved):** `transport` on
> `ListWatches`, `synced` on `ListApps`, `WatchDetails`, `SetWatchNickname`,
> `GetHealthSummary`/`GetHealthSeries` (Health screen), `ExtConfigSchema`/`ExtGetConfig`/`ExtSetConfig`
> + `config`/`description` on `ExtList` (extension config), the `changelogUrl` on `CheckFirmware`, and
> the full Settings/Sync write path: `GetConfig`/`GetConfigSchema` + `SetConfig` (all **live** — no
> restart, via the `config/ConfigStore.kt` reload + re-reconcile), `GetSyncStatus` (now **live** runtime
> state) + `SetSyncEnabled` (runtime per-service on/off, full start *and* stop), and the notification
> filters (`NotifListFilters`/`NotifAddFilter`/`NotifRemoveFilter`, a global allow/block list gated in
> `WatchNotifier.push()`). **Quiet-hours was dropped** as redundant with `dnd.sync`.
>
> **Reactive signals landed** (the "Six signals augment polling" section above): `WatchesChanged`,
> `FirmwareProgress`, `LockerChanged`, `LanguageProgress`, `ExtensionsChanged`, `ExtensionStateChanged` —
> so the Watch, firmware/language-progress, Apps and Plugins screens update without polling (polling kept
> as the fallback), and **per-service `lastSync` is now a real relative age** for weather/calendar/health.
> `ExtensionStateChanged` surfaces the unsolicited per-extension crash/quarantine that `ExtensionsChanged`
> (user/CLI ops only) can't. Still open: only the byte-returning/remote method variants.

### Screen 1 — Watch

*Active watch, battery, transport, known-watch list, pair/connect/repair/unpair.*

**Satisfied by:** `ListWatches` (identity + state + battery snapshot), `Battery` (active watch
level), `Connect`, `Pair`+`PairStatus`, `Repair`, `Unpair`, `WatchInfoText` (free-text details panel).

| Gap | Kind | Today | Proposed hook | Feasibility |
|---|---|---|---|---|
| Watch connect/disconnect/state-change push | signal | none — must re-poll `ListWatches` | `WatchStateChanged(s name, s state, i battery)` (or a zero-arg `WatchesChanged()` poke) | **wiring-only** — libpebble3 `LibPebble.watches: StateFlow` + `Watches.connectionEvents: Flow` already exist |
| Live battery updates | signal | none — `Battery`/`ListWatches` are one-shot | `BatteryChanged(s name, i level)`, or fold into the state signal | **wiring-only** — each battery change re-emits the `watches` StateFlow; diff it |
| Transport per watch (BLE vs Classic badge) | data | **dropped** from the `ListWatches` record | append `transport`(ble/classic) to the `ListWatches` row | **wiring-only** — the daemon already pattern-matches `PebbleBtClassicIdentifier` vs `PebbleBleIdentifier` (`ActiveDevice.usingBtClassic`) |
| Richer per-watch identity (model, fw, serial, color, last-connected) | data | only the unstructured `WatchInfoText`, connected watch only | extend `ListWatches` row or add `WatchDetails(s) → s` | **wiring-only** — all fields live on `KnownPebbleDevice` |
| Bluetooth adapter power / scanning state | property/signal | not exposed (checked internally) | `BluetoothStatus() → s` + `BluetoothStateChanged` | **wiring-only** — libpebble3 `Scanning.bluetoothEnabled`/`isScanningBle/Classic` StateFlows |
| Pair progress as push (vs `PairStatus` polling) | signal | poll only | `PairStatusChanged(s status)` on each internal `pairingState` change | **wiring-only** — `pairingState` already maintained |
| Rename / nickname a known watch | action | none | `SetWatchNickname(s watch, s nickname) → s` | **wiring-only** — `KnownPebbleDevice.setNickname()` exists |
| Battery charging state | data | not available | *(none — not feasible)* | **unavailable** — BLE Battery Service (0x180F) is level-only; not a wiring gap |

### Screen 2 — Apps & Faces

*Locker list with active/system/sideloaded/config flags, launch/install/remove, Clay config.*

**Satisfied by:** `ListApps` (list + **all four flags** `active|system|sideloaded|config` are
present), `LaunchApp`, `RemoveApp`, `SideloadApp`, `OpenConfig` + `WebviewClose` (Clay round-trip).

| Gap | Kind | Today | Proposed hook | Feasibility |
|---|---|---|---|---|
| Locker-changed push (install/remove/reorder) | signal | none — `ListApps` is a one-shot `getLocker(...).first()`, dropping the live stream | `LockerChanged()` poke | **wiring-only** — `getLocker()` returns a live `Flow`; keep the collector |
| Active-watchface-changed push | signal | none — computed per call | `ActiveWatchfaceChanged(s uuid)` or fold into `LockerChanged` | **wiring-only** — `LockerApi.activeWatchface: StateFlow` |
| Config session available/unavailable push | signal | none — must call `OpenConfig` speculatively | `CompanionSessionsChanged()` | **wiring-only** — `currentCompanionAppSessions: StateFlow`, already read |
| `synced`-to-watch flag per app | data | the `sync` field is read but **not** added to flags | add `synced` to the `ListApps` flags set | **wiring-only** — `NormalApp.sync` already in the iterated object |
| Menu icon per app/face | data | ~~dropped from the row~~ **done** — `GetAppIcon(s)` (below) | `GetAppIcon(s) → s` returns a local PNG path | **done** — extracted LOCALLY from the cached `.pbw` (`app_resources.pbpack` → PNG passthrough or GBitmap decode, no AWT, no network); the GUI fetches it lazily per row |
| Version / category / capabilities for richer rows | data | dropped from the row | extend `ListApps` row or `AppDetails(s) → s` | **wiring-only** — version/category/caps are already locker fields |
| Reorder apps (drag-to-reorder) | action | `order` is shown but read-only | `SetAppOrder(s uuid, i order) → s` (+ `RestoreSystemAppOrder`) | **wiring-only** — libpebble3 `setAppOrder()`/`restoreSystemAppOrder()` exist |
| Install from cloud locker / store (not just a local `.pbw`) | action | only `SideloadApp(path)` | `AddAppFromLocker(s uuid) → s` | **design work** — plumbing exists (`addAppToLocker`/`fetchLocker`) but stoandl is local-only/no-egress; needs a store fetch + egress opt-in |

### Screen 3 — Plugins

*Extensions: list, enable/disable/restart/install/uninstall, running state.*

**Satisfied by:** `ExtList` (list + installed + enabled + running flags, one shot), `ExtInstall`,
`ExtUninstall` (with the `keepConfig` bool → a GUI checkbox), `ExtEnable`, `ExtDisable`, `ExtRestart`.

| Gap | Kind | Today | Proposed hook | Feasibility |
|---|---|---|---|---|
| Live running-state updates (crash/quarantine/restart/needs-config) | signal | none — re-poll `ExtList` only | `ExtensionStateChanged(s name, s state)` | **wiring-only** — `ExtensionProcess` already observes every transition (`ready`, exit, quarantine) internally |
| Richer run-state than `running\|stopped` (quarantined, needs-config, restarting) | data | collapsed to `running.containsKey()` | 5th `ExtList` field `runState`, or `ExtStatus(s) → s` | **needs bookkeeping** — facts exist (`StartResult.NEEDS_CONFIG`, quarantine after `MAX_FAST_FAILURES`) but aren't recorded into a queryable field |
| Per-extension config (declares `requiresConfig`? `userConfigured`? path? read/write settings) | data + action | not exposed (only a desktop notification tells the user to edit the file) | `ExtConfigGet(s) → s` / `ExtConfigSet(s,s,s) → s` | **wiring-only to read** (manifest + `readConfigFile()` already parsed); write is new |
| Install from bytes/upload (not a daemon-side path) | action | `ExtInstall` takes a daemon-resolved path | optional `ExtInstallBytes(s name, ay data) → s` | **wiring-only** but low priority for a co-located GUI |

### Screen 4 — Sync

*Per-service on/off + force-sync for notifications, weather, calendar, music/MPRIS, health, plus global
notification filters.*

**Satisfied by:** `GetSyncStatus` (live per-service enabled/available/lastSync) + `SetSyncEnabled`
(**runtime** per-service master on/off — full start *and* stop, no restart) for all six services;
`SyncWeather`, `SyncCalendar`, `SyncHealth` (force-sync for three of them);
`ListCalendars` + `SetCalendarEnabled` (per-*calendar* toggle); the notification filters
`NotifListFilters`/`NotifAddFilter`/`NotifRemoveFilter` (a global allow/block list, live);
`NotifList` (per-app `lastNotified` timestamps).

| Gap | Kind | Today | Proposed hook | Feasibility |
|---|---|---|---|---|
| **Per-service master ON/OFF at runtime** (notifications, weather, calendar, music, health, dnd) | action | **DONE** — `SetSyncEnabled(s service, b enabled) → s` persists the backing config key **and** starts/stops the live service, no restart. Built on the live `config/ConfigStore.kt` (reload + re-reconcile) + a per-service start/stop lifecycle for all six. | — | **implemented (live)** |
| Per-service **enabled** read (initialize the toggles) | property | **DONE** — `GetSyncStatus() → as` (`service\tenabled\tavailable\tlastSync`), now reflecting **live** runtime state. | — | **implemented** |
| Per-service **last-sync** timestamp (+ result/error) | data | partial — `GetSyncStatus.lastSync` is a state label (`live`/`off`/`no source`, dnd mode when on), not a timestamp | add a real `lastSync`/`lastResult` timestamp to the row | **needs bookkeeping** — sync moments are observable but no timestamp is stored yet |
| Quiet-hours (scheduled mute window) | action | **dropped — superseded by `dnd.sync`** (which mirrors desktop DND ↔ the watch's native Quiet Time); a separate host-side time-window subsystem would be redundant | *(none — dropped)* | **not pursued** |
| Force-sync for **music** and **notifications** | action | **no `SyncMusic`/`SyncNotifications`** (both are continuous push by design) | `SyncMusic() → s` (re-enumerate MPRIS + re-push) / `SyncNotifications() → s`, or omit from the screen | **wiring-only if wanted** — `MprisMusicControl` has `enumerateExisting()`/`recompute()`; arguably unnecessary |
| Music/MPRIS state for the screen (active player, playing?) | data | not exposed (state stays internal to the watch bridge) | include music in `GetSyncStatus`, or `MusicStatus() → s` | **wiring-only** — `MprisMusicControl._playbackState: StateFlow` already carries it |
| DND ↔ Quiet Time sync state + mode (`dnd.sync`) | property + action | live on/off via `SetSyncEnabled("dnd", …)` (true → `both`, false → `off`); the **direction** mode is set via `SetConfig dnd.sync` and read via `GetSyncStatus` (mode string in `lastSync`) | *(covered)* | **implemented (live)** |

### Screen 5 — System

*Firmware check/update/flash + progress, language list/install, backup/restore, screenshot, logs,
support bundle, reset recovery/factory.*

**Satisfied by:** firmware `CheckFirmware`/`UpdateFirmware`/`SideloadFirmware`/`FirmwareStatus`;
language `ListLanguages`/`InstallLanguage`/`SideloadLanguage`/`LanguageStatus`; `TakeScreenshot`;
`GatherLogs`/`GetCoreDump`/`WatchInfoText`; `ResetIntoRecovery`/`FactoryReset`.

| Gap | Kind | Today | Proposed hook | Feasibility |
|---|---|---|---|---|
| Live firmware-flash progress push | signal | `FirmwareStatus` polling only | `FirmwareProgress(s phase, i percent, s detail)` | **wiring-only** — libpebble3 `FirmwareUpdater.firmwareUpdateState: StateFlow` with nested `InProgress.progress: StateFlow<Float>` |
| Distinguish flash-failure cause (download vs board-mismatch vs CRC) | data | collapsed to `failed:<reason>`; an `update` download failure is logged only and silently drops back to `idle:` | carry an error code in `FirmwareProgress.detail`; set state on download failure | **needs bookkeeping** — typed causes exist (`ErrorStarting`/`Idle(lastFailure)`) but the download failure is swallowed in `FirmwareControl.startFlash` |
| Live language-install progress push | signal | `LanguageStatus` polling only | `LanguageProgress(s phase, i percent, s detail)` | **wiring-only** — `LanguagePackInstaller.state: StateFlow` with `Installing.progress: StateFlow<Float>` |
| **Backup / Restore over D-Bus** | action | **not on D-Bus at all** — CLI-local `tar`; backup warns and restore refuses while the daemon runs | `BackupTo(s path) → s` (daemon snapshots its own config + checkpoints the DB) and a coordinated `RestoreFrom(s path)` (+ restart) or `PrepareForRestore()`; at minimum `ConfigDir() → s` | **design work** — data is daemon-owned (right place to snapshot consistently), but the DB-lock/restart coordination is real work; a remote GUI also needs a byte transfer |
| Screenshot bytes to a remote/sandboxed GUI | data | **file-path coupling** — writes a daemon-side PNG, returns the path | `TakeScreenshotBytes() → (i,i,ay)` | **wiring-only** — `ScreenshotControl` already has the encoded PNG bytes before writing |
| Watch-log / coredump content to a remote GUI | data | file-path coupling (same as screenshot) | `GatherLogsText() → s` (logs are text) + `GetCoreDumpBytes() → ay` | **wiring-only** — content already in hand daemon-side |
| One-call support bundle | action | **not a D-Bus method** — CLI-local orchestration + redaction | `SupportBundle(b includeCoredump) → s` (and a bytes variant) | **wiring-only** — all inputs are daemon-side; consolidates CLI assembly + keeps redaction authoritative |
| Reset completion / watch reboot confirmation | signal | fire-and-forget; `ok:` = "queued" | the general `WatchStateChanged` signal (Screen 1) covers the post-reset drop/reconnect | **wiring-only** — connection state is already a Flow |

### Cross-cutting: the hooks worth adding first

Most screens wanted the **same handful** of new members. In rough priority (all now **landed** except
where noted):

1. **A connection/state signal** — shipped as the `WatchesChanged()` poke (the Watch screen re-reads
   `ListWatches`); the canonical "stop polling `ListWatches`" fix (poll relaxed to a 20 s safety-net). **Done.**
2. **Progress signals** — `FirmwareProgress` and `LanguageProgress`, both off the libpebble3 inner
   `StateFlow<Float>` progress. **Done.**
3. **`LockerChanged()`** + extension live-state — shipped as `LockerChanged()` + `ExtensionsChanged()`
   pokes plus the typed `ExtensionStateChanged(name, state)` (ready/exited/quarantined) for the
   unsolicited crash/quarantine the poke can't catch (live Apps & Faces and Plugins). **Done.**
4. **Add dropped fields to existing records** — `transport` on `ListWatches`; `synced` on
   `ListApps`. **Done** (Batch A).
5. **`GetSyncStatus()`** + **`SetSyncEnabled()`** — **done** (Batch D): the Sync screen's toggles are
   live (full runtime start *and* stop for all six services, on a live `ConfigStore` + per-service
   lifecycle), and `GetSyncStatus` reflects live state. Only the real per-service `lastSync`
   *timestamp* (vs the current state label) remains small bookkeeping.
6. **Byte-returning variants** — `TakeScreenshotBytes`, `GatherLogsText`, `GetCoreDumpBytes`,
   `SupportBundle`, and `BackupTo`/`RestoreFrom` — needed only if the GUI is ever **not co-located**
   with the daemon (different user, sandbox, or host). A same-host Kirigami GUI can use the existing
   path-returning methods.

Adopting these is idiomatically cleanest as `org.freedesktop.DBus.Properties` on the control object
(watches list, sync status, progress) so standard `PropertiesChanged` fires — but plain custom
signals work and match the dbus-java machinery the daemon already uses for inbound BlueZ signals.
