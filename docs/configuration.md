# Configuration

stoandl reads an optional config file at:

```
$XDG_CONFIG_HOME/stoandl/stoandl.conf      # default: ~/.config/stoandl/stoandl.conf
```

A missing or unreadable file is fine — the daemon falls back to defaults. **Edits require a
restart** (`systemctl --user restart stoandl`); the file is read once at startup.

Syntax is `key = value`, `#` starts a comment, and list values are comma-separated. A starter file
is shipped at [`packaging/stoandl.conf.example`](../packaging/stoandl.conf.example).

## Keys

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `notification.per_app` | bool | `true` | Track every observed desktop app in a per-app store and enforce its mute state host-side before sending (dropped before it crosses BLE). Exact-match, stateful, schedulable — managed at runtime with `stoandl notif` (see [Per-app notification settings](#per-app-notification-settings)). |
| `notification.default_mute` | string | `never` | Mute state for a newly observed app: `never` (deliver), `always` (mute), or the day-of-week schedules `weekdays` / `weekends`. |
| `notification.sync_to_watch` | bool | `false` | Sync the per-app list + mute states to the watch (libpebble3 `NotificationAppItem` → BlobDB). **Off by default** — current Core/PebbleOS firmware has no per-app notification UI on the watch, so the records surface nowhere; mute is enforced host-side regardless. Opt-in for firmware that does surface it. Watch-link only, no web egress. |
| `call.dialer_apps` | list | `spacebar, calls` | Telephony/dialer app-name substrings. Their notifications are suppressed from the watch (the native call screen replaces them) and their title is used as a fallback caller name. |
| `contacts.vcard_paths` | list | _(empty)_ | vCard (`.vcf`) files or directories scanned for caller-ID resolution. `~` expands to `$HOME`. |
| `music.enabled` | bool | `true` | Bridge desktop media players (MPRIS) to the watch's Music app — now-playing display plus play/pause, next/previous and volume from the watch. Local-only; set `false` to disable. |
| `music.volume` | string | `system` | What the watch's volume buttons control: `system` (master/output volume — auto-detects `wpctl`/`pactl`/`amixer`) or `player` (the active player's own MPRIS volume; pure D-Bus but ignored by players that don't expose it, e.g. most browsers). |
| `music.volume_up_command` | string | _(empty)_ | For `music.volume = system`: an explicit shell command to raise volume, overriding the auto-detected backend. Only used if **both** up and down are set. |
| `music.volume_down_command` | string | _(empty)_ | The matching volume-down command (see above). |
| `weather.locations` | list | _(empty)_ | Locations to fetch weather for, each as `Name:lat:lon` (e.g. `Berlin:52.52:13.405`). Merged with `weather.location_source`. |
| `weather.location_source` | string | `manual` | Where extra locations come from: `manual` (only the list above), `gnome` (read GNOME/Phosh's weather GSettings), or `command` (run `weather.location_command`). |
| `weather.location_command` | string | _(empty)_ | For `weather.location_source = command`: a shell command that prints `Name:lat:lon` lines. |
| `weather.units` | string | `metric` | Temperature unit sent to the watch: `metric` (°C) or `imperial` (°F). |
| `weather.interval` | number | `30` | Minutes between weather refreshes. |
| `weather.gps` | bool | `false` | Add a GeoClue2-tracked **current location** entry (shown first on the watch) alongside the fixed locations. |
| `weather.gps_desktop_id` | string | `stoandl` | GeoClue `DesktopId` — must match the allow-list entry in `/etc/geoclue/geoclue.conf` (see below). |
| `weather.gps_name` | string | `Current location` | Label for the GPS entry (used as-is unless `weather.reverse_geocode` is on). |
| `weather.reverse_geocode` | bool | `false` | Reverse-geocode GPS coordinates to a place name via OSM Nominatim. Off by default — it discloses your coordinates to a third-party web service. |
| `weather.pins` | bool | `true` | Also emit weather **timeline pins** (a sunrise + sunset pin per day, today … +2 days) for the primary location. On by default whenever weather is enabled; set `false` to keep the Weather app but leave the timeline clear. |
| `geolocation.enabled` | bool | `false` | Expose the device's GeoClue2 position to watchapps (`navigator.geolocation` in PKJS, location-aware sports/GPS apps). Off by default — it shares your location with whatever watchapp asks. Reuses the `weather.gps_desktop_id` GeoClue identity. See [Geolocation](#geolocation). |
| `calendar.ics_paths` | list | _(empty)_ | Local `.ics` files or directories (scanned for `*.ics`) to sync to the watch timeline. `~` expands to `$HOME`. No egress. Setting any `calendar.*` source enables calendar sync. |
| `calendar.discover` | bool | `false` | Auto-discover calendars the desktop keeps as local `.ics` (e.g. Calindori on Plasma Mobile, `~/.calendars`). No egress. |
| `calendar.ical_urls` | list | _(empty)_ | Published iCal feed URLs — an HTTP(S) GET of an `.ics` (e.g. a Google/Nextcloud/Outlook "secret iCal address"). **Opt-in egress.** |
| `calendar.caldav` | list | _(empty)_ | CalDAV calendars, each `url\|user\|password`. Point at an **account/principal URL** to auto-discover and sync **all** the user's calendars, or a single **collection URL** for just that one. **Opt-in egress.** |
| `calendar.sync_interval` | number | `30` | Minutes between calendar refreshes (also rolls the timeline window forward). |
| `classic.discover` | bool | `false` | **Experimental.** Discover classic-era Pebbles (Time / Time Steel) over a BR/EDR inquiry and auto-pair + auto-connect them over [Bluetooth Classic](#bluetooth-classic). The RFCOMM channel is resolved via SDP. Inquiry runs only while a pairing window (`stoandl watch pair`) is open. Off by default. |
| `watch.<id>` | varies | _(unset)_ | An advanced watch setting (see [Watch settings](#watch-settings-advanced) below). |

## Bluetooth Classic

> **Experimental** — hardware-verified on a Pebble Time Steel, off by default.

Classic-era Pebbles (Pebble Time / Time Steel, and by class the original Pebble / Steel) connect
reliably only over **Bluetooth Classic** (BR/EDR, RFCOMM/SPP), not BLE — see
[docs/devices.md](devices.md) for the diagnosis. BLE-native watches (Time 2 / Pebble 2) are
unaffected and keep using BLE. The adapter must have **BR/EDR enabled** (the default; *not*
LE-only mode).

The hands-off path is `classic.discover`:

```ini
classic.discover = true            # discover + auto-pair + auto-connect classic-era Pebbles
```

Then `stoandl watch pair` (confirm the 6-digit code on the watch; the host auto-confirms). A BR/EDR
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

**`notification.sync_to_watch`** (off by default) additionally pushes the list + mute states to the
watch via libpebble3's `NotificationAppItem` BlobDB. It's off because current firmware has no per-app
*settings menu* to surface them (muting is via the action menu above, which needs no sync). Kept as an
opt-in for firmware that does. Watch-link only, no web egress.

## Weather

stoandl pushes weather to the watch's built-in **Weather** app. Because it runs headless with no GPS,
locations are fixed in the config rather than tracked — list one or more under `weather.locations`:

```
weather.locations = Berlin:52.52:13.405, London:51.5074:-0.1278
weather.units = metric
weather.interval = 30
```

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

# CalDAV — url|user|password, opt-in egress. An account/principal URL auto-discovers ALL the user's
# calendars; a single collection URL syncs just that one:
calendar.caldav    = https://dav.example.com/dav/alice@example.com/|alice|s3cret

calendar.sync_interval = 30
```

Events are synced for a fixed window of **yesterday through 7 days ahead** (set by libpebble3's
timeline). Recurring events (RRULE/RDATE, minus EXDATE) are expanded to individual pins; all-day
events and per-event timezones are handled. stoandl re-reads on `calendar.sync_interval`, immediately
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
just that one. Auth is **Basic only** (no Digest/OAuth), and credentials sit in `stoandl.conf` in
plaintext — protect the file (a Secret Service/libsecret backend is a possible follow-up). RSVP
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
watch.textStyle = Larger
```

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

- **Plasma Mobile** stores contacts as `.vcf` via the `kpeoplevcard` KPeople backend, typically in
  `~/.local/share/kpeoplevcard/` — point `contacts.vcard_paths` straight at it.
- **GNOME Contacts** / any CardDAV setup (`vdirsyncer`, `khard`) can export/sync a `.vcf` directory.

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
| `firmware.github_prereleases` | `false` | Consider pre-releases too (otherwise only the latest stable release). |
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
**Classic / Rebble watches** pull from Rebble's cohorts service (`GET /cohort?hardware=<board>&select=fw`,
the same contract the classic Pebble app used) — the board is the same `WatchHardwarePlatform.revision`
(e.g. `snowy_dvt`). stoandl routes to the right source via one shared `isCoreDevice()` partition (which
also decides language-pack boards); if the chosen source ships nothing for the board, `firmware check`
reports "no firmware published for board". A watch booted into recovery (PRF) is always offered a
reflash so it can leave recovery.

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

| Key | Default | Meaning |
|-----|---------|---------|
| `health.sync` | `true` | Request a health sync from the watch on every connect (incremental — the first run, with an empty DB, is a full pull). Costs a little watch BLE/battery. |
| `health.export` | `true` | Project the synced data to NDJSON under `~/.config/stoandl/health/` whenever new data arrives. |
| `health.export_samples` | `false` | Also write minute-level samples (steps + heart rate per minute). Much higher volume than the daily summary, so off by default. |
| `health.export_days` | `30` | How many days back each export re-projects (the daily/activities/samples window). Days already written outside the window stay in place. |
