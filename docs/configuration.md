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
| `weather.locations` | list | _(empty)_ | Locations to fetch weather for, each as `Name:lat:lon` (e.g. `Berlin:52.52:13.405`). Merged with `weather.location_source`. |
| `weather.location_source` | string | `manual` | Where extra locations come from: `manual` (only the list above), `gnome` (read GNOME/Phosh's weather GSettings), or `command` (run `weather.location_command`). |
| `weather.location_command` | string | _(empty)_ | For `weather.location_source = command`: a shell command that prints `Name:lat:lon` lines. |
| `weather.units` | string | `metric` | Temperature unit sent to the watch: `metric` (°C) or `imperial` (°F). |
| `weather.interval` | number | `30` | Minutes between weather refreshes. |
| `weather.gps` | bool | `false` | Add a GeoClue2-tracked **current location** entry (shown first on the watch) alongside the fixed locations. |
| `weather.gps_desktop_id` | string | `stoandl` | GeoClue `DesktopId` — must match the allow-list entry in `/etc/geoclue/geoclue.conf` (see below). |
| `weather.gps_name` | string | `Current location` | Label for the GPS entry (used as-is unless `weather.reverse_geocode` is on). |
| `weather.reverse_geocode` | bool | `false` | Reverse-geocode GPS coordinates to a place name via OSM Nominatim. Off by default — it discloses your coordinates to a third-party web service. |

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
