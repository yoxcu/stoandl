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
| `notification.blocklist` | list | _(empty)_ | App-name substrings (case-insensitive) whose notifications are never forwarded to the watch. |
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
| `calendar.ics_paths` | list | _(empty)_ | Local `.ics` files or directories (scanned for `*.ics`) to sync to the watch timeline. `~` expands to `$HOME`. No egress. Setting any `calendar.*` source enables calendar sync. |
| `calendar.discover` | bool | `false` | Auto-discover calendars the desktop keeps as local `.ics` (e.g. Calindori on Plasma Mobile, `~/.calendars`). No egress. |
| `calendar.ical_urls` | list | _(empty)_ | Published iCal feed URLs — an HTTP(S) GET of an `.ics` (e.g. a Google/Nextcloud/Outlook "secret iCal address"). **Opt-in egress.** |
| `calendar.caldav` | list | _(empty)_ | CalDAV collections to read, each `url\|user\|password`. **Opt-in egress.** |
| `calendar.sync_interval` | number | `30` | Minutes between calendar refreshes (also rolls the timeline window forward). |
| `watch.<id>` | varies | _(unset)_ | An advanced watch setting (see [Watch settings](#watch-settings-advanced) below). |

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

# CalDAV collection — url|user|password, opt-in egress:
calendar.caldav    = https://dav.example.com/cal/me/work/|alice|s3cret

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

CalDAV here is a **collection URL + Basic auth** only (no account discovery, Digest or OAuth), and
the credentials sit in `stoandl.conf` in plaintext — protect the file (a Secret Service/libsecret
backend is a possible follow-up). RSVP (accept/decline from the watch) isn't wired up yet. A single
edited occurrence of a recurring event shows at its original time (detached overrides are skipped to
avoid duplicates).

### Network / external services

Local `.ics` files and discovery make **no web requests.** The two network sources are opt-in:

| Service | What is sent | When it's called |
|---------|--------------|------------------|
| iCal feed URL(s) | an HTTP(S) GET to each `calendar.ical_urls` entry | only when `calendar.ical_urls` is set |
| CalDAV server(s) | a `calendar-query` REPORT (+ Basic auth) to each `calendar.caldav` collection | only when `calendar.caldav` is set |

## Watch settings (advanced)

The official companion app exposes "advanced" watch settings that the watch's own menus don't —
quick-launch button mappings, ambient-light threshold, backlight, vibration patterns, etc. stoandl can
set the same ones (they live in the watch's settings BlobDB).

Discover them with the CLI — it lists every setting, its current value and allowed values:

```sh
stoandl settings                  # all settings (one row each)
stoandl settings light            # filter by id/name substring
stoandl set-setting lightAmbientThreshold 200
stoandl set-setting qlUp "Music"  # hold-Up quick-launches the Music app (by name or UUID; "off" to clear)
stoandl set-setting clock24h true
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
