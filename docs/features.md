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
_To be tested:_ also the notifications that never pass through `Notify` — sandboxed (Flatpak) apps on
Plasma ≥ 6.7, whose portal notifications plasmashell draws itself, and GApplication apps on GNOME
(`org.gtk.Notifications`) — see [below](#notifications-from-sandboxed-and-gtk-apps).

### Notification dismiss

Watch → desktop: `Dismiss` / `AncsDismiss` actions mark the notification read on the watch **and**
close it on the desktop, so dismissing on the wrist clears it there too. Automatic. A `Notify`
notification is closed with `CloseNotification()`; GNOME ≥ 42's relay refuses that for a live
notification another app posted, so stoandl then closes it on GNOME Shell's own object, which uses the
same ids (_to be tested_; `notify-send` never showed the problem because its ids aren't live any more).
Portal and GTK notifications are removed with their backend's `RemoveNotification` (_to be tested_).

Desktop → watch (`notification.sync_dismissals`, default on, _to be tested_, [TESTING §5.42](TESTING.md)):
a notification closed by the user or its app on the desktop or phone (`NotificationClosed` reason 2 or 3),
or withdrawn through the portal or GTK, is dismissed on the watch too. A popup that only times out into
the history stays. Extensions' `closeNotification` (message read elsewhere) takes the same path.

Actions from the watch's history: stoandl advertises capability bit 3, so Reply, Mute, app actions and
Dismiss are offered on a notification opened from the watch's Notifications app, not only on the popup
(_to be tested_, [TESTING §5.42](TESTING.md)).

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

_Implemented — to be tested ([TESTING.md §5.33f](../TESTING.md)):_ with the libpebble3 bump, watchapp JS
reaches only an allow-list of host methods (before, any Java method), intercepted XHRs (timeline-pin
apps) fire `onload`, and a reused `XMLHttpRequest` sends again.

_Implemented — to be tested ([TESTING.md §5.70q–r](../TESTING.md)):_ an XHR ends instead of hanging on a
dead link: 15 s to connect, then the app's `xhr.timeout` (60 s when it sets none) for the whole request,
with `timeout` and `loadend` events; `abort()`, and closing the app, stop a request in flight and close its
connection. A failed request logs one WARN line with its host only, no stack trace and no query (which often
carries the location or an API key); `STOANDL_LOG=DEBUG` adds its stack.

_Implemented — to be tested ([TESTING.md §5.73m–n](../TESTING.md)):_ a connection closes right after its
last answer instead of idling in a pool until the server closes it (whose FIN woke a sleeping phone; a
half-closed pooled connection also failed the next request with `EOFException`), and an app's HTTP client
ends with its session.

_Implemented — to be tested ([TESTING.md §5.72](../TESTING.md)):_ PKJS sessions start on one shared GraalJS
engine (one per host-access policy, so PKJS and plugins get one each) with the shims parsed once, so an app's
JS starts 2–3× faster (warm JVM on x86: ~45–57 ms → 13–25 ms per session), and Truffle's per-launch
"fallback runtime" warning is gone from the journal.

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

_Implemented — to be tested ([TESTING.md §5.33j](../TESTING.md), 5.30b):_ the libpebble3 bump brings the
newer prefs: the Quiet Time weekday/weekend hours (`dndWeekdaySchedule`, a `HH:MM-HH:MM` window that
needs its `…Enabled` switch on), `dndAutoDismiss`, `lightPreset`, `lightDynamicMode`, `unitsWind` and
`language` (removed again upstream with the `de47e36f` bump); `langEnglish`, `lightDynamicIntensity` and `dynBacklightMinThreshold` are gone. The
notification timeout can't be set below 15 s (shorter ones vanish without a vibe). Text size is
two settings, `systemTextSize` and `notifTextSize` (upstream PR #443, cherry-picked), which the phone can
set from fw 4.39; the old `textStyle` is gone.

_Implemented — to be tested ([TESTING.md §5.64](../TESTING.md)):_ `dndNotificationBacklight` (PebbleOS 4.41),
the Quiet Time switch that stops notifications lighting the screen, listed under Quiet Time in the GUI.
A setting a firmware release introduced carries that release in libpebble3 (`WatchPref.minFirmware`, a fork
addition: this one, the 4.39 text sizes, and the 4.22–4.37 keys `lightPreset`, `lightDynamicMode`,
`language`, `musicShowAlbumArt`, `dndAutoDismiss` and `unitsWind`): libpebble3 doesn't send it to an older watch, which would
reject the key and have it re-sent on every connect, stoandl skips a `watch.*` pin for it on such a watch
and says so on `settings set`, and `stoandl settings` names the release in its description.

_Implemented — to be tested ([TESTING.md §5.5q](../TESTING.md)):_ quick-launch buttons also take the
firmware system actions the official app offers — Quiet Time, Backlight, Motion Backlight, Airplane Mode,
Toggle Touch, Timeline Past, Timeline Future, Clear Notifications (libpebble3's `SYSTEM_SHORTCUTS`, in its
order) — by name in the CLI and config and from the GUI's dropdown, and list them
by name (the hold-Back and tap-Down defaults showed as raw UUIDs before). _Implemented — to be tested
([TESTING.md §5.5r](../TESTING.md)):_ the fw 4.39 Toggle Touch action, offered only for a touch watch (Pebble
Time 2, Pebble Round 2) on 4.39 or later and marked "touch watches only" while no watch is connected.
libpebble3 (fork) doesn't send it to a watch without it, which would read it as an uninstalled app (a tap on
such a button reboots firmware older than 4.40). _Implemented — to be tested ([TESTING.md 5.77r](../TESTING.md)):_
Clear Notifications (PebbleOS 4.38+, every watch), gated the same way.

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

_Hardware-verified on a Pebble Time Steel. The JNA→FFM rewrite removes the Classic transport's own
native blob and is pending re-test on the phone. One glibc-built native library remains: androidx's
SQLite JNI library, which on musl is handled by `SqliteNative` (see
[musl: bundled SQLite](#musl-postmarketos--alpine-bundled-sqlite))._

_Fixed 2026-09-29: when a config file existed but omitted `classic.discover`, Classic discovery was off
(an absent key was read as `false`), so a Time / Time Steel never showed up in `watch pair`. An absent
key now means on. To be tested ([TESTING.md §5.32d](../TESTING.md))._

See [devices.md](devices.md) for how to enable and use it, and
[configuration.md](configuration.md#bluetooth-classic) for the config keys.

### BLE pairing / bonding

Headless BlueZ agent (Numeric Comparison / MITM / SC). When pairing, the watch shows a **6-digit
code**. Confirm it on the watch, and answer `y` when `stoandl watch pair` shows the same code; a piped
answer (`echo y | stoandl watch pair`) counts too. With `--yes`, or with nothing to read an answer from
(e.g. `ssh host stoandl watch pair` without `-t`), the CLI accepts it by itself and says so. Either way,
the watch-side confirmation is the MITM check. Subsequent reconnects are automatic. stoandl's agent is the
system's default one, so it refuses every other pairing: from anything that isn't a Pebble, and at any time
no pairing window is open. It also authorises services (`AuthorizeService`) for Pebbles only, so a device
you paired elsewhere and didn't mark trusted can't open a profile while stoandl runs (`bluetoothctl trust
<mac>` fixes that).

```sh
stoandl watch pair [--yes]        # pair a new watch (~2 min window; finds BLE and classic watches; --yes: don't ask for the code)
stoandl watch list                # known watches, their connection state and battery level
stoandl watch connect B349        # connect a specific known watch by name/substring (switches the active watch)
stoandl watch disconnect          # keep every watch disconnected until `watch connect` (see Manual disconnect)
stoandl watch connect             # resume after `watch disconnect`
stoandl watch repair B349 [--yes] # re-pair ONE watch by name/substring (forgets just it, then pairs)
stoandl watch unpair [name]       # forget watches on this host — all of them, or just the named one
```

_Implemented — to be tested ([TESTING.md §5.32e](../TESTING.md)):_ a pairing you decline (or leave
unanswered for 60 s) ends the window and isn't retried: no further pairing requests reach the watch
until you open the next pairing window. A pairing window also discovers with the display off
(it's an explicit request), but only while the host is awake, so keep a phone that
suspends with the display off awake while pairing. If the window can't discover, `watch pair` prints
why (Bluetooth off, another watch connecting, the phone slept, the scan failing to start) instead of
timing out silently.

_Implemented — to be tested ([TESTING.md §5.74](../TESTING.md)):_ a **second watch** can be paired while
the first is connected (BLE or Bluetooth Classic), and a Classic one also while the first is away: the window waits for a watch that wasn't known
when it opened, the new watch takes the link over once it bonds, and a declined or timed-out pairing hands
the connection back to the watch that had it. Known watches reconnect only through libpebble3's connect
goal, which only pairing and `stoandl watch connect` move (the Classic pairing step no longer re-requests a
known classic watch). The GUI offers *Re-pair* on a disconnected or endlessly
connecting watch, and the pair dialog offers to turn on Bluetooth Classic for a Pebble Time / Time Steel /
Round.

If you forget the host **on the watch** (one-sided bond), stoandl notices the watch endlessly
re-connecting and dropping, and sends a notification with a one-tap **Re-pair** button — or run
`stoandl watch repair <name>`. `pair` never disturbs other watches, so it's safe with multiple
Pebbles. Conversely, if the pairing is removed **on this computer** (e.g. `bluetoothctl remove`)
while the watch still holds its side, stoandl forgets the now-unusable watch and notifies you: a
half-removed bond can't be restored, so unpair it on the watch too and `stoandl watch pair` again.
(To forget a watch cleanly, use `stoandl watch unpair`, not `bluetoothctl`.)

stoandl never deletes a pairing just because the watch failed to connect, for example while Bluetooth
restarts or the adapter is reloaded; the watch simply reconnects afterwards. It acts only on BlueZ's
own evidence, the two cases above: repeated authentication-failure disconnects (the **Re-pair**
notification deletes nothing until you tap it), or a pairing already gone on this computer.
`bluetoothctl remove` deletes BlueZ's record of the watch too, so that "pairing removed" notice only
comes once BlueZ sees the watch again. These alerts stay on the desktop; they are not sent to a watch.
_Implemented — to be tested: the failure-count "stale-bond reaper" that deleted a good bond after an
adapter reload is gone ([TESTING.md §5.32c](../TESTING.md))._

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

**Multiple paired watches — switch by hand.** stoandl links one watch at a time: the one you last
connected or paired, and only that one reconnects, after a drop, a resume or a restart. `stoandl watch
connect <name>` (or *Connect* on a watch in the GUI's Watch page) switches: the chosen watch gets the
connection goal and every other watch loses it, in libpebble3's database too, so the old watch's link or
connect attempt ends at once and a restart reconnects only the new one. A switch is refused while a
firmware or language update runs, since the updating watch has to reconnect to confirm it, and while a
pairing window is open, whose end gives the goal back to the watch that had it. A bare
`stoandl watch connect` gives the goal back to the most recently connected watch when none holds it (a
declined pairing, the watch that held it unpaired); it leaves an open pairing window's goal alone. _The automatic "follow the wrist" switching
(`connection.autoswitch`) was removed on 2026-10-21: rotating the goal between watches that were away kept
the phone awake, and it rotated off a watch that was still linking up. A leftover line in stoandl.conf is
ignored with a warning._ _To be tested ([TESTING.md §5.30](../TESTING.md))._

### Music / now-playing control

Bridges desktop media players (MPRIS over D-Bus, on the session bus) to the watch's native Music app:
now-playing (title/artist/album, play state, position) plus play/pause, next/previous and volume from
the watch. Volume drives the master/output level by default (auto-detected `wpctl`/`pactl`/`amixer`,
configurable) or the active player's own MPRIS volume (`music.volume`). Follows the actively-playing
player; `playerctld` is skipped so it doesn't duplicate. On by default (`music.enabled`); local-only,
no egress, no command — it just tracks whatever's playing. A player that can seek but can't change
track (a podcast or audiobook app, a single video) gets ±15 s seeks from next/previous instead, and the
watch shows fast-forward/rewind for it; a seek on the desktop updates the watch's progress at once
(_to be tested_, [TESTING §5.43](../TESTING.md)).

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
default, `weather.pins = false` disables just the pins. The GPS entry's forecast position moves only once
a fix is more than 5 km away (and outside its own accuracy radius), so a jittering neighborhood-level fix doesn't rewrite the pins on every refresh. The position is looked up at most every 45 min (cell tower, no GPS), and a watchapp's recent fix answers too: weather and watchapp geolocation share one GeoClue provider, whose client stops at each answer. Its coordinates are rounded to about 2 km before they go to Open-Meteo or Nominatim, so a watchapp's GPS fix discloses no more than weather's own scrambled one. Temperatures are in the watch's own units
(`stoandl health profile set units metric|imperial`), which fw 4.37+ also uses for its weather warnings.

```sh
stoandl weather                          # force a refresh now
stoandl weather search Lyon [--add [n]]  # find a city, add it (weather.place_search; to be tested)
```

_Hardware-verified. (Watch firmware only surfaces the next ~2–3 days of timeline, so the furthest
pins sync but may not display.)_ Following the watch's units instead of the removed `weather.units`, and
non-ASCII location names such as "München" on fw ≥ 4.34, are _to be tested_ ([TESTING.md §4.4–4.4c,
§5.33i](../TESTING.md)). See [configuration.md](configuration.md#weather).

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

_Implemented — to be tested ([TESTING.md §5.56a, §5.33l](../TESTING.md)):_ all-day events are sent at
UTC midnight of their date, which the watch expects (east of UTC they landed on the previous day), and with the libpebble3 bump a reminder offers Dismiss/Snooze on the watch.

_Implemented — to be tested ([TESTING.md §5.61a–c](../TESTING.md)):_ a local `.ics` file is parsed again
only when its content changed, not on every sync tick or vdirsyncer rewrite (a read and a checksum
instead); a weekly series with a date-only `UNTIL` on a timed start (old Outlook/Thunderbird data) and a
recurring event in floating time expand instead of failing; `calendar.todos` no longer starts a calendar
sync, and the reminder settings start one, not two.

See [configuration.md](configuration.md#calendar).

Reminders, declined events and the reminder vibration are configurable (`calendar.reminders`,
`calendar.show_declined`, `calendar.reminder_vibe`), live without a restart (_to be tested_,
[TESTING §5.43](../TESTING.md)).

_Implemented — to be tested ([TESTING.md §5.73](../TESTING.md)):_ each source says why its last read
failed and when it last read fine (`stoandl calendar sources`: `problem: Sign-in failed (401)`, `last sync:
just now`; the GUI shows the same per source and can browse for a local `.ics` file or folder).

### Watch screenshot

Capture the watch's current screen to a PNG on the host — handy for sharing watchfaces and filing bug
reports (the official app has it).

```sh
stoandl screenshot                 # → ~/Pictures/Screenshots/pebble-screenshot-<time>.png (prints the path)
stoandl screenshot watchface.png   # → a name you choose
stoandl screenshot ~/Pictures/     # → a timestamped PNG in that directory
```

libpebble3's `ScreenshotService` does the protocol work over the SCREENSHOT endpoint and decodes the
1-bit (classic) or 8-bit (colour) framebuffer; a small fork addition exposes the decoded pixels
(`takeScreenshotPixels()` → ARGB `IntArray`) because the existing `takeScreenshot()` returns a Compose
`ImageBitmap` that is a null stub on the JVM/desktop build. stoandl encodes the pixels to PNG with a
tiny `Deflater`-based encoder (no `java.awt`/`ImageIO`, so it stays portable on musl/postmarketOS).
Works on colour (Basalt/Chalk/Emery) and 1-bit classic Pebbles alike. Purely local — no egress.
_Hardware-verified. [TESTING.md §5.15](../TESTING.md)._ _Implemented — to be tested
([TESTING.md §5.73](../TESTING.md)):_ the capture runs as a daemon watch task (D-Bus `TakeScreenshot` answers
at once, `TaskStatus` follows it), so a slow transfer never freezes the GUI; with no path it lands in the
XDG Pictures/Screenshots folder (localized names included), and the GUI shows a preview with *Open* and
*Open folder*.

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
_**Hardware-verified.** [TESTING.md §5.21](../TESTING.md)._ _Implemented — to be tested
([TESTING.md §5.219, 5.219d](../TESTING.md)):_ with the libpebble3 bump the v14 minute records of fw
4.38.0/4.38.1 are no longer dropped and a workout's active and resting kcal are no longer swapped; a
workout's distance is exported in metres (it was 1/100 of that). See
[configuration.md](configuration.md#health--activity).

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

Decoding is strictly gated on the record's `(size, version)` — every released layout from fw 4.10.0
(the first that really packs the record), derived from PebbleOS `analytics.def`, up to the current
567 B / version 3 of fw ≥ 4.33 — and captures the raw blob
on any mismatch, so a firmware layout change degrades to the BLE battery-level fallback rather than
emitting garbage. The record carries 101 metrics on current firmware (the battery block is 7;
`stoandl watch battery heartbeat --all` prints them all); the rest — per-subsystem on-times
(backlight, vibration, speaker, HRM), CPU run/stop + per-task residency, and event counters
(notifications received) — are decoded on demand from the stored raw blob (so they **backfill across
existing history**) to build three further views the official cloud screen showed: a per-hour
**drain** bar, a **power-attribution** breakdown (an *estimate*: on-time weighted by a modeled
current and anchored to the measured drop, not metered mAh) and a **notification-density** overlay on
the charge graph. Also surfaced in the GUI's **Battery** page and via the `battery.heartbeat` /
`battery.history` / `battery.retention_days` config keys (on by default, local-only, no egress).
Because the heartbeat rides the datalog service rather than GATT, it also works over **Bluetooth
Classic** and backfills across disconnects.

_**Hardware-verified.** [TESTING.md §5.29](../TESTING.md)._ The fw ≥ 4.33 layout (567 B / v3), without
which the three views were empty on current firmware, is _to be tested_ (§5.29M). See
[battery-insights.md](battery-insights.md).

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
stoandl notif mute-all 1h          # mute every app for a while (30m/1h/2d/today); notif unmute-all ends it
stoandl notif style <app> --color/--icon/--vibe   # per-app colour / icon / vibration
stoandl notif styles               # list every accepted colour / icon / vibe (generated from the enums)
stoandl notif vibe-test <vibe>     # buzz the watch with a vibration (to be tested, below)
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

_Implemented — to be tested ([TESTING.md §5.73](../TESTING.md)):_ `notif mute-all` is now one temporary
state apart from the per-app mutes (a duration or `today`, kept across a restart): it no longer overwrites
each app's own mute, so `unmute-all` gives back a `weekdays` app its schedule, and it also holds back apps
not seen before. `stoandl notif list`, `stoandl status` and the GUI's Watch tab show it while it lasts.

### Watch logs / support bundle

Pull the watch's own firmware logs, or package a full diagnostic bundle for a bug report.

```sh
stoandl logs                    # → ~/Documents/stoandl/pebble-logs-<time>.txt  (the watch's firmware log)
stoandl logs /tmp/watch.txt     # a name you choose

stoandl support                 # → ./stoandl-support-<time>.tar.gz
stoandl support --coredump      # also pull a coredump off the watch, if it has one
```

`stoandl logs` dumps the connected watch's firmware log (multi-generation `=== Generation: N ===`
blocks). `stoandl support` builds a resilient `.tar.gz` bug-report bundle: watch logs + watch info +
(opt-in `--coredump`) a coredump, plus the daemon log (`/tmp/stoandl*.log`), the newest 5 JVM crash
reports (`stoandl-hs_err_pid*.log` in `$XDG_RUNTIME_DIR` or, under OpenRC, `/run/stoandl`, where the
shipped services put them, and only files the user owns; to be tested,
[TESTING.md §5.32f](../TESTING.md)), the stoandl version, and
your `stoandl.conf` **with secrets redacted** (CalDAV passwords and any credentials/tokens in URLs →
`***`). It degrades gracefully — with no watch (or no daemon) it still produces a bundle and notes in
`bundle-notes.txt` which pieces were omitted rather than failing. Wiring-only: libpebble3 already had
`gatherLogs` / `getCoreDump` / `WatchInfo`. No egress — nothing is uploaded (unlike the official app's
Core backend). An output argument may be a filename, an existing directory, or a not-yet-existing
`dir/` (created for you); omit it for a timestamped name (`logs` in Documents/stoandl, `support` in the
cwd; _to be tested, [TESTING.md §5.73](../TESTING.md)_). Review the archive before
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

Reads the files directly — no daemon needed. The receive plumbing is libpebble3's, which emits
custom-app frames on `Datalogging.thirdPartyEvents`; a small fork patch adds each batch's item type,
which the decoding needs. Local-only
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
is connected, so you can browse before pairing. The catalog is the official Core app's last in-tree
manifest, frozen as the resource `language-packs.json` (its `_origin` key names the commit): upstream moved
the list to a server manifest (`7cf80230`), and fetching it would be new egress, so a submodule bump no
longer refreshes it. No fork change: the installer is libpebble3's `installLanguagePack(url/path,
name)` (the same PutBytes machinery as firmware/app sideload); the CLI polls `LanguageStatus` and
shows a progress bar. Boards match the way the official app does — Core devices (Pebble 2 Duo / Time
2) share the Diorite (`silk`) packs, classic Pebbles use their own board revision. Sideload + list are
egress-free; `install` (which downloads from Rebble's CDN / community GitHub) is opt-in egress, off by
default (`language.download`). Revert to English with `stoandl language install en_US` (on PebbleOS 4.42+,
`stoandl language use english`, which keeps the packs: see
[Several language packs on the watch](#several-language-packs-on-the-watch)).
_Hardware-verified. [TESTING.md §5.13](../TESTING.md)._ See [configuration.md](configuration.md#language-packs).

### Firmware updates

Flash watch firmware. All three layers ride libpebble3's full `FirmwareUpdater` (PutBytes transfer +
the `FIRMWARE_UPDATE_START`/`COMPLETE` handshake + safety checks: board/CRC/slot match, so a wrong
bundle is refused before flashing — no fork change).

```sh
stoandl firmware sideload <file.pbz>   # (a) flash a local firmware bundle (shows a progress bar)
stoandl firmware check                 # (b) is newer firmware available for this watch?
stoandl firmware check --refresh       # (b) the same, skipping the 3 h check cache
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

_Implemented — to be tested ([TESTING.md §5.11e–5.11h](../TESTING.md)):_ "latest" is the highest GitHub
release, so a backport marked latest (v4.27.3, v4.30.3) no longer hides the newest firmware or gets
flashed onto a watch in recovery. Flashing an **older** `.pbz` onto a dual-slot Core watch goes through
recovery: the watch reboots into PRF, and libpebble3 installs the same file there when it reconnects (since
the `de47e36f` bump; before, stoandl did; the CLI and the GUI follow it; `firmware update` and
`firmware sideload` answer `busy:` meanwhile). An update that crosses 4.38.0
(4.37.0 on a Pebble 2 Duo) says that the built-in translations go away and points to
`stoandl language sideload`. A download is checked against the SHA-256 the source publishes (GitHub's
asset digest, the cohorts `sha-256`) before it reaches the watch, and a failed or damaged download shows
as `failed:` with the reason instead of falling back to idle (TESTING §5.11i).

_Implemented — to be tested ([TESTING.md §5.11j–5.11l](../TESTING.md)):_ **the check is cached.** A
GitHub check downloaded the whole release list (5–7 MB, 8–9 s on a OnePlus 6) every time, and the GUI
asked on every Watch page. Now the daemon keeps the answer for **3 hours** per source, board and running
firmware (a failed check for 1 minute), `firmware check`, the GUI and the connect-time notification
share it, and simultaneous checks wait for one lookup instead of each downloading. The request asks for
gzip and revalidates with the release list's ETag (a 304 saves the transfer, but unauthenticated it
still counts against GitHub's 60/h limit), and reads the newest 10 releases instead of 30, looking
through 30 only when those 10 have nothing for the board (two requests then). `firmware check --refresh`
(D-Bus `CheckFirmwareForce`) skips the cache; `firmware update` and the daily connect-time notification
always ask afresh. A refresh that fails (GitHub answers 403 once the hourly budget is used up) keeps
answering a good result that is still fresh instead of an error. A new flash changes the running version, so the next check after it is fresh too.

## Implemented — to be tested

Implemented but not yet fully verified on hardware — each entry's note says what's confirmed and
what's still pending. Test plan: [TESTING.md](../TESTING.md).

### libpebble3 on current upstream

The libpebble3 fork sits on upstream `coredevices/libpebble3` `de47e36f` (fork branch
`stoandl-bump-de47e36f`, merged 2026-11-26 onto `ec2156cc`, which had been in daily use since 2026-10-20).
The database moves from schema 49 to 54 on the first start, one way, so take a `stoandl backup` before
upgrading ([TESTING.md 5.77a](../TESTING.md)). Upstream dropped its JVM support and hid most of its API; the
fork keeps the JVM target, and the daemon now builds libpebble3 through one host object instead of
overriding parts of it (nothing a user sets changes). Offline everything passes; the hardware rows are _to be
tested_ ([TESTING.md §5.77](../TESTING.md)). What changes for a user:

- **New:** find my phone from the watch, the PKJS `WebSocket`, audio streams and permission queries for
  watchapps, several language packs on one watch (entries below).
- **Health profile:** a `birth_year` key next to `age` (setting one sets the other). `max_hr` is read-only:
  libpebble3 derives it from the age (220 − age, at least the elevated heart rate) and the watch got that value
  whatever was set, so `health profile set max_hr` now refuses another value and the GUI shows it read-only
  ([TESTING 5.77p](../TESTING.md)).
- **Watch settings:** 22 new watch prefs from PebbleOS 4.42–4.48 (dark mode and its schedule, the theme
  highlight colour, the speaker, touch, the temperature unit, hourly and disconnect vibes, notification grouping
  and more), in new sections Theme, Speaker and Health. The `language` pref is gone: a pack sets the language,
  and a `watch.language` line logs "Unknown watch pref" and is ignored ([TESTING 5.77n, 5.77q](../TESTING.md)).
- **Quick Launch:** the firmware's Clear Notifications action (PebbleOS 4.38+), in the order libpebble3 lists
  the actions ([TESTING 5.77r](../TESTING.md)).
- **Firmware:** a downgrade through recovery is now libpebble3's to finish; stoandl reports it and refuses
  another flash meanwhile ([TESTING 5.11e, 5.77h](../TESTING.md)).
- **Plugins:** the Weather plugin follows the official `weather/location` contract (`weather/hour` is gone);
  an extension's `launchApp` of a Quick Launch action goes only to a watch that has it
  ([TESTING 5.77t](../TESTING.md)). The Phone plugin follows NetworkManager by signal instead of reading it
  every minute ([TESTING 5.77u](../TESTING.md)).
- **Egress:** upstream's new prefetch of locker app updates is pinned off; nothing new is contacted
  ([TESTING 5.77v](../TESTING.md)).
- **Startup:** if libpebble3 never lists the known watches, the daemon exits after 2 minutes so systemd
  restarts it ([TESTING 5.77w](../TESTING.md)).

### Find my phone

The watch can ring this device: libpebble3's built-in **Find My Phone** plugin (a watchapp subscribing to
`phone/find_my_phone`, or its `ring`/`stop` actions) plays the freedesktop `alarm-clock-elapsed` sound (else
`phone-incoming-call`) in a loop through `pw-play` or `paplay`, and shows a "Find my phone" desktop
notification with **Stop**. It stops when the watch or the plugin says so, on Stop, or after 60 s. No egress
and no new setting; it is there while `plugins.enabled` is on, like every plugin
([plugins.md](plugins.md)). Try it with `stoandl plugins invoke "Find My Phone" ring`. Off-device:
`FindMyPhoneRingerTest`. _Implemented — to be tested ([TESTING.md 5.77s](../TESTING.md))._ (The findphone extension
([extensions.md](extensions.md)) still works and needs no plugins.)

### PKJS: WebSocket, audio streams, permission queries

Watchapp companion JS gets three APIs the official app's PKJS has: `WebSocket` (on libpebble3's shared client;
while plugins are on, refused for a host the app's network permission doesn't cover, and counted in its data
usage), the watch microphone's audio as `audiostream` events (while plugins are on and the app holds the microphone
permission; the daemon's libspeex decodes it), and
`Pebble.permissions.query()`, which answers without asking. Every XHR redirect hop is now checked against the
app's network permission before it is followed, and https→http is never followed. Off-device: fork
`GraalJsRunnerTest`. _Implemented — to be tested ([TESTING.md 5.77j–l](../TESTING.md))._

### Several language packs on the watch

PebbleOS 4.42 keeps up to four packs and switches between them. `stoandl language installed` lists them
(built-in English first, the active one marked), `language use <slot|locale|name|english>` switches, and
`language remove <slot|locale|name>` frees a slot (removing the active pack puts the watch on English). An
install adds a pack to a free slot and is refused when all four are in use (a catalog install also when
the locale is already there).
On older firmware the watch holds one pack, which an install replaces, as before. A second install while one
runs answers `busy:`, and an install ends `done:` only when the watch is on the new pack. D-Bus
`LanguagePacks`/`UseLanguage`/`RemoveLanguage` ([dbus-interface.md](dbus-interface.md)); the GUI has no slot
list yet. Off-device: `LanguageControlStatusTest`. _Implemented — to be tested
([TESTING.md 5.77n–o](../TESTING.md))._

### Reply to desktop notifications

On Plasma with the `InvokeReply` patch (plasma-workspace, submitted upstream; carried on the phone as
`plasma-workspace` 99996.7.5-r100 + `plasma-mobile` 6.7.5-r101), a notification whose app offers an
inline reply — NeoChat, Kaidan, Telegram, Nheko, Ruqola, KDE Connect mirrors — gets a **Reply** action on
the watch with the canned list from `notification.canned_replies` (the watch also offers emoji, and
voice on watches with a microphone). The chosen text goes to the app exactly as if typed into the
popup: stoandl asks plasmashell to send it (`org.kde.NotificationManager.InvokeReply`), because apps
only accept a reply from the notification server itself. stoandl probes the server for that method at
start and after every restart, so without the patch no Reply action appears. The app's other actions
(e.g. Telegram's *Mark as read*) appear on the wrist too, up to three, through the `InvokeAction`
method every Plasma since 5.19 has. Replies also go out while the phone is locked — answering from the
wrist is the point. The watch shows *Sent* when plasmashell accepted it and *Failed* otherwise
(notification already gone, server restarted). Not on GNOME: GNOME Shell has no inline replies at all,
so reply there per channel (the [Matrix extension](../examples/extensions/matrix)). _To be tested_
([TESTING.md §5.34](../TESTING.md)).

_Implemented — to be tested ([TESTING.md §5.62](../TESTING.md)):_ a canned reply may contain a comma.
List settings keep `,` as the separator and take `\,` for a comma inside an item (`\\` for a
backslash; older files read unchanged), in `stoandl.conf`, `stoandl daemon set` and over D-Bus. The GUI
edits every list setting (canned replies, dialer apps, contact files, weather locations) as one row per
item, with add, remove and move up/down.

### Open a notification's app from the wrist

A desktop notification that opens its app when clicked (its `default` action — NeoChat, Telegram, a
browser's web notification) gets an **Open** action on the watch. Choosing it opens the app as a click on
the popup would: stoandl asks plasmashell to invoke the action (`org.kde.NotificationManager.InvokeAction(id,
"default")`, the same method the app's other wrist actions use, in every Plasma since 5.19), and the watch
shows *Done*. Toggle: `notification.open_action` (default on; the action only appears when the
notification has a click action and the server supports it). On Wayland the app gets no activation token
this way, so KWin may only flag it as wanting attention instead of raising it, and on a locked phone it opens
behind the lock screen. Not on GNOME: GNOME Shell offers no method for another program to click a
notification, and `ActionInvoked` is only accepted from the notification server, so the action never
appears there; nor for portal/GTK notifications. Off-device: `DesktopNotifOwnerTest`.
_Implemented — to be tested ([TESTING.md §5.52](../TESTING.md))._

### Notifications from sandboxed and GTK apps

Two kinds of notification never reach `org.freedesktop.Notifications.Notify`, so the watch never saw
them: on Plasma ≥ 6.7 a sandboxed (Flatpak) app's portal notification is drawn by plasmashell itself
(`plasmanotify`), and on GNOME every GApplication app — and every portal notification — goes to
`org.gtk.Notifications`. stoandl now also watches those calls (passively, as before). A notification
seen twice on its way (GNOME forwards portal notifications to `org.gtk.Notifications`; Plasma ≤ 6.6
turns them into a `Notify`) is sent once. Their app name comes from the app's `.desktop` file, so they
share a per-app mute entry with the same app's `Notify` notifications. They have no reply or actions on
the wrist; Dismiss works. And when the notification server restarts (plasmashell crash, GNOME Shell
restart), or appears only after stoandl started (every boot on a phone), the monitor follows it without
going silent until stoandl's own restart, and forgets the old ids, so a wrist action can't hit a new
notification that reuses one. _To be tested_ ([TESTING.md §5.34, §5.36](../TESTING.md)).

### Notification layout: app name and markup

The watch draws a notification as the title (bold), the subtitle (the largest bold text on the card), a
small line, then the body; the phone only chooses which text goes where. stoandl used to put the app
name in the subtitle, so every NeoChat message carried a large bold "NeoChat" above the text. Now
`notification.app_name` decides: a small line under the title (`small`, the default), the subtitle
(`subtitle`, the old look) or nothing (`off`). A source's own subtitle (the Matrix extension's room)
keeps the subtitle, and the app name is left out when it would repeat the title. Each app can override
it (`stoandl notif style <app> --app-name …`, or the app's notification settings in either GUI), and
stoandl's own firmware-update notification follows the global setting. Without a subtitle the watch's
notification list also shows a preview of the message instead of the app name.

Desktop notification bodies are markup, so `&`, `<` and `>` used to arrive as `&amp;`, `&lt;` and
`&gt;` (NeoChat escapes every message), and other apps' `<b>` or links arrived as raw tags. stoandl now
turns a body the desktop draws as markup into plain text first: every `Notify` body, and a portal one on
Plasma ≤ 6.6 (xdg-desktop-portal-kde re-posts it through `Notify`). Other portal and GTK bodies are plain
text, so a Flatpak NeoChat, which escapes them anyway, still shows `&amp;` on Plasma ≥ 6.7 and GNOME, as
the desktop does. See
[configuration.md](configuration.md#per-app-notification-settings). _To be tested_
([TESTING.md §5.39](../TESTING.md)).

### Phone call notifications

ModemManager (system bus) → `currentCall` → native Pebble call screen; watch Answer/Hangup drive
`Accept()` / `Hangup()`. Automatic once a modem is present. _To be tested. [TESTING.md §5](../TESTING.md)._

### Missed-call sync

Unanswered incoming calls become timeline pins via libpebble3's `MissedCallSyncer` (backed by an
in-memory `SystemCallLog` fed by the call monitor). _To be tested._

### Caller-ID resolution

DE-agnostic vCard lookup (`contacts.vcard_paths`, suffix-matched), with the dialer's own notification
title as fallback. See [configuration.md](configuration.md#caller-id-resolution). _To be tested._

### Vibration test and custom vibrations

`stoandl notif vibe-test <name|ms,ms,…|default>` and a **Test** button next to each vibration in the
Kirigami app dialog buzz the connected watch with that pattern, as the official app's test does: a short
"Vibration test" notification sent outside the notification pipeline (no mute, filter or forwarding
switch in the way). `stoandl notif vibe-add <name> <ms,ms,…>` / `vibe-remove` keep named custom
vibrations in libpebble3's pattern table next to the built-ins, so per-app `--vibe`, the GUI picker, an
extension's `vibe` and `calendar.reminder_vibe` all take them; `stoandl notif vibes` lists both kinds.
Firmware before 4.9.97 can't play custom vibrations, and the test says so. An on-demand action with no
new config key. See [configuration.md](configuration.md#per-app-notification-settings). _To be tested_
([TESTING.md §5.18a–5.18d](../TESTING.md)).

### Notification filtering

Free-text allow/block **filters** (matched live against app name + title + body) mute or gate
individual conversations regardless of app; `call.dialer_apps` holds back the dialer's redundant call
notification while the call is up. Notifications with the `transient` hint aren't forwarded unless an
allow filter matches. (Per-app muting is a separate, hardware-verified feature above.)

```sh
stoandl notif filter add <regex> block|allow   # matched against app name + title + body
```

See [configuration.md](configuration.md#per-app-notification-settings). _To be tested._

### Forward only while the screen is off or locked

`notification.only_when_locked` (default off, so everything is forwarded as before) holds notifications
back while you're using the phone or computer: the display is on (DRM DPMS) **and** your graphical session
is unlocked (logind `LockedHint`, set by KScreenLocker and GNOME Shell). This is the official app's "Always
send notifications" switched off. Critical-urgency notifications (alarms, a dialer's incoming call), allow-filter
matches and `stoandl notif test` still reach the watch, and calls always ring through the native call screen.
If stoandl can't tell, for example with no graphical logind session or logind not answering within 1 s, it
forwards. Toggle it in the GUI's Notifications settings or with
`stoandl daemon set notification.only_when_locked true`. Off-device: `SessionLockTest`.
_Implemented — to be tested ([TESTING.md §5.51](../TESTING.md))._

### Watch fully charged alert

When a connected watch on its charger reaches 100 %, stoandl says so on the desktop and on the watch
(*Watch fully charged — name is fully charged*), as the official app does. Once per charge: it re-arms when
the level falls to 97 % or below, so topping up doesn't repeat it, and a watch that is already full when it
connects doesn't alert. BLE watches report their level over GATT; Classic-era watches have no GATT battery,
so theirs comes from the hourly analytics heartbeat (whether or not `battery.heartbeat` stores it) and can
be up to an hour late. Toggle `alerts.battery_full` (default on, under `alerts.enabled`); the watch copy
is a `stoandl` notification, so muting the `stoandl` app keeps it on the desktop only. Off-device:
`BatteryFullAlertTest`. _Implemented — to be tested ([TESTING.md §5.53](../TESTING.md))._

### Scan failures while pairing

When a pairing window's scan can't start (BlueZ refuses `StartDiscovery`: the adapter isn't ready, LE or
BR/EDR is switched off on the controller), `stoandl watch pair` and the GUI pair dialog say so —
`Scan failed, retrying — Failed to scan for watches: Bluetooth discovery didn't start (…)` — instead of
searching silently until the timeout, and a timed-out window names the failure. As in the official app, which
shows libpebble3's user-facing errors as a toast, the failure also raises a desktop alert, once per window and
only when it repeats: toggle `alerts.scan_failed` (default on, under `alerts.enabled`). Every libpebble3
user-facing error (scan, sideload, locker) is logged at WARN. Off-device: `ScanFailuresTest`, fork
`ScanningTest` / `BlueZDiscoveryTest`. _Implemented — to be tested ([TESTING.md §5.57](../TESTING.md))._

### HR during activities, podcast seek, face order

Three official-app switches. The health profile gains `hrm_activity` ("HR During Activities": continuous heart
rate during detected walks and runs, at some battery cost; firmware 4.38+ on a watch with an HRM), in
`stoandl health profile set hrm_activity on|off` and the GUI's Health profile page. `music.seek_when_available`
(default on, as before) turns off the ±15 s seek for podcast and audiobook players, so next/previous always
skip. `apps.order_faces_by_last_used` (default off, so the order you set stays; the official app turns it on at
first run) moves a watchface to the top of the locker order when it becomes the active face. Both keys are
live. Off-device: `HealthProfileTest`, `MprisSkipTest`, `LibPebbleConfigProjectionTest`.
_Implemented — to be tested ([TESTING.md §5.54](../TESTING.md))._

### 5-minute heart-rate interval

PebbleOS 4.39.0 added a 5-minute heart-rate measurement interval (code 4 in its `HRMonitoringInterval`).
libpebble3 knew only codes 0–3 and threw on any other, so when the watch synced 5 minutes stoandl answered
"invalid data", kept the old interval and later wrote it back to the watch. The fork now names 5 minutes, and
it keeps any interval code a newer firmware adds as is (it is sent back unchanged until someone picks a
different interval) instead of throwing. `stoandl health profile set hrm_interval 5min` and the GUI's Health
profile page offer it; with no watch connected, or one older than 4.39.0, the daemon refuses it and says why,
and `GetHealthProfile` leaves it out of the intervals it advertises, so the GUI doesn't offer it.
Off-device: `HealthProfileTest`, fork `HrmPreferencesValueTest`.
_Implemented — to be tested ([TESTING.md §5.63](../TESTING.md))._

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
GPS + Wi-Fi/cell) with no GPS-specific code. The GPS runs only for apps that ask for high accuracy, and
GeoClue stops as soon as a request is answered; only `watchPosition` ticks at most a minute apart keep it
between ticks. A small fork addition makes the GraalJS PKJS runtime
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

Secret settings: a schema field marked `secret` is kept in the keyring (or the 0600 secrets file), shown
in the GUI form as a password field with *Clear*, and handed only to that extension; a plaintext one in
its `config` file is moved out. A `required` field keeps the extension stopped until it is set. Extensions
can also hold off a suspend for up to a minute (`keepAwake`). _To be tested ([TESTING.md §5.49](../TESTING.md))._

_Implemented — to be tested ([TESTING.md §5.71](../TESTING.md)):_ `stoandl ext config <name> [<key>
[<value>|--unset]]` lists and sets an extension's settings from the CLI (a secret is prompted without
echo, never taken from the command line); `stoandl ext install <dir>` installs straight from an unpacked
directory (staged, so a failed install keeps the old version running); the "needs setup" alert says where to
set it up (the GUI's extension settings, or `stoandl ext config`), and an extension quarantined after 5
rapid failures raises a "<name> stopped" alert with its last stderr line and a *Restart* button. `stoandl
ext list` and the GUI show that last error line; a schema field can carry a `description` and `help`.
Extensions get `synthesize` (text to speech, below) and an immediate `-32601` for a method the daemon
doesn't know. A reusable watch speaker receiver ships in
[`examples/extensions/watchlib/`](../examples/extensions/watchlib/) ([speaker-stream.md](speaker-stream.md)).

### Phones that deep-sleep (suspend-aware daemon)

For a phone that suspends whenever the screen is off: a logind delay lock makes a suspend wait until
a notification in flight has reached the watch; weather/calendar/firmware checks count real time and
run right after a resume when overdue; pairing-window scans run with the display off too, but only
while the phone is awake (stopped for every suspend); a reconnecting watch catches up on the
notifications it missed ([below](#missed-notification-catch-up)); an optional datalog pause while the
display is off (health and battery data then arrive when the screen is next on, when stoandl asks the
watch for the backlog). Groundwork for keeping the watch link across suspend ("Mode B", needs host kernel
work). _Partially verified on hardware (OnePlus 6, postmarketOS, 2026-09-29): in Mode A, notifications
while the phone is awake and the logind delay lock work. Delivery of notifications that arrive with a
push wake (catch-up after reconnect) is fixed and to be tested. Mode B is untested
([TESTING.md §5.32](../TESTING.md))._
Details: [deep-sleep.md](deep-sleep.md).

_Implemented — to be tested ([TESTING.md §5.37](../TESTING.md)):_ the Quiet Time pause
(`power.quiet_time_link_off`, GUI *Pause watch connection during Quiet Time*, off by default). During the
watch's scheduled Quiet Time, with the display off for 10 min, the watch link is paused, so the phone's
radio doesn't serve an idle link all night. It comes back when the window ends or the display comes on,
and the watch then gets the notifications from the pause. It stays on if calls may interrupt Quiet Time.

No idle polling: while the phone is awake and nothing changes, stoandl no longer wakes up every few
seconds. The display state is sampled only while the datalog pause or the Quiet Time link pause follows it,
and the D-Bus listeners (notification monitor, ModemManager, MPRIS) wait for their connection to end
instead of polling it, so an idle daemon on the OnePlus 6 should drop from about 5 wakeups/s to near 0.
_To be tested ([TESTING.md §5.73](../TESTING.md))._

Faster BLE reconnect: the BlueZ connector now notices a link that comes up while it waits to retry,
instead of only when its 5 s wait ends. Before, a reconnect was usually noticed only at the end of that
wait: 5.05 s after `connect() starting` after a resume on the phone, and 5.1–5.7 s (sometimes ~10.5 s)
on the desktop. After a Mode A resume the watch should be back about 5 s sooner. The ~2.9 s from resume
to `connect() starting` is unchanged: bluetoothd's drop delivery plus libpebble3's 2.5 s reconnect
settle. The log line `connected and services resolved (N ms after connect())` shows the time. _To be
tested ([TESTING.md §5.32h](../TESTING.md))._

### Pictures in notifications and album art

On watches that show pictures (Pebble Time 2 / Round 2, emery and gabbro), stoandl serves them over the
watch's imaging endpoint, as the official app does:

- **Notification pictures** (`notification.images`, default on): a photo an extension attaches (the Matrix
  extension sends image messages' thumbnails), the first image file a desktop notification links in
  `x-kde-urls` (Spectacle screenshots), a web notification's image on Plasma. Sender avatars are not sent.
  Firmware 4.35+. Pictures are kept (at most 32, ≤300 px) in `~/.cache/stoandl/images/notifications` and
  deleted with the notification.
- **Per-app pictures** (`stoandl notif images <app> off`, or **Send images** in the app's dialog on the
  GUI's Alerts page): one app's pictures stay off while `notification.images` is on, the official app's
  per-app switch (libpebble3's `sendImages` column). `on`/`default` follows `notification.images` again.
  Needs `notification.per_app`. _To be tested_: [TESTING §5.45j](../TESTING.md).
- **Album art** in the Music app: the cover the player publishes (`mpris:artUrl` as a local file or a
  `data:` URI: Elisa, Firefox, Chromium, mpv-mpris, Lollypop, …), never downloaded, so players that only
  give a web URL (Spotify) show none. Switched on the watch (Settings → Music → album art), firmware 4.36+.

Decoding uses Java's ImageIO: PNG, GIF and BMP everywhere, JPEG with the full JRE (`openjdk25-jre` on
Alpine/postmarketOS, which the package depends on; the daemon logs `Images: png gif bmp jpeg` at start).
_To be tested_: [TESTING §5.45](../TESTING.md).

### Voice dictation

The watch's microphone works as on the official app: Voice in a notification's Reply menu, and the
dictation API watchapps use. stoandl decodes the watch's Speex audio (libspeex) and hands it to a
speech-to-text backend of your choice (`voice.backend`, off by default): a **local engine** run as a
command (whisper.cpp's `whisper-cli`, `parakeet-cli`, or any script printing the text; no audio leaves
the machine), or an **OpenAI-compatible HTTP service** (a `whisper-server` on the LAN, or a hosted one with
an API key kept in the keyring). The text goes back to the watch within its 15 s window and from there to
the reply path, like a canned reply (Plasma with InvokeReply, extensions' `onReply`). `stoandl voice test
<file>` transcribes a file through the same backend. Extensions get `transcribe`: the Matrix extension
puts the text of voice notes into their notification. Engines and models for old hardware:
[voice.md](voice.md). _To be tested_: [TESTING §5.46](../TESTING.md).

_Implemented — to be tested ([TESTING §5.46l](../TESTING.md)):_ a fallback engine (`voice.fallback_backend`,
none by default) answers when the first one isn't ready, fails, hears nothing or runs out of time. As in the
official app, the first engine then gets 7 of the 14 seconds the watch waits and the fallback the rest; file
transcriptions (`stoandl voice test`, voice notes) fall back too.

_Implemented — to be tested ([TESTING §5.46m–o](../TESTING.md)):_ NVIDIA Canary 180M flash
(`canary-180m`, run by transcribe.cpp's `transcribe-cli`) as a local model for English, German, Spanish and
French: 3–4x faster than Parakeet on a phone, in the language set in `voice.language`, which it requires
(with `auto` it isn't used: told a wrong language, Canary translates). [voice.md](voice.md#canary-transcribecpp).

### Weather for classic watchfaces

Hundreds of 2014–2016 watchfaces fetch weather from their PebbleKit JS: Yahoo's YQL weather API, shut
down in 2019, or OpenWeatherMap 2.5 with an embedded key that has since been revoked. On their own they
show "--". As the official app does, stoandl answers these requests itself (libpebble3's PKJS HTTP
interceptor hook), from Open-Meteo instead of Core's weather service: Yahoo's place and forecast queries
in Yahoo's JSON shape, in °C or °F as asked, with stoandl-issued place ids for the follow-up query; and
OpenWeatherMap's current-weather call, which still goes to OWM first with the app's own key and is
answered from Open-Meteo only when OWM refuses the key (in Kelvin, metric or imperial as asked). Place
names come from `weather.locations` within 10 km, or Nominatim with `weather.reverse_geocode`. Off by
default (`weather.intercept_pkjs`): it sends the coordinates or place a watchface asks about to a web
service; most of these watchfaces also need `geolocation.enabled`. Ported from the official app (GPLv3).
_To be tested — needs a watch and one of these watchfaces. [TESTING §5.47](../TESTING.md)._

### Weather city search

Add a weather location by name instead of looking up its coordinates: `stoandl weather search Lyon` lists
the matches from Open-Meteo's geocoder (most populous first, with region, country and coordinates), and
`--add [n]` stores match *n* (default 1) in `weather.locations`, applied live; `--as <label>` names it on the
watch. A location of the same name is replaced, so re-adding moves it. The GUI has the same search under
Settings → Weather (*Find a location*; Enter or **Search** looks up, **Add** stores).
It is a web request, so it is off by default (`weather.place_search`) and refused while weather sync is
off; only the typed name is sent, and only when you search. D-Bus `SearchWeatherPlace` /
`AddWeatherLocation`. Off-device: `WeatherPlacesTest`, `WeatherSearchArgsTest`.
_Implemented — to be tested ([TESTING.md §4.18](../TESTING.md))._

### Plugins

The official app's plugin system (phone-side data sources and actions for watchfaces and watchapps, with
app permissions), on the computer: built-in Weather, Music, Calendar, Phone (battery, Wi-Fi/cellular),
Watch Settings, Watch State and Find My Phone ([Find my phone](#find-my-phone)); the plugin a `.pbw` ships, run in GraalJS; and extensions that serve
sources over their pipe. Permission requests are asked on the watch and the desktop (Allow / Deny), and
answered in the GUI or with `stoandl perms`; plugins' secure storage is in the keyring. A `.pbw`'s own
settings page talks to its plugin while open, through the daemon's page server. Off by default
(`plugins.enabled`). OAuth sign-in (store broker) isn't available. [plugins.md](plugins.md). _To be
tested_: [TESTING §5.48](../TESTING.md).

### App permissions: effective status, closed apps, Ask later

`stoandl perms list <app>` and the GUI show what a permission means now, as the official app does
(libpebble3 `appPermissionStatuses`): `granted` / `denied`, **allowed automatically** (undecided, but
`plugins.permission_policy` lets it through), **waiting** (the app must ask) or **blocked** (global
internet for an app that declares its domains). `stoandl perms` and the GUI
banners now include requests from apps that have closed since they asked (they used to be invisible and the
app stayed refused). **Later** (`perms later <app> [permission]`, the banner's and the dialog's Later)
stops the prompts and banners for a request without deciding it (libpebble3 `markPermissionRequestsSeen`);
`reset` makes it ask again. In the GUI each app row has a **Permissions** button (badged while a request
waits) that opens the app's permissions: status, last use, Allow / Deny, a switch for decided ones, Reset.
No new settings: nothing is asked or sent that wasn't before — closed apps are listed, not prompted, and
putting off is always the user's own action. Off-device: `PluginsTest`; GUI smoke (`stoandl-smoke: perms`).
_Implemented — to be tested ([TESTING.md §5.48m–q](../TESTING.md))._

### Ask Claude (assistant extension)

Ask Claude a question from the wrist with your own Anthropic API key: reply to an "Ask Claude"
notification by voice or with a preset, and the answer comes back as a notification card (paged, with
Reply for follow-ups and Retry on errors); or open the bundled **Assistant** watchapp, which dictates at
once and streams the answer in. A Go extension in
[`examples/extensions/assistant/`](../examples/extensions/assistant/) on the official Anthropic Go SDK;
the key lives in the keyring, conversations persist 24 h in a 0600 file (`keep_history`), web search is
a separate opt-in, and `stoandl-assistant bench` compares the three models' latency and cost. No fork
change. Guide: [assistant.md](assistant.md). _Built and tested offline (a fake streaming endpoint drives
the request, notification and watchapp paths); not yet run against the real API or on a watch
([TESTING.md §5.50](../TESTING.md))._

_Implemented — to be tested ([TESTING.md §5.69](../TESTING.md)):_ the Assistant app **reads answers aloud**
on the watch speaker (Pebble Time 2 / 2 Duo) through stoandl's text to speech: `speak = always` by default,
inert until `tts.backend` is set; Hold Down replays or stops an answer; Select, Back, an incoming call or
notification stop it; quiet in Quiet Time (Hold Down still plays). Speech never plays during dictation
(the receiver is held off, so the microphone doesn't hear the speaker), and the watch drops a stream that
stalls for 35 s. The sleep hold ("reading an answer aloud") lasts only while it reads. German and English
answers pick a voice of that language. [assistant.md → Reading answers aloud](assistant.md#reading-answers-aloud).

_Implemented — to be tested ([TESTING.md §5.76](../TESTING.md)):_ **to-dos by voice.** "Don't let me forget
to call the garage tomorrow at five" becomes a to-do in your list, with the due time and a reminder, in the
same single request (Claude's `add_todo` call carries the confirmation it speaks). The daemon writes it to
`calendar.todos` (a CalDAV list of an existing calendar account, or a local `.ics`/vdir) through the new
extension method `addTodo`, and keeps a to-do the server can't take now until a later calendar sync sends
it. Also `stoandl calendar todo`. [assistant.md → To-dos](assistant.md#to-dos).

### Missed-notification catch-up

A reconnecting watch also gets the notifications posted while it was disconnected. This covers Mode A
push wakes on a phone that drops the link on suspend, and a watch that was out of range. It is bounded
by `notification.catch_up_minutes` (default 60; 0 = upstream behaviour; a change applies from the next
connection, no restart), and never reaches back past the
daemon's start or the watch's pairing. Nothing the watch already has is re-sent, and stoandl's own
alerts about a watch that can't connect stay on the desktop. Implemented in the libpebble3 fork
(`NotificationCatchUp`, `NotificationConfig.missedNotificationCatchUpMs`, default 0 there); the fork's
jvmTest covers the threshold rule. See
[configuration.md → Missed notifications](configuration.md#missed-notifications-catch-up).
_Implemented — to be tested ([TESTING.md §5.32b](../TESTING.md))._

### musl (postmarketOS / Alpine): bundled SQLite

libpebble3's SQLite driver ships a JNI library built for glibc that calls `__isnan`, which musl lacks.
The JVM crashed at the first floating-point value in the database, which was the health sync right
after pairing. On musl, stoandl now loads a tiny built-in `__isnan` shim
([tools/isnan-shim](../tools/isnan-shim/)) into the global symbol namespace before the database opens,
so no `LD_PRELOAD` is needed. If the cache can't hold it, a temp copy is loaded instead; only if that
fails too is there an `SQLite: could not provide __isnan` ERROR, naming a ready `LD_PRELOAD` fix. The
SQLite library is also extracted once to `~/.cache/stoandl/native/` instead of a new
`/tmp/androidx_sqliteJni*.tmp` on every start. _Built; verified off-device on a real musl JVM (Alpine
openjdk25: x86_64 natively, aarch64 under qemu-user). Without the fix it reproduces the phone's exact
`avgFinalize+0xc4` crash; with it `AVG()` works and the daemon starts cleanly. Not yet run on the phone
([TESTING.md §5.32a](../TESTING.md))._

### Manual disconnect

`stoandl watch disconnect` (GUI: Watch page → *Disconnect*) drops the watch link and keeps every watch
disconnected until `stoandl watch connect` (or `stoandl watch connect <name>` to resume on a particular
watch; GUI: the *Watch disconnected* banner's *Connect*). A pairing window (`watch pair`, `watch repair`) also
ends it, and it is refused while a firmware or language update runs. It pauses the link in memory (libpebble3's `holdDisconnected`) rather than clearing the watch's
persisted connection goal, so nothing is left that would keep a later launch from reconnecting on its own.
With `connection.disconnect_persists` (default on, as in the official app) the pause also lasts across a
daemon restart; set it off and a restart reconnects. While paused nothing reaches the watch (calls neither);
notifications posted meanwhile arrive after `connect` (paused time doesn't count towards the catch-up window).

It applies to every watch: stoandl links one watch at a time, and a paused link moves to no other watch.
It stacks with the Quiet Time pause (`power.quiet_time_link_off`): the link is
off while either holds it, so Quiet Time ending doesn't undo a manual disconnect, and `connect` inside the
Quiet Time window takes effect when the window ends (Connect says so). `stoandl watch list` and the GUI's
Watch page show either pause (D-Bus `LinkPause`). _Implemented — to be tested
([TESTING.md §5.55](../TESTING.md))._

### Multiple concurrent Pebble watches

`libPebble.watches` is a `List<PebbleDevice>`; scan, auto-connect, notifications, and calls all iterate
the full list, so the core architecture is multi-watch by design — but this is untested and needs two
Pebbles to verify. _To be tested._ Known gaps before multi-watch is fully usable:

- _BlueZ GATT server multi-client: whether BlueZ allows two Pebbles as simultaneous GATT clients to
  the same application is untested._
- _CLI commands (`launch`, `sideload`, `remove`, `settings`) target the single connected watch with no
  disambiguation flag (`--watch <name|address>`). With two watches the behaviour is unspecified._
- _Pairing a second watch while one is connected (or, for a Classic watch, away) is implemented, to be
  tested ([TESTING.md §5.74](../TESTING.md)); see [BLE pairing / bonding](#ble-pairing--bonding)._
- _A second **BLE** watch still can't be paired while the first is armed but away: the armed watch counts
  as connecting, and an LE scan would disturb its connect (they share BlueZ's discovery slot), so the scan
  stays paused and `watch pair` says `BLE scan paused — <watch> is connecting`. Bring the first watch in
  range first; a classic watch pairs via the BR/EDR inquiry, which no
  longer waits for an arming BLE watch._

### Settings: one page per topic, help on every row

The GUI's settings are rebuilt around the daemon's schema: a landing list with *Watch preferences*, ten
topic pages (Notifications, Weather, Calendar, Music, Voice, Health & battery, Apps & plugins, Connection &
power, Firmware & language, Developer), *Backup & restore*, *Diagnostics & developer* and *About* (both
versions and a copyable support line). One control per key: the separate Sync screen, the second calendar
*discover* switch and the Alerts app-name combo are gone; each service master is the first row of its page,
and forwarding off shows as a "Forwarding is off [Turn on]" banner on the Alerts and Watch tabs. Every row
has a help button (?) with what the one-line description leaves out — also on long-press, right-click and
F1 — and says what it contacts when it makes stoandl reach a server. A row that only matters while another
setting has a value is locked (its control, not its help) and says "· Used when …"; `Advanced` sections
are collapsed. A search covers labels, descriptions, help, keys and watch preferences. The daemon side is
`GetConfigSchema`'s columns 12–16 (`page`, `help`, `requires`, `default`, `egress`) and the `ListWatchPrefs`
columns 9–11; the CLI's `stoandl daemon list` groups by page › section and `daemon get <key>` prints the
help, default, "used when" and "contacts" lines. Combo labels were reworded (tokens unchanged, see
[configuration.md](configuration.md)). _Implemented — to be tested ([TESTING.md §5.66, §5.67](../TESTING.md))._

### Settings apply live: hand edits, ConfigChanged, Restart from the GUI

Every setting applies the same way whichever way it is changed: the GUI, `stoandl daemon set`,
`stoandl sync enable|disable` (now an alias of the key), the daemon itself (a finished voice download), or
**a hand edit of `stoandl.conf`**, which the daemon picks up half a second after the file settles (an
editor's save, `sed -i`, a restore). Each changed key is logged and announced with the D-Bus signal
`ConfigChanged`, so an open GUI page updates at once. `notification.catch_up_minutes` is live now too
(from the next connection), which leaves five keys that need a restart; for those the GUI says "(after
restart)" and offers *Restart now*, which restarts stoandl through systemd's user manager (D-Bus `Restart`,
also `stoandl daemon restart`; it works from the Flatpak, and refuses during a pairing window or an
update). _Implemented — to be tested ([TESTING.md §5.66, §5.73](../TESTING.md))._

### Watch preferences: sections, help, reset

Watch preferences come grouped the way the GUI shows them (Quick launch, Backlight & display, Theme,
Notifications & timeline, Quiet Time, Vibration, Speaker, Music, Time & units, Health, Motion & menus,
Advanced), with help text
and "only matters when" rules from the daemon (the backlight timeout and intensity rows need the Advanced
backlight preset, except on firmware too old to have the preset). *Reset to defaults* (`stoandl settings
reset`, D-Bus `ResetWatchPrefs`) puts every changed preference back, pinned `watch.*` lines included. The
GUI shows the notification timeout in seconds. _Implemented — to be tested ([TESTING.md §5.66,
§5.67](../TESTING.md))._

### Text to speech

stoandl can read text aloud (`tts.backend`, off by default): **Piper** with a downloaded voice or
**espeak-ng** on this machine, a custom command, or an OpenAI-compatible speech server (opt-in egress, its
own API key). The assistant uses it to read answers on the watch speaker (above); any extension can, through
`synthesize`, which returns the audio already encoded for the watch speaker. `stoandl voice say <text>` and
the GUI's *Test voice* play on this device (`pw-play`, `paplay` or `aplay`); `stoandl voice stop` stops it.
`stoandl voice download piper-de-thorsten-low` (or `piper-en-kathleen-low`) fetches a voice and turns text
to speech on; with one German and one English voice installed, German and English text each get their own
voice. The text is never logged and the system stays awake while it speaks. Setup, voices and licences:
[voice.md → Text to speech](voice.md#text-to-speech). _Implemented — to be tested ([TESTING.md
§5.68](../TESTING.md))._

### CLI: `stoandl status`, exit codes, faster start

`stoandl status` shows the daemon, the watch (state, transport, battery), forwarding and the global mute,
the sync services, a firmware update a recent check found (no new request), running or finished watch
tasks, extensions, waiting permission requests, speech to text and text to speech, at a glance; it exits 0
with a watch connected, 1 without. Every command exits 0 done, 1 failed, 2 usage error, 3 daemon not
running, with one wording for D-Bus errors ("stoandl isn't running — systemctl --user start stoandl",
"stoandl is still starting", "versions differ", "didn't answer in time"); commands that wait on the watch
get 120 s instead of 15. The CLI logs to stderr only (never into the daemon's `/tmp/stoandl.log`), and its
JVM starts from a class-data archive it writes on the first run after an install (about 470 → 270 ms on
x86). `STOANDL_LOG=DEBUG` covers all of stoandl's own code. Packaging: one launcher (the JAR's manifest
grants native access, so no restricted-method warnings), a slimmer reproducible JAR without foreign
natives, an APK post-install message with the `systemctl --user enable --now stoandl` step, and
`install.sh --remote` copies an rsync delta. _Implemented — to be tested ([TESTING.md §5.70](../TESTING.md))._

### Daemon startup and watch tasks

The daemon takes its public D-Bus name only once it is ready (behind a private
`de.yoxcu.stoandl.Instance` guard that keeps a second daemon out), so a GUI that sees it appear can call
at once; the CLI and the GUI say "stoandl is starting" meanwhile. Sideload, screenshot, watch logs and core
dump run as daemon watch tasks (`TaskStatus`/`TaskChanged`), and the daemon serves D-Bus calls on 12 threads
instead of 4, so a slow transfer or firmware check no longer freezes the GUI's other calls. _Implemented —
to be tested ([TESTING.md §5.73](../TESTING.md))._

### GUI quality of life

On top of the settings pages: worded errors ("Your stoandl daemon is older than this app", "didn't answer
in time") and a daemon-down screen that tells not running from starting from crash-looping (with *Start
daemon* outside the Flatpak); long actions run without blocking the UI, with a thin progress bar;
pairing a second watch, *Re-pair* on a stuck watch, a Bluetooth Classic offer and a retry hint in the pair
dialog; a screenshot preview with *Open* / *Open folder*, and *Open folder* for watch logs and core dumps
(also from the Flatpak); per-app mute kinds and schedules in Alerts (temporary mutes only for an app that
forwards), the global mute as its own state with a Watch-tab chip; a custom vibration editor with *Test*;
Ping and a speech-to-text recording test; an extension's last error with a *Restart* button; installing a
`.pbl` language pack or an extension folder from a file picker; pull to refresh; *Sync now* (only services
with a source) apart from *Check for updates*; confirmations for what can't be undone and *Undo* for a
deleted filter; empty states that offer the one setting that fills them and say what it contacts; and a
layout that fits a 360 px phone. _Implemented — to be tested on hardware; smoke-tested against the mock
daemon ([TESTING.md §5.67, §5.75](../TESTING.md))._

## Not yet implemented

### Send text / reply

Reply to messages from the watch. On Plasma with the `InvokeReply` patch, replies to ordinary desktop
notifications now work ([above](#reply-to-desktop-notifications)); elsewhere a generic reply off the
notification bus is still impossible ([why](#why-generic-notification-reply-isnt-viable)). The path that
works everywhere, GNOME included, is **per-channel, bypassing the notification bus**: **Matrix** already ships as an extension
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

Moved to "Implemented — to be tested" (Voice dictation).
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

**Update 2026-09-30:** on Plasma it *is* viable after all — not by owning the name, but by asking the
owner. plasmashell's private `org.kde.NotificationManager` interface already lets any client invoke a
notification's actions (`InvokeAction`); a ~20-line plasma-workspace patch adds `InvokeReply`, and stoandl
uses it ([Reply to desktop notifications](#reply-to-desktop-notifications)). Two statements below were
wrong and are corrected: KDE Connect forwards desktop notifications with `BecomeMonitor` exactly like
stoandl and can reply only to notifications it posted itself; and on GNOME ≥ 42 `CloseNotification()`
is *not* callable on any notification — the relay only lets the posting client close a live one.
GNOME and the portal remain as described (no inline reply in GNOME Shell; no sandboxed app sends a
reply button through the portal yet). Research: the two reports behind this are summarised in memory
`notification-reply-parked`.

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
