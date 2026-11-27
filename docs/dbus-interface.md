# D-Bus interface

The stoandl daemon exposes a single control interface on the **session bus**. The `stoandl` CLI
(`stoandl-ctl` → the fat JAR in `ctl` mode) is just a client of it; a GUI would be another. This
document is the contract between the daemon and any out-of-process front-end.

> **Source of truth.** This was extracted from the Kotlin source — the interface declaration in
> [`StoandlControl.kt`](../src/main/kotlin/de/yoxcu/stoandl/dbus/StoandlControl.kt), its
> implementation `StoandlControlImpl` in
> [`PebbleIntegration.kt`](../src/main/kotlin/de/yoxcu/stoandl/pebble/PebbleIntegration.kt) (exported
> in `PebbleIntegration.init()`), and the CLI dispatch in
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
> A live introspection should show the 122 methods below **plus 11 signals** (see
> [Eleven signals](#eleven-signals-augment-polling-no-properties)) and no properties.

## Service summary

| | |
|---|---|
| **Bus** | session bus |
| **Bus name** | `de.yoxcu.stoandl` |
| **Object path** | `/de/yoxcu/stoandl` |
| **Interface** | `de.yoxcu.stoandl.Control` |
| **Methods** | 122 |
| **Signals** | **11** — `WatchesChanged`, `FirmwareProgress`, `LockerChanged`, `LanguageProgress`, `ExtensionsChanged`, `ExtensionStateChanged`, `PluginsChanged`, `PermissionRequestsChanged`, `CalendarsChanged`, `ConfigChanged`, `TaskChanged` (reactive layer on top of the poll methods) |
| **Properties** | **0** |
| **Activation** | **not** D-Bus-activated — a systemd **user** service ([`packaging/stoandl.service`](../packaging/stoandl.service); also OpenRC via `packaging/stoandl.openrc`). There is no `dbus-1/services/*.service` activation file — a caller that finds the name unowned must start/`enable` the service itself. |
| **Startup** | Two names. `main()` first takes the private **`de.yoxcu.stoandl.Instance`**, the single-instance guard held for the daemon's life: a second daemon can't get it and exits before it touches any shared file (`SqliteNative`'s cache, the database). The public **`de.yoxcu.stoandl`** is requested only at the very end of `PebbleIntegration.init()`, once Control is exported and everything it answers about has started, so a client may call as soon as the name appears (on a phone that is ~20 s after the process starts). Instance is not for calling; a client that gets `ServiceUnknown` while Instance has an owner says "stoandl is starting" instead of "isn't running" (the CLI and the GUI do). `stoandl restore` checks Instance, so it refuses a daemon that is only starting too. |

The daemon's own connection is `openServiceBus()` (`util/DbusConnections.kt`): a private session
connection whose incoming method calls run on **12** dbus-java worker threads (the default is 4, which a
few slow calls — a firmware check, a screenshot, a transcription — used to fill, queueing the GUI's
quick reads behind them). The systemd unit sets
`Environment=DBUS_SESSION_BUS_ADDRESS=unix:path=%t/bus` so the headless daemon reaches the user
session bus with no graphical login.

Every method call is timed: one that takes 500 ms or longer logs
`WARN … D-Bus StoandlControl.<Method> took <n> ms` (the object is exported through a timing proxy,
`dbus/SlowCallLog.kt`; no arguments are logged, and nothing below the threshold). A GUI that calls on
its UI thread freezes for that long, so this is where to look first when it does. Methods that block
by design (`CheckFirmwareForce` with a slow network, `Transcribe`, `PluginRead`) show up there too.
The watch transfers that used to block (`SideloadApp`, `TakeScreenshot`, `GatherLogs`, `GetCoreDump`)
now start a [watch task](#watch-tasks) and answer at once.

### Eleven signals augment polling; no properties

`de.yoxcu.stoandl.Control` emits **eleven D-Bus signals** as a reactive layer **on top of** the
poll methods — they do not replace them:

| Signal | D-Bus sig | Meaning | Client reaction |
|---|---|---|---|
| `WatchesChanged` | *(none)* | a known watch's set / connection-state / battery / transport changed | re-call `ListWatches` |
| `FirmwareProgress` | `sis` | firmware-flash `phase` (the `FirmwareStatus` kinds, `prf` included) + `percent` (0–100 while `inprogress`, else −1) + `detail` | drive the progress UI directly; every phase change and % tick |
| `LockerChanged` | *(none)* | the locker (installed apps/faces) or the active watchface changed — incl. from the watch or another client | re-call `ListApps` |
| `LanguageProgress` | `sis` | language-pack install `phase` + `percent` (0–100 while `installing`, else −1) + `detail` | drive the progress UI directly; every phase change and % tick |
| `ExtensionsChanged` | *(none)* | an extension's installed/enabled/running state changed (enable/disable/restart/install/uninstall — incl. from the CLI) | re-call `ExtList` |
| `ExtensionStateChanged` | `ss` | per-extension runtime transition: `name` + `state` (`ready` / `exited` (restarting) / `quarantined`) — the unsolicited crash/quarantine the poke can't catch | show the live state (a `quarantined` ext otherwise polls as `running`) |
| `PluginsChanged` | *(none)* | the set of plugins changed (plugins switched on/off, a plugin `.pbw` installed or removed, an extension's plugin came or went) | re-call `PluginList` |
| `PermissionRequestsChanged` | *(none)* | the permission requests waiting for an answer changed (including put off, or their app started/stopped) | re-call `PermPending` |
| `CalendarsChanged` | *(none)* | a calendar sync added/dropped calendars — after a source CRUD (the sync runs ~5 s later + CalDAV discovery) or a periodic re-read | re-call `ListCalendars` (+ `ListCalendarSources`); lets the GUI update when the data's ready, not race the async sync |
| `ConfigChanged` | `s` | the `stoandl.conf` key `configKey` (a `GetConfigSchema` row, secrets included) changed: written by `SetConfig`/`SetSyncEnabled`, by the daemon itself (a finished model or voice download sets `voice.local_model`/`tts.local_voice` and maybe the backend), or by a hand edit of `stoandl.conf` (applied live, below). One signal per changed key; a write that changes nothing emits none. | re-call `GetConfig` (and `GetSyncStatus` for a master) |
| `TaskChanged` | `ss` | the [watch task](#watch-tasks) `taskKind` started or ended; `status` is what `TaskStatus(taskKind)` now returns (`running:<what>` on start, the result on the end) | show the result; `TaskStatus` is the fallback poll |

The pokes carry no payload; the typed signals are `FirmwareProgress`/`LanguageProgress` (the live %, which a
poke would lose), `ExtensionStateChanged` (the per-extension state a `quarantined` ext can't be polled
for — its ExtList row still reads `running`), `ConfigChanged` (which key) and `TaskChanged` (which task, and
its status). `NotifMuteAllStatus` has no signal: a client re-reads it on its own tick and when it comes to
the front. **The methods stay the source of truth** — a signal says
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
registered with `org.bluez.AgentManager1` for headless pairing (it answers the numeric comparison:
through `ConfirmPairing` on a `Pair`/`Repair` window, by itself otherwise) and is **not** part of
the public control API — callers never invoke it; BlueZ does.

### Type signatures

Six types appear across the 122 methods and the signals:

| Kotlin | D-Bus sig | Plain language |
|---|---|---|
| `String` | `s` | string |
| `Boolean` | `b` | boolean |
| `Int` | `i` | signed 32-bit int (`SetAppOrder`'s position, the health `offset`, the progress signals' `percent`) |
| `Long` | `x` | signed 64-bit int (only the `sinceEpoch` of `BatteryHistory`/`BatteryActivity`/`BatteryPower`) |
| `Double` | `d` | double (only the coordinates of `AddWeatherLocation`) |
| `List<String>` | `as` | array of strings (one per record; fields tab-separated) |

There are no struct, dict, variant, or object-path types on the control interface.

### Status-string convention

Most methods return a single `String` shaped as **`kind:message`** — a status token, a colon, then
a human/payload tail. The CLI splits on the first `:` (`splitStatus`/`handleStatusResponse` in
`Main.kt`). Common kinds: `ok`, `error`, `notready` (libPebble not up / no watch), `notfound`,
`ambiguous`, plus method-specific ones (`pending`, `timeout`, `disabled`, `idle`, `inprogress`,
`reboot`, `failed`, `done`, `uptodate`, `noasset`, `busy` (refused while something else runs: a watch task
of that kind, a pairing window, an update, a download), `running` (a watch task in progress), `none`,
`unknown`, `off`, `unavailable`, `muted`). When a payload has
multiple fields they are **tab-separated** in the tail (e.g. `ok:<name>\t<level>`).

## Methods

In-args and out-args are given as D-Bus signatures; see the per-group notes for the field layout of
tab-separated payloads. "CLI" is the `stoandl` subcommand that calls each method.

### Daemon / meta

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `Version` | `() → s` | Daemon version (from `git describe` at build time). | `version` (soft — falls back to the CLI's embedded version if the daemon is down); first line of `status` |
| `Restart` | `() → s` | Restart the daemon through its service manager, e.g. to apply the `apply=restart` keys (the GUI's *Restart now*; works from the Flatpak, which can't run `systemctl`). Under systemd (`INVOCATION_ID` set) it restarts only a `.service` whose `MainPID` is the daemon itself: `INVOCATION_ID` leaks into everything started from a terminal that systemd runs as a service (a `gnome-terminal` or Plasma `app-*.service` shell) and into `gradlew run`, so the unit is looked up with `GetUnitByPID` and checked first. Then it replies `ok:restarting <unit>` and, ~300 ms later (so the reply goes out first), calls the user manager's `ResetFailedUnit` (so `StartLimitBurst` can't leave a GUI-requested restart stopped) and `RestartUnit(<unit>, "replace")`; the name drops and comes back. A `RestartUnit` that fails at that point is only logged at WARN. Other replies: `busy:a pairing window is open`, `busy:a firmware or language update is running`, `error:restart with rc-service stoandl restart` (OpenRC), `error:stoandl isn't running as a service; stop it and start it again` (no `INVOCATION_ID`), `error:stoandl isn't running as its own service (it is in <unit>); stop it and start it again`, `error:couldn't ask systemd to restart stoandl (<msg>); use systemctl --user restart stoandl`. All refusals come before anything is scheduled. | `daemon restart` |

### Watch (`stoandl watch`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `BluetoothStatus` | `() → s` | Whether host Bluetooth is on/usable: `ok:on` / `ok:off`. libpebble3's state, read from BlueZ: `on` = some adapter exists and is `Powered` (rfkill and airplane mode show as `Powered=false`, `PowerState=off-blocked`). While it is off the daemon makes no connection attempts. The daemon logs every transition; this method exposes the state for polling. | `watch list` (a line when off), GUI |
| `ListWatches` | `() → as` | Known watches, one record each: `name\tstate\tbattery\ttransport` (below). | `watch list` (also bare `watch`) |
| `Battery` | `() → s` | Active watch's battery: `ok:<name>\t<level>` (0–100), `unknown:<name>`, or `notready:`. | `watch battery` |
| `BatteryHistory` | `(s,x) → s` | Battery %-over-time series for `(watch, sinceEpoch)` (empty watch = connected). `ok:` + newline-joined `ts\tlevel\tsource\tvoltage` records (see below); `notready:` when capture is off. | `watch battery history` |
| `BatteryInsights` | `(s) → s` | Derived insights for the GUI Battery card. `ok:` + 12 tab fields (see below), `unknown:<name>` (too little data), or `notready:`. | `watch battery insights` |
| `BatteryActivity` | `(s,x) → s` | Per-interval drop + notification counts for `(watch, sinceEpoch)`. `ok:` + newline-joined `ts\tdrop\tnotif\tnotifDnd` records (see below); `notready:` when capture is off. | `watch battery activity` |
| `BatteryPower` | `(s,x) → s` | Estimated battery-drain attribution for `(watch, sinceEpoch)` (heartbeat only). `ok:` + newline-joined `category\testDrainPct\tsharePct` slices, largest first (empty body for a GATT-only watch); `notready:`. | `watch battery power` |
| `HeartbeatInfo` | `(s) → s` | Header of the newest captured analytics heartbeat for `watch` (connected watch; empty = the connected one). `ok:watchTs\trx\tsize\tversion\tbuildId\tfw\tknown\tmetricCount` (see below); `unknown:<label>` (watch not connected, or no heartbeat captured yet); `notready:` (`battery.heartbeat` off). | none (`watch battery heartbeat` reads the files offline) |
| `HeartbeatMetrics` | `(s) → as` | **Every** metric of that newest heartbeat in wire order, one `name\tvalue\ttext\traw` record each (101 on fw ≥ 4.33). Empty whenever `HeartbeatInfo` is not `ok:` or says `known` 0. | none (`watch battery heartbeat --all` decodes the files offline) |
| `Connect` | `(s) → s` | Connect/switch to a known watch by name (exact-then-unique-substring); hands it the single connection slot: it gets the persisted connection goal and every other watch loses it, so only it reconnects after a restart. Refused (`error:`) while a firmware or language update runs or a pairing window is open (its end hands the goal back to the watch that had it), unless it names the watch that is connecting or connected. Empty name: resume after `Disconnect` (the watch holding the connection goal connects again; when no watch, a new one being paired included, holds it and no pairing window is open, a lone known watch, or with Bluetooth on the most recently connected one, gets it). A name resumes too. Inside a Quiet Time pause the `ok:` message says the link comes back when Quiet Time ends. | `watch connect [name]` |
| `Disconnect` | `() → s` | Disconnect the watch and keep every watch disconnected until `Connect` or a pairing window (`Pair`/`Repair` resume it). A libpebble3 `holdDisconnected` hold, not `requestDisconnection` (the persisted connect goal is untouched). With `connection.disconnect_persists` (default on) it lasts across a daemon restart. `ok:<message>`, also when already paused; `error:` while a firmware/language update (and its reboot) runs or a pairing window is open. | `watch disconnect` |
| `LinkPause` | `() → s` | Why the watch link is held off: `ok:` + comma-separated `manual` (`Disconnect`) and/or `quiet_time` (`power.quiet_time_link_off`); bare `ok:` when nothing holds it. `WatchesChanged` also fires when this changes. | `watch list` (a line when paused), GUI Watch page banner |
| `Pair` | `() → s` | Open a ~2-min pairing window; returns `ok:` immediately, poll `PairStatus`. The window discovers whether or not the display is on (it is an explicit request), but only while the host is awake: discovery stops for every suspend. It also opens while a watch is connected or away (to pair a second one): libpebble3 links one watch at a time, so the new watch takes the link over once it bonds. | `watch pair [--yes]` |
| `PairStatus` | `() → s` | Pairing outcome: `pending:<msg>` / `confirm:<code>` (numeric comparison awaiting `ConfirmPairing`) / `ok:` / `error:` / `timeout:`. Only a watch that **wasn't known when the window opened** resolves it: `ok:Paired and connected` once it connects, `ok:Paired` on a new bond (BLE, or a Bluetooth Classic bond). The watch a `Repair` re-pairs counts once it has been seen without a link (so a still-connected watch doesn't end the window at once); a known watch coming back in range never does. On `error:` (declined) or `timeout:` the daemon gives the connect goal back to the watch that held it when the window opened, so pairing a second watch never leaves the first one disconnected. `pending:` messages are progress (`Found <watch> — pairing...`, `Confirm code <NNNNNN> on the watch`) or why the window can't discover right now: `Discovery paused — Bluetooth is off. Turn it on to pair.` / `BLE scan paused — <watch> is connecting (a scan would disturb it).` / `Searching again — the phone slept, which pauses discovery. Keep it awake (e.g. the screen on) until the watch is found.` / `Scan failed, retrying — <libpebble3's scan error>` (while the scan keeps failing to start) — show them as they change. A window that times out after such a failure ends `timeout:Pairing timed out — <the scan error>`. | (polled by `watch pair`/`watch repair`, which print each new `pending:` message; on `confirm:` the CLI asks y/N when stdin is a terminal or holds a piped answer, and accepts by itself with `--yes` or with nothing to read an answer from) |
| `ConfirmPairing` | `(b) → s` | Accept/decline the `confirm:<code>` numeric comparison (verify it matches the watch first). `ok:accepted`/`ok:declined`, or `error:No pairing confirmation pending`. Gates client-initiated `Pair`/`Repair` only; the notification re-pair path auto-accepts. A declined or timed-out pairing ends the window and isn't retried: the daemon drops libpebble3's connection request for that watch, and its pairing agent refuses every pairing request outside a pairing window (and any from a device that isn't a Pebble). | (CLI y/N prompt during `watch pair`/`repair`; skipped with `--yes` or when stdin is neither a terminal nor holds an answer) |
| `Repair` | `(s) → s` | Forget one known watch (bond + Trusted intent) and reopen the pairing window; multi-watch-safe. Poll `PairStatus`. | `watch repair <name> [--yes]` |
| `Unpair` | `(s) → s` | Forget watch(es): empty = blanket (all), name = single (exact-then-substring). libpebble `forget()` + BlueZ `RemoveDevice`. | `watch unpair [name]` |
| `FindWatch` | `() → b` | Ring the watch continuously (a "Find My Watch" call screen) until a button is pressed. `false` = not ready. | `watch find` |
| `WatchDetails` | `() → s` | Structured details for the connected watch: `ok:name\tcode\tmodel\tplatform\ttransport\tfirmware\tserial\tbattery\tlastSync`, or `notready:`. | *(GUI; no standalone verb — see `support`/`watch list`)* |
| `SetWatchNickname` | `(s,s) → s` | Rename a known watch `(query, nickname)` (exact-then-unique-substring; nickname non-empty). `ok:`/`notfound:`/`error:`/`notready:`. | `watch rename <name> <new name>` |

`ListWatches` record: `name \t state \t battery \t transport` — `state` ∈ `connected` | `recovery` |
`connecting` | `disconnected`; `battery` is the 0–100 level for a connected watch, else empty; `transport` is
`ble`/`classic` for a connected watch, else empty. `recovery` is a watch connected in its recovery firmware
(PRF): `SideloadFirmware`/`UpdateFirmware`/`FirmwareStatus`, `GetCoreDump`, `GatherLogs` and
`WatchInfoText` work on it, every other watch method answers as if no watch were connected
(`notready:`/`unknown:`). A client should offer only those tools for it — they are how a watch stuck in
recovery gets flashed back.

`WatchDetails` record (after `ok:`): `name \t code \t model \t platform \t transport \t firmware \t
serial \t battery \t lastSync`. `code` is the BLE-advert-name suffix (may be empty); `model` is the
full `WatchColor.uiDescription` (e.g. "Pebble Time Steel - Black"); `platform` is the watch-type name
(e.g. `BASALT`); `transport` is the human label "Bluetooth LE"/"Bluetooth Classic"; `lastSync` is a
relative age of the last connection.

`BatteryHistory` record (per line, after `ok:`): `ts \t level \t source \t voltage`. `ts` is epoch
seconds; `level` is 0–100 % (fractional for the heartbeat source); `source` is `heartbeat`
(primary — the watch's hourly analytics blob) or `gatt` (fallback — the BLE level); `voltage` is
volts for the heartbeat source, empty for GATT. See [battery-insights.md](battery-insights.md).

`BatteryInsights` record (after `ok:`): `name \t level \t charging(0|1) \t dischargePerHour \t
hoursRemaining \t chargeSessions7d \t lastChargedEpoch \t min24h \t max24h \t sampleCount \t voltage \t
source`. `hoursRemaining` is empty when unknown/charging (prefers the firmware's own `battery_tte_s`);
`voltage` is present only for the `heartbeat` source.

`BatteryActivity` record (per line, after `ok:`): `ts \t drop \t notif \t notifDnd`. `ts` is epoch
seconds; `drop` is the SoC percent consumed that interval (firmware `soc_pct_drop` for `heartbeat`, else
the consecutive-sample delta); `notif`/`notifDnd` are notifications received that interval (total / while
in Quiet Time — always 0 for the `gatt` fallback). Powers the GUI's drop-per-hour bar + notification
overlay. See [battery-insights.md](battery-insights.md).

`BatteryPower` record (per line, after `ok:`): `category \t estDrainPct \t sharePct`. Categories: System
(always-on floor), Display (backlight), Vibration, Speaker, Heart rate, Bluetooth, CPU. Each interval's
firmware-measured `soc_pct_drop` is split across subsystems by a modeled per-subsystem average current ×
on-time weight, so `estDrainPct` is a percent of battery (slices sum to the window's measured drop; 0 when
the window has no measured discharge to anchor to) and `sharePct` is the slice's share of it. A modeled
**estimate**, not measured energy — the record has no per-subsystem mAh. Powers the GUI's "what drew
power" pie. See [battery-insights.md](battery-insights.md).

`HeartbeatInfo` / `HeartbeatMetrics` back the GUI's **Debug → Heartbeat** page: the raw analytics record
the watch emits hourly, decoded in full. Both look the watch up among the **connected** watches (its
serial keys the store), so a disconnected watch reads as `unknown:` / empty.

`HeartbeatInfo` fields: `watchTs` is the record's own timestamp and `rx` the host receive time (epoch
seconds; `watchTs` is 0 when the record was too short to carry one); `size` is the record length in
bytes and `version` its layout version byte; `buildId` is the firmware's GNU build-id (40 hex chars,
**not** a git SHA; empty for a truncated record); `fw` is the firmware version libpebble3 reported when
the record was captured (not the record's own `fw_version` metric); `known` is 1 when `(size, version)`
is a released layout stoandl derives from PebbleOS's `analytics.def`, else 0 — the record is still
captured raw, but **no metrics are emitted rather than guessed ones**, and the page should say so;
`metricCount` is the number of `HeartbeatMetrics` records (0 when `known` is 0).

`HeartbeatMetrics` records are `name \t value \t text \t raw`, in wire order: `value` is already
scale-divided, printed as an integer when whole and with two decimals (`.` separator) otherwise, and
empty for string metrics; `text` is set only for string metrics (`fw_version`, `watchface_name`,
`watchface_uuid`; tabs and newlines replaced by spaces); `raw` is the undivided wire integer (negative
for signed metrics such as `utc_offset_s` and `battery_temp_c`; empty for strings). Which metrics
appear depends on the watch's firmware release. Metric names, offsets per layout, and what each is
good for: [heartbeat-metrics.md](heartbeat-metrics.md).

### Apps & watchfaces (`stoandl apps`, `stoandl config`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `ListApps` | `() → as` | Locker contents (apps + faces), one record each (see below). | `apps list` |
| `GetAppIcon` | `(s) → s` | The menu icon of the installed app/face `<uuid>`, extracted **locally** from its cached `.pbw` (no network/appstore) and written as a PNG under `<configDir>/icons/<uuid>.png` (cached after first call). A `none:` is cheap to repeat: the `pbw-cache` listing is kept until that directory's mtime changes, and a `.pbw` without a `menuIcon` isn't reopened. `ok:<abs png path>` / `none:` (no cached `.pbw`, no declared `menuIcon`, or undecodable) / `notready:` / `error:<msg>`. Fetched lazily per-row by the GUI (kept off `ListApps`). | *(GUI only)* |
| `LaunchApp` | `(s) → s` | Launch app/face by UUID or name. `ok:`/`notfound:`/`ambiguous:`/`notready:`/`error:`. | `apps launch <name\|uuid>` |
| `RemoveApp` | `(s) → s` | Uninstall app/face from the locker (system apps refused). | `apps remove <name\|uuid>` |
| `SideloadApp` | `(s) → s` | Start installing a local `.pbw` (absolute daemon-side path) as the `sideload` [watch task](#watch-tasks): `ok:started` / `busy:<msg>` (one already runs) / `error:No such file: <path>`. The task ends `ok:Sideloaded <file>` or `error:<msg>` (e.g. an invalid `.pbw`). | `apps install <path.pbw>` (aliases `sideload`, `add`; waits for the task) |
| `OpenConfig` | `(s) → s` | Open the settings page of a PKJS app (name or UUID; launches it if needed). Returns a loopback `http://127.0.0.1:<port>/<token>/…` URL the daemon serves the page from (`data:` Clay pages decoded, `pebblejs://close` rewritten to the daemon), or empty string if none. The caller only opens it in a browser: Save goes from the page to the daemon to the app. The token is single-use and lapses after an hour or when the same app's page is opened again. | `config [app]` |
| `RunningApp` | `() → s` | The app/face **currently on the watch's screen** (what's running now — may be a launched watchapp, distinct from the `active` watchface flag in `ListApps`). `ok:<title>\t<uuid>` / `none:` / `notready:`. | `watch running` |
| `SetAppOrder` | `(s,i) → s` | Move the app/face matching `query` to 0-based locker position `order`; syncs to the watch menu via the locker BlobDB. `ok:`/`notfound:`/`ambiguous:`/`error:`. | `apps order <name\|uuid> <pos>` |
| `RestoreSystemAppOrder` | `() → s` | Reset the system apps to their default locker order. | `apps order reset` |

`ListApps` record: `uuid \t type \t order \t flags \t title \t developer \t version` (`version` = the
locker version string, empty if unknown), where `flags` is a
comma-joined subset of **`{active, sideloaded, config, system, synced}`** (`active` = current
watchface, `config` = has a Clay/PKJS config page, `system` = built-in, `sideloaded` = installed from
a `.pbw`, `synced` = present on the watch — system apps are always synced).

### Watch settings (`stoandl settings`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `ListWatchPrefs` | `() → as` | Watch advanced settings, one record each, grouped by section in display order (see below). Blocks up to 5 s while the prefs load. | `settings` / `settings list [filter]` |
| `SetWatchPref` | `(s,s) → s` | Set setting `<id>` to `<value>` (parsed per the pref's type). | `settings set <id> <value>` |
| `ResetWatchPrefs` | `() → s` | Set every watch setting that differs from its default back to the default and sync it to the watch like `SetWatchPref`; a `watch.<id>` line pinned in `stoandl.conf` is rewritten to the default too, so a reconnect doesn't re-apply the old value. `ok:Reset <n> watch settings…`, or `notready:` while the settings aren't loaded. | `settings reset` |

`ListWatchPrefs` record (11 columns): `id \t type \t current \t default \t allowed \t flags \t name \t
description \t section \t help \t requires`. Columns 9–11 (0-based 8–10) were appended, so a client that
reads eight is unaffected. `name` and `description` are libpebble3's, which match the watch's own menu,
except where two prefs would share a name: `systemTextSize` reads **Menu Text Size** and `notifTextSize`
**Notification Text Size**. `section` is the GUI section title, in this order: *Quick launch*, *Backlight &
display*, *Theme*, *Notifications & timeline*, *Quiet Time*, *Vibration*, *Speaker*, *Music*, *Time & units*,
*Health*, *Motion & menus*, *Advanced* (diagnostic prefs), and *Other* for a pref stoandl doesn't know yet. `help` is
stoandl's longer text behind the row's help button (may be empty; never repeats `description`). `requires`
is `id=value` alternatives joined by `|`, in the `current` spelling, when the pref only matters while
another one has that value (empty: always) — e.g. the light timeout/intensity/dynamic/ambient prefs carry
`lightPreset=Advanced`. A clause whose gating pref is firmware-gated (`lightPreset` needs PebbleOS 4.22) is
dropped when the watch's firmware lacks that pref, so a classic watch on older firmware keeps the rows
editable; "the watch's firmware" is the connected watch's, else the firmware the most recently connected
known watch last ran, and with none known the clause is dropped too. The section, help and requires text
lives in `pebble/WatchPrefText.kt`; `gui/tools/watch-pref-text.tsv` is its generated copy for the GUI mock
([GUI mock fixtures](#gui-mock-fixtures)). `flags` carries `debug` for advanced settings. `type` ∈ **`bool`** (`current`/`allowed`
= `true|false`), **`number`** (`current` is the value with its unit appended, e.g. `3000 ms`; `allowed`
is `min..max[ unit]`), **`enum`** (`current` + `allowed` are **display names** like `Standard - Low`,
not the Kotlin constant — round-trip-safe, `SetWatchPref` accepts either), **`quicklaunch`** (`current`
is a system action / an app name / `off` / a raw uuid; `allowed` is `off|Quiet Time|Backlight|Motion
Backlight|Airplane Mode|Toggle Touch|Timeline Past|Timeline Future|Clear Notifications|<app name or uuid>`: the
firmware system actions libpebble3 lists (`SystemAppIDs.SYSTEM_SHORTCUTS`, the official app's picker), then the
literal app placeholder — pick the target by action or app name. An action only some watches have (`Toggle Touch`:
touch watches on fw 4.39+; `Clear Notifications`: fw 4.38+) is left out while a watch without it is connected,
and `SetWatchPref` refuses it then; with no watch connected it is listed, `description` names its audience
(`Toggle Touch: touch watches only. Clear Notifications: PebbleOS 4.38 or later.`) and the `ok:` reply ends
`; touch watches only` (or `; PebbleOS 4.38 or later`)), **`color`** (`current` is `0xRRGGBB`; `allowed` is `RRGGBB|<preset name>|…`), and
**`schedule`** (the Quiet Time weekday/weekend hours: `current`/`default` are a 24 h `HH:MM-HH:MM`
window in the watch's local time, where an end before the start runs overnight; `allowed` is the
literal `HH:MM-HH:MM`. The hours alone do nothing: the matching `bool` pref
`dndWeekdayScheduleEnabled`/`dndWeekendScheduleEnabled`, off by default, turns the schedule on). **The `allowed` option list is pipe-(`|`)-separated** for
`enum`/`quicklaunch`/`color` (a display name can contain a comma), so split on `|`, not `,`.
`SetWatchPref` parses the value per type and accepts an enum display-name-or-constant, a color
preset-name-or-hex, a quick-launch system-action-or-app-name-or-uuid-or-`off` (an action name ignores case, spaces, `-`
and `_`, and wins over a locker app of the same title), and a schedule `HH:MM-HH:MM`.

### Notifications (per-app + filters) (`stoandl notif`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `NotifList` | `() → as` | Per-app notification store, one record each (see below). | `notif list` (also bare `notif`) |
| `NotifSetMute` | `(s,s) → s` | Set mute for the app matching `<query>`; spec = `always`/`never`/`weekdays`/`weekends` or a duration (`30m`/`1h`/`2d`). | `notif mute <app> [spec]` / `notif unmute <app>` (sends `never`) |
| `NotifSetMuteAll` | `(s) → s` | Mute every app's notifications for a while: one temporary state **apart from** the per-app mutes, which it never touches (unmuting restores nothing because nothing was overwritten). `spec` ∈ a duration `30m`/`1h`/`2d`, `today` (until the next local midnight) or `never` (resume now). Persisted across restarts; applies whatever `notification.per_app` says, and an allow filter still gets through. `always`/`weekdays`/`weekends` are refused with `error:` — they are per-app states, and the indefinite form is `notification.forward`. `ok:<msg>` / `error:<msg>`. | `notif mute-all <30m\|1h\|2d\|today>` / `notif unmute-all` (sends `never`) |
| `NotifMuteAllStatus` | `() → s` | Whether `NotifSetMuteAll` has notifications muted: `muted:<epoch seconds it ends>` or `none:`. No signal: a client re-reads it on its own tick and when it comes to the front. | `notif list` (a header line while muted), `status` |
| `NotifSetStyle` | `(s,s,s,s) → s` | Per-app styling `(query, color, icon, vibe)` applied host-side; per value, empty = unchanged, `default` = reset. A name the watch can't show is refused (`error:`) and nothing changes. | `notif style <app> [--color] [--icon] [--vibe]` |
| `NotifStyleChoices` | `() → as` | What a style picker offers: `vibe \t value \t label \t ms,ms,… \t builtin\|custom` (every built-in vibration, libpebble3's `DefaultVibePattern`, then the custom ones from `NotifSetVibePattern`, by name) or `icon \t value \t label` (a curated set). `value` is what `NotifSetStyle` takes and `NotifList` reports. The two vibe columns were appended, so a client reading three is unaffected. | `notif vibes` (vibes only); `notif styles` prints the built-ins offline |
| `NotifSetVibePattern` | `(s,s) → s` | Add or replace a named custom vibration `(name, pattern)`, `pattern` a CSV of on/off ms (≤ 64 segments, ≤ 10 s in total); an empty `pattern` removes it. Stored in libpebble3's `VibePatternEntity` table next to the built-ins, so `NotifSetStyle`, the extension `notify` `vibe` field and `calendar.reminder_vibe` take the name. A name that is a built-in, a CSV or `default`/`none`/`clear` is refused. Case-insensitive: re-adding `work` replaces `Work`. `ok:` / `notfound:` (remove) / `error:`. Live; no signal (pickers re-read `NotifStyleChoices` on open). | `notif vibe-add <name> <ms,ms,…>` / `notif vibe-remove <name>` |
| `NotifVibeTest` | `(s) → s` | Buzz the connected watch with a vibration `(spec)`: a built-in or custom name, a CSV of on/off ms, or `default` (the watch's own). Sent as a short "Vibration test" notification **outside** the notification pipeline (like the official app's test), so mute, filters, `notification.forward` and per-app styles don't apply; Quiet Time on the watch still silences it. `notready:` with no watch connected; `error:` for an unknown name or a firmware without custom vibrations (before 4.9.97, which would buzz only its default). | `notif vibe-test <name\|ms,ms,…\|default>` |
| `NotifSetAppName` | `(s,s) → s` | Where the app matching `query` shows its name on the watch `(query, placement)`, overriding `notification.app_name` for it: `small` / `subtitle` / `off`; `default` (or empty, `none`, `clear`) drops the override. Applies while `notification.per_app` is on. `ok:` / `notfound:` / `ambiguous:` / `error:Unknown app-name placement …`. Live. | `notif style <app> --app-name <small\|subtitle\|off\|default>` |
| `NotifSetImages` | `(s,s) → s` | Turn pictures off for the app matching `query` while `notification.images` stays on `(query, mode)`: `off`, or `on`/`default` (also empty, `none`, `clear`) to follow `notification.images` again. Stored in libpebble3's per-app `sendImages` column; applies while `notification.per_app` is on. `ok:` / `notfound:` / `ambiguous:` / `error:Unknown images setting …`. Live. | `notif images <app> on\|off\|default` |
| `SendTestNotification` | `(s,s) → s` | Debug: inject a synthetic notification `(title, body)` to the watch through the normal send path (per-app mute, style and filters all apply; `notification.only_when_locked` doesn't) — the notification-pipeline counterpart of `FakeCallRing`. `ok:`/`error:`/`notready:`. | `notif test [title] [body]` |
| `NotifListFilters` | `() → as` | Global notification filters, one record each: `pattern\taction` (`action` ∈ `allow`/`block`). | `notif filter list` |
| `NotifAddFilter` | `(s,s) → s` | Add a global filter `(pattern, action)`. `pattern` is a Java regex (inline flags like `(?i)` work), matched against appName + title + body; `action` `allow`/`block` (anything not `allow` ⇒ `block`). `ok:added <action> filter` / `error:empty pattern` / `error:invalid regex …`. Applied **live** (no restart). | `notif filter add <regex> [allow\|block]` |
| `NotifRemoveFilter` | `(s) → s` | Remove a global filter by exact `pattern`. `ok:filter removed` / `notfound:`. Live. | `notif filter remove <regex>` |

`NotifList` record: `name \t muteLabel \t color \t icon \t vibe \t lastNotifiedEpochSeconds \t appNamePlacement
\t images` (`muteLabel` ∈ `never`/`always`/`weekdays`/`weekends` or `muted-until <instant>`; an unset style
field is empty; `appNamePlacement` is the app's own placement `small`/`subtitle`/`off`, empty = it follows
`notification.app_name`; `images` is `off` when the app's pictures are off (`NotifSetImages`), empty = it
follows `notification.images`). The 7th and 8th columns were appended, so a client that reads fewer
positionally is unaffected.

`stoandl notif style <app>` calls `NotifSetStyle` only when `--color`, `--icon` or `--vibe` is given and
`NotifSetAppName` only for `--app-name`, so one command can set both.

**Notification filters** are a host-side **global** allow/block list enforced in `WatchNotifier.push()`
(the single send choke point). `allow` is a whitelist that **bypasses** the block filters, the master
forwarding switch (`notification.forward`), *and* per-app mute; `block` drops the notification. Filters
are stored in a dedicated file `<configDir>/notification-filters` (one `action⇥pattern` line each) and
are **live-mutable** — `NotifAddFilter`/`NotifRemoveFilter` take effect immediately, no restart.

### Sync — service on/off + force-sync triggers, weather city search (`stoandl sync`/`weather`/`calendar`/`health`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `SetSyncEnabled` | `(s,b) → s` | An **alias** for `SetConfig` of the service's master key (the `key` column of `GetSyncStatus`): `service` ∈ {notifications, weather, calendar, music, health} → `notification.forward`, `weather.enabled`, `calendar.enabled`, `music.enabled`, `health.sync`. Applied live through the same write path, so it emits `ConfigChanged`. `dnd`'s key is four-way, so `SetSyncEnabled("dnd", …)` returns `error:use dnd.sync (off\|to_watch\|to_host\|both)` and changes nothing (use `SetConfig dnd.sync`). `ok:<service> enabled\|disabled` / `notfound:` (unknown service) / `error:`. The GUI no longer calls it (each master is one schema row); it stays for the CLI and scripts. | `sync enable <service>` / `sync disable <service>` (`sync dnd` prints the `dnd.sync` hint) |
| `SyncWeather` | `() → s` | Fetch weather now and push to the watch. `error:` if weather isn't enabled. | `weather` |
| `SearchWeatherPlace` | `(s) → s` | Look a place name up with Open-Meteo's geocoder (a web request): `ok:` followed by one `name\tlat\tlon\tregion\tcountry` record per line (newline-separated, most populous first; coordinates rounded to 4 decimals, the name already cleaned for `weather.locations`); `none:` (no match, or Open-Meteo unreachable within 8 s); `disabled:<why>` unless `weather.enabled` **and** `weather.place_search` are on (then nothing is sent); `error:` (empty query). | `weather search <city>` |
| `AddWeatherLocation` | `(s,d,d) → s` | Add `(name, latitude, longitude)` to `weather.locations`, replacing an entry of the same name (case-insensitive); persisted and applied live through the `SetConfig` path. No egress, no gate. `ok:Added\|Updated <name> (<lat>, <lon>)` / `error:` (empty name, coordinates out of range, or the write refused). | `weather search <city> --add [n] [--as <label>]` |
| `SyncCalendar` | `() → s` | Re-read calendar sources → update timeline pins. `error:` if calendar isn't enabled. | `calendar sync` |
| `SyncHealth` | `() → s` | Request fresh health/activity data from the watch and re-project the export. | `health sync` |
| `HeartRate` | `() → s` | Latest stored heart-rate reading: `ok:<bpm>\t<epochSec>`, `none:` (no sample yet), or `notready:`. Read-only DB lookup (not a live GATT stream); fills from the connect-time health sync. | `health hr` |
| `GetHealthProfile` | `() → as` | The watch's health **profile** (its own activity-tracking config, in the HealthParams BlobDB — the *write* side, vs the read-only data sync). One `key \t value` per row; keys: `tracking`, `activity_insights`, `sleep_insights`, `hrm`, `hrm_interval` (`5min`/`10min`/`30min`/`1h`/`off`; its row has a third field, the intervals `SetHealthProfile` accepts right now, `\|`-joined: `5min` only with every connected watch on fw 4.39.0+, or when it is already the value, so the GUI offers just those), `hrm_activity` (HR during activities: continuous HR in detected walks and runs), `units`, `height_cm`, `weight_kg`, `age`, `birth_year` (setting it or `age` sets both; libpebble3 re-derives the age from it on every write), `gender`, `resting_hr`, `max_hr` (derived: 220 − age, at least the elevated heart rate, which is what the watch gets; read-only). `units` is the watch's metric/imperial setting, and also the unit weather is sent in (there is no separate weather unit). | `health profile` |
| `SetHealthProfile` | `(s,s) → s` | Set one profile `key` to `value` and sync to the watch. `ok:`/`error:`/`notready:`. `age` takes 1–120, `birth_year` the 120 years before this one; `max_hr` accepts only its current value (a record round-trips) and answers `error:max_hr follows from the age …` for any other. Switching `hrm_interval` to `5min` is an `error:` unless every connected watch runs firmware 4.39.0 or newer (and when none is connected). | `health profile set <key> <value>` |
| `ListCalendars` | `() → as` | Synced calendars: `id \t name \t enabled\|disabled \t accountId`. `accountId` is the owning source's id (see `ListCalendarSources`) so the GUI nests calendars under their account (`discover` for auto-discovered, empty if unknown). | `calendar list` (also bare `calendar`) |
| `SetCalendarEnabled` | `(s,b) → s` | Enable/disable a single calendar by id or name substring. | `calendar enable\|disable <id\|name>` |
| `ListCalendarSources` | `() → as` | Editable calendar **sources** (vs the discovered calendars): `id \t type \t url \t username \t label \t lastError \t lastSync`, `type` ∈ {caldav, ical, ics}, `id` self-describing (`caldav:<token>` / `ical:<url>` / `ics:<path>`). `lastError` (appended) is why the source's last read failed, worded for the user (`Sign-in failed (401)`, `Unreachable: …`, `No such file or folder`), empty while it reads fine; `lastSync` (appended) is the epoch seconds of its last good read, empty if none since the daemon started. Password is **never** returned (write-only). | `calendar sources` (a `problem:` and `last sync:` line per source) |
| `AddCalendarSource` | `(s,s,s,s) → s` | Add a source: `(type, url, username, password)`; username/password apply to CalDAV only (password → system keyring, else a 0600 file — never config). Persisted + synced live. `ok:<id>\t<backend>` (backend = keyring/file/none) / `error:`. | `calendar add <type> <url> [user]` |
| `UpdateCalendarSource` | `(s,s,s,s) → s` | Edit a source by id: `(id, url, username, password)`. An **empty** password keeps the stored CalDAV one. `ok:<id>\t<backend\|kept\|none>` / `notfound:` / `error:`. | `calendar passwd <id>` |
| `RemoveCalendarSource` | `(s) → s` | Remove a source by id (and its stored CalDAV password). `ok:removed` / `notfound:` / `error:`. | `calendar remove <id>` |
| `AddTodo` | `(s,s,s) → s` | Save a to-do `(title, due, note)` in the list `calendar.todos` names (a CalDAV list, a local `.ics` or a vdir). `due` is empty, `YYYY-MM-DD` or `YYYY-MM-DDTHH:MM` (local time; an offset or `Z` is taken too); a due to-do gets a reminder at that time (09:00 on a due day). `ok:saved` / `ok:queued\t<problem>` (the server couldn't take it now: kept on this device and sent after a later calendar sync; `problem` is empty when the server wasn't reachable. For a local `.ics` whose Calindori group names a `lockFile`, `problem` is `the calendar is being synchronized` when a sync held that lock for more than 10 s: written as soon as the sync lets it go) / `error:` (no list chosen, a bad due, no account for the list's host, an unwritable file). The extension method `addTodo` takes the same path. | `calendar todo <title> [--due …] [--note …]` |
| `PendingTodos` | `() → as` | The to-dos waiting to be sent, oldest first: `title \t target \t lastError`. | `calendar todos` |
| `GetHealthSummary` | `(s,i) → s` | Health summary (20 tab fields; see below) for the period `periodType`(`day`\|`week`\|`month`) + `offset`(periods back, 0 = current) from the synced DB, or `notready:`. | *(GUI; CLI `health` reads the export files directly)* |
| `GetHealthSeries` | `(s,s,i) → as` | Health chart series for `metric` over (`periodType`, `offset`): `steps` (`day`: 24 `hour\tsteps` hourly buckets; `week`/`month`: one `label\tsteps\ttypical` row per day), `sleep` (`day`: `startFraction\twidthFraction\tisDeep` timeline rows; `week`/`month`: one `label\ttotalMin\tdeepMin` row per night), `heart` (`day`: **minute-level** `minuteOfDay\tbpm` rows; `week`/`month`: one `label\tavgBpm` row per day). | *(GUI)* |
| `GetSyncStatus` | `() → as` | Per-feature **live** runtime status, one row each: `service\tenabled\tavailable\tlastSync\tkey` for {notifications, weather, calendar, music, health, dnd}. Reflects the running state, not just the startup config. `available` is `false` for weather/calendar when no source is configured (the GUI then says "No place yet" / "No calendar source yet"); `lastSync` is `live`/`off`/`no source` (and the dnd mode string when dnd is on); `key` (appended) is the `stoandl.conf` key that switches the service, a `GetConfigSchema` row. | `sync list` (KEY column), `status`, GUI |
| `MusicStatus` | `() → s` | Now-playing state from the desktop MPRIS bridge, for the GUI's Music settings page: `ok:<playing\|paused>\t<player>\t<track>` (track = `Title — Artist`, may be empty), `none:` (nothing playing / no player), or `notready:` (music control off). | *(GUI)* |

`GetHealthSummary` record (after `ok:`, 20 fields): `stepsTotal \t stepsAvgPerDay \t stepsTypical \t
distanceKm \t kcal \t activeMin \t sleepTotalMin \t sleepDeepMin \t sleepLightMin \t sleepTypicalMin \t
sleepBedtime \t sleepWakeup \t hrAvg \t hrResting \t hrCurrent \t hrMin \t hrMax \t hrAvailable(yes\|no) \t
daysWithData \t lastSync`. For `day` the step/sleep/HR fields are that day's; for `week`/`month` they're
per-day averages (`stepsAvgPerDay`; `sleepTotalMin`/`Deep`/`Light` = avg per night) with `stepsTotal` the
period sum. `stepsTypical`/`sleepTypicalMin` are the typical *daily* figures (`getTypicalSteps().sum()` /
30-day avg). `sleepBedtime`/`sleepWakeup` (epoch sec) and `hrCurrent`/`hrMin`/`hrMax` are populated only
for `day` (`0` otherwise — the GUI hides them). Pebble models only light/deep (REM dropped; light = total
− deep). There is no step-goal (dropped).

`GetHealthSeries("sleep")` rows: `startFraction \t widthFraction \t isDeep(0\|1)` — one per sleep
interval, as fractions of an 18 h window (6 PM yesterday → noon today). Light intervals come first,
deep last, so a client drawing them in order paints deep on top of light; awake gaps are uncovered
space. (Steps/heart rows keep the `label \t value` shape.)

There is **no** force-sync for music or notifications. `GetSyncStatus` exposes the per-service
enabled/available **read**; the master on/off is the service's own config key, written with `SetConfig`
(or the `SetSyncEnabled` alias) and applied **live** — the service starts or stops with no daemon restart.
`lastSync` is a state label (`live`/`off`/`no source`, plus the dnd mode when dnd is on), not yet a
tracked timestamp; per-source health for calendars is in `ListCalendarSources`.

### Firmware (`stoandl firmware`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `CheckFirmware` | `() → s` | Check the matching source (GitHub for Core, cohorts.rebble.io for classic) for a newer build: `ok:<board>\t<current>\t<latest>\t<asset>\t<yes\|no>\t<source>\t<changelogUrl>` (the 7th field, the PebbleOS changelog page, is a constant present on both `ok:` branches), or `noasset:`/`disabled:`/`notready:`/`error:`. **Cached:** the source's answer is reused for 3 h per source (repo/URL, pre-release setting), board and running firmware version (an `error:` for 1 min), and concurrent callers share one lookup, so a client may call it freely; a cache miss on GitHub still takes seconds on a slow phone link, so call it off the UI thread. The daemon's once-a-day connect-time update notification asks afresh and refreshes the cache, like `UpdateFirmware`; a forced lookup that fails while a good answer under 3 h old is cached answers that one instead of `error:` (GitHub answers 403 once the unauthenticated 60 requests/hour are used up, and a 304 revalidation still counts). | `firmware check` |
| `CheckFirmwareForce` | `() → s` | `CheckFirmware` that asks the source afresh (and refreshes the cache); joins a lookup already in flight. Same replies. For an explicit user refresh only. Added 2026-10; a client that must work with older daemons falls back to `CheckFirmware` on `UnknownMethod`. | `firmware check --refresh` |
| `UpdateFirmware` | `() → s` | Re-check the source (never answered from the cache, so it can't act on a stale "up to date"), download newer firmware and start flashing. `ok:<board>\t<current>\t<latest>\t<asset>` once started; `uptodate:`/`noasset:`/`busy:` (while the watch's own update state is active: an install running, a reboot to apply one, a downgrade waiting for or running in recovery)/`disabled:`/`notready:`/`error:`. Poll `FirmwareStatus`. | `firmware update` |
| `SideloadFirmware` | `(s) → s` | Flash a local `.pbz` (absolute daemon-side path), async. `ok:`/`busy:` (as `UpdateFirmware`)/`error:`/`notready:`. Poll `FirmwareStatus`. | `firmware sideload <file.pbz>` / `firmware <file.pbz>` |
| `FirmwareStatus` | `() → s` | Flash state: `idle:` (or `idle:available <version>` when the last check, cached and at most a few hours old, found newer firmware; it never checks online itself) / `downloading:<asset>` / `waiting:` / `inprogress:<percent>` / `reboot:` (success) / `prf:<version>` (a downgrade on a dual-slot watch: rebooting into recovery with nothing flashed yet; libpebble3 installs the same `.pbz` once the watch reconnects there) / `failed:<reason>` (also when the watch comes back on its normal firmware instead of recovery, so the downgrade was dropped: libpebble3's `The watch didn't reboot into recovery to install the update`; it stays until the next flash) / `notready:`. | `firmware status` (also polled during update/sideload); a `Firmware:` line in `status` |

### Language packs (`stoandl language`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `ListLanguages` | `() → as` | Catalog packs for the watch's board, one record each (see below). | `language list` (also bare `language`; soft — falls back to the offline bundled catalog) |
| `InstallLanguage` | `(s) → s` | Auto-pick (locale/name/id; blank = system locale), download and install. `ok:<displayName>` once started; `notfound:`/`disabled:`/`notready:`/`busy:` (an install runs: libpebble3 would drop a second one)/`error:` (also, on firmware with several packs: every slot in use, or the locale already on the watch). Poll `LanguageStatus`. | `language install <locale\|name\|id>` |
| `SideloadLanguage` | `(s) → s` | Install a local `.pbl` (absolute daemon-side path), async. Same refusals as `InstallLanguage` (no locale check: a `.pbl`'s locale isn't known before it's installed). Poll `LanguageStatus`. | `language sideload <file.pbl>` (alias `add`) |
| `LanguageStatus` | `() → s` | Install state: `idle:` / `downloading:<name>` / `installing:<percent>` / `done:<name>` (the watch's language is that pack) / `failed:<reason>` (also when the watch is on another language after the install: a failure, or an added pack the watch didn't switch to) / `notready:`. | `language status` (also polled during install/sideload) |
| `LanguagePacks` | `() → s` | The packs on the watch, for firmware that keeps several (PebbleOS 4.42, `SupportsMultipleLanguagePacks`: an install adds a pack to a free slot of 4 and switches to it; older firmware keeps one, which an install replaces). `ok:` then one record per line (below; none active = built-in English); `unavailable:` (one-pack firmware) / `busy:` / `notready:` / `error:`. | `language installed` (alias `packs`) |
| `UseLanguage` | `(s) → s` | Make a pack on the watch (slot, locale or name; a unique part of a name) the watch's language, or `english` for the built-in one (the packs stay; what the removed `language` watch pref's English/Custom did). `ok:` / `notfound:` / `ambiguous:` / `failed:` (the watch refused) / `unavailable:` / `busy:` / `notready:` / `error:` (a fonts-only slot). | `language use <slot\|locale\|name\|english>` |
| `RemoveLanguage` | `(s) → s` | Remove a pack from the watch (slot, locale or name), freeing its slot; removing the active one switches the watch to English. Same returns as `UseLanguage`. | `language remove <slot\|locale\|name>` |

`ListLanguages` record: `id \t isoLocal \t displayName \t installed(yes\|no) \t source(rebble\|github)`.
`LanguagePacks` record: `slot \t locale \t name \t version \t active(yes\|no) \t kind(translation\|fonts\|unreadable)`.

### Diagnostics (`stoandl screenshot`/`logs`/`support`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `TakeScreenshot` | `(s) → s` | Start capturing the screen to a PNG as the `screenshot` [watch task](#watch-tasks): at the absolute daemon-side path, or with an empty one a new `pebble-screenshot-<yyyyMMdd-HHmmss>.png` in the user's Pictures/Screenshots (`XDG_PICTURES_DIR`, created if missing). `ok:started` / `busy:<msg>` / `notready:<msg>` (no watch) / `error:<msg>` (a relative path). The task ends `ok:<path>\t<width>\t<height>`, `notready:<msg>` or `error:<msg>` (timeout, watch busy, write failed). | `screenshot [path]` (waits for the task, prints the path) |
| `GatherLogs` | `(s) → s` | Start dumping the watch firmware logs to a text file as the `logs` task: at the absolute path, or with an empty one a new `pebble-logs-<yyyyMMdd-HHmmss>.txt` in Documents/stoandl (`XDG_DOCUMENTS_DIR`). `ok:started` / `busy:` / `notready:` / `error:`. The task (a handful of seconds) ends `ok:<path>`, `notready:<msg>` or `error:<msg>`. | `logs [path]`; also `support` |
| `GetCoreDump` | `(s) → s` | Start fetching an existing coredump as the `coredump` task: to the absolute path, or with an empty one a new `pebble-coredump-<yyyyMMdd-HHmmss>.bin` in Documents/stoandl. `ok:started` / `busy:` / `notready:` / `error:`. The task ends `ok:<path>`, `none:<msg>` (no coredump on the watch), `notready:<msg>` or `error:<msg>`. | `support --coredump` (no standalone verb) |
| `TaskStatus` | `(s) → s` | The state of the watch task `kind` ∈ {`sideload`, `screenshot`, `logs`, `coredump`}: `idle:` (none since the daemon started), `running:<what>`, or the last one's result, kept until the next start (`ok:<…>`, `none:<msg>`, `notready:<msg>`, `error:<msg>`; each starter above gives its `ok:` shape). `error:unknown task '<kind>'` for any other kind. `TaskChanged` carries the same string. | polled by `screenshot`/`logs`/`apps install`/`support`; `status` (a `Watch tasks:` line for every kind that isn't `idle:`) |
| `WatchInfoText` | `() → s` | Watch metadata as a human-readable text block (model/fw/board/serial/language/battery/capabilities). `ok:<text>` / `notready:`. Returned **inline** (no file). | `support` (no standalone verb) |

### Voice: speech to text and text to speech (`stoandl voice`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `VoiceStatus` | `() → s` | Speech-to-text state: `off:`, `ready:<backend>\t<detail>[\t<note>]` (backend `local`, `command` or `http`: the one that answers first; detail = the model's name (with its language for Canary: `Canary 180M flash (en)`), the command's program or the URL; with `voice.fallback_backend` a third field `then <backend> <detail>`, `(fallback <backend> unavailable: <reason>)`, or, when the fallback answers because the primary isn't ready, `(<primary> unavailable: <reason>)`) or `unavailable:<reason>` (no libspeex, model not downloaded, whisper.cpp, transcribe.cpp or the command not found, Canary without a `voice.language` it knows, no URL — the primary's reason when no ready fallback covers it). | `voice` / `voice status` |
| `VoiceModels` | `() → as` | The catalog of speech-to-text models **and** text-to-speech voices, one record each: `id\tname\tprogram\tsizeMb\tstate\trecommended\tengineOk\tnote\tkind`. `state` = `installed`, `available`, `downloading:<percent>` or `failed:<message>`; `program` = `parakeet-cli` / `whisper-cli` / `transcribe-cli` / `piper`, `engineOk` whether it is on PATH (Piper: `piper_exe`, `piper-tts`, or a bare `piper` whose `--help` names the length scale — on Arch and Alpine `piper` is also libratbag's mouse tool); `kind` (appended) = `stt` or `tts`, so a client that reads eight columns sees voices as extra rows (filter on `kind`; a record without it is `stt`). Poll it while downloading (no signal). | `voice models` (two lists) |
| `VoiceDownloadModel` | `(s) → s` | Start downloading the model or voice with that id (one at a time; egress to huggingface.co, only on this call; a voice is its `.onnx` and `.onnx.json`). When it lands: an STT model becomes `voice.local_model` and, unless a custom command or HTTP is set, `voice.backend` turns to `local`; a TTS voice becomes `tts.local_voice`, and `tts.backend` turns to `local` only if it was `off`. Each key written emits `ConfigChanged`. `ok:` / `installed:` (already there; activates the same way) / `busy:` / `notfound:`. | `voice download <id>` |
| `VoiceCancelDownload` | `() → s` | Stop the running download. `ok:` / `none:`. | `voice cancel` |
| `VoiceRemoveModel` | `(s) → s` | Delete a downloaded model or voice (every file of it). `ok:` / `notfound:`. | `voice remove <id>` |
| `Transcribe` | `(s) → s` | Transcribe an audio file at the absolute daemon-side path (16-bit WAV, or Ogg/Opus) with the configured backend (and `voice.fallback_backend` after it) in `voice.language`. Blocks up to a minute. `ok:<ms>\t<text>` (ms first: the text may hold a tab) / `off:` / `unavailable:` / `error:`. With `http` as backend or fallback the audio leaves the machine. | `voice test <file>`; GUI *Try a recording…* |
| `TtsStatus` | `() → s` | Text to speech: `off:`, `ready:<backend>\t<detail>` (backend `local`, `command` or `http`; detail the voice's name, `espeak-ng`, the command's program or the server URL) or `unavailable:<reason>` (Piper or espeak-ng not installed, no voice downloaded, the command not found, no URL). | `voice` / `voice status` (second line), `status` |
| `Speak` | `(s) → s` | Read `text` aloud **on this device** (not the watch) with the configured text to speech, through the first of `pw-play`, `paplay` and `aplay` installed; a new call stops the one before. Answers at once: `ok:speaking <n> characters` (n after whitespace folding and the 2000-character cap), `off:`, `unavailable:<reason>` (no audio player, or the engine's readiness reason) or `error:no text`. The audio is made (seconds) and played in the background, so a synthesis or player failure after the reply is only in the daemon log (WARN). Never `ok:<ms>` or `stopped:`. The system is kept awake (a logind block, "Reading text aloud") while it synthesises and plays. With `http` the text leaves the machine. | `voice say <text>` (prints "Reading aloud — stoandl voice stop to stop" and returns); GUI *Test voice* |
| `StopSpeaking` | `() → s` | Stop what `Speak` is reading (the engine and the player are killed): `ok:stopped` / `none:nothing is being read aloud`. | `voice stop` |

Extensions reach the same engine through the JSON-RPC `synthesize` request (audio back for the watch
speaker, [extensions.md](extensions.md#synthesize-text-to-speech-for-the-extensions-watchapp)); one synthesis runs at
a time, and `Speak` waits behind an extension's while `synthesize` answers `busy` behind `Speak`'s.

> `screenshot`/`logs`/`coredump` write to a **daemon-side filesystem path** and return that path —
> fine for a co-located CLI or GUI (the Flatpak GUI is granted `xdg-pictures/Screenshots` and
> `xdg-documents/stoandl`, the folders the daemon picks for an empty path), a problem for a remote GUI
> (see gaps). `WatchInfoText` is the exception: it returns content inline.

### Reset (`stoandl reset`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `ResetIntoRecovery` | `() → s` | Reboot the watch into recovery (PRF) firmware. Fire-and-forget. | `reset recovery` / `reset prf` |
| `FactoryReset` | `() → s` | Wipe the watch to out-of-box state and reboot. Fire-and-forget; **destructive** (CLI/GUI owns the confirmation). | `reset factory [--yes]` |
| `ForceCoreDump` | `() → s` | Ask the watch to capture a fresh core dump (a RAM snapshot). The watch reboots to write it to flash (fire-and-forget); pull it afterwards with `GetCoreDump`. | `reset coredump` |
| `Ping` | `() → s` | Round-trip liveness check: send a PING and await the echoed cookie, exercising the full PPoG/protocol path (not just the BLE link). `ok:<msg with latency>` / `notready:` / `error:` (timeout / cookie mismatch). | `watch ping` |

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
| `ExtList` | `() → as` | Installed/enabled extensions: `name \t installed\|missing \t enabled\|disabled \t running\|stopped \t config \t description \t author \t version \t lastStderr` (config ∈ {none, schema} — `schema` = the manifest declares a `configSchema`; author/version from the manifest, empty if absent; `lastStderr` (appended) is the child's last non-blank stderr line since the daemon started it, kept across its restarts and a quarantine, empty once it is stopped — usually names why a crashed or quarantined extension failed). | `ext list` / `ext status` (also bare `ext`) |
| `ExtInstall` | `(s) → s` | Install from an archive (`.tar.gz`/`.tgz`/`.tar`/`.zip`) **or an unpacked directory** (absolute daemon-side path): extract or copy, sideload bundled `.pbw`, enable, hotplug-start. A directory is copied with symlinks kept as symlinks (never followed), `.git`/`.hg`/`.svn` skipped and exec bits kept, up to 512 MB of files; the copy is staged and swapped in only once complete, so a failed install leaves the previous version installed and running (its `config` is carried over as before). Errors: `error:… overlaps the install location …` (the path is inside or contains `<extDir>/<name>`), `error:Could not install '<name>': it holds more than 512 MB of files (install an archive of just the extension)` or `…: <IOException class>: <message>`, `error:Could not replace the installed '<name>'`, `error:Failed to install files for '<name>'`. The `ok:` reply ends with where to configure it (as the needs-setup alert words it). | `ext install <archive\|dir>` |
| `ExtUninstall` | `(s,b) → s` | Stop, drop from `extensions.enabled`, delete files; `keepConfig` retains the `config` file for a later reinstall. | `ext uninstall <name>` (aliases `remove`; `--keep-config`/`--delete-config`) |
| `ExtEnable` | `(s) → s` | Add to `extensions.enabled` and hotplug-start. | `ext enable <name>` |
| `ExtDisable` | `(s) → s` | Remove from `extensions.enabled` and stop (files kept). | `ext disable <name>` |
| `ExtRestart` | `(s) → s` | Restart the extension's process. | `ext restart <name>` |
| `ExtConfigSchema` | `(s) → s` | The extension's typed config schema (manifest `configSchema`) as a JSON array: `ok:<json-array>` of `{key,type,label,description?,help?,secret?,required?,options?}`, `none:`, or `notfound:`. `description` is one line shown under the field, `help` the longer text behind its help button; `secret` (string fields) = kept in the secret store as `ext:<name>:<key>`; `required` = the extension doesn't start until the field has a value. | `ext config <name>`; GUI |
| `ExtGetConfig` | `(s) → s` | The extension's effective settings (manifest `config` defaults overlaid by its `config` file) as a JSON object of string values: `ok:<json>`, or `notfound:`. Every secret field reads `""`; the reserved key `_secrets` lists the stored secret keys, comma-separated (`""` when none). | `ext config <name> [<key>]`; GUI |
| `ExtSetConfig` | `(s,s) → s` | Merge a JSON object of key→value into the extension's `config` file (atomic, comment- and unchanged-key-preserving), then restart it if running, or start it if it's enabled but stopped (e.g. waiting on a required secret; the reply then says `started` or `still needs configuration`). Secret fields go to the secret store instead: `""` = unchanged, JSON `null` = remove, any other value = store; `_secrets` is ignored. `ok:`/`notfound:`/`error:`. | `ext config <name> <key> <value>\|--unset`; GUI |

`stoandl ext config <name>` lists the extension's settings (from `ExtConfigSchema` + `ExtGetConfig`);
`ext config <name> <key>` prints one, or for a **secret** prompts for it without echo (a secret is never
taken from argv, where `ps` would show it); `ext config <name> <key> <value>` sets one (the value checked
against its type and options first) and `--unset` clears it (JSON `null` for a secret, `""` for a plain
key), all through `ExtSetConfig`. For an extension without a `configSchema` it lists the effective
settings `ExtGetConfig` reports and sets any key as plain text. (`ExtOpenConfig`, a never-implemented `url`
config backend, was removed.)

### Plugins and app permissions (`stoandl plugins`, `stoandl perms`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `PluginList` | `() → as` | Installed plugins while `plugins.enabled` is on (empty while off): `uuid\tkind\tname\tdescription\tsources\tactions`; kind ∈ {builtin, pbw, extension}; sources `category/item` and actions comma-separated. | `plugins [list]` |
| `PluginRead` | `(sss) → s` | One envelope of `category/item`, preferably from a plugin uuid (empty = any): `ok:<envelope JSON>` / `off:` / `notfound:` / `notready:` / `error:`. Blocks up to 15 s. Acts for the user: callers' permission checks don't apply (logged). | `plugins read <category>/<item> [plugin]` |
| `PluginInvoke` | `(sss) → s` | Invoke an action of a plugin (uuid or name) with JSON args: `ok:<text>` / `failed:<code> <message>` / `off:` / `notfound:` / `error:`. Acts for the user. | `plugins invoke <plugin> <action> [json]` |
| `PermList` | `(s) → as` | The permissions an app (name or uuid) asked for or was given: `key\tstatus\tkind\tlabel\tlastUsedMs`; status is the effective one (libpebble3 `appPermissionStatuses`): `granted` / `denied` (decided), `auto` (undecided, allowed by `plugins.permission_policy`), `waiting` (undecided, must ask) or `blocked` (refused regardless: Legacy PebbleKit off, global internet for an app that declares its domains, app not installed); kind ∈ {read, write, exfiltrating, dangerous} (`dangerous`: upstream's kind for `Notifications`). | `perms list <app>` |
| `PermPending` | `() → as` | Requests waiting for an answer from every installed app, running or not: `appUuid\tappName\tkey\tlabel\trequestedAtMs\trunning\tseen` (`true`/`false`). Only running apps' unseen requests are prompted on the watch and desktop; `seen` = put off with `later`. | `perms [pending]` |
| `PermSet` | `(sss) → s` | Answer a permission: `(app, key, allow|deny|reset|later)` → `ok:` / `notfound:` / `ambiguous:` / `error:`. `later` stops asking about a waiting request without deciding it (libpebble3 `markPermissionRequestsSeen`; empty key = all of the app's); `reset` makes it undecided and unseen again. | `perms allow|deny|reset <app> <key>`, `perms later <app> [key]` |

`OpenConfig` also serves a `.pbw`'s own settings page (`configPage`, plugins on), which needs no running
watchapp and talks to the app's PKJS and plugin while open; plugin-only apps are listed by `ListApps` with
type `plugin`.

### Daemon config (`stoandl.conf` over D-Bus)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `GetConfigSchema` | `() → as` | Schema for the GUI-exposed config keys, one row each, in display order (16 columns, below). | `daemon list` (grouped by page › section), `daemon get <key>` (the metadata on stderr) |
| `GetConfig` | `() → as` | Current values of those keys, one `key\tvalue` row each (combo value = an options label; toggle = `true`/`false`; a `list` = its items joined by `,`, with `\,` for a comma and `\\` for a backslash inside an item; a `secret` = `set` or empty, never the value). Read from the **live** config store, reloaded on every write and every edit of the file, so a value just written is reflected. | `daemon list` / `daemon get <key>` |
| `SetConfig` | `(s,s) → s` | Set one curated key `(key, value)` — value is a toggle's `true`/`false`, a combo's option label (or its raw `stoandl.conf` token), or a `list` in the `GetConfig` encoding (blank items dropped, re-escaped); mapped to the raw `stoandl.conf` token and upserted atomically. `ok:<key> = <token>`, `notfound:` (unknown key), or `error:` (bad value / IO). A `secret` key (`voice.api_key`, `tts.api_key`) is stored in the secret store (keyring, else the 0600 `secrets` file, both out of backups), never in `stoandl.conf`; an empty value removes it; `ok:<key> stored\|removed`. Applied **live** for most keys: the write persists to `stoandl.conf`, reloads the config store, re-reconciles the affected subsystem and emits `ConfigChanged`. Five keys decide startup wiring and cannot be re-applied at runtime (`notification.sync_to_watch`, `classic.discover`, `power.sleep_guard`, `power.sleep_guard_max_ms`, `datalog.enabled`; why each, in [settings-parity.md](settings-parity.md)); the schema marks them `apply=restart` and their `ok:` tail carries “(restart stoandl to apply)” (`Restart` does that). | `daemon set <key> <value>` |

`GetConfigSchema` record (16 columns): `key \t type \t label \t options \t desc \t group \t apply \t min \t
max \t unit \t placeholder \t page \t help \t requires \t default \t egress`.

| # | Column | Meaning |
|---|---|---|
| 1 | `key` | the `stoandl.conf` key |
| 2 | `type` | `toggle` · `combo` · `text` · `int` · `list` · `secret` |
| 3 | `label` | the row's title |
| 4 | `options` | a combo's option labels, comma-separated (empty for every other type). A combo whose options change at runtime (`voice.local_model`, `tts.local_voice`, `calendar.reminder_vibe`) is re-read on every call. |
| 5 | `desc` | one sentence of at most 90 characters, shown under the label |
| 6 | `group` | the section header on `page`; a page's optional last section is titled `Advanced <page>` (the GUI collapses it) |
| 7 | `apply` | `live` or `restart` |
| 8–10 | `min` · `max` · `unit` | `int` only: inclusive bounds and the unit shown next to the number |
| 11 | `placeholder` | `text`/`list` only: hint text for an empty field |
| 12 | `page` | the GUI page: Notifications, Weather, Calendar, Music, Voice, Health & battery, Apps & plugins, Connection & power, Firmware & language, Developer |
| 13 | `help` | the longer text behind the row's help button; never repeats `desc` (may be empty) |
| 14 | `requires` | when the row matters at all: `alt('|'alt)*`, each `alt` `key=value` or `key!=value` with `value` as `GetConfig` shows it; empty = always. Only hard gates (the row does nothing otherwise); the GUI locks the control, not its help, while no alternative holds. |
| 15 | `default` | the value of an empty `stoandl.conf`, as `GetConfig` would show it (empty for a secret, and for a runtime combo none of whose options is the default) |
| 16 | `egress` | the hosts the key makes stoandl contact (`api.open-meteo.com`, "this address", …); empty = none. Every row with egress is off by default, or only matters under a requirement that is; `weather.enabled` and `calendar.enabled` are the two exceptions, on but contacting nothing until a place or a source is added (`ConfigSchemaTest`). |

Columns 6 onward were **appended** to the original 5-column contract, so a client that indexes
positionally keeps working; a new client against an old daemon treats missing columns as empty.

The curated key set lives in `config/ConfigSchema.kt` (a hand-maintained view, not the full config
surface). `SetConfig` shares the atomic `key = value` writer + lock (`util/ConfFile.kt`) with the
extension-config writers. The running daemon holds the config in a live store (`config/ConfigStore.kt`,
an `AtomicReference`). Every write — `SetConfig`, the `SetSyncEnabled` alias, the daemon's own writes —
persists to `stoandl.conf` and then goes through the same reload as **any other edit of the file**: the
daemon watches `stoandl.conf` and, 500 ms after the last write to it (a text editor's rename-on-save and
`sed -i` included), re-reads it, applies what changed since the last applied version (each LIVE key's
subsystem re-reconciled once; a RESTART key only logged, "applies after stoandl restarts") and emits one
`ConfigChanged` per changed key. A write that changes nothing changes nothing and emits nothing. The
per-service masters (notifications, weather, calendar, music, health, dnd) are ordinary rows;
`SetSyncEnabled` is only an alias for five of them.

### Debug (`stoandl fakecall`)

| Method | In → Out | Purpose | CLI |
|---|---|---|---|
| `FakeCallRing` | `(s,s) → b` | Inject a synthetic incoming (ringing) call `(name, number)` so the watch shows the native call screen. `false` = not ready. | `fakecall ring [name] [number]` |
| `FakeCallEnd` | `() → b` | Clear the current synthetic call. | `fakecall end` |

## Tab-separated record formats (the `as` returns)

| Method | Fields |
|---|---|
| `ListWatches` | `name` · `state`(connected/recovery/connecting/disconnected) · `battery`(0–100 or empty) · `transport`(ble/classic or empty) |
| `ListApps` | `uuid` · `type` · `order` · `flags`(⊆ active,sideloaded,config,system,synced) · `title` · `developer` · `version` |
| `ListWatchPrefs` | `id` · `type` · `current` · `default` · `allowed` · `flags` · `name` · `description` · `section` · `help` · `requires` |
| `ListCalendars` | `id` · `name` · `enabled`/`disabled` · `accountId`(owning source id, `discover`, or empty) |
| `ListCalendarSources` | `id`(`caldav:`/`ical:`/`ics:`…) · `type`(caldav/ical/ics) · `url` · `username` · `label` · `lastError` · `lastSync`(epoch s or empty) — never the password |
| `ListLanguages` | `id` · `isoLocal` · `displayName` · `installed`(yes/no) · `source`(rebble/github) |
| `LanguagePacks` (after `ok:`, one per line) | `slot` · `locale` · `name` · `version` · `active`(yes/no) · `kind`(translation/fonts/unreadable) |
| `NotifList` | `name` · `muteLabel` · `color` · `icon` · `vibe` · `lastNotifiedEpochSeconds` · `appNamePlacement`(small/subtitle/off, empty = global) · `images`(off, empty = global) |
| `NotifListFilters` | `pattern` · `action`(allow/block) |
| `NotifStyleChoices` | `kind`(vibe/icon) · `value` · `label`; a vibe adds `ms,ms,…` · `builtin`/`custom` |
| `ExtList` | `name` · `installed`/`missing` · `enabled`/`disabled` · `running`/`stopped` · `config`(none/schema) · `description` · `author` · `version` · `lastStderr` |
| `GetHealthSeries` | steps bars: `label` · `steps` · `typical` · sleep timeline (day): `startFraction` · `widthFraction` · `isDeep`(0/1) · sleep bars: `label` · `totalMin` · `deepMin` · heart (day): `minuteOfDay`(0–1439) · `bpm` · heart bars: `label` · `avgBpm` |
| `GetSyncStatus` | `service` · `enabled`/`disabled` · `available`/`unavailable` · `lastSync` · `key` |
| `VoiceModels` | `id` · `name` · `program` · `sizeMb` · `state` · `recommended` · `engineOk` · `note` · `kind`(stt/tts) |
| `GetConfig` | `key` · `value` |
| `GetConfigSchema` | `key` · `type`(toggle/combo/text/int/list/secret) · `label` · `options`(CSV) · `desc` · `group` · `apply`(live/restart) · `min` · `max` · `unit` · `placeholder` · `page` · `help` · `requires` · `default` · `egress` |

## Long-running operations

These operations run asynchronously on the daemon and report progress via a **polled status
string** (firmware and language also have a progress signal, the watch tasks `TaskChanged`; the CLI
polls). The CLI's poll loops document the cadence and terminal states:

| Operation | Start method | Polled method | Cadence | Timeout | Terminal states |
|---|---|---|---|---|---|
| **Pair / Repair** | `Pair()` / `Repair(name)` | `PairStatus()` | 1.5 s | 145 s | `ok:` (paired), `error:`, `timeout:`; `pending:<msg>` continues (progress, or a `Discovery paused — …`/`BLE scan paused — …`/`Searching again — the phone slept …`/`Scan failed, retrying — …` reason); `confirm:<code>` → answer with `ConfirmPairing(b)` (≤60 s or it declines) |
| **Firmware flash** | `UpdateFirmware()` / `SideloadFirmware(path)` | `FirmwareStatus()` | 0.8 s | 600 s | `reboot:` or post-activity `notready:` = success; `failed:` = failure; `prf:` continues (restart the activity tracking and the timeout: the flash resumes from recovery after a reconnect) |
| **Language install** | `InstallLanguage(query)` / `SideloadLanguage(path)` | `LanguageStatus()` | 0.6 s | 180 s | `done:` = success; `failed:` = failure; post-activity `notready:` = disconnected |
| **Watch tasks** | `SideloadApp(path)` / `TakeScreenshot(path)` / `GatherLogs(path)` / `GetCoreDump(path)` | `TaskStatus(kind)` (+ `TaskChanged`) | 0.3 s | 300 s | `ok:`, `none:`, `notready:`, `error:`; `running:<what>` continues (a CLI that gives up leaves the task running in the daemon) |

For firmware/language, the start method returns `ok:` (kicked off) and the *snapshot* status is read
repeatedly. A successful flash/install ends with the watch rebooting, so the link drops and the poll
loop treats a `notready:` *after* it has seen activity as success. (Language install skips one stale
sticky `done:`/`idle:`/`failed:` on the first poll — it can be the previous install's terminal value
before the new kickoff propagates.)

### Watch tasks

The watch transfers a client used to wait on — `SideloadApp` (`sideload`), `TakeScreenshot`
(`screenshot`), `GatherLogs` (`logs`) and `GetCoreDump` (`coredump`) — run as **watch tasks**
(`pebble/WatchTasks.kt`): the method checks its arguments and answers at once with `ok:started`,
`busy:<msg>` (that kind already runs; a second one is refused, not queued), `notready:` or `error:`, and the
transfer goes on in the daemon. `TaskStatus(kind)` then reads `running:<what>` and, when it ends, the
result (`ok:<…>`/`none:`/`notready:`/`error:`), which stays until the next start of that kind;
`TaskChanged(kind, status)` announces both edges. An empty `path` lets the daemon pick a timestamped file
in the user's Pictures/Screenshots (screenshots) or Documents/stoandl (logs, coredumps), from the XDG user
dirs (`user-dirs.dirs`, localized names included), so a sandboxed client needn't know a writable path; the
`ok:` result names it. A slow BLE transfer therefore no longer holds a D-Bus worker or freezes a GUI.

## CLI exit codes

Every `stoandl` command exits **0** done, **1** failed, **2** usage error, **3** daemon unreachable, so
scripts can tell "stoandl isn't running" from a failed command. One place maps a D-Bus error to its
message and code (`ctlError` in `Main.kt`, `CtlErrorTest`):

| D-Bus error | Message | Exit |
|---|---|---|
| `ServiceUnknown`, while `de.yoxcu.stoandl.Instance` has an owner | `stoandl is still starting — try again in a moment` | 1 |
| `ServiceUnknown` otherwise | `stoandl isn't running — systemctl --user start stoandl (OpenRC: rc-service stoandl start)` | 3 |
| `UnknownMethod`, `UnknownInterface` | `The CLI and daemon versions differ (the daemon doesn't know this call) — see: stoandl version` | 1 |
| `NoReply`, `Timeout` | `stoandl didn't answer in time (it may still be working)` | 1 |
| anything else (`NameHasNoOwner` included: dbus-java raises it as a plain `DBusExecutionException`) | `Error: <message>` | 1 |

No session bus at all also exits 3. The CLI waits 15 s for a reply, and 120 s for the verbs that wait on
the watch or the network (`apps install`/`launch`/`remove`/`order`, firmware and language, `sync`, `voice
test`, …); the watch tasks are then followed through `TaskStatus`. `stoandl status` exits 0 with a watch
connected and 1 without one. A status reply the CLI prints (`handleStatusResponse`) exits 0 for `ok:` and 1
for anything else.

## GUI mock fixtures

The GUI's offscreen smoke test runs against `gui/tools/mock_stoandl.py`, which serves two files the
daemon's tests generate, so the mock can't drift from the real contract: `gui/tools/config-schema.tsv`
(`GetConfigSchema`, from `ConfigSchemaTest`) and `gui/tools/watch-pref-text.tsv` (one row per libpebble3
pref in display order, `id \t section \t help \t requires`, from `WatchPrefTextTest`). Each test checks
its file only when the recorded gui commit has it. After a schema or watch-pref text change, regenerate
both with `STOANDL_UPDATE_GOLDEN=1` on those tests
(`STOANDL_UPDATE_GOLDEN=1 ./gradlew test --tests '*ConfigSchemaTest*' --tests '*WatchPrefTextTest*'`), commit
them in gui and bump the gitlink.

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
- `watch battery heartbeat [--raw] [--all]` — reads `~/.config/stoandl/battery/heartbeat/*.ndjson`
  directly; `--all` decodes the newest record with the same table as `HeartbeatMetrics`.
- `health` / `health [days]` / `health activities` / `health dump` — read
  `~/.config/stoandl/health/*.ndjson` directly (`health sync` and `health hr` call the daemon).
- `notif styles` — generated offline from the `TimelineColor`/`TimelineIcon` enums, the built-in vibe
  presets and the app-name placements (custom vibrations live in the daemon: `notif vibes`).
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
> the full Settings/Sync write path: `GetConfig`/`GetConfigSchema` + `SetConfig` (live via the
> `config/ConfigStore.kt` reload + re-reconcile, **except** the five keys the schema marks
> `apply=restart` — see [settings-parity.md](settings-parity.md)), `GetSyncStatus` (now **live** runtime
> state) + `SetSyncEnabled` (runtime per-service on/off, full start *and* stop; now an alias of
> `SetConfig`), and the notification
> filters (`NotifListFilters`/`NotifAddFilter`/`NotifRemoveFilter`, a global allow/block list gated in
> `WatchNotifier.push()`). **Quiet-hours was dropped** as redundant with `dnd.sync`.
>
> **Reactive signals landed** (the [Eleven signals](#eleven-signals-augment-polling-no-properties) section
> above): `WatchesChanged`, `FirmwareProgress`, `LockerChanged`, `LanguageProgress`, `ExtensionsChanged`,
> `ExtensionStateChanged`, `PluginsChanged`, `PermissionRequestsChanged`, `CalendarsChanged`, and with the
> QoL wave `ConfigChanged` and `TaskChanged` — so the Watch, firmware/language-progress, Apps, Plugins and
> Settings screens update without polling (polling kept as the fallback). `ExtensionStateChanged` surfaces
> the unsolicited per-extension crash/quarantine that `ExtensionsChanged` (user/CLI ops only) can't. Still
> open: only the byte-returning/remote method variants.
>
> **QoL wave (2026-10) — what the GUI consumes now.** Settings is a landing list: *Watch preferences*, the
> ten schema pages (one per `GetConfigSchema` `page`, sections from `group`, the `Advanced …` section
> collapsed), *Backup & restore*, *Diagnostics & developer* (the Developer keys on top, then the support
> bundle) and *About* (both versions from `Version` and the GUI's packaging, a copyable support line). Every
> row has a help button (`help`; also long-press, right-click and F1); `requires` **locks** a row's control
> (off, with "· Used when …" in the subtitle) but leaves its help working; restart keys say "(after
> restart)" and the page offers *Restart now* (`Restart`, disabled while a pairing window or update the GUI
> follows runs). A search covers labels, descriptions, help, keys and watch prefs. The **Sync screen is
> gone**: each master is the first row of its page, written with `SetConfig` like every other key (the
> GUI has no `SetSyncEnabled` wrapper any more), and *Sync now* is a page action; a weather or calendar
> master whose `GetSyncStatus` is unavailable with `no source` says "No place yet" / "No calendar source
> yet". `notification.forward` lives in Settings → Notifications; the Alerts and Watch tabs show a
> "Forwarding is off [Turn on]" banner instead of a second switch. The GUI reads `ConfigChanged`,
> `Restart`, `ResetWatchPrefs`, `ListWatchPrefs` columns 9–11, the `GetSyncStatus` key, `VoiceModels`
> kind, `TtsStatus`/`Speak`/`StopSpeaking`, `TaskStatus`/`TaskChanged`, `NotifMuteAllStatus`, the
> `ExtList` stderr column, the calendar sources' `lastError`/`lastSync`, and also calls `Ping`,
> `Transcribe`, `NotifSetVibePattern` and `NotifVibeTest`.
>
> **Error wording (G1).** One place in the GUI words a D-Bus failure: an unknown method → "Your stoandl
> daemon is older than this app"; no reply → "stoandl didn't answer in time (it may still be working)";
> `ServiceUnknown` → "stoandl isn't running", or "stoandl is starting" while `de.yoxcu.stoandl.Instance`
> has an owner. The daemon-down placeholder also tells a stopped service from one systemd gave up on
> (start limit) and offers *Start daemon* where it can (not in the Flatpak, which shows the command). The
> Flatpak GUI is granted `--talk-name=de.yoxcu.stoandl.Instance` for this, and
> `--filesystem=xdg-pictures/Screenshots:create` / `--filesystem=xdg-documents/stoandl:create` so the
> files the watch tasks save open in the sandbox.

### Screen 1 — Watch

*Active watch, battery, transport, known-watch list, pair/connect/repair/unpair.*

**Satisfied by:** `ListWatches` (identity + state + battery snapshot), `Battery` (active watch
level), `BatteryHistory`/`BatteryInsights` (trend + charging/voltage/time-to-empty), `Connect`, `Disconnect`+`LinkPause`,
`Pair`+`PairStatus`, `Repair`, `Unpair`, `WatchInfoText` (free-text details panel).

**QoL wave, done in the GUI:** *Pair new watch* works while a watch is connected (the new one takes the
link); the pair dialog offers to turn on Bluetooth Classic (`classic.discover`, a restart key: it restarts
the daemon and reopens itself) for a Pebble Time / Time Steel / Round, and after a timeout says what to
check and offers Retry. *Re-pair* sits on a disconnected or endlessly connecting watch's row and in Watch
details. Chips on the Watch tab show the global mute ("Muted until HH:MM", from `NotifMuteAllStatus`, with
Resume) and forwarding off. *Sync now* runs `SyncWeather`/`SyncCalendar`/`SyncHealth` for the services that
are on and have a source; *Check for updates* is separate (`CheckFirmwareForce`). Pull to refresh re-reads
a page without a firmware check.

| Gap | Kind | Today | Proposed hook | Feasibility |
|---|---|---|---|---|
| Watch connect/disconnect/state-change push | signal | none — must re-poll `ListWatches` | `WatchStateChanged(s name, s state, i battery)` (or a zero-arg `WatchesChanged()` poke) | **wiring-only** — libpebble3 `LibPebble.watches: StateFlow` + `Watches.connectionEvents: Flow` already exist |
| Live battery updates | signal | none — `Battery`/`ListWatches` are one-shot | `BatteryChanged(s name, i level)`, or fold into the state signal | **wiring-only** — each battery change re-emits the `watches` StateFlow; diff it |
| Transport per watch (BLE vs Classic badge) | data | **dropped** from the `ListWatches` record | append `transport`(ble/classic) to the `ListWatches` row | **wiring-only** — the daemon already pattern-matches `PebbleBtClassicIdentifier` vs `PebbleBleIdentifier` (`ActiveDevice.usingBtClassic`) |
| Richer per-watch identity (model, fw, serial, color, last-connected) | data | only the unstructured `WatchInfoText`, connected watch only | extend `ListWatches` row or add `WatchDetails(s) → s` | **wiring-only** — all fields live on `KnownPebbleDevice` |
| Bluetooth adapter power / scanning state | property/signal | not exposed (checked internally) | `BluetoothStatus() → s` + `BluetoothStateChanged` | **wiring-only** — libpebble3 `Scanning.bluetoothEnabled`/`isScanningBle/Classic` StateFlows |
| Pair progress as push (vs `PairStatus` polling) | signal | poll only | `PairStatusChanged(s status)` on each internal `pairingState` change | **wiring-only** — `pairingState` already maintained |
| Rename / nickname a known watch | action | none | `SetWatchNickname(s watch, s nickname) → s` | **wiring-only** — `KnownPebbleDevice.setNickname()` exists |
| Battery insights (trend, discharge rate, time-to-empty, charge cycles, voltage) | data | **shipped** | `BatteryHistory(s,x)` + `BatteryInsights(s)` | **done** — decodes the watch's hourly analytics native-heartbeat (primary; also works over Classic + backfills), with the GATT level series as fallback. See [battery-insights.md](battery-insights.md) |
| Battery charging state | data | **shipped** (was "not feasible" over GATT) | folded into `BatteryInsights` (`charging` field) | **done** — the heartbeat's `charge_time_ms` gives a *measured* charge signal; the GATT fallback still can't (0x180F is level-only), so it infers charging from a rising level |

### Screen 2 — Apps & Faces

*Locker list with active/system/sideloaded/config flags, launch/install/remove, Clay config.*

**Satisfied by:** `ListApps` (list + **all four flags** `active|system|sideloaded|config` are
present), `LaunchApp`, `RemoveApp`, `SideloadApp`, `OpenConfig` (the daemon serves the page and carries Save back to the app).

| Gap | Kind | Today | Proposed hook | Feasibility |
|---|---|---|---|---|
| Locker-changed push (install/remove/reorder) | signal | none — `ListApps` is a one-shot `getLocker(...).first()`, dropping the live stream | `LockerChanged()` poke | **wiring-only** — `getLocker()` returns a live `Flow`; keep the collector |
| Active-watchface-changed push | signal | none — computed per call | `ActiveWatchfaceChanged(s uuid)` or fold into `LockerChanged` | **wiring-only** — `LockerApi.activeWatchface: StateFlow` |
| Config session available/unavailable push | signal | none — must call `OpenConfig` speculatively | `CompanionSessionsChanged()` | **wiring-only** — `currentCompanionAppSessions: StateFlow`, already read |
| `synced`-to-watch flag per app | data | the `sync` field is read but **not** added to flags | add `synced` to the `ListApps` flags set | **wiring-only** — `NormalApp.sync` already in the iterated object |
| Menu icon per app/face | data | ~~dropped from the row~~ **done** — `GetAppIcon(s)` (below) | `GetAppIcon(s) → s` returns a local PNG path | **done** — extracted LOCALLY from the cached `.pbw` (`app_resources.pbpack` → PNG passthrough or GBitmap decode, no AWT, no network); the GUI fetches it lazily per row |
| Version / category / capabilities for richer rows | data | dropped from the row | extend `ListApps` row or `AppDetails(s) → s` | **wiring-only** — version/category/caps are already locker fields |
| Reorder apps (drag-to-reorder) | action | **done** — `SetAppOrder(s query, i order)` + `RestoreSystemAppOrder()` | `SetAppOrder(s query, i order) → s` (+ `RestoreSystemAppOrder`) | **done** — backed by libpebble3 `setAppOrder()`/`restoreSystemAppOrder()`; CLI `apps order <name\|uuid> <pos>` / `apps order reset`; GUI up/down move buttons per row on Apps & Faces + a "Restore default order" action |
| Per-app permissions (review, revoke, put off) | data + action | **done** — `PermList` (effective status), `PermSet` (allow / deny / reset / later), `PermPending` (every app, with running + seen) | — | **done** — GUI: a Permissions button on each app row (with a badge while it has a waiting request) opens a dialog listing every permission with its effective status, last use, Allow / Deny, Reset and "Ask later"; the request banners cover apps that aren't running and offer Later and Review |
| Install from cloud locker / store (not just a local `.pbw`) | action | only `SideloadApp(path)` | `AddAppFromLocker(s uuid) → s` | **design work** — plumbing exists (`addAppToLocker`/`fetchLocker`) but stoandl is local-only/no-egress; needs a store fetch + egress opt-in |

### Screen 3 — Plugins

*Extensions: list, enable/disable/restart/install/uninstall, running state.*

**Satisfied by:** `ExtList` (list + installed + enabled + running flags, one shot), `ExtInstall`,
`ExtUninstall` (with the `keepConfig` bool → a GUI checkbox), `ExtEnable`, `ExtDisable`, `ExtRestart`.

| Gap | Kind | Today | Proposed hook | Feasibility |
|---|---|---|---|---|
| Live running-state updates (crash/quarantine/restart/needs-config) | signal | **done** — `ExtensionStateChanged(s name, s state)` | — | **done** |
| Richer run-state than `running\|stopped` (quarantined, needs-config, restarting) | data | partly done: `ExtensionStateChanged` gives `exited`/`quarantined` live, and `ExtList`'s `lastStderr` column says why; the GUI shows "Last error: <line>" with a **Restart** button (`ExtRestart`) for a crashed or quarantined extension | a polled `runState` column | **needs bookkeeping** — `quarantined` still polls as `running` |
| Per-extension config (declares `requiresConfig`? `userConfigured`? path? read/write settings) | data + action | **done** — `ExtConfigSchema`/`ExtGetConfig`/`ExtSetConfig`; the GUI form shows each field's `description` and `help`, steps ints by 1 and opens a picker for enums | — | **done** |
| Install from bytes/upload (not a daemon-side path) | action | `ExtInstall` takes a daemon-resolved path (an archive or, now, a directory; the GUI can pick a folder) | optional `ExtInstallBytes(s name, ay data) → s` | **wiring-only** but low priority for a co-located GUI |

### Screen 4 — Settings (was: Sync)

*Per-service on/off + force-sync for notifications, weather, calendar, music/MPRIS, health, the daemon's
other settings, plus global notification filters.*

The separate Sync screen is **gone** (QoL wave): one control per key. Each service master is a schema row
on its own page, written with `SetConfig`; `GetSyncStatus` adds availability and state to that row, and
*Sync now* is a page action.

**Satisfied by:** `GetConfigSchema`/`GetConfig`/`SetConfig` + `ConfigChanged` (every key, live; `Restart`
for the five restart keys); `GetSyncStatus` (live per-service enabled/available/lastSync and the key);
`SyncWeather`, `SyncCalendar`, `SyncHealth` (force-sync for three of them); `ListCalendars` +
`SetCalendarEnabled` (per-*calendar* toggle) and `ListCalendarSources` (per-source problem and last read);
the notification filters `NotifListFilters`/`NotifAddFilter`/`NotifRemoveFilter` (a global allow/block list,
live); `NotifList` (per-app `lastNotified` timestamps); `ListWatchPrefs`/`SetWatchPref`/`ResetWatchPrefs`
(Watch preferences, with *Reset to defaults*; the notification timeout shown in seconds); `VoiceModels` +
`TtsStatus`/`Speak`/`StopSpeaking` (Settings → Voice: a speech-model card, a voices card with *Test voice*
playing on this computer, and *Try a recording…* through `Transcribe`).

| Gap | Kind | Today | Proposed hook | Feasibility |
|---|---|---|---|---|
| **Per-service master ON/OFF at runtime** (notifications, weather, calendar, music, health, dnd) | action | **DONE** — each master is its own key, applied live through `SetConfig` (start *and* stop, no restart); `SetSyncEnabled` remains as a CLI alias | — | **implemented (live)** |
| Per-service **enabled** read (initialize the toggles) | property | **DONE** — `GetConfig` for the value, `GetSyncStatus` for availability and state | — | **implemented** |
| Per-service **last-sync** timestamp (+ result/error) | data | partial — `GetSyncStatus.lastSync` is a state label (`live`/`off`/`no source`, dnd mode when on); calendar sources carry a real `lastSync` and `lastError` (`ListCalendarSources`) | a real `lastSync`/`lastResult` for weather and health | **needs bookkeeping** |
| Quiet-hours (scheduled mute window) | action | **dropped — superseded by `dnd.sync`** (which mirrors desktop DND ↔ the watch's native Quiet Time); a separate host-side time-window subsystem would be redundant | *(none — dropped)* | **not pursued** |
| Force-sync for **music** and **notifications** | action | **no `SyncMusic`/`SyncNotifications`** (both are continuous push by design) | `SyncMusic() → s` (re-enumerate MPRIS + re-push) / `SyncNotifications() → s`, or omit from the screen | **wiring-only if wanted** — `MprisMusicControl` has `enumerateExisting()`/`recompute()`; arguably unnecessary |
| Music/MPRIS state (active player, playing?) | data | **done** — `MusicStatus() → s` (`playing\|paused\tplayer\ttrack`), shown on the Music page | `MusicStatus() → s` | **done** — reads `MprisMusicControl.playbackState`; refreshed on page open |
| Weather location by city name | action | **done** — `SearchWeatherPlace(s) → s` + `AddWeatherLocation(s,d,d) → s`; the Weather page has a *Find a location* card (gated by `weather.place_search`, off by default) | *(covered)* | **implemented** |
| DND ↔ Quiet Time sync mode (`dnd.sync`) | property + action | one four-way combo row (`SetConfig dnd.sync`), mode also in `GetSyncStatus.lastSync` | *(covered)* | **implemented (live)** |
| Global mute push | signal | `NotifMuteAllStatus` is polled (the GUI's 20 s watch tick and on coming to the front) | a `ConfigChanged`-style signal for the global mute | **wiring-only** — daemon follow-up |

### Screen 5 — System

*Firmware check/update/flash + progress, language list/install, backup/restore, screenshot, logs,
support bundle, reset recovery/factory.*

**Satisfied by:** firmware `CheckFirmware`/`UpdateFirmware`/`SideloadFirmware`/`FirmwareStatus`;
language `ListLanguages`/`InstallLanguage`/`SideloadLanguage`/`LanguageStatus`, and on firmware with several
packs `LanguagePacks`/`UseLanguage`/`RemoveLanguage` (GUI gap: the watch-details Language section has no slot
list, switch or remove yet; the CLI has them); `TakeScreenshot`;
`GatherLogs`/`GetCoreDump`/`WatchInfoText`; `ResetIntoRecovery`/`FactoryReset`.

**QoL wave, done in the GUI (G2, G9, G10):** sideload, screenshot, watch logs and core dump run as
[watch tasks](#watch-tasks) with an empty path (the daemon picks the file), followed through
`TaskStatus`/`TaskChanged`, so the GUI never blocks on a transfer; Capture screenshot opens a preview with
Open and Open folder, the logs and dump toasts offer Open folder. Watch details → Language → *Install
from file…* sideloads a `.pbl`; Diagnostics has Ping. Long actions (weather search, Sync now, app launch,
Update now) run off the UI thread with a thin progress bar; irreversible ones (Update now, removing a
voice model, Restore default order, removing an API key) ask first and a deleted filter has Undo.

| Gap | Kind | Today | Proposed hook | Feasibility |
|---|---|---|---|---|
| Live firmware-flash progress push | signal | `FirmwareStatus` polling only | `FirmwareProgress(s phase, i percent, s detail)` | **wiring-only** — libpebble3 `FirmwareUpdater.firmwareUpdateState: StateFlow` with nested `InProgress.progress: StateFlow<Float>` |
| Distinguish flash-failure cause (download vs board-mismatch vs CRC) | data | collapsed to `failed:<reason>`; an `update` download failure is logged only and silently drops back to `idle:` | carry an error code in `FirmwareProgress.detail`; set state on download failure | **needs bookkeeping** — typed causes exist (`ErrorStarting`/`Idle(lastFailure)`) but the download failure is swallowed in `FirmwareControl.startFlash` |
| Live language-install progress push | signal | `LanguageStatus` polling only | `LanguageProgress(s phase, i percent, s detail)` | **wiring-only** — `LanguagePackInstaller.state: StateFlow` with `Installing.progress: StateFlow<Float>` |
| **Backup / Restore over D-Bus** | action | **not on D-Bus at all** — CLI-local `tar`; backup warns and restore refuses while the daemon runs | `BackupTo(s path) → s` (daemon snapshots its own config + checkpoints the DB) and a coordinated `RestoreFrom(s path)` (+ restart) or `PrepareForRestore()`; at minimum `ConfigDir() → s` | **design work** — data is daemon-owned (right place to snapshot consistently), but the DB-lock/restart coordination is real work; a remote GUI also needs a byte transfer |
| Screenshot bytes to a remote GUI | data | **file-path coupling** — writes a daemon-side PNG, returns the path (a same-host sandboxed GUI is covered: an empty path puts it in Pictures/Screenshots, which the Flatpak may read) | `TakeScreenshotBytes() → (i,i,ay)` | **wiring-only** — `ScreenshotControl` already has the encoded PNG bytes before writing |
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
5. **`GetSyncStatus()`** + live masters — **done** (Batch D, then the QoL wave): every master is a
   schema row applied live (full runtime start *and* stop for all six services, on a live `ConfigStore` +
   per-service lifecycle), `SetSyncEnabled` is an alias of `SetConfig`, and `GetSyncStatus` reflects live
   state. Only the real per-service `lastSync` *timestamp* (vs the current state label) remains small
   bookkeeping.
6. **Byte-returning variants** — `TakeScreenshotBytes`, `GatherLogsText`, `GetCoreDumpBytes`,
   `SupportBundle`, and `BackupTo`/`RestoreFrom` — needed only if the GUI is ever **not co-located**
   with the daemon (different user, sandbox, or host). A same-host Kirigami GUI can use the existing
   path-returning methods.

Adopting these is idiomatically cleanest as `org.freedesktop.DBus.Properties` on the control object
(watches list, sync status, progress) so standard `PropertiesChanged` fires — but plain custom
signals work and match the dbus-java machinery the daemon already uses for inbound BlueZ signals.
