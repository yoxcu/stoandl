# Settings parity — daemon ↔ GUI

Every setting the daemon has, and whether the GUI can reach it. This is the matrix behind
`config/ConfigSchema.kt`'s `ConfigApply` labels: the **Applies** column here is the justification for
each key's `live`/`restart` marking, traced through the code rather than assumed.

The schema itself is the single source of truth for everything else a row carries (label, description,
help, page and section, `requires`, default, egress): read it with `stoandl daemon list` / `stoandl
daemon get <key>`, or in `gui/tools/config-schema.tsv`, the copy the tests generate for the GUI mock.
`ConfigSchemaTest` enforces the mechanical half (every exposed key round-trips through
`StoandlConfig.load`, no duplicates, no framing characters, descriptions of at most 90 characters,
`requires` naming real keys and options, egress rows off by default, `stoandl.conf.example` at the
defaults), but the *Applies* column is prose and only a human can keep it honest.

**Scope.** `stoandl.conf` has 91 top-level keys plus two prefix families (`watch.<prefId>`,
`extension.<name>.<key>`). 87 of them are rendered by the schema-driven Settings pages, which also render
two secrets (`voice.api_key`, `tts.api_key`, kept in the keyring, never in the file); the remaining four
are reachable through a **richer dedicated surface** and are listed in
[§3](#3-keys-deliberately-not-in-the-schema). Nothing is unreachable.

---

## 1. Legend

| Column | Meaning |
| --- | --- |
| **Kind** | `toggle` · `combo` · `text` · `int` · `list` · `secret` — the widget the GUI renders from the schema |
| **Applies** | `live` = takes effect on write · `restart` = the daemon only reads it at startup (surfaced on the row) |
| **Why** / **Validated** | how a live key reaches its consumer; what the write path rejects beyond the type check |
| **Default** | the value of an empty `stoandl.conf`, as the GUI shows it (a combo's label) |

**One control per key.** Every key in §2 is one row on one GUI page (Settings → *page*, under its
section), and nothing else writes it. The service masters (`notification.forward`, `weather.enabled`,
`calendar.enabled`, `music.enabled`, `health.sync`, `dnd.sync`) are ordinary rows, the first of their
page; the old Sync screen, the calendar page's second *discover* switch and the Alerts tab's app-name
combo are gone. `SetSyncEnabled` stays as a D-Bus/CLI alias that writes the same key through the same
path. Forwarding off shows as a banner on the Alerts and Watch tabs with *Turn on*, not as a second
switch.

**Rows the GUI derives from the schema.** A row whose `requires` isn't met is **locked**, not hidden:
the control is off and the subtitle says "· Used when …", but its help button (and long-press,
right-click, F1) still opens the help. A page's last section titled `Advanced <page>` is collapsed. A
`restart` key says "(after restart)" and the page shows which keys wait for one, with *Restart now*
(`Restart`). The help of a `secret` ends with "Stored outside stoandl.conf (in the keyring, else a private
file); key: <key>." A weather or calendar master whose `GetSyncStatus` row is unavailable with
`no source` says "No place yet" / "No calendar source yet"; "Not available here" is only for other
cases (dnd with no desktop backend).

Every `text` and `list` value is additionally rejected if it contains `#` or a newline: `stoandl.conf`
is `key = value`, one per line, with `#` starting a comment, so either would silently truncate or split
the setting on the next read. A comma inside a `list` item is written `\,` (and a backslash `\\`;
`config/ConfList.kt`), so items such as canned replies can hold commas while older plain lists read
back unchanged.

---

## 2. Daemon config keys, by GUI page

### Notifications

| Section | Key | Kind | Applies | Why / Validated | Default |
| --- | --- | --- | --- | --- | --- |
| Forwarding | `notification.forward` | toggle | live | read per push in `WatchNotifier.push` | `true` |
| Forwarding | `notification.only_when_locked` | toggle | live | read per push; asks logind (`SessionLock`) only while on | `false` |
| Forwarding | `dnd.sync` | combo | live | `applyDnd()` rebuilds the mirror; one of off/to_watch/to_host/both | `Off` |
| Forwarding | `notification.catch_up_minutes` | int | live | projected into the pinned `LibPebbleConfig` (`NotificationConfigFlow`), which the fork reads at each connection; 0–1440 min | `60` |
| On the watch | `notification.app_name` | combo | live | read per push and per firmware-update notification; an app's own override (`NotifSetAppName`) wins while `notification.per_app` is on; small/subtitle/off | `Small line` |
| On the watch | `notification.images` | toggle | live | read per push; turning it off deletes the stored pictures | `true` |
| On the watch | `notification.canned_replies` | list | live | read per notification in `WatchNotifier.push` | `Ok,Yes,No,Call me,Call you later` |
| On the watch | `notification.open_action` | toggle | live | read per desktop notification in `DbusNotificationListenerConnection.forward` | `true` |
| On the watch | `notification.sync_dismissals` | toggle | live | read per desktop close / withdrawal | `true` |
| Per app | `notification.per_app` | toggle | live | read per push in `WatchNotifier.push` | `true` |
| Per app | `notification.default_mute` | combo | live | read per newly-tracked app; never/always/weekdays/weekends | `Unmuted` |
| Calls | `call.dialer_apps` | list | live | read per notification | `calls` |
| Calls | `contacts.vcard_paths` | list | live | `ContactResolver` re-reads on its file-signature check | `~/.local/share/kpeoplevcard` |
| Messages from stoandl | `alerts.enabled` and `alerts.pairing`, `alerts.bluetooth`, `alerts.extensions`, `alerts.permissions`, `alerts.battery_full`, `alerts.scan_failed` | toggle | live | read per alert via `alertsAllow` | `true` |
| Advanced notifications | `notification.sync_to_watch` | toggle | **restart** | decides a Koin binding (`PlatformConfig(syncNotificationApps=…)`) built once in `init()` | `false` |

**Messages from stoandl** are what stoandl says *about itself*, mostly on the host desktop (permission
prompts and the full-battery alert also show on the watch), as opposed to forwarded app notifications.
Muting an alert silences the popup only — the condition is still logged at WARN, so a muted alert never
hides a diagnosis. Each sub-key `requires alerts.enabled=true`. `alerts.extensions` covers both "needs
setup" and "stopped" (quarantined after rapid failures, with its last error and a *Restart* button).
`firmware.notify` is a further alert but keeps its own key, because it also drives a **watch**
notification with an Update button — see [§5](#5-redundancy-noted).

`stoandl notif mute-all` is not a setting: it is a temporary state (`NotifSetMuteAll`) apart from the
per-app mutes, and works whatever `notification.per_app` says.

### Weather

All `weather.*` keys reconcile through `applyWeather()`, which tears down and rebuilds `WeatherSync`.

| Section | Key | Kind | Applies | Validated | Default |
| --- | --- | --- | --- | --- | --- |
| Weather | `weather.enabled` | toggle | live | — (contacts nothing until a place is added) | `true` |
| Weather | `weather.locations` | list | live | parsed with `StoandlConfig.parseWeatherLocations` — the very function that reads it back, so the GUI cannot persist entries the daemon would drop at load | _(empty)_ |
| Weather | `weather.place_search` | toggle | live | — (opt-in egress; requires `weather.enabled`) | `false` |
| Weather | `weather.gps` | toggle | live | — | `false` |
| Weather | `weather.reverse_geocode` | toggle | live | — (opt-in egress) | `false` |
| Weather on the watch | `weather.pins` | toggle | live | — | `true` |
| Weather on the watch | `weather.intercept_pkjs` | toggle | live | — (opt-in egress) | `false` |
| Weather on the watch | `weather.interval` | int | live | 5–1440 min | `30` |
| Advanced weather | `weather.location_source` | combo | live | manual/gnome/command | `None` |
| Advanced weather | `weather.location_command` | text | live | — (requires the source `A command`) | _(empty)_ |
| Advanced weather | `weather.gps_name` | text | live | — (requires `weather.gps`) | `Current location` |

`weather.gps_desktop_id` (the GeoClue identity, also used for watchapp geolocation) is on the Apps &
plugins page, below.

### Calendar

| Section | Key | Kind | Applies | Why | Default |
| --- | --- | --- | --- | --- | --- |
| Calendar | `calendar.enabled` | toggle | live | (contacts nothing until a source is added) | `true` |
| Calendar | `calendar.discover` | toggle | live | sources are re-read per enumeration | `false` |
| Reminders | `calendar.reminders` | toggle | live | projected into libpebble3's `WatchConfig` (`libPebbleConfigOf`) | `true` |
| Reminders | `calendar.reminder_vibe` | combo | live | ”; the options are the built-ins plus the user's custom vibrations, read per use | `Watch default` |
| Reminders | `calendar.show_declined` | toggle | live | ” | `false` |
| Advanced calendar | `calendar.sync_interval` | int | live¹ | the ticker re-samples it each tick | `30` min |

¹ Applies at once: the ticker is keyed on a `StateFlow` of the interval, so changing the value cancels
the pending delay and starts counting the new one. Because a `StateFlow` conflates equal values, a
calendar write that does *not* change the interval leaves the countdown running — which is what stops
repeated edits from starving the sync.

The calendar sources themselves are on the same page but are not keys (§3).

### Music

| Section | Key | Kind | Applies | Validated | Default |
| --- | --- | --- | --- | --- | --- |
| Music | `music.enabled` | toggle | live | — | `true` |
| Music | `music.volume` | combo | live | system/player | `System volume` |
| Music | `music.seek_when_available` | toggle | live | — (applied when MPRIS publishes the status) | `true` |
| Advanced music | `music.volume_up_command`, `music.volume_down_command` | text | live | — (require `System volume`) | _(empty)_ |

### Voice

| Section | Key | Kind | Applies | Validated | Default |
| --- | --- | --- | --- | --- | --- |
| Speech to text | `voice.backend` | combo | live | off/local/command/http | `Off` |
| Speech to text | `voice.local_model` | combo | live | an installed model (options read per use) | _(none installed)_ |
| Speech to text | `voice.language` | text | live | a language code or `auto` | `auto` |
| Speech to text | `voice.fallback_backend` | combo | live | off/local/command/http; uses the same settings below | `None` |
| Text to speech | `tts.backend` | combo | live | off/local/command/http | `Off` |
| Text to speech | `tts.local_voice` | combo | live | an installed voice, or `espeak-ng` while it is installed (options read per use) | _(none installed)_ |
| Text to speech | `tts.speed` | int | live | 50–200 % | `100` |
| Advanced voice | `voice.command` | text | live | — | _(empty)_ |
| Advanced voice | `voice.url` | text | live | `http(s)://…` | _(empty)_ |
| Advanced voice | `voice.http_model` | text | live | — | _(empty)_ |
| Advanced voice | `voice.api_key` | secret | live | single line; keyring (or a 0600 file), never `stoandl.conf` | _(unset)_ |
| Advanced voice | `voice.save_audio` | toggle | live | — | `false` |
| Advanced voice | `tts.command` | text | live | — | _(empty)_ |
| Advanced voice | `tts.url` | text | live | `http(s)://…` | _(empty)_ |
| Advanced voice | `tts.http_model`, `tts.http_voice` | text | live | — | _(empty)_ |
| Advanced voice | `tts.api_key` | secret | live | as `voice.api_key`, but its own key: never inherited from it | _(unset)_ |

Every voice key is read per dictation or per synthesis (`SpeechToText`/`TtsBackends.current()`), so there
is nothing to reconcile. A finished model or voice download writes `voice.local_model`/`tts.local_voice`
(and turns an `off` backend to `local`) through the same write path, so the GUI hears `ConfigChanged`.

### Health & battery

| Section | Key | Kind | Applies | Validated | Default |
| --- | --- | --- | --- | --- | --- |
| Health | `health.sync` | toggle | live | — | `true` |
| Health | `health.export` | toggle | live | — | `true` |
| Health | `health.export_samples` | toggle | live | — | `false` |
| Health | `health.export_days` | int | live | 1–365 days | `30` |
| Battery | `battery.heartbeat` | toggle | live | — | `true` |
| Battery | `battery.history` | toggle | live | — | `true` |
| Battery | `battery.retention_days` | int | live | 1–3650 days | `90` |

`music.*`/`health.*`/`battery.*` reconcile through `applyMusic()`/`applyHealth()`/`applyBattery()`.

### Apps & plugins

| Section | Key | Kind | Applies | Why | Default |
| --- | --- | --- | --- | --- | --- |
| Apps | `apps.order_faces_by_last_used` | toggle | live | projected into libpebble3's `WatchConfig`, which `Locker` reads per activation | `false` (keeps today's order; the official app turns it on at first run) |
| Apps | `geolocation.enabled` | toggle | live | read per request in `LiveGeolocation` | `false` |
| Plugins and permissions | `plugins.enabled` | toggle | live | — | `false` |
| Plugins and permissions | `plugins.permission_policy` | combo | live | ask_if_exfiltrating/always_ask/never_ask | `When data could leave` |
| Plugins and permissions | `plugins.ask_legacy_apps` | toggle | live | — | `false` |
| Advanced apps & plugins | `weather.gps_desktop_id` | text | live² | — | `stoandl` |

² Also the GeoClue identity for weather GPS. `LiveGeolocation`'s provider cache is keyed on the id.

### Connection & power

| Section | Key | Kind | Applies | Why | Default |
| --- | --- | --- | --- | --- | --- |
| Connection | `connection.autoswitch` | toggle | live | read inside the wrist-follower loop | `true` |
| Connection | `connection.disconnect_persists` | toggle | live | read at startup, when `ManualDisconnect.restore()` finds the pause marker; nothing to apply before then | `true` |
| Connection | `classic.discover` | toggle | **restart** | `startClassicWatch()` is a one-shot check in `init()` | `true` |
| Phone battery | `power.quiet_time_link_off` | toggle | live | a live `StateFlow` `QuietTimeLink` follows: on, it evaluates on every tick, resume and display change; turning it on while the display is off pauses at once, off releases the hold at once | `false` |
| Phone battery | `power.pause_datalog_screen_off` | toggle | live | the datalog pause follows the shared display state only while this is on, and the before-sleep hook reads it; turning it off while paused resumes the sends | `false` |
| Advanced connection & power | `power.sleep_guard` | toggle | **restart** | `SleepGuard` takes or skips its logind delay lock once, at construction in `init()` | `true` |
| Advanced connection & power | `power.sleep_guard_max_ms` | int | **restart** | handed to `SleepGuard` at construction; 0–4500 ms | `3000` |

See [deep-sleep.md](deep-sleep.md) for the phone-battery keys.

### Firmware & language

`FirmwareControl` and `LanguageControl` take a live getter, and the firmware sources are rebuilt per
check.

| Section | Key | Kind | Applies | Validated | Default |
| --- | --- | --- | --- | --- | --- |
| Updates and downloads | `firmware.github` | toggle | live | — (opt-in egress) | `false` |
| Updates and downloads | `firmware.cohorts` | toggle | live | — (opt-in egress) | `false` |
| Updates and downloads | `firmware.notify` | toggle | live | — (requires a source on) | `true` |
| Updates and downloads | `firmware.github_prereleases` | toggle | live | — | `false` |
| Updates and downloads | `language.download` | toggle | live | — (opt-in egress) | `false` |
| Advanced firmware & language | `firmware.github_repo` | text | live | `owner/repo` | `coredevices/PebbleOS` |
| Advanced firmware & language | `firmware.cohorts_url` | text | live | `http(s)://…` | `https://cohorts.rebble.io` |

### Developer

Shown on Settings → Diagnostics & developer, above the support bundle.

| Section | Key | Kind | Applies | Why | Default |
| --- | --- | --- | --- | --- | --- |
| Developer | `developer.autostart` | toggle | live | the on-connect hook is always registered and the switch checked inside it | `false` |
| Developer | `datalog.enabled` | toggle | **restart** | `DatalogStore` is started (or not) once in `init()` | `false` |

**Five keys are restart-required** — `notification.sync_to_watch`, `classic.discover`,
`datalog.enabled`, `power.sleep_guard`, `power.sleep_guard_max_ms`. All of them decide startup wiring (a
DI binding, a transport, a subscriber, the logind lock) that cannot be rebuilt safely at runtime.
(`notification.catch_up_minutes` used to be one; it is now projected live and applies from the next
connection.) The GUI says so on the row and offers *Restart now*; `stoandl daemon restart` does the same.

---

## 3. Keys deliberately NOT in the schema

Not gaps — each has a richer surface that a flat key/value row cannot express.

| Key(s) | Surface | GUI |
| --- | --- | --- |
| `calendar.ics_paths`, `calendar.ical_urls`, `calendar.caldav` | `AddCalendarSource`/`UpdateCalendarSource`/`RemoveCalendarSource`, with each source's problem and last read in `ListCalendarSources` — CalDAV passwords are write-only and live in the keyring, never in config | Settings → Calendar |
| `watch.<prefId>` | `ListWatchPrefs`/`SetWatchPref`/`ResetWatchPrefs` — typed per pref (bool/number/enum/quicklaunch/colour/schedule) with the allowed set read off the watch, and the section, help and requires text from `pebble/WatchPrefText.kt` | Settings → Watch preferences |
| `extensions.enabled`, `extension.<name>.<key>` | `ExtEnable`/`ExtDisable`, `ExtConfigSchema`/`ExtGetConfig`/`ExtSetConfig` — each extension ships its own typed schema | Apps → Extensions |

Both of these used to have a **precedence trap** where `stoandl.conf` silently beat the GUI. Fixed:

- **`extension.<name>.<key>`** — settings merge as *manifest defaults < `stoandl.conf` < the extension's
  own `config` file*, i.e. the file the GUI and `stoandl ext config` write wins. A leftover conf key that
  the file also sets is logged once at resolve time so it can be cleaned up. `cmd` keeps its old
  precedence (stoandl.conf first) — an explicit `cmd` is how you rescue an extension whose own config is
  broken.
- **`watch.<prefId>`** — `SetWatchPref` (and `ResetWatchPrefs`) also rewrite the conf key, **but only when
  that key is already pinned there**. `config.watchPrefs` stays authoritative on connect (so a pinned pref
  still wins over an on-watch change), it just can no longer revert a GUI/CLI change. A pref you have
  *not* pinned is untouched, so `watch.*` stays an opt-in pin list rather than becoming a mirror.

---

## 4. Notification / alert events

Every notification the daemon can originate (i.e. not a forwarded desktop notification), and the setting
that governs it. "Choke point" = `WatchNotifier.push`, which enforces the regex filters (an `allow` match
skips everything after), then `notification.forward`, then the global mute (`notif mute-all`), then
`notification.only_when_locked`, then per-app mute — in that order.

| Event | Surface | Through the choke point? | Governed by |
| --- | --- | --- | --- |
| Forwarded desktop notification | watch | yes | `notification.forward`, `notification.only_when_locked` (critical urgency exempt), `notif mute-all`, per-app mute, filters |
| Extension notification | watch | yes | same |
| Test notification (`SendTestNotification`) | watch | **yes, deliberately** — the test is only useful if the policy applies to it | same, except `notification.only_when_locked` (like the official app's test, it would never arrive while you're at the screen sending it) |
| Firmware update available | watch **and** desktop | no (own path, so the Update button works) | `firmware.notify` + a source enabled; on the watch, its app name ("stoandl") follows `notification.app_name` |
| "Pebble won't stay connected" (broken bond) | desktop, with Re-pair | n/a | `alerts.enabled` + `alerts.pairing` |
| "Pebble pairing removed" (host bond lost) | desktop, with Pair | n/a | `alerts.enabled` + `alerts.pairing` |
| "Blocked by a Bluetooth scan" | desktop | n/a | `alerts.enabled` + `alerts.bluetooth` |
| "<extension> needs setup" | desktop | n/a | `alerts.enabled` + `alerts.extensions` |
| "<extension> stopped" (quarantined after 5 rapid failures; its last stderr line, a Restart button) | desktop | n/a | `alerts.enabled` + `alerts.extensions` |
| "Pebble pairing can't search" (discovery keeps failing during a pairing window) | desktop, once per window | n/a | `alerts.enabled` + `alerts.scan_failed` |
| "Watch fully charged" | watch **and** desktop | yes (the watch half) | `alerts.enabled` + `alerts.battery_full`; on the watch also per-app mute of "stoandl" |
| Incoming/missed call | watch (native call screen) | no — telephony has its own path | `call.dialer_apps` suppresses the duplicate app notification |
| Find my watch | watch (ring) | no — reuses the call path | on demand only |
| Weather / calendar pins | watch timeline | no — pins are not notifications | `weather.pins`, `calendar.enabled` |

**Thresholds.** None of these events is user-threshold-based: the two internal thresholds (the
broken-bond flap count, and the tick count before the Bluetooth-blocked warning) are debounce
constants, not policy — exposing them would be a knob with no right answer. The full-battery alert
fires at 100 % and re-arms at ≤ 97 %, both fixed.

**Test action.** `SendTestNotification` is reachable from the GUI on **Alerts → Send test** and
Settings → Diagnostics & developer.

---

## 5. Redundancy noted

Per the project's preference for a single source of truth, these are called out rather than silently
left alone:

1. **The sync masters had two controls** (the Sync screen switch and the settings row) — **resolved
   2026-10**: one row per key; the Sync screen is gone, `SetSyncEnabled` is an alias of `SetConfig`, and
   `dnd.sync` is a single four-way combo (the old switch's "remember the direction" state is gone with it;
   `SetSyncEnabled("dnd", …)` now answers with a hint to use `dnd.sync`). Forwarding is a banner, not a
   second switch, on the tabs that need to show it.
2. **`firmware.notify` vs `alerts.*`** — deliberately not unified: `firmware.notify` gates a *watch*
   notification too, so folding it under `alerts.enabled` would make one switch mean two different
   things. Documented in both KDoc and the row's help.

---

## 6. Wire format

`GetConfigSchema` emits sixteen tab-separated columns:

```
key \t type \t label \t options \t desc \t group \t apply \t min \t max \t unit \t placeholder \t page \t help \t requires \t default \t egress
```

The first five are the original contract; columns 6 onward were **appended**, and both clients index
positionally with a fallback per column, so an old client against a new daemon (reads fewer, ignores the
rest) and a new client against an old daemon (missing columns → empty) both work. The meaning and grammar
of each column (`requires` is `key=value`/`key!=value` alternatives joined by `|`) are in
[dbus-interface.md → Daemon config](dbus-interface.md#daemon-config-stoandlconf-over-d-bus).
`ConfigSchemaTest` pins the column count and order, and asserts no label, description, section or combo
option can contain a tab, newline or comma — any of which would corrupt the record for both parsers.

`GetConfig` is unchanged: `key \t value`, where value is a combo's option **label**, a toggle's
`true`/`false`, the raw text/number/comma-joined list (`\,` escaping a comma inside an item), or `set`/empty
for a secret.

`SetConfig` returns `ok:` / `notfound:` / `error:<reason>`, and takes a combo's label or its raw token. A
restart-required key's `ok:` tail carries “(restart stoandl to apply)”. Every change — `SetConfig`, the
`SetSyncEnabled` alias, the daemon's own writes, a hand edit of `stoandl.conf` — is applied by the same
reload (500 ms after the file settles) and announced with one `ConfigChanged(key)` per changed key.

**Combo labels changed in 2026-10, tokens did not.** A script that passed an old label to `stoandl daemon
set` must use the token or the new label:

| Key | Old label → new label |
| --- | --- |
| `notification.default_mute` | Never/Always/Weekdays/Weekends → Unmuted/Muted/Muted on weekdays/Muted at weekends |
| `dnd.sync` | Off/To watch/To host/Both → Off/Desktop → watch/Watch → desktop/Both ways |
| `music.volume` | System/Player → System volume/Player volume |
| `weather.location_source` | Manual/GNOME/Command → None/GNOME Weather/A command |
| `plugins.permission_policy` | If data could leave this machine/Always/Never → When data could leave/Always/Never |

---

## 7. CLI

`stoandl daemon list | get <key> | set <key> <value> | restart` is the CLI half of the same methods —
same keys, same validation, same restart marking. `list` groups the keys as the GUI pages do ("Page ›
Section") with a `(restart)` marker; `get` prints the value on stdout and, on stderr so it stays pipeable,
the label and description, the help, the default, when it matters (`# used when: Weather is on`), what it
contacts (`# contacts: …`) and what it accepts. `restart` is `Restart`. `stoandl sync list` shows each
service's key; `stoandl sync enable|disable <service>` is the alias, and `stoandl sync dnd` points at
`stoandl daemon set dnd.sync <mode>`. `stoandl settings reset` resets the watch preferences
(`ResetWatchPrefs`).
