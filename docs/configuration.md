# Configuration

stoandl reads an optional config file at:

```
$XDG_CONFIG_HOME/stoandl/stoandl.conf      # default: ~/.config/stoandl/stoandl.conf
```

A missing or unreadable file is fine — the daemon falls back to defaults.

**Hand-edits require a restart** (`systemctl --user restart stoandl`) — nothing watches the file. But
you rarely need to hand-edit: almost every key below is also settable at runtime, from the GUI
(Settings → Daemon configuration) or the CLI:

```sh
stoandl daemon list                    # every key, its current value, grouped
stoandl daemon get weather.interval
stoandl daemon set weather.interval 15
```

Those go through the daemon, which validates the value, writes it, reloads, and re-applies the affected
subsystem — no restart. Eight keys are the exception because they decide startup wiring
(`notification.sync_to_watch`, `notification.catch_up_minutes`, `classic.discover`, `datalog.enabled`,
`power.sleep_guard`, `power.sleep_guard_max_ms`, `ble.conn_params`, `ble.conn_params_fast`); they are
marked **Needs a daemon restart** below, the GUI says so on the row, and `stoandl daemon` prints
`(restart)` next to them.
[docs/settings-parity.md](settings-parity.md) records why, per key.

The service allows 5 starts within 5 minutes, and manual restarts count towards that. A 6th restart
inside the window leaves the daemon stopped. `systemctl --user reset-failed stoandl` clears the limit
(see [README → Logging & reporting bugs](../README.md#logging--reporting-bugs)).

Syntax is `key = value`, `#` starts a comment, and list values are comma-separated. A starter file
is shipped at [`packaging/stoandl.conf.example`](../packaging/stoandl.conf.example).

## Keys

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `notification.per_app` | bool | `true` | Track every observed desktop app in a per-app store and enforce its mute state host-side before sending (dropped before it crosses BLE). Exact-match, stateful, schedulable — managed at runtime with `stoandl notif` (see [Per-app notification settings](#per-app-notification-settings)). |
| `notification.default_mute` | string | `never` | Mute state for a newly observed app: `never` (deliver), `always` (mute), or the day-of-week schedules `weekdays` / `weekends`. |
| `notification.sync_to_watch` | bool | `false` | Sync the per-app list + mute states to the watch (libpebble3 `NotificationAppItem` → BlobDB). **Off by default** — current Core/PebbleOS firmware has no per-app notification UI on the watch, so the records surface nowhere; mute is enforced host-side regardless. Opt-in for firmware that does surface it. Watch-link only, no web egress. **Needs a daemon restart.** |
| `notification.forward` | bool | `true` | Master switch for forwarding desktop/extension notifications to the watch. Flipped live by the GUI's Alerts screen (`SetSyncEnabled("notifications", …)`) — when off, every notification is dropped host-side at the send choke point. Calls and firmware prompts use their own paths and are unaffected. |
| `notification.catch_up_minutes` | number | `10` | How far back (minutes) a reconnecting watch catches up on notifications it hasn't received — those posted while it was disconnected. Never older than the daemon's start or the watch's pairing; `0` = only notifications posted after the connection came up (libpebble3's default). 0–1440. **Needs a daemon restart.** See [Missed notifications](#missed-notifications-catch-up). |
| `notification.canned_replies` | list | `Ok, Yes, No, Call me, Call you later` | The watch's Reply list for desktop notifications that take a reply (Plasma with the `InvokeReply` patch, see [features.md → Reply to desktop notifications](features.md#reply-to-desktop-notifications)) and for extensions that don't send their own. Whole items are kept while the NUL-joined list fits the firmware's 512 bytes; empty = the firmware's list. A reply can't contain a comma. |
| `alerts.enabled` | bool | `true` | Master switch for the desktop alerts stoandl raises **about itself** (pairing trouble, a Bluetooth scan blocking reconnects, an extension needing setup) — as opposed to forwarded app notifications. Muting an alert silences the popup only; the condition is still logged at WARN, so it never hides a diagnosis. The firmware-update alert has its own key (`firmware.notify`) because it also drives a *watch* notification. |
| `alerts.pairing` | bool | `true` | Alert when a watch keeps connecting-then-dropping (unpaired on the watch) or its pairing was removed on this host — each with the action that fixes it (Re-pair / Pair). Without it a watch can silently stop reconnecting forever. |
| `alerts.bluetooth` | bool | `true` | Alert when another process' Bluetooth discovery is monopolising the adapter's scanner, which blocks the watch from reconnecting. |
| `alerts.extensions` | bool | `true` | Alert when an installed extension requires configuration before it can start. |
| `call.dialer_apps` | list | `calls` | Dialer apps, by app name or desktop-entry id (exact, case-insensitive). Their notifications are held back from the watch while a call is up (the native call screen replaces them); their other notifications, such as Missed call, still arrive. Their title is a fallback caller name. `calls` is GNOME Calls; Plasma Mobile's Phone app shows calls full-screen and needs no entry. Don't list Spacebar: it is Plasma Mobile's SMS app. |
| `contacts.vcard_paths` | list | `~/.local/share/kpeoplevcard` | vCard files (`.vcf`, `.vcard`) or folders, searched recursively, for caller-ID resolution. Hidden files and folders are skipped. `~` expands to `$HOME`. The default is Plasma Mobile's phonebook; a missing folder is simply empty. |
| `music.enabled` | bool | `true` | Bridge desktop media players (MPRIS) to the watch's Music app — now-playing display plus play/pause, next/previous and volume from the watch. Local-only; set `false` to disable. |
| `music.volume` | string | `system` | What the watch's volume buttons control: `system` (master/output volume — auto-detects `wpctl`/`pactl`/`amixer`) or `player` (the active player's own MPRIS volume; pure D-Bus but ignored by players that don't expose it, e.g. most browsers). |
| `music.volume_up_command` | string | _(empty)_ | For `music.volume = system`: an explicit shell command to raise volume, overriding the auto-detected backend. Only used if **both** up and down are set. |
| `music.volume_down_command` | string | _(empty)_ | The matching volume-down command (see above). |
| `weather.locations` | list | _(empty)_ | Locations to fetch weather for, each as `Name:lat:lon` (e.g. `Berlin:52.52:13.405`). Merged with `weather.location_source`. |
| `weather.location_source` | string | `manual` | Where extra locations come from: `manual` (only the list above), `gnome` (read GNOME/Phosh's weather GSettings), or `command` (run `weather.location_command`). |
| `weather.location_command` | string | _(empty)_ | For `weather.location_source = command`: a shell command that prints `Name:lat:lon` lines. |
| `weather.interval` | number | `30` | Minutes between weather refreshes. |
| `weather.gps` | bool | `false` | Add a GeoClue2-tracked **current location** entry (shown first on the watch) alongside the fixed locations. |
| `weather.gps_desktop_id` | string | `stoandl` | GeoClue `DesktopId` — must match the allow-list entry in `/etc/geoclue/geoclue.conf` (see below). |
| `weather.gps_name` | string | `Current location` | Label for the GPS entry (used as-is unless `weather.reverse_geocode` is on). |
| `weather.reverse_geocode` | bool | `false` | Reverse-geocode GPS coordinates to a place name via OSM Nominatim. Off by default — it discloses your coordinates to a third-party web service. |
| `weather.pins` | bool | `true` | Also emit weather **timeline pins** (a sunrise + sunset pin per day, today … +2 days) for the primary location. On by default whenever weather is enabled; set `false` to keep the Weather app but leave the timeline clear. |
| `weather.enabled` | bool | `true` | Master switch for weather sync, flipped live by the Sync screen (`SetSyncEnabled("weather", …)`). Weather runs only when this is on **and** a source is configured — turning it off stops the sync while leaving the locations in place. |
| `geolocation.enabled` | bool | `false` | Expose the device's GeoClue2 position to watchapps (`navigator.geolocation` in PKJS, location-aware sports/GPS apps). Off by default — it shares your location with whatever watchapp asks. Reuses the `weather.gps_desktop_id` GeoClue identity. See [Geolocation](#geolocation). |
| `calendar.ics_paths` | list | _(empty)_ | Local `.ics` files or directories (scanned for `*.ics`) to sync to the watch timeline. `~` expands to `$HOME`. No egress. Setting any `calendar.*` source enables calendar sync. |
| `calendar.discover` | bool | `false` | Auto-discover calendars the desktop keeps as local `.ics` (e.g. Calindori on Plasma Mobile, `~/.calendars`). No egress. |
| `calendar.ical_urls` | list | _(empty)_ | Published iCal feed URLs — an HTTP(S) GET of an `.ics` (e.g. a Google/Nextcloud/Outlook "secret iCal address"). **Opt-in egress.** |
| `calendar.caldav` | list | _(empty)_ | CalDAV accounts, each `id\|url\|username` (the **password is not here** — it's in the keyring/secrets store). **Don't hand-edit** — manage via the GUI (Settings → Calendars) or `stoandl calendar add/passwd/remove`. Point at an **account/principal URL** to auto-discover and sync **all** the user's calendars, or a single **collection URL** for just that one. **Opt-in egress.** |
| `calendar.sync_interval` | number | `30` | Minutes between calendar refreshes (also rolls the timeline window forward). |
| `calendar.enabled` | bool | `true` | Master switch for calendar sync, flipped live by the Sync screen (`SetSyncEnabled("calendar", …)`). Turning it off stops syncing and removes the watch's calendar pins until re-enabled. |
| `classic.discover` | bool | `true` | **Experimental.** Discover classic-era Pebbles (Time / Time Steel) over a BR/EDR inquiry and auto-pair + auto-connect them over [Bluetooth Classic](#bluetooth-classic). The RFCOMM channel is resolved via SDP. Inquiry runs only while a pairing window (`stoandl watch pair`) is open, so it's idle when no classic watch is paired. On by default; set `false` to disable. **Needs a daemon restart.** |
| `power.sleep_guard` | bool | `true` | Hold a logind *delay* inhibitor so a suspend waits until watch traffic in flight (e.g. the notification a push wake produced) has reached the watch. Never makes a suspend fail. See [deep-sleep.md](deep-sleep.md). **Needs a daemon restart.** |
| `power.sleep_guard_max_ms` | number | `3000` | Longest a suspend is held for pending watch traffic (0–4500; logind's own cap is `InhibitDelayMaxSec`, 5 s). `0` doesn't wait for watch traffic; stoandl still stops its own discovery before every suspend. **Needs a daemon restart.** |
| `power.pause_datalog_screen_off` | bool | `false` | Pause the watch's datalog sends (health data, app datalog) while the display is off; resumed when it comes on. |
| `ble.conn_params` | set | _(off)_ | `min_ms,max_ms,latency,supervision_ms` the watch keeps while idle (e.g. `500,520,0,6000`); off keeps the upstream "phone manages" write. Needs `MaxConnectionInterval` in BlueZ's `main.conf` — read [deep-sleep.md](deep-sleep.md#connection-parameters--read-this-before-turning-them-on). **Needs a daemon restart.** |
| `ble.conn_params_fast` | set | _(off)_ | Optional fast set during the connect handshake and bulk transfers (e.g. `15,15,0,6000`). Only with the kernel "K5" fix — see deep-sleep.md. **Needs a daemon restart.** |
| `battery.history` | bool | `true` | Log the watch's BLE GATT battery level whenever it changes, as a lean **fallback** for `stoandl watch battery history\|insights` when the analytics heartbeat has no decoded data for a watch. Local-only. |
| `battery.heartbeat` | bool | `true` | Capture and decode the watch's hourly analytics native-heartbeat — state of charge, real voltage, the firmware's own time-to-empty and a measured charge signal. The **primary** battery source (and the only one over Bluetooth Classic / across disconnects). The raw blob is written under `<configDir>/battery/heartbeat/` and never uploaded. Decoded only behind a strict firmware-layout guard, else captured raw. |
| `battery.retention_days` | int | `90` | How many days of battery history (both sources) to keep before pruning. |
| `connection.autoswitch` | bool | `true` | "Follow the wrist": with two or more paired watches, connect whichever is actually in range rather than only the one that last held the connection goal. A live link is never dropped to chase another watch. Inert with a single paired watch. |
| `datalog.enabled` | bool | `false` | Capture datalog frames from custom watchapps (PebbleKit DataLogging) to NDJSON under `<configDir>/datalog/<uuid>/<tag>.ndjson`. Local-only, but it writes app-supplied data to disk, so it's off by default. **Needs a daemon restart.** |
| `dnd.sync` | string | `off` | Mirror the desktop's Do Not Disturb state to/from the watch's manual Quiet Time: `off`, `to_watch`, `to_host` or `both`. Opt-in — it actively changes state on both sides (it never touches the network). GNOME (`show-banners` GSettings) and KDE/Plasma (the `Inhibited` property) are auto-detected. |
| `extensions.enabled` | list | _(empty)_ | Enabled extensions ("companion apps"). Each resolves to a child process under `<configDir>/ext/<name>/`. Edited live by `stoandl ext` and the GUI's Apps → Extensions — see [extensions.md](extensions.md). |
| `watch.<id>` | varies | _(unset)_ | An advanced watch setting (see [Watch settings](#watch-settings-advanced) below). |
| `extension.<name>.<key>` | string | _(unset)_ | Optional per-extension settings, passed to the child in its `initialize` handshake (`cmd` overrides the default entry command). **Note:** the extension's own `config` file — which is what the GUI and `stoandl ext` edit — now wins over a value here; a shadowed key is logged once at startup so you can remove it. `cmd` is the exception and still takes precedence from here, so it can rescue an extension whose own config is broken. |

## Bluetooth Classic

> **Experimental** — hardware-verified on a Pebble Time Steel; on by default (idle when no classic watch is paired).

Classic-era Pebbles (Pebble Time / Time Steel, and by class the original Pebble / Steel) connect
reliably only over **Bluetooth Classic** (BR/EDR, RFCOMM/SPP), not BLE — see
[docs/devices.md](devices.md) for the diagnosis. BLE-native watches (Time 2 / Pebble 2) are
unaffected and keep using BLE. The adapter must have **BR/EDR enabled** (the default; *not*
LE-only mode).

`classic.discover` is **on by default** — it discovers, auto-pairs and auto-connects classic-era
Pebbles hands-off. It's idle when no classic watch is paired, so leave it on; disable it only if you
never use one:

```ini
classic.discover = false           # turn off classic-era Pebble discovery (on by default)
```

Just `stoandl watch pair` (confirm the 6-digit code on the watch and, on a terminal, answer `y` to the
same code in the CLI; without a terminal or with `--yes` the CLI accepts it by itself). A BR/EDR
inquiry runs only while that pairing window is open — the rest of the time the radio is quiet. A
bonded watch reconnects on its own afterwards: stoandl pages its fixed address (no advertising), so
it survives airplane mode / out-of-range. There's no kernel-side background auto-connect for BR/EDR
(that's BLE-only), so stoandl runs a quiet standing reconnect loop instead.

Pairing a dual-mode watch occasionally yields an LE bond rather than the BR/EDR link key RFCOMM
needs; if it pairs but won't connect, run `btmgmt pair -t bredr <mac>` by hand and restart the daemon.

All other commands (`connect`, `unpair [name]`, `repair`, `list`, `battery`) and every feature work
over Classic just as over BLE — the Pebble protocol layer is transport-agnostic. Only one watch is
connected at a time; `stoandl watch connect <name>` switches the active watch.

No web egress: Bluetooth Classic is local-radio only.

## Per-app notification settings

**`notification.per_app`** (on by default) tracks which apps notify and controls each one. Every
desktop app that notifies is lazily added to a per-app store the first time it's seen (the app name is
its identity — there are no package ids on Linux), and its mute state is enforced **host-side** before
sending, so a muted app's notification is dropped before it crosses BLE. It's exact-match, schedulable,
and adjustable at runtime without editing the config.

Manage the store with `stoandl notif`:

```sh
stoandl notif list                     # tracked apps, their mute state and when each last notified
stoandl notif mute "Element"           # mute always (drops on the host)
stoandl notif mute Slack weekdays      # mute on Mon–Fri only (weekdays / weekends schedules)
stoandl notif mute Discord 1h          # temporary mute (also 30m / 2d …); auto-expires
stoandl notif unmute Slack             # deliver again
stoandl notif mute-all  / unmute-all   # apply to every tracked app
stoandl notif style Element --color Red --icon NotificationElement --vibe double   # per-app styling
stoandl notif styles                   # list every available colour, icon and vibe preset (offline)
```

Quote multi-word app names. The app argument is a case-insensitive **substring** match, but an **exact**
name wins outright — so when one app's name is a substring of another (e.g. `whatsapp` vs `whatsapps`),
typing the full exact name targets just that one; a partial that still hits several reports them as
ambiguous. New apps default to `notification.default_mute` (`never` = deliver).
Muting is enforced **host-side** (the notification is dropped before it crosses BLE), so per-app mute
works fully without any watch-side support.

**Muting from the wrist.** Every forwarded notification carries a **"Mute *app*" action** in its
on-watch action menu (the same mechanism the official Android app uses). Selecting it mutes that app
host-side — it shows up as muted in `stoandl notif list`, and `stoandl notif unmute <app>` reverses it.
This needs no config and no BlobDB sync. (Note: there is no per-app *settings menu* on current
Core/PebbleOS firmware — Settings → Notifications is global only — so muting is per-notification, via the
action menu.)

**Per-app styling** (`notif style`) sets, for an app's notifications: `--color` (a
[TimelineColor](https://github.com/coredevices/libpebble3) name like `Red`/`MintGreen`/`DukeBlue`),
`--icon` (a TimelineIcon enum name like `NotificationSlack`; defaults to an icon picked from the app
name), and `--vibe` (a preset `short`/`long`/`double`/`triple`/`pulse`, or a CSV of on/off milliseconds
like `100,50,100`). These are applied **host-side at send time** to the outgoing notification, so they
render on the watch with no sync. For each flag, `default` resets it; omitting it leaves it unchanged.
Run **`stoandl notif styles`** to print the full list of accepted colours (64), icons (the `Notification*`
app/messaging set plus the generic timeline icons) and vibe presets — it's generated from the enums, so
it always matches what the daemon accepts, and needs no daemon or watch.

**Transient notifications** are not forwarded: a notification with the `transient` hint
(`notify-send --transient`, many on-screen-display notices) is one the desktop itself doesn't keep, so
stoandl doesn't buzz the wrist for it. An `allow` filter (`stoandl notif filter add <regex> allow`) still
lets one through. Plasma's network notices (app "Network Management") don't set the hint; mute them with
`stoandl notif mute "Network Management"` if they are too chatty, as on a phone whose modem keeps
re-registering.

**`notification.sync_to_watch`** (off by default) additionally pushes the list + mute states to the
watch via libpebble3's `NotificationAppItem` BlobDB. It's off because current firmware has no per-app
*settings menu* to surface them (muting is via the action menu above, which needs no sync). Kept as an
opt-in for firmware that does. Watch-link only, no web egress.

## Missed notifications (catch-up)

libpebble3 sends a watch only the notifications created after its connection came up, so a reconnect
never floods the watch. On its own that silently drops everything posted while the watch was away — and
on a phone that loses the Bluetooth link on every suspend (see [deep-sleep.md](deep-sleep.md)) that is
every notification a push message wakes it for, because it is posted a few seconds *before* the link is
back.

**`notification.catch_up_minutes`** (default `10`) makes a reconnecting watch also get the notifications
it hasn't received that are at most that many minutes old. They keep their original time on the watch.
The window bounds a long absence (out of range for an hour → only the last 10 minutes arrive), and it
never reaches back past the daemon's start (no replay of a previous run) or the watch's pairing /
factory reset (it starts clean). What the watch already has is not sent again — sync state is tracked
per notification and watch. One an extension has withdrawn in the meantime (`closeNotification`) is
not sent; stoandl doesn't see a desktop notification being dismissed on the host, so those still
arrive. With two or more watches each one catches up on its own, so a watch you switch to also shows
what the other one already did inside the window. `0` restores the upstream behaviour.

If the phone suspends again before the watch has reconnected, the notification goes out on the next
connection — if that is still inside the window. Raise the value if you prefer late delivery to none.
Once the reconnected watch is syncing, the sleep guard (`power.sleep_guard`) holds a suspend until the
caught-up notifications are sent (at most `power.sleep_guard_max_ms`); it doesn't hold one for the
reconnect handshake itself.

A reconnect that has something to catch up on logs `Notification catch-up: sending N unsent
notification(s) created after …` (the time is UTC); a reconnect with nothing missed logs nothing.
stoandl's own alerts about a watch that can't connect ("Pebble blocked by a Bluetooth scan", "Pebble
won't stay connected", "Pebble pairing removed") stay on the desktop, so they don't turn up on the watch
once it is back.

## Weather

stoandl pushes weather to the watch's built-in **Weather** app. Because it runs headless with no GPS,
locations are fixed in the config rather than tracked — list one or more under `weather.locations`:

```
weather.locations = Berlin:52.52:13.405, London:51.5074:-0.1278
weather.interval = 30
```

**Units.** There is no weather unit setting: temperatures are sent in the watch's own units, the
metric/imperial choice that also sets the Health app's distances. Change it with
`stoandl health profile set units imperial` (or `metric`); weather refreshes in the new unit straight
away. They have to be one setting: from PebbleOS 4.37 the watch converts its weather-warning
thresholds to °F when its units are imperial, so °C readings on an imperial watch warned "Below
freezing" on a warm day. The first time weather starts, stoandl writes its units to the watch if none were
ever stored, so a watch still on its factory default (miles) follows the host too; a units row that
exists (set from the host or on the watch) is never overwritten. A `weather.units` line left over from an older
stoandl is ignored (the daemon logs a warning).

Data comes from [Open-Meteo](https://open-meteo.com/) — a free, no-API-key, no-account provider, which
fits stoandl's headless, sign-in-free model (unlike the official app's account-gated weather proxy). The
first location's current temperature, today's high/low and tomorrow's forecast appear in the Weather app.

stoandl refreshes on the configured interval and again immediately whenever a watch connects, so a
freshly-connected watch shows current weather within seconds. Force a refresh any time with `stoandl
weather`. A transient fetch failure keeps the last-known weather on the watch rather than blanking it.

### Timeline pins

Alongside the Weather app, stoandl also emits weather **timeline pins**, replicating the original Core
companion app: for each of today, tomorrow and the day after it places a **Sunrise** pin (at sunrise)
and a **Sunset** pin (at sunset). Each pin shows the day's high/low and condition; the daytime and
overnight halves get their own temperature and icon, derived from Open-Meteo's hourly forecast.

Pins follow a single **primary** location — the GPS current location if `weather.gps` is on, otherwise
the first `weather.locations` entry — so configuring several nearby places (whose sunrises coincide)
never produces overlapping pin sets. The other configured locations appear inside each pin's detail
view as a temperature comparison. Set `weather.pins = false` to keep the Weather app but leave the
timeline clear. (Note: current watch firmware only surfaces the next ~2–3 days of timeline, so the
furthest pins may not be visible even though they sync.)

### Importing the location from your desktop

No desktop environment exposes its weather *data* over a shared API — the widgets fetch from the cloud
with their own libraries and keep the result. But the **place you picked** is readable, so you can avoid
re-entering coordinates:

- `weather.location_source = gnome` reads GNOME/Phosh's weather GSettings (`org.gnome.shell.weather`,
  then `org.gnome.Weather`). Coordinates there are stored in radians; stoandl converts them.
- `weather.location_source = command` runs `weather.location_command` and expects `Name:lat:lon` lines —
  a DE-agnostic escape hatch. For KDE/KWeather or anything else, point it at a small script. Examples:

  ```ini
  # Anything: just hard-code or compute it
  weather.location_command = echo "Munich:48.137:11.575"

  # KDE/Plasma: it has no coordinate store (the widget keeps a provider source string; KWeather keeps
  # the city). Resolve a city name to coordinates with Open-Meteo's free geocoder (needs curl + jq):
  weather.location_command = curl -s "https://geocoding-api.open-meteo.com/v1/search?name=Munich&count=1" | jq -r '.results[0] | "\(.name):\(.latitude):\(.longitude)"'
  # …or splice the name out of KWeather's config first:
  #   name=$(kreadconfig6 --file kweatherrc --group <group> --key <key>); curl -s ".../search?name=$name..." | jq ...
  ```

Imported locations are **merged** with `weather.locations` (de-duplicated by name) and re-read on every
refresh, so changing the location in your DE takes effect without restarting stoandl. The weather itself
is still fetched from Open-Meteo — importing only supplies the coordinates, not the forecast.

### GPS current location

Set `weather.gps = true` to add a **current location** entry tracked via
[GeoClue2](https://gitlab.freedesktop.org/geoclue/geoclue) — the standard Linux geolocation service,
which aggregates modem GPS, Wi-Fi and A-GPS. It's resolved fresh on every refresh (so it follows
`weather.interval`) and marked as the current location so the watch shows it first. By default the entry
is labelled `weather.gps_name`; set `weather.reverse_geocode = true` to instead look up the nearest place
name from the coordinates (via OSM Nominatim — see the network note below).

GeoClue authorises clients by their `DesktopId`, so a headless daemon must be **allow-listed**. Add this
to `/etc/geoclue/geoclue.conf` (the id must match `weather.gps_desktop_id`, default `stoandl`):

```ini
[stoandl]
allowed=true
system=true
users=
```

Without this entry GeoClue denies the request and the log notes that GPS is unavailable; fixed
`weather.locations` still work. `weather.gps` can be used on its own (no fixed locations) or together
with them.

### Network / external services

stoandl makes **no background web requests unless you enable weather.** Every external call is opt-in
and off by default:

| Service | What is sent | When it's called |
|---------|--------------|------------------|
| [Open-Meteo](https://open-meteo.com/) | location coordinates | only when `weather.locations` or `weather.gps` is set |
| [OSM Nominatim](https://nominatim.openstreetmap.org/) | GPS coordinates | only when `weather.reverse_geocode = true` |

GeoClue (location) is a local system service, not a web call; importing a location from your DE
(`weather.location_source`) is local too (GSettings / your command). PKJS/Clay pages can make their own
HTTP requests, but only for watchapps you install and (for config pages) only when you run `stoandl
settings`.

## Geolocation

Watchapps can ask for the device's position — PKJS companion scripts via the standard
`navigator.geolocation` API (`getCurrentPosition`, `watchPosition`, `clearWatch`), and location-aware
sports/GPS watchapps via the same underlying hook. Off by default — every request fails with
"Geolocation is disabled — set geolocation.enabled=true in stoandl.conf …" until you opt in:

```
geolocation.enabled = true
```

to back it with **GeoClue2** — the same Linux geolocation service stoandl already uses for weather's
current-location entry (modem GPS aggregated with Wi-Fi and A-GPS). Coordinates, accuracy, altitude,
heading and speed are passed through to the watchapp when GeoClue reports them.

It's **off by default** because, once on, *any* watchapp you launch can read your location with no
per-app prompt (Linux has no location-permission UI; libpebble3's per-app permission defaults to
allow). Enable it only if you run watchapps you trust with your position.

GeoClue authorises clients by their `DesktopId`, so — exactly as for `weather.gps` — the daemon must be
**allow-listed** in `/etc/geoclue/geoclue.conf`. Geolocation reuses the **same** identity as weather
(`weather.gps_desktop_id`, default `stoandl`), so a single allow-list entry covers both:

```
[stoandl]
allowed=true
system=true
users=
```

Without it GeoClue denies the request and `getCurrentPosition` returns an error. GeoClue is a local
system-bus service — no web egress — though a watchapp may of course send the fix onward over its own
PKJS `XMLHttpRequest`.

## Music / now-playing

stoandl bridges desktop media players to the watch's native **Music** app over
[MPRIS](https://specifications.freedesktop.org/mpris-spec/latest/) — the standard media-player D-Bus
interface that VLC, mpv, Spotify, browsers and most Linux players implement on the session bus. The
watch shows the current track (title / artist / album, play state, position) and its buttons drive the
player: play/pause, next/previous and volume. It follows whichever player is actively playing; if you
run `playerctld`, it's skipped so it doesn't show up twice.

This is **local-only** — it reads and controls media players already running on your machine and makes
no web requests. It's on by default; set `music.enabled = false` to turn it off.

**Volume.** By default (`music.volume = system`) the watch's volume buttons change the **master/output
volume**, just like a phone. There's no portable D-Bus volume API on Linux, so stoandl shells out to
the first of these found on `PATH`: `wpctl` (PipeWire — Plasma Mobile, modern desktops), `pactl`
(PulseAudio / pipewire-pulse) or `amixer` (ALSA). If your setup needs something else, set both
`music.volume_up_command` and `music.volume_down_command` to override the backend, e.g.:

```
music.volume_up_command   = pactl set-sink-volume @DEFAULT_SINK@ +5%
music.volume_down_command = pactl set-sink-volume @DEFAULT_SINK@ -5%
```

Set `music.volume = player` instead to control the **active player's own** MPRIS volume (pure D-Bus, no
external tool). Note many players — most browsers — don't expose a `Volume` property, so player-volume
is silently ignored there; mpv, VLC and Spotify do support it. If `system` is selected but no backend is
found, stoandl logs a warning and falls back to player volume. (In `system` mode the now-playing volume
bar on the watch still reflects the player, not the master level — only the buttons act on master.)

## Calendar

stoandl syncs upcoming calendar **events** to the watch's **timeline** as native calendar pins —
title, time, location, a recurring marker, and reminders for events that carry a VALARM. libpebble3
does all the watch-side work (pin creation, diffing and deletion); stoandl just reads your calendars
and hands it the events, so re-syncs update pins in place and deleting an event removes its pin.

It's **disabled until you configure a source.** The design is *DE-agnostic first, reuse what the DE
already imported where that's cheap*:

```ini
# Local .ics files or directories — no egress. The reliable, DE-agnostic path.
calendar.ics_paths = ~/.local/share/calindori, ~/calendars/work.ics

# …or let stoandl find local .ics the desktop already keeps (Calindori, ~/.calendars, …):
calendar.discover  = yes

# Published iCal feed URL — opt-in egress:
calendar.ical_urls = https://example.com/cal.ics

# CalDAV — managed by the GUI / `stoandl calendar add` (the password goes to the keyring, not here).
# Persisted form is `id|url|username` (no password). An account/principal URL auto-discovers ALL the
# user's calendars; a single collection URL syncs just that one. Add one with:
#   stoandl calendar add caldav https://dav.example.com/dav/alice@example.com/ alice   # prompts for password
calendar.caldav    =

calendar.sync_interval = 30
```

Events are synced for a fixed window of **yesterday through 7 days ahead** (set by libpebble3's
timeline). Recurring events (RRULE/RDATE, minus EXDATE) are expanded to individual pins; all-day
events and per-event timezones are handled. All-day events go to the watch at UTC midnight of their
date, the frame the watch expects (it applies its own timezone offset to all-day items), and a
fixed-time alarm on one moves with them. `calendar dump` therefore prints an all-day date read in UTC.
stoandl re-reads on `calendar.sync_interval`, immediately
when a watched local `.ics` changes, and on demand:

```sh
stoandl calendar list                 # synced calendars + enabled state
stoandl calendar disable <id|name>    # stop syncing one calendar (enable to undo)
stoandl calendar sync                 # force a re-read now
stoandl calendar dump <file|url>      # parse + print events offline (no daemon/watch needed)
```

### Reusing your desktop's calendars

There is no cross-desktop calendar API, so "reuse the DE's calendars" means different things per DE:

- **Plasma Mobile (Calindori)** keeps calendars as plain local `.ics` — `calendar.discover = yes`
  (or point `calendar.ics_paths` at `~/.local/share/calindori`) picks them up with no reconfiguration.
- **Any DE** can point `calendar.ics_paths` at a local `.ics` (one your calendar app writes, or one a
  tool like `vdirsyncer` syncs), or use `calendar.ical_urls` / `calendar.caldav` for online accounts.
- **GNOME (Evolution Data Server)** and **KDE desktop (Akonadi)** keep *online* calendars (Google,
  Nextcloud, Microsoft) in caches with no practical read API for a non-GNOME / non-C++ process, so
  reading those native stores is **not yet supported**. Reach them via `calendar.ical_urls` (most
  providers offer a "secret iCal address") or `calendar.caldav`. A GNOME EDS reader is a possible
  future addition.

Point `calendar.caldav` at an **account/principal URL** and stoandl auto-discovers every calendar
(RFC 6764/4791: `current-user-principal` → `calendar-home-set` → `PROPFIND Depth:1`) and syncs them
all — use `stoandl calendar disable` to drop any you don't want; a single **collection URL** syncs
just that one. Auth is **Basic only** (no Digest/OAuth). The **password is never stored in
`stoandl.conf`** — it goes in the system keyring (`org.freedesktop.secrets`) when one is unlocked,
otherwise a 0600 `secrets` file beside the config (excluded from backups); manage accounts from the GUI
(Settings → Calendars) or `stoandl calendar add/passwd/remove`, which mint the id and store the password
securely. RSVP
(accept/decline from the watch) isn't wired up yet. A single edited occurrence of a recurring event
shows at its original time (detached overrides are skipped to avoid duplicates).

### Network / external services

Local `.ics` files and discovery make **no web requests.** The two network sources are opt-in:

| Service | What is sent | When it's called |
|---------|--------------|------------------|
| iCal feed URL(s) | an HTTP(S) GET to each `calendar.ical_urls` entry | only when `calendar.ical_urls` is set |
| CalDAV server(s) | PROPFIND discovery + a `calendar-query` REPORT (Basic auth) for each `calendar.caldav` account's calendars | only when `calendar.caldav` is set |

## Watch settings (advanced)

The official companion app exposes "advanced" watch settings that the watch's own menus don't —
quick-launch button mappings, ambient-light threshold, backlight, vibration patterns, etc. stoandl can
set the same ones (they live in the watch's settings BlobDB).

Discover them with the CLI — it lists every setting, its current value and allowed values:

```sh
stoandl settings                  # all settings (one row each)
stoandl settings light            # filter by id/name substring
stoandl settings set lightAmbientThreshold 200
stoandl settings set qlUp "Music"  # hold-Up quick-launches the Music app (by name or UUID; "off" to clear)
stoandl settings set clock24h true
```

To make them stick across restarts, put them in the config as `watch.<id> = <value>`:

```ini
watch.lightAmbientThreshold = 200
watch.clock24h = true
watch.qlUp = Music
watch.qlBack = off
watch.textStyle = Larger   # fw < 4.38.1, see below
```

`textStyle` (Text Size) sizes different things per firmware. Up to PebbleOS 4.36 it sizes notifications
and the timeline; on 4.37 the whole system UI, notifications included; on 4.38.0 the system UI, while
notifications have their own size that the phone can't set. From 4.38.1 the system size is a separate
setting too (the phone can't set it either), and `textStyle` only seeds the notification size once, on a
watch that has never stored one. So on 4.38.1 and later a pin here does nothing: set the sizes on the
watch.

Values are parsed per the setting's type: booleans (`true`/`false`), numbers (validated against the
setting's range), enums (by name — `stoandl settings` shows the choices), quick-launch (an app name/UUID, or
`off`), and colors (hex `RRGGBB` or a preset name). **Configured settings are authoritative**: stoandl
re-applies them on every connect, so a `watch.*` value wins over a change made on the watch. Settings you
don't list are left untouched. `*` in `stoandl settings` marks debug/advanced settings (e.g. the ambient-light
threshold) — they work the same, they're just hidden in the official app.

## Caller-ID resolution

There is no contacts D-Bus API shared across GNOME (evolution-data-server) and Plasma/KDE
(Akonadi/KPeople), so stoandl resolves names from **vCard files** — the DE-agnostic common
denominator. Two convenient sources:

- **Plasma Mobile**'s phonebook stores contacts through the `kpeoplevcard` KPeople backend in
  `~/.local/share/kpeoplevcard/` (the phonebook's own as `own/*.vcard`, synced address books in folders
  of their own). That is the default for `contacts.vcard_paths`, so it works without a setting.
- **GNOME Contacts** / any CardDAV setup (`vdirsyncer`, `khard`) can export/sync a `.vcf` directory.

Folders are searched recursively for `*.vcf` and `*.vcard`; hidden files and folders are skipped, and
so are vdirsyncer's temp files (`<name>.vcf<random>`).

Numbers are matched digits-only by suffix, so a stored `0151 2345678` resolves an incoming
`+49151 2345678` and vice versa. Files are re-read automatically when they change.

If a number isn't in the vCard files, stoandl falls back to the title of the dialer's own
incoming-call notification (see `call.dialer_apps`) — best-effort, since that depends on the
notification arriving at or before the call rings.

## Firmware updates

Flash watch firmware. The transfer, the `FIRMWARE_UPDATE_START`/`COMPLETE` handshake and the
pre-flash safety checks (board, CRC, slot — a mismatched bundle is **refused before anything is sent**)
are all libpebble3's; stoandl just drives them and shows progress. It works the same over BLE or
Bluetooth Classic — the flash rides the transport-agnostic Pebble protocol.

### Local sideload (no config, no network)

```sh
stoandl firmware /path/to/normal_<board>_<version>.pbz
```

Flashes a firmware bundle already on disk. Always available — no keys, no egress. The CLI shows a
progress bar and reports when the watch reboots to apply. A firmware `.pbz` for the *wrong* board is
rejected by the safety check, so this can't flash a bundle your watch won't accept.

### Online check / update (opt-in egress)

```sh
stoandl firmware check     # is newer firmware available for this watch?
stoandl firmware update    # download the matching bundle and flash it
```

These fetch firmware over the network and are **off by default**. The source is chosen automatically
by the watch's generation, so the two switches are independent — enable whichever matches your watch
in `stoandl.conf`:

| Key | Default | Meaning |
|-----|---------|---------|
| `firmware.github` | `false` | **Core devices** (Pebble 2 Duo / Pebble Time 2): allow `check`/`update` to query GitHub releases and download firmware. |
| `firmware.github_repo` | `coredevices/PebbleOS` | `owner/repo` whose releases publish `normal_<board>_<version>.pbz` bundles. |
| `firmware.github_prereleases` | `false` | Consider pre-releases too (otherwise only stable releases). |
| `firmware.cohorts` | `false` | **Classic / Rebble watches** (Pebble Time / Time Steel / Time Round / Pebble 2): allow `check`/`update` to query Rebble's cohorts service. |
| `firmware.cohorts_url` | `https://cohorts.rebble.io` | Base URL of the cohorts service — override only for a self-hosted/mirror instance. |
| `firmware.notify` | `true` | When a source is on, notify you when newer firmware appears — on both the watch and your desktop (see below). |

### Update notifications (watch + desktop)

With a source on, stoandl checks for newer firmware **on each watch connect** (throttled to at most
once a day) and, when it finds some, pushes an **Update** notification to **both the watch and the
host desktop** (your laptop). Pressing Update on either downloads and flashes the matching bundle right
there — no phone, no CLI. The desktop notification reuses the same mechanism as the re-pair prompt
(`org.freedesktop.Notifications`); if no session bus is reachable it falls back to a plain notification
whose text points you at `stoandl firmware update`. It only re-notifies when the available version
actually changes, so reconnecting doesn't nag. Set `firmware.notify = false` to keep
`firmware check`/`update` on the command line but suppress the automatic notifications.

No account or token is needed — both sources are public. **Core devices** (Pebble 2 Duo / Pebble
Time 2) pull from GitHub: the watch's board revision (e.g. `obelix_pvt`) **exactly matches** the
release asset `normal_<board>_<version>.pbz`, so the right bundle is picked with no mapping table.
"Latest" is the highest version among the 30 most recent releases that ship a bundle for the board,
not the release GitHub marks latest: PebbleOS publishes backports (v4.27.3, v4.9.142.4, …) after newer
releases, and GitHub's mark follows the publish date.
**Classic / Rebble watches** pull from Rebble's cohorts service (`GET /cohort?hardware=<board>&select=fw`,
the same contract the classic Pebble app used) — the board is the same `WatchHardwarePlatform.revision`
(e.g. `snowy_dvt`). stoandl routes to the right source via one shared `isCoreDevice()` partition (which
also decides language-pack boards); if the chosen source ships nothing for the board, `firmware check`
reports "no firmware published for board". A watch booted into recovery (PRF) is always offered a
reflash so it can leave recovery.

> **Languages (PebbleOS 4.38).** PebbleOS 4.38.0 (4.37.0 on the Pebble 2 Duo) removed the built-in
> German, French, Italian, Spanish, Portuguese, Dutch, Catalan and Polish translations. A watch using
> one falls back to English after the update and needs a [language pack](#language-packs), e.g. from
> [pebbleos-translations](https://github.com/coredevices/pebbleos-translations/releases) with
> `stoandl language sideload <file.pbl>`. `firmware check`/`update` and the update notification say so
> when an update crosses that release.

> **Risk note.** Flashing firmware is the highest-risk operation stoandl performs. It's mitigated by
> the pre-flash safety checks and by Pebble's recovery (PRF) firmware — a failed flash drops the watch
> to recovery rather than bricking it — but flash on charger, keep the watch in range, and prefer a
> non-critical watch when trying it the first time.

`stoandl firmware status` prints the current state at any time (`idle`, `downloading`, `inprogress`,
`reboot`, `failed`).

### Recovery & factory reset (no config, no network)

The companion to the firmware tooling — reset the connected watch.

```sh
stoandl reset recovery     # reboot the watch into recovery (PRF) firmware
stoandl reset factory      # wipe the watch back to out-of-box state (asks to confirm)
stoandl reset factory --yes   # …skip the confirmation prompt (for scripts)
```

`reset recovery` reboots the watch into its recovery (PRF) firmware — the way out of a bad normal
firmware. From PRF, reflash a normal bundle with `stoandl firmware <file.pbz>`. It's recoverable, so it
needs no confirmation.

`reset factory` wipes the watch back to out-of-box state — all installed apps, settings **and the host
pairing**. It's **irreversible** and the watch must be re-paired afterwards, so the CLI requires you to
type `yes` at the prompt; pass `--yes`/`-y` to skip it. Both are fire-and-forget: the watch drops the BLE
link as it reboots/wipes, so confirm the result on the watch itself. Always available with a connected
watch — no keys, no egress.

## Language packs

Install a firmware **language pack** (`.pbl`) onto the watch — this changes its notification/UI language
and loads the fonts a script needs (Cyrillic, Simplified/Traditional Chinese, Japanese, Burmese, Hebrew,
…). The transfer is libpebble3's (`installLanguagePack` → PutBytes, the same machinery as firmware/app
sideload); stoandl drives it and shows progress.

The packs come from a built-in **catalog** — the same manifest the official Core app ships, bundled with
stoandl. See what's available with:

```sh
stoandl language list   # packs for your watch's board (installed one marked *), or the full catalog
```

With a watch connected, `list` shows the packs for its board — system locale first, the installed pack
marked `*`, community packs tagged `[community]`. With **no** watch connected (or no daemon running) it
falls back to the full bundled catalog — every locale and board, one row per language with the number of
boards it covers — so you can browse what's available before pairing. This fallback is fully offline.

Boards are matched the way the official app does: **Core devices (Pebble 2 Duo / Pebble Time 2) share the
Diorite (`silk`) packs**, classic Pebbles use their own board revision (a Time Steel → `snowy_s3`, etc.).

From PebbleOS 4.38.0 (4.37.0 on the Pebble 2 Duo) the firmware has no built-in translations left, so a
non-English Core watch needs a pack after updating. The bundled catalog doesn't have the current
PebbleOS packs yet: take them from
[pebbleos-translations](https://github.com/coredevices/pebbleos-translations/releases) and use
`stoandl language sideload`.

### Local sideload (no config, no network)

```sh
stoandl language sideload /path/to/pack.pbl
```

Installs a `.pbl` already on disk. Always available — no keys, no egress. The CLI shows a progress bar
and reports when the install finishes.

### Catalog install (opt-in egress)

```sh
stoandl language install de_DE     # by ISO locale (also: a name like "German", or a catalog id)
stoandl language install           # no arg = the daemon's own system locale
```

Auto-picks the best catalog pack for your watch and **downloads** it (from Rebble's CDN
`binaries.rebble.io`, or a community GitHub repo for packs like Japanese/Hebrew), then installs it. Off by
default because it makes a network call:

| Key | Default | Meaning |
|-----|---------|---------|
| `language.download` | `false` | Allow `language install` to download a `.pbl` from the catalog source and install it. (`language list` and `language sideload` never touch the network.) |

To revert to English, install the watch's English pack (`stoandl language install en_US`, or sideload it).
`stoandl language status` prints the current state at any time (`idle`, `downloading`, `installing`,
`done`, `failed`).

## Developer connection

Bridge the Pebble SDK / CloudPebble to the connected watch, so you can install and live-debug
watchapps through stoandl the way the official phone app's developer connection does — not just
`stoandl apps install`. `stoandl developer start` brings up libpebble3's LAN WebSocket server on **port
9000**; it relays raw Pebble-protocol frames to/from the watch, installs `.pbw` bundles, and streams
PKJS logs. Point the SDK at this host:

```sh
stoandl developer start            # prints the host's LAN address(es) + a security warning
# on your dev machine (in a watchapp project):
pebble install --phone <host-ip>   # install + run on the watch
pebble logs    --phone <host-ip>   # stream app + PKJS logs
stoandl developer status           # active / inactive
stoandl developer stop             # tear the server down
```

> ⚠ **Security.** The server binds `0.0.0.0:9000` (all interfaces) with **no authentication**: while
> it runs, anyone who can reach this host on the network can install apps and relay protocol traffic to
> the watch. It's therefore off by default and started explicitly; stop it when you're done developing.
> It's a plain LAN listener — nothing is uploaded anywhere (no egress).

The server lives in the watch's connection scope, so it goes away when the watch disconnects. Set the
key below to bring it back up automatically on every connect (handy for a dedicated dev device):

| Key | Default | Meaning |
|-----|---------|---------|
| `developer.autostart` | `false` | Auto-start the developer connection (LAN server, port 9000) on every watch connect. Leave off unless you accept the unauthenticated-LAN-listener exposure above; `stoandl developer start`/`stop` work on demand regardless. |

## Health / activity

Pull the watch's health data — steps, distance, calories, active minutes, **sleep** sessions,
**heart rate** (incl. resting HR and per-zone minutes), and **workout** sessions (Walk / Run /
OpenWorkout) — into the host. libpebble3 ingests the watch's health frames into its database on its
own; the watch only sends them when **asked**, and headless stoandl has no dashboard to show them in,
so stoandl requests the data and projects it to a local NDJSON store other tools can read.

```sh
stoandl health                 # last 7 days: steps / distance / sleep / active / resting+avg HR
stoandl health 30              # last 30 days
stoandl health activities      # recent Walk/Run/workout sessions
stoandl health sync            # ask a connected watch to sync now, then re-export
stoandl health dump daily      # raw NDJSON (daily | activities)
```

Files are written under `~/.config/stoandl/health/` (honouring `XDG_CONFIG_HOME`):

- `daily.ndjson` — one object per day (keyed by date), upserted on each sync.
- `activities.ndjson` — one object per workout session (keyed by start time).
- `samples/<date>.ndjson` — minute-level steps + heart rate (only with `health.export_samples`).

Units are normalised for consumers: **distance in metres, energy in kcal, durations in minutes**;
timestamps are unix epoch seconds. Both halves are local-only (no egress), so they're **on by default**.

> **Session distances written by earlier versions are 100× too small.** `activities.ndjson` divided the
> session's `distance_m` by 100 (the watch sends metres, not centimetres). Rows inside the
> `health.export_days` window are rewritten correctly at the next export; older rows keep the wrong
> value (multiply by 100). `daily.ndjson` was always right.

| Key | Default | Meaning |
|-----|---------|---------|
| `health.sync` | `true` | Request a health sync from the watch on every connect (incremental — the first run, with an empty DB, is a full pull). Costs a little watch BLE/battery. |
| `health.export` | `true` | Project the synced data to NDJSON under `~/.config/stoandl/health/` whenever new data arrives. |
| `health.export_samples` | `false` | Also write minute-level samples (steps + heart rate per minute). Much higher volume than the daily summary, so off by default. |
| `health.export_days` | `30` | How many days back each export re-projects (the daily/activities/samples window). Days already written outside the window stay in place. |
