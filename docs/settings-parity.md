# Settings parity — daemon ↔ GUI

Every setting the daemon has, and whether the GUI can reach it. This is the matrix behind
`config/ConfigSchema.kt`'s `ConfigApply` labels: the **Applies** column here is the justification for
each key's `live`/`restart` marking, traced through the code rather than assumed.

Keep it in sync with `GUI_CONFIG_FIELDS` — `ConfigSchemaTest` enforces the mechanical half (every
exposed key round-trips through `StoandlConfig.load`, no duplicates, no framing characters), but the
*Applies* column is prose and only a human can keep it honest.

**Scope.** `stoandl.conf` has 57 top-level keys plus two prefix families (`watch.<prefId>`,
`extension.<name>.<key>`). 50 are rendered by the schema-driven Settings page; the remaining ones are
reachable through a **richer dedicated surface** and are listed in [§3](#3-keys-deliberately-not-in-the-schema).
Nothing is unreachable.

---

## 1. Legend

| Column | Meaning |
| --- | --- |
| **Kind** | `toggle` · `combo` · `text` · `int` · `list` — the widget both front-ends render from the schema |
| **GUI** | ✓ = rendered on Settings → Daemon configuration; the alternative surface otherwise |
| **Applies** | `live` = takes effect on write · `restart` = the daemon only reads it at startup (surfaced on the row) |
| **Validated** | what the write path rejects beyond the type check |

Every `text` and `list` value is additionally rejected if it contains `#` or a newline: `stoandl.conf`
is `key = value`, one per line, with `#` starting a comment, so either would silently truncate or split
the setting on the next read.

---

## 2. Daemon config keys

### Notifications

| Key | Kind | GUI | Applies | Why | Validated | Default |
| --- | --- | --- | --- | --- | --- | --- |
| `notification.per_app` | toggle | ✓ | live | read per push in `WatchNotifier.push` | — | `true` |
| `notification.default_mute` | combo | ✓ | live | read per newly-tracked app (was a startup snapshot; **fixed this session**) | one of never/always/weekdays/weekends | `Never` |
| `notification.sync_to_watch` | toggle | ✓ | **restart** | decides a Koin binding (`PlatformConfig(syncNotificationApps=…)`) built once in `init()` | — | `false` |
| `notification.catch_up_minutes` | int | ✓ | **restart** | part of the host-built `LibPebbleConfig` that `init()` pins into `NotificationConfigFlow` once | 0–1440 min | `10` |
| `notification.canned_replies` | list | ✓ | live | read per notification in `WatchNotifier.push` | — | `Ok,Yes,No,Call me,Call you later` |
| `notification.forward` | toggle | Sync screen | live | read per push; `SetSyncEnabled("notifications", …)` | — | `true` |

### stoandl alerts — the daemon's own desktop alerts (new this session)

Distinct from forwarded app notifications: these are things stoandl says *about itself*, on the host
desktop. Previously unconditional with no way to silence them. Muting an alert silences the popup only —
the condition is still logged at WARN, so a muted alert never hides a diagnosis.

| Key | Kind | GUI | Applies | Why | Default |
| --- | --- | --- | --- | --- | --- |
| `alerts.enabled` | toggle | ✓ | live | read per alert via `alertsAllow` | `true` |
| `alerts.pairing` | toggle | ✓ | live | ” — gates "won't stay connected" / "pairing removed" | `true` |
| `alerts.bluetooth` | toggle | ✓ | live | ” — gates "blocked by a Bluetooth scan" | `true` |
| `alerts.extensions` | toggle | ✓ | live | ” — gates "extension needs setup" | `true` |

`firmware.notify` is the fifth alert but keeps its own older key, because it also drives a **watch**
notification with an Update button. One switch per event, not two — see [§5](#5-redundancy-noted).

### Calls & contacts

| Key | Kind | GUI | Applies | Why | Default |
| --- | --- | --- | --- | --- | --- |
| `call.dialer_apps` | list | ✓ | live | read per notification (was a constructor snapshot; **fixed this session**) | `calls` |
| `contacts.vcard_paths` | list | ✓ | live | `ContactResolver` re-reads on its file-signature check (was a snapshot; **fixed**) | `~/.local/share/kpeoplevcard` |

### Weather

All `weather.*` keys reconcile through `applyWeather()`, which tears down and rebuilds `WeatherSync`.

| Key | Kind | GUI | Applies | Validated | Default |
| --- | --- | --- | --- | --- | --- |
| `weather.locations` | list | ✓ | live | parsed with `StoandlConfig.parseWeatherLocations` — the very function that reads it back, so the GUI cannot persist entries the daemon would drop at load | _(empty)_ |
| `weather.location_source` | combo | ✓ | live | Manual/GNOME/Command | `Manual` |
| `weather.location_command` | text | ✓ | live | — | _(empty)_ |
| `weather.interval` | int | ✓ | live | 5–1440 min | `30` |
| `weather.gps` | toggle | ✓ | live | — | `false` |
| `weather.gps_name` | text | ✓ | live | — | `Current location` |
| `weather.gps_desktop_id` | text | ✓ | live¹ | — | `stoandl` |
| `weather.reverse_geocode` | toggle | ✓ | live | — | `false` |
| `weather.pins` | toggle | ✓ | live | — | `true` |
| `weather.enabled` | toggle | Sync screen | live | `SetSyncEnabled("weather", …)` | `true` |

¹ Also used as the GeoClue identity for **watchapp** geolocation. `LiveGeolocation` used to cache its
provider forever, so the watchapp path kept the old id until a restart; the cache is now keyed on the id
(**fixed this session**).

### Calendar

| Key | Kind | GUI | Applies | Why | Default |
| --- | --- | --- | --- | --- | --- |
| `calendar.discover` | toggle | ✓ | live | sources are re-read per enumeration | `false` |
| `calendar.sync_interval` | int | ✓ | live² | the ticker re-samples it each tick (was captured once; **fixed this session**) | `30` min |
| `calendar.enabled` | toggle | Sync screen | live | `SetSyncEnabled("calendar", …)` | `true` |

² Applies at once: the ticker is keyed on a `StateFlow` of the interval, so changing the value cancels
the pending delay and starts counting the new one. Because a `StateFlow` conflates equal values, a
calendar write that does *not* change the interval leaves the countdown running — which is what stops
repeated edits from starving the sync.

### Music · Health · Battery

| Key | Kind | GUI | Applies | Validated | Default |
| --- | --- | --- | --- | --- | --- |
| `music.enabled` | toggle | ✓ (+ Sync screen) | live | — | `true` |
| `music.volume` | combo | ✓ | live | System/Player | `System` |
| `music.volume_up_command` | text | ✓ | live | — | _(empty)_ |
| `music.volume_down_command` | text | ✓ | live | — | _(empty)_ |
| `health.sync` | toggle | ✓ (+ Sync screen) | live | — | `true` |
| `health.export` | toggle | ✓ | live | — | `true` |
| `health.export_samples` | toggle | ✓ | live | — | `false` |
| `health.export_days` | int | ✓ | live | 1–365 days | `30` |
| `battery.heartbeat` | toggle | ✓ | live | — | `true` |
| `battery.history` | toggle | ✓ | live | — | `true` |
| `battery.retention_days` | int | ✓ | live | 1–3650 days | `90` |

`music.*`/`health.*`/`battery.*` all reconcile through `applyMusic()`/`applyHealth()`/`applyBattery()`.

### Firmware · Language

`FirmwareControl` and `LanguageControl` used to be handed a **frozen config snapshot**, so every key
below persisted and read back changed while doing nothing until a restart. They now take a live getter,
and the firmware sources are rebuilt per check (**fixed this session**).

| Key | Kind | GUI | Applies | Validated | Default |
| --- | --- | --- | --- | --- | --- |
| `firmware.notify` | toggle | ✓ | live | — | `true` |
| `firmware.github` | toggle | ✓ | live | — | `false` |
| `firmware.github_repo` | text | ✓ | live | `owner/repo` | `coredevices/PebbleOS` |
| `firmware.github_prereleases` | toggle | ✓ | live | — | `false` |
| `firmware.cohorts` | toggle | ✓ | live | — | `false` |
| `firmware.cohorts_url` | text | ✓ | live | `http(s)://…` | `https://cohorts.rebble.io` |
| `language.download` | toggle | ✓ | live | — | `false` |

### Connection · Do Not Disturb · Privacy · Developer

| Key | Kind | GUI | Applies | Why | Default |
| --- | --- | --- | --- | --- | --- |
| `classic.discover` | toggle | ✓ | **restart** | `startClassicWatch()` is a one-shot check in `init()` | `true` |
| `connection.autoswitch` | toggle | ✓ | live | read inside the wrist-follower loop | `true` |
| `dnd.sync` | combo | ✓ (+ Sync screen) | live | `applyDnd()` rebuilds the mirror | `Off` |
| `geolocation.enabled` | toggle | ✓ | live | read per request in `LiveGeolocation` | `false` |
| `datalog.enabled` | toggle | ✓ | **restart** | `DatalogStore` is started (or not) once in `init()` | `false` |
| `developer.autostart` | toggle | ✓ | live | the on-connect hook is always registered and the switch checked inside it (its *registration* used to be gated, so "on" needed a restart and "off" kept auto-starting — **fixed this session**) | `false` |

### Deep sleep

The settings for phones that suspend whenever the display is off ([deep-sleep.md](deep-sleep.md)).

| Key | Kind | GUI | Applies | Why | Validated | Default |
| --- | --- | --- | --- | --- | --- | --- |
| `power.sleep_guard` | toggle | ✓ | **restart** | `SleepGuard` takes or skips its logind delay lock once, at construction in `init()` | — | `true` |
| `power.sleep_guard_max_ms` | int | ✓ | **restart** | handed to `SleepGuard` at construction | 0–4500 ms | `3000` |
| `power.pause_datalog_screen_off` | toggle | ✓ | live | read per tick of the datalog poll and in the before-sleep hook; the poll always runs, so turning it off while the watch is paused resumes it within ~5 s | — | `false` |
| `ble.conn_params` | text | ✓ | **restart** | part of the `BleConfig` in the `LibPebbleConfig` that `init()` pins | decoded with `StoandlConfig.decodeConnParams`, the function `load()` uses, including `BleConnParamSet.validate()`; empty or `off` = off | _(off)_ |
| `ble.conn_params_fast` | text | ✓ | **restart** | ” (only used together with `ble.conn_params`) | ” | _(off)_ |

**Eight keys are restart-required** — `notification.sync_to_watch`, `notification.catch_up_minutes`,
`classic.discover`, `datalog.enabled`, `power.sleep_guard`, `power.sleep_guard_max_ms`,
`ble.conn_params`, `ble.conn_params_fast`. All of them decide startup wiring (a DI binding or the
pinned `LibPebbleConfig`, a transport, a subscriber, the logind lock) that cannot be rebuilt safely at
runtime. Both front-ends say so on the row.

---

## 3. Keys deliberately NOT in the schema

Not gaps — each has a richer surface that a flat key/value row cannot express.

| Key(s) | Surface | GUI |
| --- | --- | --- |
| `calendar.ics_paths`, `calendar.ical_urls`, `calendar.caldav` | `AddCalendarSource`/`UpdateCalendarSource`/`RemoveCalendarSource` — CalDAV passwords are write-only and live in the keyring, never in config | Settings → Calendars |
| `watch.<prefId>` | `ListWatchPrefs`/`SetWatchPref` — typed per pref (bool/number/enum/quicklaunch/colour/schedule) with the allowed set read off the watch | Settings → Watch settings |
| `extensions.enabled`, `extension.<name>.<key>` | `ExtEnable`/`ExtDisable`, `ExtConfigSchema`/`ExtGetConfig`/`ExtSetConfig` — each extension ships its own typed schema | Apps → Extensions |
| `notification.forward`, `weather.enabled`, `calendar.enabled` | `GetSyncStatus`/`SetSyncEnabled` — three of the six master switches, which also report availability and last-sync. (The other three — `music.enabled`, `health.sync`, `dnd.sync` — **are** in the schema as well; see [§5](#5-redundancy-noted).) | Settings → Sync (and the Alerts screen for forwarding) |

Both of these used to have a **precedence trap** where `stoandl.conf` silently beat the GUI. Fixed:

- **`extension.<name>.<key>`** — settings now merge as *manifest defaults < `stoandl.conf` < the
  extension's own `config` file*, i.e. the file the GUI and `stoandl ext` write now wins. A leftover
  conf key that the file also sets is logged once at resolve time so it can be cleaned up. `cmd` keeps
  its old precedence (stoandl.conf first) — an explicit `cmd` is how you rescue an extension whose own
  config is broken.
- **`watch.<prefId>`** — `SetWatchPref` now also rewrites the conf key, **but only when that key is
  already pinned there**. `config.watchPrefs` stays authoritative on connect (so a pinned pref still
  wins over an on-watch change), it just can no longer revert a GUI/CLI change. A pref you have *not*
  pinned is untouched, so `watch.*` stays an opt-in pin list rather than becoming a mirror.

---

## 4. Notification / alert events

Every notification the daemon can originate (i.e. not a forwarded desktop notification), and the setting
that governs it. "Choke point" = `WatchNotifier.push`, which enforces the regex filters, then
`notification.forward`, then per-app mute — in that order.

| Event | Surface | Through the choke point? | Governed by |
| --- | --- | --- | --- |
| Forwarded desktop notification | watch | yes | `notification.forward`, per-app mute, filters |
| Extension notification | watch | yes | same |
| Test notification (`SendTestNotification`) | watch | **yes, deliberately** — the test is only useful if the policy applies to it | same |
| Firmware update available | watch **and** desktop | no (own path, so the Update button works) | `firmware.notify` + a source enabled |
| "Pebble won't stay connected" (broken bond) | desktop, with Re-pair | n/a | `alerts.enabled` + `alerts.pairing` |
| "Pebble pairing removed" (host bond lost) | desktop, with Pair | n/a | `alerts.enabled` + `alerts.pairing` |
| "Blocked by a Bluetooth scan" | desktop | n/a | `alerts.enabled` + `alerts.bluetooth` |
| "<extension> needs setup" | desktop | n/a | `alerts.enabled` + `alerts.extensions` |
| Incoming/missed call | watch (native call screen) | no — telephony has its own path | `call.dialer_apps` suppresses the duplicate app notification |
| Find my watch | watch (ring) | no — reuses the call path | on demand only |
| Weather / calendar pins | watch timeline | no — pins are not notifications | `weather.pins`, `calendar.enabled` |

**Thresholds.** None of these events is user-threshold-based: the two internal thresholds (the
broken-bond flap count, and the tick count before the Bluetooth-blocked warning) are debounce
constants, not policy — exposing them would be a knob with no right answer. There is no
battery-level alert today, which is the one event where a threshold *would* be meaningful; see
[FIRMWARE-GAPS.md](../FIRMWARE-GAPS.md).

**Test action.** `SendTestNotification` is reachable from both front-ends on **Alerts → Send test** (new
this session) and Settings → Debug → *Write notification…*.

---

## 5. Redundancy noted

Per the project's preference for a single source of truth, these are called out rather than silently
left alone:

1. **`music.enabled`, `health.sync` and `dnd.sync` each have two controls** — the Sync screen master
   toggle and the Daemon configuration row. Both write the same conf key through the same store, so they
   cannot disagree; it is duplicated *presentation*, not duplicated state. **Decision: keep both.** The
   Sync screen adds availability and last-sync that a flat row cannot express, and keeping the keys in
   `GUI_CONFIG_FIELDS` is also what makes them reachable from `stoandl daemon list/set`. For `dnd.sync` the
   Sync switch is two-way over a four-way mode: "off" writes `off`, and "on" restores the direction that
   "off" replaced — remembered for the daemon's lifetime only, so after a restart "on" gives `both`.
2. **`firmware.notify` vs `alerts.*`** — deliberately not unified: `firmware.notify` gates a *watch*
   notification too, so folding it under `alerts.enabled` would make one switch mean two different
   things. Documented in both KDoc and the Alerts screen's footer text.

---

## 6. Wire format

`GetConfigSchema` emits eleven tab-separated columns:

```
key \t type \t label \t options \t desc \t group \t apply \t min \t max \t unit \t placeholder
```

The first five are the original contract. Columns 6–11 were **appended**, and both front-ends index
positionally with a fallback per column, so an old client against a new daemon (reads five, ignores the
rest) and a new client against an old daemon (missing columns → defaults) both work. `ConfigSchemaTest`
pins the column count and order, and asserts no label, description, group or combo option can contain a
tab, newline or comma — any of which would corrupt the record for both parsers.

`GetConfig` is unchanged: `key \t value`, where value is a combo's option **label**, a toggle's
`true`/`false`, or the raw text/number/comma-joined list.

`SetConfig` returns `ok:` / `notfound:` / `error:<reason>`. A restart-required key's `ok:` tail carries
“(restart stoandl to apply)”.

---

## 7. CLI

`stoandl daemon list | get <key> | set <key> <value>` (new this session) is the CLI half of the same
three methods — same keys, same validation, same restart marking. There was previously **no** CLI path
to daemon config at all (`stoandl config` is the PKJS/Clay *watchapp* settings page, which takes an app
name), so a headless host could only hand-edit `stoandl.conf` and restart.
