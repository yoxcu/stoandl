# Testing

Manual test plan for stoandl. There is no automated test harness in this
project — everything here is driven from the CLI, the watch, and
`/tmp/stoandl.log`.

## 0. Setup & conventions

```sh
# Tail logs in a second terminal throughout:
tail -f /tmp/stoandl.log

# Handy grep while testing reconnect:
tail -f /tmp/stoandl.log | grep -E "Starting BLE scan|Ble scan failed|Already connecting|Watchdog|connected|connect\(\)"

# Service control:
systemctl --user restart stoandl
systemctl --user status stoandl      # watch the "since" time + restart behaviour
```

**Baseline:** watch paired and connected — confirm with `stoandl apps`
listing real entries. Note your watch's address (from the log,
`dev_XX_XX_...`) and pick one **sideloaded** watchapp + one **system** app
as test targets.

---

## 1. Locker management (`apps` / `launch` / `remove`)

| # | Test | Command | Expected |
|---|------|---------|----------|
| 1.1 | List | `stoandl apps` | Aligned table: NAME / TYPE / DEVELOPER / FLAGS / UUID. Your current watchface row has `active`; sideloaded apps show `sideloaded`; PKJS apps show `config`; built-ins show `system`. |
| 1.2 | Launch by name | `stoandl launch "<app name>"` | Prints `Launched <title>`; the app/watchface opens **on the watch**. Log: `LaunchApp: <title>`. |
| 1.3 | Launch by UUID | `stoandl launch <uuid>` | Same as 1.2. |
| 1.4 | Launch sets active watchface | launch a *watchface*, then `stoandl apps` | That watchface now carries the `active` flag. |
| 1.5 | Ambiguous name | `stoandl launch e` (a substring matching several) | Non-zero exit; stderr lists candidates with their UUIDs; nothing launches. |
| 1.6 | Not found | `stoandl launch zzzzz` | Non-zero exit; `No app matching 'zzzzz'`. |
| 1.7 | Remove sideloaded | `stoandl remove "<sideloaded app>"` | `Removed <title>`; it disappears from `stoandl apps` **and** from the watch's app menu. Log: `RemoveApp: <title>`. |
| 1.8 | Remove refuses system app | `stoandl remove "<system app>"` | Non-zero exit; `Refusing to remove system app <title>`; app still present. |
| 1.9 | Re-add after remove | `stoandl sideload <that>.pbw` then `stoandl apps` | App is back (confirms remove was clean, not corrupting). |
| 1.10 | Daemon down | `systemctl --user stop stoandl; stoandl apps` | Clear error (can't reach D-Bus), non-zero exit — not a stack trace. Restart after. |
| 1.11 | Sideload relative path | `cd` to a dir holding `x.pbw`, then `stoandl sideload x.pbw` | Installs — the CLI resolves the path absolutely before handing it to the daemon (whose cwd is `$HOME`). Prints `Sideloaded x.pbw`. |
| 1.12 | Sideload missing file | `stoandl sideload /nope/x.pbw` | Non-zero exit; `No such file: /nope/x.pbw` (not a misleading "Pbw does not contain manifest"). |

**Watch-side check for 1.7:** the removed app should be gone from the watch
menu, not just the list — confirms `removeApp` synced the deletion.

---

## 2. Backup & restore

| # | Test | Steps | Expected |
|---|------|-------|----------|
| 2.1 | Basic backup | `stoandl backup` | Creates `stoandl-backup-<ts>.tar.gz`; prints `Backed up … (N KiB)`. Warns it's running. Inspect: `tar tzf stoandl-backup-*.tar.gz` → every entry under `stoandl/` (db, `pbw-cache/`, `pkjs/`). |
| 2.2 | Named output | `stoandl backup /tmp/snap.tar.gz` | Archive written to that exact path. |
| 2.3 | Restore refused while running | `stoandl restore /tmp/snap.tar.gz` | Non-zero exit; tells you to `systemctl --user stop stoandl`; **nothing changed** in `~/.config/stoandl`. |
| 2.4 | Reject foreign archive | `tar czf /tmp/bad.tar.gz -C /etc hostname; stoandl restore /tmp/bad.tar.gz --force` | `Not a stoandl backup …`; non-zero; nothing extracted. |
| 2.5 | Missing file | `stoandl restore /tmp/nope.tar.gz` | `Backup not found …`. |
| 2.6 | **Round-trip integrity** | (a) `stoandl backup /tmp/snap.tar.gz` → (b) `stoandl remove "<an app>"` → (c) `systemctl --user stop stoandl` → (d) `stoandl restore /tmp/snap.tar.gz` → (e) start service | Restore prints the `stoandl.old-<ts>` location + "Start the daemon…". After restart, `stoandl apps` shows the removed app **back**. Confirms DB+cache restored. |
| 2.7 | Non-destructive | after 2.6 | `~/.config/stoandl.old-<ts>/` exists (your pre-restore state, recoverable). |
| 2.8 | PKJS settings survive | configure a Clay app (`stoandl config <app>`, save) → backup → change a setting → restore → reopen config | The backed-up values are back (verifies `pkjs/<uuid>.properties` round-trips). |

**Real-world scenario** (unpair → other phone → re-pair): you don't need
restore for that — `~/.config/stoandl` persists and re-pair re-syncs. To
simulate "move to a new machine": `stoandl backup`, copy the tarball to
another box, stop its daemon, `restore`, start — your whole locker +
settings appear.

---

## 3. Reconnect resilience (scan hygiene + fork fix + watchdog)

### 3a. Scan hygiene (the overnight bug's visible symptom)

```sh
# While CONNECTED — should be quiet, no scanning:
grep -c "Ble scan failed" /tmp/stoandl.log     # should stop growing
```

- **Connected:** no `Starting BLE scan` lines (scan is suppressed), and
  **zero** new `Ble scan failed` / `org.bluez.Error.InProgress`.
- **Disconnected:** `Starting BLE scan` roughly every 35 s, each preceded
  by a clean stop — and crucially **no** `InProgress` errors. Compare: the
  old log had 817 `Ble scan failed` overnight; the new one should have
  ~none.

### 3b. Normal reconnect (the core fix)

Put the watch out of range, then bring it back:

- **Method A:** power the watch off, wait ~1 min, power on.
- **Method B:** walk out of BLE range for a few minutes and return.
- **Method C (forced disconnect):** `bluetoothctl disconnect <watch-mac>`.

**Expected:** within a connect cycle the watch reconnects; log shows connect
attempts resuming and a successful connection; `stoandl apps` works again; a
test notification reaches the watch. **Must NOT** see
`Already connecting (this is a bug)` — that's the wedge the fork fix removes.

Run 3b **several times in a row** (especially Method A/B, which produce the
"messy" disconnect that originally wedged it). The original failure was
intermittent, so repeat 5–10×.

### 3c. Watchdog — negative test (most important, no rebuild)

Leave the watch out of range for **15–20 min** with host Bluetooth **on**:

- **Expected:** the daemon keeps trying (connect attempts/failures =
  "activity"), and the watchdog does **NOT** restart the process. Verify it
  stayed up:

  ```sh
  systemctl --user status stoandl     # "Active: active (running) since …" unchanged; no restart
  grep "Watchdog" /tmp/stoandl.log    # no wedge line
  ```

- When you return, it reconnects (per 3b). This confirms the watchdog won't
  churn-restart while you're simply away.

### 3d. Watchdog — positive test (optional, needs a throwaway build)

The restart only fires on a *true* wedge (no connection activity at all for
10 min), which is hard to induce on purpose. To exercise the mechanism,
build a temporary copy with a short timeout:

- In `PebbleIntegration.kt` set `WATCHDOG_STALL_RESTART = 2.minutes`, and to
  simulate "no activity" comment out the failure-count branch in the
  activity tracker (so out-of-range no longer counts as activity). Rebuild,
  run, disconnect the watch.
- **Expected:** after ~2 min, log:
  `Watchdog: bonded watch shows no connection activity … exiting for systemd restart`,
  process exits 1, `systemctl --user status` shows it restarted
  (`RestartSec=5`), and the fresh process reconnects.
- **Revert** the throwaway changes afterward.

### 3e. Stale ("zombie") connection recovery

Symptom this guards against: after a marginal-range stretch, kable/btleplug can hold a **dead** link
for many minutes without emitting a disconnect, so the daemon neither scans nor reconnects and the
watch stays disconnected even back in range (observed: ~26 min stuck, then a 15 s reconnect once kable
finally noticed). A BlueZ cross-check now forces recovery in ~1 min instead.

- Reproduce the stuck state (walk out of range mid-connection until the log goes silent on a
  `Watch connected` with no following `Disconnection`). While stuck, confirm the split view:

  ```sh
  bluetoothctl info <MAC>            # Connected: no   ← BlueZ knows it's gone
  tail -f /tmp/stoandl.log           # daemon silent, NOT scanning ← it still thinks it's connected
  ```

- **Expected:** within ~40–60 s the stale-connection watchdog logs
  `Stale connection: libpebble reports <MAC> connected but BlueZ shows it disconnected … — forcing reconnect`,
  then the normal teardown / `Starting BLE scan` / reconnect follows. If the forced disconnect doesn't take,
  ~30 s later: `… persists … after a forced disconnect — restarting for a clean BLE stack` and systemd
  restarts it. Either way the watch is back in well under the old multi-minute wait.
- **Negative:** during a *healthy* connection, `bluetoothctl info` shows `Connected: yes`, so the
  watchdog never fires — no spurious `Stale connection` lines or restarts.

---

## 4. Weather sync

Set at least one location in `~/.config/stoandl/stoandl.conf`, e.g.
`weather.locations = Berlin:52.52:13.405`, and restart the daemon.

| # | Test | Command / Step | Expected |
|---|------|----------------|----------|
| 4.1 | Startup | restart with a location set | Log: `Weather sync started: 1 manual location(s) …`. With nothing set: `Weather sync disabled (no weather.locations, no source, weather.gps off)`. |
| 4.2 | On-connect sync | connect the watch | Log: `Watch connected — refreshing weather` then `Weather updated: 1/1 location(s) populated`. The Weather app on the watch shows the current temp / today's high-low. |
| 4.3 | Force sync | `stoandl weather` | Prints `Weather synced (1 location(s) updated)`; watch Weather app refreshes. |
| 4.4 | Units | set `weather.units = imperial`, restart, `stoandl weather` | Temperatures shown on the watch are in °F. |
| 4.5 | Not enabled | unset all weather config, restart, `stoandl weather` | Non-zero exit; stderr `Weather sync not enabled (set weather.locations in stoandl.conf)`. |
| 4.6 | Transient failure | disconnect network, `stoandl weather` | Log warns the fetch failed; the watch keeps its last-known weather (not blanked). |
| 4.7 | Multiple locations | `weather.locations = Berlin:52.52:13.405, London:51.5074:-0.1278` | `Weather updated: 2/2 …`; both locations available in the watch Weather app. |

### 4a. DE / command location import

| # | Test | Command / Step | Expected |
|---|------|----------------|----------|
| 4.8 | Command source | `weather.location_source = command`, `weather.location_command = echo "Munich:48.137:11.575"`, restart, `stoandl weather` | Log: `weather.location_command produced 1 location(s)`; Munich appears in the watch Weather app. |
| 4.9 | Merge with manual | also set `weather.locations = Berlin:52.52:13.405` | Both Berlin and Munich sync; duplicates by name are dropped. |
| 4.10 | GNOME source | (GNOME/Phosh only) set a city in GNOME Weather, `weather.location_source = gnome`, restart | Log: `Imported N location(s) from org.gnome.…`; that city syncs. If parsing fails, the raw value is worth capturing — paste `gsettings get org.gnome.Weather locations`. |
| 4.11 | Bad command | `weather.location_command = false` | Log warns the command exited non-zero; manual locations still sync; no crash. |

### 4b. GPS current location (GeoClue2)

Needs a device with a working GeoClue fix (modem GPS / Wi-Fi). Set `weather.gps = true` and add the
allow-list entry to `/etc/geoclue/geoclue.conf` (see configuration.md). Confirm GeoClue works first
with `/usr/libexec/geoclue-2.0/demos/where-am-i` (or `where-am-i` from the `geoclue-demo-agent` pkg).

| # | Test | Command / Step | Expected |
|---|------|----------------|----------|
| 4.12 | GPS enabled | restart with `weather.gps = true` | Log: `GeoClue client started …` and `Weather sync started: … + GPS current location`. |
| 4.13 | Fix + sync | wait for a fix, then `stoandl weather` | Watch Weather app lists the current location **first** (current-location marker), labelled `weather.gps_name` (default "Current location"). No Nominatim call in the log. |
| 4.14 | Reverse geocode | set `weather.reverse_geocode = true`, restart, `stoandl weather` | The current-location entry is now labelled with the nearest place name (e.g. "Munich"); a Nominatim request appears. |
| 4.15 | No fix yet | `stoandl weather` before a fix | Log: `GPS enabled but no fix yet — keeping last-known current location`; fixed locations still update; the watch isn't blanked. |
| 4.16 | Not allow-listed | remove the `[stoandl]` block, restart | Log warns GeoClue init failed with the geoclue.conf hint; fixed `weather.locations` still sync. |
| 4.17 | GPS only | `weather.gps = true`, no `weather.locations` | Weather sync still starts; once a fix lands the watch shows the current location alone. |

---

## 5. Phone calls & telephony  ⚠️ UNVERIFIED

> The telephony path is written but **not yet tested against a real modem**. Only the watch-side
> `fakecall` path is confirmed. Expect rough edges; capture `/tmp/stoandl.log` for anything that
> misbehaves. The whole stack hangs off **ModemManager on the system bus** — confirm it's there first:
>
> ```sh
> mmcli -L                              # lists modem(s); none → 5b–5d can't run, only 5a applies
> tail -f /tmp/stoandl.log | grep -iE "ModemManager|call|fakecall|missed"
> ```
>
> At startup expect `ModemManager call monitor started` then `ModemManager call monitor: connected to
> system bus`. With **no modem** you still get those lines — calls just never surface. With **no system
> bus** the monitor loop logs `ModemManager monitor error … reconnecting` every ~5 s but the daemon keeps
> running. The `Failed to start ModemManager call monitor (telephony notifications disabled)` warning is
> the hard-failure path (the monitor couldn't even launch). See 5.13.

### 5a. fakecall path (no phone or SIM needed)

Drives the watch call screen via a synthetic call, so the watch-side UI and Answer/Decline round-trip
can be exercised without telephony.

| # | Test | Command / Step | Expected |
|---|------|----------------|----------|
| 5.1 | Ring | `stoandl fakecall ring "Jane Doe" "+15551234567"` | Prints `Ringing watch: Jane Doe <+15551234567>`. Watch shows the **native call screen** with the name/number. Log: `[fakecall] ringing: …`. |
| 5.2 | Answer on watch | press the watch's **Answer** button | Call screen switches to an **active** call (timer runs). Log: `[fakecall] watch answered … → active call`. |
| 5.3 | Decline on watch | ring again, press **Decline** | Call screen dismisses. Log: `[fakecall] watch declined/ended ringing call`. |
| 5.4 | Remote hangup | `stoandl fakecall ring …`, then `stoandl fakecall end` | Prints `Call ended`; the watch call screen clears. Log: `[fakecall] remote ended call`. |
| 5.5 | Ring with defaults | `stoandl fakecall ring` | Uses `Test Caller` / `+15551234567`; watch rings. |
| 5.6 | No watch connected | disconnect watch, `stoandl fakecall ring` | Non-zero exit; `Daemon not ready (no watch connected?)`. |

### 5b. Real incoming/outgoing calls (needs a modem + SIM)

Place a real call **to** the phone, and one **from** it. Watch what the watch shows and whether its
buttons drive the modem. Log lines: `ModemManager: CallAdded …`, `StateChanged … old→new`,
`Call <id>: state=… name=… number=…`.

| # | Test | Step | Expected |
|---|------|------|----------|
| 5.7 | Incoming ring | call the phone from another number | Watch shows the native call screen with the caller's number (or name — see 5c). Log: `StateChanged … →3` (RINGING_IN). |
| 5.8 | Answer from watch | press **Answer** | The **phone call actually connects**. Log: `[call] watch answered → ModemManager Accept(…)`, then `StateChanged … →4` (ACTIVE). |
| 5.9 | Hang up from watch | during an active call, press the watch's end/Decline | The **phone call ends**. Log: `[call] hangup → ModemManager Hangup(…)`. |
| 5.10 | Decline from watch | incoming call, press **Decline** | The call is rejected on the phone. |
| 5.11 | Outgoing call | dial out from the phone's dialer | Watch reflects the dialing/active call (`StateChanged …→1/2` then `→4`); ending it on either side clears the watch. |
| 5.12 | Restart mid-call | during an active call, `systemctl --user restart stoandl` | After restart the in-progress call is picked up (log: `ModemManager: existing call …`) and shown on the watch — confirms `scanExisting`. |
| 5.13 | No modem | a device with no modem (`mmcli -L` → none), restart stoandl | Monitor still starts (`connected to system bus`); no calls ever surface; notifications/weather unaffected. Stopping the **system bus** instead yields repeated `ModemManager monitor error … reconnecting`, daemon still up. |

### 5c. Caller-ID & notification filtering

Caller-ID resolves from vCard files (`contacts.vcard_paths`), falling back to the dialer's own
notification title. Configure at least one `.vcf` with a known number first (see configuration.md).

| # | Test | Step | Expected |
|---|------|------|----------|
| 5.14 | vCard name | call from a number stored in a configured `.vcf` | Watch call screen shows the **contact name**, not the bare number. Number matching is digits-only by suffix (so `+49…` matches a stored `0…`). |
| 5.15 | Dialer-title fallback | call from a number **not** in any vCard, while the DE dialer raises its own incoming-call notification | Watch shows the dialer's title as the name (best-effort). |
| 5.16 | Dialer notification suppressed | with `call.dialer_apps = spacebar, calls`, observe the watch during a call | The dialer's **redundant** call notification does **not** reach the watch (the native call screen replaces it). Log: `Suppressed dialer notification from …`. |
| 5.17 | Blocklist | set `notification.blocklist = <some app>`, trigger that app's notification | It's dropped. Log: `Filtered notification from … (blocklist)`. |

### 5d. Missed calls

Unanswered **incoming** calls become timeline pins (via libpebble3's `MissedCallSyncer`, fed by the
call monitor's in-memory log).

| # | Test | Step | Expected |
|---|------|------|----------|
| 5.18 | Missed incoming | call the phone and **don't** answer; let it ring out (or decline it) | A **missed-call timeline pin** appears on the watch with the caller number/name. |
| 5.19 | Answered ≠ missed | call and **answer**, then hang up | **No** missed-call pin (answered calls aren't missed). |
| 5.20 | Outgoing ≠ missed | dial out, then end | **No** missed-call pin (only unanswered *incoming* count). |

---

## 5.5 Watch settings (advanced prefs)  ⚠️ UNVERIFIED

Drives the watch's settings BlobDB (quick-launch, ambient-light threshold, backlight, etc.).

| # | Test | Command / Step | Expected |
|---|------|----------------|----------|
| 5.21 | List | `stoandl settings` | Aligned table of settings (SETTING/NAME/CURRENT/ALLOWED); `*` marks debug ones. Works even with no watch connected (shows defaults). |
| 5.22 | Filter | `stoandl settings light` | Only rows whose id/name contains "light". |
| 5.23 | Set number | `stoandl set-setting lightAmbientThreshold 200` | `Set lightAmbientThreshold = 200 …`; `stoandl settings light` shows current=200; watch behaviour changes. |
| 5.24 | Range check | `stoandl set-setting lightAmbientThreshold 99999` | Non-zero exit; `lightAmbientThreshold must be 1..4096 …`. |
| 5.25 | Set bool | `stoandl set-setting clock24h true` | Clock switches to 24h on the watch. |
| 5.26 | Quick launch by name | `stoandl set-setting qlUp "Music"` | Holding Up on the watch launches Music. `off` clears it. Bad name → `no single app matching …`. |
| 5.27 | Enum by name | `stoandl set-setting textStyle Larger` | Text size changes; a bad value lists the allowed names. |
| 5.28 | Config applied on connect | put `watch.clock24h = true` in stoandl.conf, restart, reconnect | Log: `Applied watch pref clock24h = true`; watch shows 24h. |
| 5.29 | Config is authoritative | with `watch.clock24h = true` set, change it on the watch, reconnect | Reverts to 24h (config wins). |
| 5.30 | Unknown id | `stoandl set-setting nope 1` | `Unknown watch pref 'nope' …`, non-zero. |

---

## 6. Multiple concurrent watches  ⚠️ UNVERIFIED (needs 2 Pebbles)

The daemon's connection layer is multi-watch by design (`watches` is a list; scan and auto-connect
iterate all discovered devices; notifications fan out to all connected watches). Whether BlueZ actually
allows two Pebbles as simultaneous GATT clients to the same application is unknown.

**Prerequisite:** two bonded Pebbles and a host with a Bluetooth adapter that accepts two BLE
peripheral connections at once.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| 6.1 | Both connect | restart daemon with two bonded Pebbles in range | Log shows two `Watch connected` entries with distinct identifiers. `stoandl apps` (against whichever watch happens to answer first) lists that watch's locker. |
| 6.2 | Notifications fan out | trigger a desktop notification | Log: `Notification queued for watch` once per connected watch; **both** watches show the notification. |
| 6.3 | One disconnects | power off one watch | That watch's disconnect appears in the log; the other stays connected; notifications still reach the remaining watch. |
| 6.4 | Reconnect | power the disconnected watch back on | It reconnects cleanly; both are connected again. |
| 6.5 | `stoandl pair` — bonded watch absent | one watch bonded but out of range, run `stoandl pair` for a new (unbonded) watch | Should work: the absent watch is `KnownPebbleDevice` (not `ConnectedPebbleDevice`), so the guard doesn't fire; the `alreadyBonded` snapshot prevents false-positive completion on the old watch. New watch bonds and `pair` returns `"ok:Paired"`. |
| 6.6 | `stoandl pair` — watch already connected | one watch actively connected, run `stoandl pair` for a second (unbonded) watch | **Known broken**: the early-return guard in `Pair()` exits immediately with `"Watch already connected"`. Fix requires removing that guard and scoping the connected-detector to newly connected devices only. |

---

## 7. Regression sanity  (run after any of the above)

- Notifications still bridge to the watch (`Notification queued for watch`
  in log).
- Watch→desktop dismissal still works.
- `stoandl fakecall ring` / `config` still function.

### 6.1 `config` launches the app if needed

- With a **configurable** PKJS app **not** open on the watch, run
  `stoandl config "<app>"`. The watch should **launch the app**, and the
  config page should still open. Log: `OpenConfig: <app> not running —
  launching it for its config page`, then `OpenConfig: got URL: …`.
- With the app already open, it should behave as before (no relaunch).
- A non-configurable or unknown app → `No config page available (app not
  found, not configurable, or it didn't start in time)`, non-zero exit.
