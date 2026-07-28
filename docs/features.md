# Features

What stoandl supports today, and what it doesn't yet.

stoandl is a **headless daemon for Linux** — that's the whole point. Every other Pebble companion
(the official Core Devices app, microPebble, Gadgetbridge) is an Android/iOS GUI app tied to a phone
OS and usually a cloud account. stoandl runs as a systemd user service on a Linux phone (postmarketOS)
with no UI and no sign-in, talking to the watch over Bluetooth — BLE for modern watches, Bluetooth
Classic for classic-era ones. Many gaps below are deliberate consequences of that (no health
dashboard, no account-backed app store); others are genuine TODOs.

Features are grouped by readiness. **[Working today](#working-today)** is implemented and
hardware-verified — each entry's note flags any remaining caveat. **[Implemented — to be
tested](#implemented--to-be-tested)** is written but not yet run on hardware (test plan:
[TESTING.md](../TESTING.md)). **[Not yet implemented](#not-yet-implemented)** is the roadmap.

## Working today

### Notification sync

Desktop → watch: D-Bus `org.freedesktop.Notifications` → the Pebble timeline. This is the core of
stoandl and runs automatically once the daemon is up and a watch is connected — no command needed.

### Notification dismiss

Watch → desktop: `Dismiss` / `AncsDismiss` actions mark the notification read on the watch **and**
call `CloseNotification()` on D-Bus, so dismissing on the wrist clears it on the desktop too.
Automatic.

### App / watchface management

Manage the watch locker — list, launch, install (`.pbw`) and remove apps & watchfaces.

```sh
stoandl apps                       # list watchfaces and apps in the locker
stoandl apps launch <name|uuid>    # launch an app or watchface on the watch
stoandl apps remove <name|uuid>    # uninstall an app or watchface from the locker
stoandl apps install app.pbw       # install a .pbw onto the connected watch
stoandl version                    # version of the running daemon (and this CLI)
```

`launch` / `remove` match a watch app by UUID or by (case-insensitive) name — exact name first,
then substring. If the name is ambiguous the command lists the candidates so you can pick the UUID.
`apps` flags each entry as `active` (current watchface), `sideloaded`, `config` (has a settings
page) or `system`. System apps cannot be removed.

### Backup & restore

stoandl keeps your whole setup — the locker DB, the cached `.pbw` binaries, app order, the active
watchface, and PKJS/Clay settings — under `~/.config/stoandl/`. None of it is tied to a specific
watch, so it survives unpairing: when you re-pair a watch, libpebble3 re-syncs the locker back onto
it automatically.

```sh
stoandl backup [out.tar.gz]   # default: ./stoandl-backup-<timestamp>.tar.gz
stoandl restore <in.tar.gz>   # stop the daemon first; --force to override
```

For a guaranteed-consistent snapshot, stop the daemon before `backup` (it's safe to run live, but
the SQLite DB is captured mid-write). `restore` moves any existing `~/.config/stoandl/` aside to
`stoandl.old-<timestamp>` rather than overwriting it, and refuses to run while the daemon is up.
Note: settings a watchapp writes *directly on the watch* (the C `persist_write` API, as opposed to
Clay/PKJS config) live only on the watch and are not part of this backup.

### PKJS (PebbleKit JS)

Watchapp companion scripts run in GraalJS (XHR, AppMessage, webhooks). The JS bridge initialises when
the watch connects and the app is launched — no command; it's part of running an app that ships a
`pkjs/index.js`.

### App configuration pages (Clay)

Serve a PKJS app's config page via a local proxy, so you can change its settings from a browser.

```sh
stoandl config [app]   # open a PKJS app's Clay config page (launches the app if needed)
```

### Watch settings (advanced)

Set the "advanced" watch prefs the official app exposes (quick-launch buttons, ambient-light
threshold, backlight, vibration, etc.), applied authoritatively on connect.

```sh
stoandl settings [filter]      # list the watch's advanced settings (quick-launch, backlight, …)
stoandl settings set <id> <v>  # set one, e.g. settings set lightAmbientThreshold 200
```

Or via `watch.<id>` config keys. See [configuration.md](configuration.md#watch-settings-advanced).
_Hardware-verified._

### Bluetooth Classic transport

Classic-era Pebbles (Pebble Time / Time Steel; by class also the original Pebble / Steel and Time
Round) connect over **Bluetooth Classic** (RFCOMM/SPP), their reliable native transport, rather than
BLE (where they're a firmware-side reconnect race). discover → auto-pair → connect → the full Pebble
protocol → auto-reconnect after going out of range / into airplane mode (it pages the watch's fixed
address, so no advertising is needed). The protocol layer is transport-agnostic, so every feature
here works the same over Classic — the lone exception is the BLE-only battery *level* read-out. The
JVM/BlueZ side (RFCOMM socket via a `java.lang.foreign` (FFM) socket — no native library, so it
carries no glibc-only blob — native SDP channel resolution, the scanner, Classic pairing) spans this
repo and the libpebble3 fork. On by default (experimental) — it's idle when no classic watch is
paired (the BR/EDR inquiry only runs during a pairing window), so disable it with
`classic.discover = false` if you never use a classic-era watch:

```ini
# ~/.config/stoandl/stoandl.conf
classic.discover = false    # turn off classic-era Pebble discovery (on by default)
```

_Hardware-verified on a Pebble Time Steel; the JNA→FFM rewrite that drops the last native blob (so it
can run on musl) is pending re-test on the phone._ See [devices.md](devices.md) for how to enable and
use it, and [configuration.md](configuration.md#bluetooth-classic) for the config keys.

### BLE pairing / bonding

Headless auto-confirm BlueZ agent (Numeric Comparison / MITM / SC). On first connection the watch
shows a **6-digit code**; stoandl auto-accepts on the Linux side — just confirm the code on the
watch. Subsequent reconnects are automatic.

```sh
stoandl watch pair            # pair a new watch (~2 min window; finds BLE and classic watches)
stoandl watch list            # known watches, their connection state and battery level
stoandl watch connect B349    # connect a specific known watch by name/substring (switches the active watch)
stoandl watch repair B349     # re-pair ONE watch by name/substring (forgets just it, then pairs)
stoandl watch unpair [name]   # forget watches on this host — all of them, or just the named one
```

If you forget the host **on the watch** (one-sided bond), stoandl notices the watch endlessly
re-connecting and dropping, and sends a notification with a one-tap **Re-pair** button — or run
`stoandl watch repair <name>`. `pair` never disturbs other watches, so it's safe with multiple
Pebbles. Conversely, if the pairing is removed **on this computer** (e.g. `bluetoothctl remove`)
while the watch still holds its side, stoandl forgets the now-unusable watch and notifies you: a
half-removed bond can't be restored, so unpair it on the watch too and `stoandl watch pair` again.
(To forget a watch cleanly, use `stoandl watch unpair`, not `bluetoothctl`.)

### Automatic reconnect

Bonded reconnect after a watch disconnect, daemon restart, or coming back into range. Reconnection is
delegated to BlueZ's own background auto-connect: the watch is marked `Trusted` and a single standing
`Device1.Connect()` intent is left in place, so BlueZ links it up the instant it's reachable — no
polling, no Connect/Disconnect churn, no process-restart watchdog. Classic-era watches reconnect by
paging the watch's fixed address, so it survives airplane mode. Either way it links up on its own,
with no restarts.

Reconnection needs the adapter's LE scanner free; another process running Bluetooth discovery blocks
it — stoandl warns (and sends a desktop notification) when it detects that. See
[devices.md](devices.md#wont-reconnect) if a watch won't come back.

**Multiple paired watches — follow the wrist.** stoandl connects one watch at a time, so with two or
more paired it picks whichever is actually in range instead of only ever the last one you paired: it
arms the most-recently-connected watch and, if it's out of range, rotates to the next until one
connects. It never drops a live link to chase another watch, and it holds the goal steady across a
firmware/language update's reboot. On by default (`connection.autoswitch`, inert with a single watch) —
set it off to pin the connection to the last watch you chose (`stoandl watch connect <name>` to move
it). When both watches are in range at once the tie goes to the most recently used.

### Music / now-playing control

Bridges desktop media players (MPRIS over D-Bus, on the session bus) to the watch's native Music app:
now-playing (title/artist/album, play state, position) plus play/pause, next/previous and volume from
the watch. Volume drives the master/output level by default (auto-detected `wpctl`/`pactl`/`amixer`,
configurable) or the active player's own MPRIS volume (`music.volume`). Follows the actively-playing
player; `playerctld` is skipped so it doesn't duplicate. On by default (`music.enabled`); local-only,
no egress, no command — it just tracks whatever's playing.

_Hardware-verified. Note: stoandl must identify as `OSType.Android` in the PhoneAppVersion handshake
(`PebbleIntegration` overrides the JVM default `OSType.Unknown`) — the firmware gates its music path
on the phone's OS type, so without this the Music app stays blank and its buttons do nothing._ See
[configuration.md](configuration.md#music--now-playing).

### Weather

Fixed locations (and an optional GeoClue2 GPS "current location") from Open-Meteo (free, no account)
pushed to the watch's Weather app **and** as **timeline pins**, replicating the original Core
companion app: a **Sunrise** and a **Sunset** pin per day (today … +2 days) for the primary location,
with the daytime/overnight halves split from Open-Meteo's hourly forecast. The primary follows
GPS-then-first-location priority; other locations show in each pin's detail view. No libpebble3
change — pins use the public `LibPebble` timeline API. Refreshes on an interval and on connect; on by
default, `weather.pins = false` disables just the pins.

```sh
stoandl weather   # force a refresh now
```

_Hardware-verified. (Watch firmware only surfaces the next ~2–3 days of timeline, so the furthest
pins sync but may not display.)_ See [configuration.md](configuration.md#weather).

### Calendar sync / timeline pins

Desktop calendar events → the watch timeline as native calendar pins, via libpebble3's
`PhoneCalendarSyncer` fed by a Linux `SystemCalendar` reader (so libpebble3 owns all pin
creation/diffing/deletion). DE-agnostic sources: local `.ics` files/dirs, best-effort discovery of the
DE's local calendars (`calendar.discover` — e.g. Calindori on Plasma Mobile), published iCal feed URLs
and CalDAV (auto-discovers all of an account's calendars via RFC 6764/4791, or a single collection
URL). CalDAV passwords go in the system keyring (`org.freedesktop.secrets`), else a 0600 `secrets`
file beside the config — never in `stoandl.conf`.

```sh
stoandl calendar list                 # synced calendars + enabled state
stoandl calendar sources              # editable sources (CalDAV accounts / iCal feeds / .ics) + ids
stoandl calendar add caldav https://dav.example.com/alice/ alice   # prompts (no echo) for the password
stoandl calendar passwd <id>          # change a stored CalDAV password
stoandl calendar remove <id>          # drop a source (and its stored password)
stoandl calendar disable <id|name>    # stop syncing one calendar (enable to undo)
stoandl calendar sync                 # force a re-read now
stoandl calendar dump <file|url>      # parse + print events offline (no daemon/watch needed)
```

Recurrence (RRULE/RDATE, minus EXDATE) is expanded with ical4j; all-day events, per-event timezones
and event reminders (VALARM) are handled. Refreshes on `calendar.sync_interval`, on local `.ics`
change (filesystem watch), and on demand. On only when a `calendar.*` source is configured; local
sources are egress-free. Manage sources from the GUI (**Settings → Calendars**) or the CLI; changes
apply live (no restart). _**Hardware-verified** — local `.ics`, CalDAV (incl. auto-discovery),
iCal-URL feeds and local DE-discovery (`calendar.discover`): pins, reminders, enable/disable,
deletion. [TESTING.md §5.7](../TESTING.md). Known gaps (the rest of libpebble3's calendar surface is
not done):_

- _**RSVP / pin actions** — the watch doesn't show Accept/Maybe/Decline/Cancel on calendar pins
  (`supportsPinActions=false`). Write-back needs CalDAV scheduling/iTIP and so is **CalDAV-only**
  (read-only `.ics`/feed sources can't write back). Tracked under
  [Not yet implemented](#not-yet-implemented) below._
- _**Attendee metadata is partial** — names/emails only; `isCurrentUser`/`isOrganizer`/`role`/
  `attendanceStatus` (PARTSTAT) aren't parsed, so pins show no RSVP "Status" line and the syncer's
  hide-declined-events filter never triggers._
- _**GNOME EDS / KDE Akonadi *online* calendars** (Google/Nextcloud/MS) aren't read from the native
  store — reach them via `calendar.ical_urls` or `calendar.caldav`. A GNOME EDS reader (raw D-Bus, to
  avoid a musl-breaking native `libecal` dep) is a possible future addition._
- _Minor: calendar **color** is left default; a singly-**edited recurring occurrence** shows at its
  original time (detached `RECURRENCE-ID` overrides are skipped to avoid duplicates)._

See [configuration.md](configuration.md#calendar).

### Watch screenshot

Capture the watch's current screen to a PNG on the host — handy for sharing watchfaces and filing bug
reports (the official app has it).

```sh
stoandl screenshot                 # → ./pebble-screenshot-<time>.png
stoandl screenshot watchface.png   # → a name you choose
stoandl screenshot ~/Pictures/     # → a timestamped PNG in that directory
```

libpebble3's `ScreenshotService` does the protocol work over the SCREENSHOT endpoint and decodes the
1-bit (classic) or 8-bit (colour) framebuffer; a small fork addition exposes the decoded pixels
(`takeScreenshotPixels()` → ARGB `IntArray`) because the existing `takeScreenshot()` returns a Compose
`ImageBitmap` that is a null stub on the JVM/desktop build. stoandl encodes the pixels to PNG with a
tiny `Deflater`-based encoder (no `java.awt`/`ImageIO`, so it stays portable on musl/postmarketOS).
Works on colour (Basalt/Chalk/Emery) and 1-bit classic Pebbles alike. Purely local — no egress.
_Hardware-verified. [TESTING.md §5.15](../TESTING.md)._

### Find my watch

Make a misplaced watch ring continuously so it can be located.

```sh
stoandl watch find   # ring the watch until you press the button on its screen
```

Reuses the incoming-call path (`currentCall`): the watch shows a call screen named "Find My Watch"
and rings like a real call until the button on that screen is pressed, which clears it. Host→watch
only, no `.pbw` and no egress. _Hardware-verified. [TESTING.md §5.10](../TESTING.md)._

### Time / timezone sync

The watch's clock is set on every connect (libpebble3's negotiator sends `SetUTC` with unix time +
UTC offset + timezone name) and on watch request. stoandl additionally re-pushes the clock when the
**host** timezone changes mid-connection, watching `org.freedesktop.timedate1`'s `PropertiesChanged`
on the system bus (a headless analogue of Android's `ACTION_TIMEZONE_CHANGED`, which libpebble3's JVM
target left as a no-op). It re-reads the zone on each change (the JVM caches its default timezone at
startup, so the cache is invalidated first, else the resend would carry the stale offset). Covers
`timedatectl set-timezone` / a DE timezone toggle and NTP enable/sync; a plain DST rollover or bare
`set-time` clock step isn't signalled by timedated and still waits for the next reconnect. Automatic,
no command. _Hardware-verified. [TESTING.md §5.9](../TESTING.md)._

### Health / activity sync

Pull the watch's health data (steps, distance, calories, active minutes, **sleep** sessions, **heart
rate** incl. resting HR + HR-zone minutes, and **workout** sessions: Walk/Run/OpenWorkout) into the
host. The official app syncs to Apple Health / Google Fit; headless stoandl has no dashboard, so its
"target to write to" is a local NDJSON store other tools can consume.

```sh
stoandl health              # last-N-day summary table
stoandl health activities   # one row per workout
stoandl health dump         # raw export
stoandl health sync         # pull fresh data from a connected watch now
```

Two halves, both wiring-only — **no fork change**: libpebble3's `HealthDataProcessor` already ingests
the watch's health datalog frames into the shared `libpebble3.db`, and `Health`/`HealthService` are
real on the JVM (not no-op stubs). The gaps were that nothing requested the data and nothing exposed
it. stoandl now (a) fires `requestHealthData` on every connect (incremental — the first run, with an
empty DB, is a full pull; `health.sync`, on by default), and (b) a `HealthExporter` projects the DB
to `~/.config/stoandl/health/` whenever new data lands (`health.export`, on by default):
`daily.ndjson`, `activities.ndjson`, and opt-in minute-level `samples/<date>.ndjson`
(`health.export_samples`, off — high volume). Units are normalised for consumers (metres, kcal,
minutes), and queries reuse libpebble3's own aggregation + sleep-grouping. Local-only, no egress.
_**Hardware-verified.** [TESTING.md §5.21](../TESTING.md)._ See [configuration.md](configuration.md#health--activity).

### Battery level read-out

Surface the connected watch's battery level (the standard BLE Battery Service 0x180F / 0x2A19
characteristic libpebble3 already subscribes to).

```sh
stoandl watch battery   # the connected watch's battery level
stoandl watch list      # each connected watch's line gains a trailing NN%
```

It also shows in the watch-info block that goes into the `stoandl support` bundle (a `Battery:`
line). Purely informational, local-only, no config, no egress. Wiring-only, no fork change (the level
is only read when a watch is connected; a disconnected watch shows none). Over **Bluetooth Classic**
this is unavailable — the level rides a BLE GATT service (see
[Battery level over Bluetooth Classic](#battery-level-over-bluetooth-classic)); the richer
[battery insights](#battery-insights) do work over Classic. _**Hardware-verified.**
[TESTING.md §5.20](../TESTING.md)._

### Battery insights

The local equivalent of the official Core app's "Battery" screen (which is a cloud WebView the
backend computes from uploaded telemetry). stoandl decodes the watch's hourly analytics
*native-heartbeat* — state of charge, real voltage, the firmware's own time-to-empty, and a measured
charge signal — locally, in the `WebServices.uploadAnalyticsHeartbeat` hook libpebble3 already routes
the blob to (zero fork change; it's the very blob the official app forwards to its cloud, decoded
on-device here instead).

```sh
stoandl watch battery insights   # charge trend, discharge rate, time-to-empty, cycles, voltage
stoandl watch battery history    # the battery %-over-time series (--since 24h / 7d / …)
stoandl watch battery activity   # per-hour battery drain + notifications-received counts
stoandl watch battery power      # estimated usage share: what drew power (display, radio, CPU, …)
stoandl watch battery heartbeat  # the raw decoded heartbeat record
```

Decoding is strictly version/size-gated against the firmware-verified 523-byte record layout and
captures the raw blob on any mismatch, so a firmware layout change degrades to the BLE
battery-level fallback rather than emitting garbage. The same 523-byte record carries 91 metrics (the
battery block is 7); the rest — per-subsystem on-times (backlight, vibration, speaker, HRM), CPU
run/stop + per-task residency, and event counters (notifications received) — are decoded on demand
from the stored raw blob (so they **backfill across existing history**) to build three further views
the official cloud screen showed: a per-hour **drain** bar, a **power-attribution** breakdown (an
*estimate*: on-time × intensity, not measured mAh) and a **notification-density** overlay on the
charge graph. Also surfaced in the GUI's **Battery** page and via the `battery.heartbeat` /
`battery.history` / `battery.retention_days` config keys (on by default, local-only, no egress).
Because the heartbeat rides the datalog service rather than GATT, it also works over **Bluetooth
Classic** and backfills across disconnects.

_**Hardware-verified.** [TESTING.md §5.29](../TESTING.md)._ See [battery-insights.md](battery-insights.md).

### Developer connection

Bridge the Pebble SDK / CloudPebble to the watch so `pebble install --phone <this-host>` and live app
debugging work through stoandl (what the official app's developer connection does), instead of only
`stoandl apps install`.

```sh
stoandl developer start    # bring up the LAN WebSocket server (port 9000), print the host address(es)
stoandl developer stop     # stop it
stoandl developer status   # query state
```

`developer start` brings up libpebble3's LAN WebSocket server (port 9000) — which relays raw
Pebble-protocol frames to/from the watch, installs `.pbw` bundles, and streams PKJS logs. Wiring-only:
the whole feature already lives in libpebble3; stoandl just pins `WatchConfig.lanDevConnection=true`
(so the transport picks the LAN server, not the token-gated CloudPebble proxy it can't use), adds the
D-Bus methods + CLI, and an opt-in `developer.autostart` that re-arms the server on every connect (the
server lives in the per-connection scope, so it dies on disconnect). **Security**: the server binds
`0.0.0.0:9000` with no authentication — while running, anyone on the network can install apps and
relay protocol traffic to the watch — so it's off by default, started explicitly, and the CLI prints
the warning. No egress. _**Hardware-verified.** [TESTING.md §5.19](../TESTING.md)._ See
[configuration.md](configuration.md#developer-connection).

> The browser-IDE / cross-NAT counterpart (the **CloudPebble cloud proxy**) is not planned; see
> [Not yet implemented](#not-yet-implemented).

### Per-app notification settings

Wires libpebble3's dormant-on-JVM `NotificationAppItem` store. Every desktop app that notifies is
lazily tracked (the app name is its identity — Linux has no package ids), and its mute state is
enforced **host-side** before sending, the same place Android's `decideNotification` enforces it.

```sh
stoandl notif list                 # tracked apps + mute state
stoandl notif mute <app> [1h|2d]   # mute an app (optionally temporarily, auto-expiring)
stoandl notif unmute <app>
stoandl notif mute-all
stoandl notif style <app> --color/--icon/--vibe   # per-app colour / icon / vibration
stoandl notif styles               # list every accepted colour / icon / vibe (generated from the enums)
```

Modes: `always`, the day-of-week schedules `weekdays`/`weekends`, and temporary mutes. Matching is a
case-insensitive substring, with an exact name winning outright so `whatsapp` can be picked over
`whatsapps`. No fork change: stoandl drives the existing `NotificationAppRealDao` from the notification
listener. **Mute from the wrist works**: every forwarded notification carries a **"Mute *app*"
action** in its on-watch action menu (the same Generic timeline action the official Android app
attaches); selecting it round-trips back and mutes host-side — no BlobDB sync needed. **Per-app
styling** is applied host-side at send time, so it renders on the watch with no sync. There is no
per-app *settings menu* on current Core/PebbleOS firmware (Settings → Notifications is global-only),
so the opt-in `notification.sync_to_watch` defaults off. _**Hardware-verified** — host-side mute,
weekday/weekend schedules, temporary mutes, persistence, the wrist "Mute" action and per-app styling
all confirmed. [TESTING.md §5.18](../TESTING.md)._ See [configuration.md](configuration.md#per-app-notification-settings).

### Watch logs / support bundle

Pull the watch's own firmware logs, or package a full diagnostic bundle for a bug report.

```sh
stoandl logs                    # → ./pebble-logs-<time>.txt  (the watch's firmware log)
stoandl logs /tmp/watch.txt     # a name you choose

stoandl support                 # → ./stoandl-support-<time>.tar.gz
stoandl support --coredump      # also pull a coredump off the watch, if it has one
```

`stoandl logs` dumps the connected watch's firmware log (multi-generation `=== Generation: N ===`
blocks). `stoandl support` builds a resilient `.tar.gz` bug-report bundle: watch logs + watch info +
(opt-in `--coredump`) a coredump, plus the daemon log (`/tmp/stoandl*.log`), the stoandl version, and
your `stoandl.conf` **with secrets redacted** (CalDAV passwords and any credentials/tokens in URLs →
`***`). It degrades gracefully — with no watch (or no daemon) it still produces a bundle and notes in
`bundle-notes.txt` which pieces were omitted rather than failing. Wiring-only: libpebble3 already had
`gatherLogs` / `getCoreDump` / `WatchInfo`. No egress — nothing is uploaded (unlike the official app's
Core backend). An output argument may be a filename, an existing directory, or a not-yet-existing
`dir/` (created for you); omit it for a timestamped name in the cwd. Review the archive before
sharing: the watch logs can still contain personal data. _**Hardware-verified** — watch-log capture,
watch info, coredump and the full bundle. [TESTING.md §5.16](../TESTING.md)._

### Data logging

Capture data logged by custom watchapps via the PebbleKit DataLogging API to append-only NDJSON, one
file per (app UUID, tag) under `~/.config/stoandl/datalog/`.

```sh
stoandl datalog list          # list captured (app UUID, tag) streams
stoandl datalog dump <uuid> <tag>   # print a stream
stoandl datalog tail <uuid> <tag>   # follow a stream live
```

Reads the files directly — no daemon needed. The receive plumbing was already in libpebble3; a small
fork hook (`Datalogging.records`) surfaces custom-app frames that were otherwise dropped. Local-only
(no egress) but writes app-supplied data to disk, so off by default (`datalog.enabled`). A throwaway
test watchapp lives in [`testing/datalogtest/`](../testing/datalogtest). _Hardware-verified.
[TESTING.md §5.8](../TESTING.md)._

### Language packs

Install a non-English firmware language pack (`.pbl`) onto the watch, replacing its notification/UI
language (and the fonts a script needs — Cyrillic, CJK, Burmese, Hebrew, …).

```sh
stoandl language list                # packs for the connected watch (installed one marked *); full catalog if none
stoandl language sideload pack.pbl   # install a local .pbl (offline)
stoandl language install de_DE       # pick from the catalog and download+install (needs language.download)
stoandl language status              # current install state
```

`list` shows the packs available for the connected watch's board (system-locale first, installed one
flagged), and falls back to the full catalog — every locale and board, fully offline — when no watch
is connected, so you can browse before pairing. The catalog is the official Core app's manifest — the
Gradle build extracts it at compile time from libpebble3's `LanguagePackRepository` into a bundled
resource, so it can't drift from the fork (bump the submodule → catalog refreshes), with no
hand-maintained copy. No fork change: the installer is libpebble3's `installLanguagePack(url/path,
name)` (the same PutBytes machinery as firmware/app sideload); the CLI polls `LanguageStatus` and
shows a progress bar. Boards match the way the official app does — Core devices (Pebble 2 Duo / Time
2) share the Diorite (`silk`) packs, classic Pebbles use their own board revision. Sideload + list are
egress-free; `install` (which downloads from Rebble's CDN / community GitHub) is opt-in egress, off by
default (`language.download`). Revert to English with `stoandl language install en_US`.
_Hardware-verified. [TESTING.md §5.13](../TESTING.md)._ See [configuration.md](configuration.md#language-packs).

### Firmware updates

Flash watch firmware. All three layers ride libpebble3's full `FirmwareUpdater` (PutBytes transfer +
the `FIRMWARE_UPDATE_START`/`COMPLETE` handshake + safety checks: board/CRC/slot match, so a wrong
bundle is refused before flashing — no fork change).

```sh
stoandl firmware sideload <file.pbz>   # (a) flash a local firmware bundle (shows a progress bar)
stoandl firmware check                 # (b) is newer firmware available for this watch?
stoandl firmware update                # (b) download the matching build and flash it
stoandl firmware status                # current firmware-update state
```

**(a) Local sideload** flashes any local bundle; offline, always available. **(b) Online check /
update** resolves the bundle matching the connected watch's board and downloads+flashes it, choosing
the source by the watch's generation: **Core devices** from a public GitHub repo's releases (default
`coredevices/PebbleOS`, where the watch's board revision maps exactly to the asset
`normal_<board>_<version>.pbz` — no lookup table to drift), **classic Pebbles** from Rebble's
`cohorts.rebble.io`. Each source is independent opt-in egress, off by default (`firmware.github` /
`firmware.cohorts`). **(c) Update notification** — with a source enabled, stoandl checks on each
connect (throttled to once/day) and, when newer firmware is available, pushes an **Update**
notification to **both the watch and the host desktop**; pressing Update on either flashes the build
right there (no phone/CLI). Toggle `firmware.notify` (default on when a source is).

> Flashing is the riskiest thing stoandl does. It's guarded by the pre-flash safety checks and
> Pebble's recovery firmware, but flash on charger and keep the watch in range.

_**Hardware-verified** — local `.pbz` sideload, the GitHub check/update flash, and the watch+desktop
Update notification all flashed flawlessly. [TESTING.md §5.11](../TESTING.md)._ See
[configuration.md](configuration.md#firmware-updates).

## Implemented — to be tested

Implemented but not yet fully verified on hardware — each entry's note says what's confirmed and
what's still pending. Test plan: [TESTING.md](../TESTING.md).

### Phone call notifications

ModemManager (system bus) → `currentCall` → native Pebble call screen; watch Answer/Hangup drive
`Accept()` / `Hangup()`. Automatic once a modem is present. _To be tested. [TESTING.md §5](../TESTING.md)._

### Missed-call sync

Unanswered incoming calls become timeline pins via libpebble3's `MissedCallSyncer` (backed by an
in-memory `SystemCallLog` fed by the call monitor). _To be tested._

### Caller-ID resolution

DE-agnostic vCard lookup (`contacts.vcard_paths`, suffix-matched), with the dialer's own notification
title as fallback. See [configuration.md](configuration.md#caller-id-resolution). _To be tested._

### Notification filtering

Free-text allow/block **filters** (matched live against app name + title + body) mute or gate
individual conversations regardless of app; `call.dialer_apps` suppresses the dialer's redundant call
notification. (Per-app muting is a separate, hardware-verified feature above.)

```sh
stoandl notif filter add <regex> block|allow   # matched against app name + title + body
```

See [configuration.md](configuration.md#per-app-notification-settings). _To be tested._

### Factory reset / reset to recovery

Reset the connected watch — the companion to the firmware tooling, for un-bricking a bad flash or
wiping the watch for handoff.

```sh
stoandl reset recovery        # reboot the watch into recovery (PRF) firmware
stoandl reset factory         # wipe the watch back to out-of-box state (asks to confirm)
stoandl reset factory --yes   # …skip the confirmation prompt
```

`reset recovery` is recoverable — from PRF, reflash a normal firmware with `stoandl firmware sideload
<file.pbz>`. `reset factory` is **irreversible**: it erases all apps, settings and the host pairing
(you'll re-pair afterwards), so the CLI requires a typed `yes` unless you pass `--yes`. Both ride
libpebble3's `ConnectedPebble.Debug` (a single RESET-endpoint packet — no JVM stub, no fork change)
and are fire-and-forget: the call returns once the packet is queued and the watch drops the link as it
reboots/wipes. Local-only, no egress, no config key. _To be tested — needs a watch; destructive, so
test last. [TESTING.md §5.17](../TESTING.md)._ See
[configuration.md](configuration.md#recovery--factory-reset-no-config-no-network).

### Location to watch apps (geolocation)

Expose the device's position to watchapps via libpebble3's `SystemGeolocation` hook, so PKJS apps'
`navigator.geolocation` and location-aware sports/GPS watchapps get a fix. The fix comes from GeoClue2
— the same provider weather's "current location" uses — so it works headlessly on a Linux phone (modem
GPS + Wi-Fi/A-GPS) with no GPS-specific code. A small fork addition makes the GraalJS PKJS runtime
route `_PebbleGeo` through the shared `GeolocationInterface`, and stoandl overrides the no-op
`SystemGeolocation` binding with a GeoClue-backed one. Off by default — it hands the device's location
to whatever watchapp asks — enable with `geolocation.enabled`. _To be tested — needs a watch + a
location-using watchapp. [TESTING.md §5.12](../TESTING.md)._ See
[configuration.md](configuration.md#geolocation).

### Extensions (companion apps)

A plug-in system so you can add your own host-side integrations (Matrix, Signal, Discord, SMS, "find
my phone", …) in any language, the way Android PebbleKit companion apps work but driving
**notifications** so no watchapp is needed (a watchapp companion is *also* supported via AppMessage
when wanted). Each extension is a child process stoandl spawns and supervises, speaking
newline-delimited JSON-RPC over stdio; capabilities are user-granted in `stoandl.conf`.

```sh
stoandl ext install matrix.tar.gz   # extract to ~/.config/stoandl/ext/, sideload any bundled .pbw,
                                     # enable + hotplug-start (no daemon restart)
stoandl ext list                    # installed extensions + enabled/running state
stoandl ext enable <name>           # add to the run-list and start (disable to stop + drop)
stoandl ext restart <name>          # restart one after editing it
stoandl ext uninstall <name>        # stop, remove its dir, and remove any watchapp it installed
```

Fully wired end to end — the D-Bus `Ext*` catalog and the GUI **Plugins** screen too — and it's the
**umbrella mechanism** that delivers the *Send text / reply* item below (each channel becomes a
per-channel extension) and makes *find my phone* trivial (a watch action that runs host code — ~20
lines, no watchapp). No libpebble3 fork change. Extensions run as you, like any script — there's no
sandbox. Two extensions ship in [`examples/extensions/`](../examples/extensions): a full **Matrix**
client — messages on the wrist + canned replies + on-demand image previews, end-to-end-encrypted — and
**find-my-phone**. A ~40-line per-language helper (e.g. `stoandl_ext.py`) is shipped as a template.
_Find-my-phone (Ring + Stop) is hardware-verified ([TESTING.md §5.26](../TESTING.md)); the
notification core and install/hotplug paths are built + smoke-tested but not yet run on hardware._
Design + wire protocol: [extensions.md](extensions.md).

### Multiple concurrent Pebble watches

`libPebble.watches` is a `List<PebbleDevice>`; scan, auto-connect, notifications, and calls all iterate
the full list, so the core architecture is multi-watch by design — but this is untested and needs two
Pebbles to verify. _To be tested._ Known gaps before multi-watch is fully usable:

- _BlueZ GATT server multi-client: whether BlueZ allows two Pebbles as simultaneous GATT clients to
  the same application is untested._
- _CLI commands (`launch`, `sideload`, `remove`, `settings`) target the single connected watch with no
  disambiguation flag (`--watch <name|address>`). With two watches the behaviour is unspecified._
- _`stoandl watch pair` with a bonded-but-absent watch works — the absent watch is
  `KnownPebbleDevice`, not `ConnectedPebbleDevice`, so the guard doesn't fire._
- _`stoandl watch pair` with a watch already **connected** is broken: the early-return guard exits
  immediately with "Watch already connected". Fixing it requires removing that guard and scoping the
  `connectedJob` to newly connected devices only._

## Not yet implemented

### Send text / reply

Reply to messages from the watch. A **generic** reply driven off the notification bus is *not viable*
([why](#why-generic-notification-reply-isnt-viable)): stoandl is a passive `BecomeMonitor` copy, not
the owner of `org.freedesktop.Notifications`, so reply signals are dropped — only `Dismiss` works (it
rides on `CloseNotification()`, the one method a third party may call). The viable path is
**per-channel, bypassing the notification bus**: **Matrix** already ships as an extension
([`examples/extensions/matrix`](../examples/extensions/matrix), mautrix-go + pure-Go goolm; long-poll
`/sync` → watch, canned reply → same room, E2EE handled) — built + smoke-tested, not yet run on a real
account/watch — and **SMS** via the ModemManager Messaging interface (reusing the telephony
integration) is still to build. The watch side is shared by every channel (a `Response` action
carrying canned text). _(The reply *mechanism* — the Extensions umbrella — is done, above.)_

### Per-chat notification settings

Mute/style per *conversation*, not just per app (e.g. mute one noisy Matrix room while keeping the
rest). The per-app store keys on the app name; per-chat needs a finer key. Free-text **muting** per
conversation already works today via the global notification filters (`stoandl notif filter add
"(?i)noisy room" block`); what's still missing is per-conversation **styling** (color/icon/vibe) and a
robust **Matrix**-native path with real room ids (matrix-nio / a direct integration that shares the
listener with the per-channel reply item above), rather than the fragile free-text summary/body match.

### Calendar RSVP / pin actions

Accept/Maybe/Decline/Cancel from the watch, written back to the calendar (libpebble3 has the watch
side via `PlatformCalendarActionHandler`). Needs full attendee/self detection **and** CalDAV
scheduling (iTIP), so it's CalDAV-only — read-only `.ics`/feed sources can't write back. Calendar
display + reminders are done (above).

### Account-backed app store

Rebble appstore browse/install and cloud locker sync (`fetchLocker()`) still stubbed; needs an
account/token. (Local locker management — list/launch/remove/sideload/backup — is already done,
above.)

### CloudPebble cloud proxy

Let the browser IDE at cloudpebble.repebble.com reach the watch across NAT, via libpebble3's other
dev-connection transport (`DevConnectionCloudpebbleProxy`). **Not planned**: it authenticates with a
**Firebase ID token for Core Devices' Firebase project** (matching is account-based — same account on
the app and in CloudPebble). Supporting it would mean a Firebase auth client, extracting Core's real
Firebase config, and egress to Google + Rebble — a hard dependency on infra not meant for third-party
clients, and a departure from stoandl's account-free design. The LAN **Developer connection**
(implemented above) already provides install + live-debug; the proxy only adds the no-local-SDK /
cross-NAT browser IDE. Revisit only if Core exposes a documented token path. (Mechanically it's small
once a token exists: emit it into `proxyTokenProvider` and flip `lanDevConnection=false`.)

### Voice / dictation

*Not planned until reply works.* Dictation isn't a standalone feature: the watch mic only ever feeds
the **Response** action, so it depends entirely on *Send text / reply* above being in place first
(there's nowhere to deliver dictated text otherwise). Only then is it a contained add-on (Pebble voice
protocol + an opt-in STT backend). Transcription provider is currently stubbed (`Failed`).

### Battery level over Bluetooth Classic

`stoandl watch battery` works over BLE but not Classic, and this is **structural, not just unwired**.
The Pebble wire protocol has *no* battery endpoint (confirmed against the original `libpebble`,
Gadgetbridge's `PebbleProtocol`, and libpebble3's full `ProtocolEndpoint` set) — battery exists *only*
as the standard BLE GATT Battery Service (`0x180F` / `0x2A19`). The official Core app gets it the same
way: its libpebble3 has no Classic transport at all, so it connects even legacy Pebble Time / Time
Steel over **BLE (PPoGATT)** and reads the GATT service over that link. stoandl deliberately routes
those watches over **RFCOMM** instead (BLE/PPoGATT is an unreliable ~1/5 reconnect race on
Linux/BlueZ), and RFCOMM carries no GATT — so the battery characteristic is simply unreachable. A
protocol-level read-out **cannot** be "added to the fork" because no such protocol message exists. The
only conceivable path is a **side-channel BLE GATT connection** to the same dual-mode watch — feasible
in principle, but unverified. What *does* reach these watches over Classic is battery **insights**:
the analytics native-heartbeat rides the transport-agnostic datalog service, so `stoandl watch battery
insights` is at parity over Classic (see [Battery insights](#battery-insights) above) — only the live
instantaneous GATT *level* stays BLE-only.

---

> **Comparison basis.** The official Core Devices Pebble app
> ([`coredevices/mobileapp`](https://github.com/coredevices/mobileapp)) is built on the same libpebble3
> library stoandl uses, so its feature set ≈ the library's platform hooks (`System*` / `Platform*` /
> `ConnectedPebble.*`). The features above are the hooks the official app wires that stoandl either
> also wires (Working today / to be tested) or leaves as no-op (not yet implemented). Android-only
> hooks (`OtherPebbleApps`, `LegacyPhoneReceiver`) and its cloud bug-report upload are deliberately
> skipped as N/A for a headless Linux daemon. (Bluetooth Classic — Android's `ClassicScanner` etc. —
> is *not* skipped: stoandl has its own BlueZ-based Classic transport, see
> [Working today](#working-today).)

## Why generic notification reply isn't viable

_Investigated 2026-06; parked. Recorded here so it isn't re-litigated._

Replying to a desktop notification means delivering text **back to the originating app**. On the
freedesktop bus that happens via the daemon→app `ActionInvoked` / `NotificationReplied` *signal*,
which clients only accept from the **owner** of `org.freedesktop.Notifications`. stoandl is a passive
`BecomeMonitor` copy, not the owner, so any reply signal it emits is dropped. (Dismiss is the lone
exception: `CloseNotification()` is a real *method* that any client may call on any notification.)

Becoming the owner — a transparent MITM proxy sitting in front of the real daemon — was evaluated and
rejected:

- **GNOME and KDE/Plasma both refuse name takeover.** Each shell owns `org.freedesktop.Notifications`
  *without* `DBUS_NAME_FLAG_ALLOW_REPLACEMENT`, acquired at login before any user service.
  `RequestName(org.freedesktop.Notifications, REPLACE_EXISTING)` returns `3` (exists) on both. The only
  sanctioned way to run a different server is to *manually disable* the shell's built-in notifications —
  which on Plasma Mobile means losing the phone's own notification UI. Unacceptable as a default.
- **xdg-desktop-portal doesn't help.** Its Notification portal is send-side only (app → portal → the
  *one* configured backend → reply routed back to the app); it is not an interception API, and replacing
  the backend is itself a desktop-config change that only catches portal/flatpak apps.
- **Prior art (KDE Connect) can only reply because it is integrated with Plasma's notification-server
  internals** — not a portable mechanism a third-party daemon can reuse.

So a proxy works *only* where no shell server is entrenched (bare wlroots/sxmo compositors, or a desktop
whose server is manually disabled), making it an opt-in niche rather than a feature. Parked.

**The viable path is per-channel reply that bypasses the notification bus** and talks to the messaging
backend directly, where the reply target (room / phone number) is unambiguous — tracked under [Send
text / reply](#send-text--reply). The watch side is shared by every channel: a `Response` action
carrying the canned replies, with the chosen text arriving back as the action's `Title` attribute.
Built once, reused per channel.
