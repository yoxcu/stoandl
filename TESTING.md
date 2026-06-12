# Testing

Manual test plan for stoandl. There is no automated test harness in this
project — everything here is driven from the CLI, the watch, and
`/tmp/stoandl.log`.

## 0. Setup & conventions

```sh
# Tail logs in a second terminal throughout:
tail -f /tmp/stoandl.log

# Handy grep while testing reconnect:
tail -f /tmp/stoandl.log | grep -E "connect\(\) starting|connected and services resolved|link dropped|StartNotify|Broken-bond|Host bond lost|Starting BLE scan|Discovering|Re-pairing|Pebble blocked"

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

## 3. Reconnect resilience (BlueZ-native auto-connect)

Reconnection is delegated to BlueZ's own background auto-connect: the watch is
marked `Trusted` and a single standing `Device1.Connect()` intent is left in
place, so BlueZ reconnects it the instant it's reachable — no reconnect poll, no
`Disconnect()`-on-failure, and **no process-restart watchdog**. The pieces below
are **VERIFIED on GNOME**; the one outstanding item is a full pass on the real
target — see **3f (phone)**.

```sh
tail -f /tmp/stoandl.log | grep -E "connect\(\) starting|connected and services resolved|link dropped|StartNotify|Broken-bond|Host bond lost|Starting BLE scan|Discovering|Re-pairing|Pebble blocked"
```

### 3a. Normal reconnect — VERIFIED (GNOME)

Drop the link and bring the watch back: power-cycle it, walk out of range, toggle
its airplane mode, or `bluetoothctl disconnect <MAC>`.

- **Expected:** it reconnects on its own within a second or two of the watch
  being reachable — `connect() starting` → `connected and services resolved` →
  `StartNotify on PPoG`; a test notification reaches the watch.
- Repeat 5–10× (airplane toggles are easiest). Must NOT see
  `Already connecting (this is a bug)`.

### 3b. No competing discovery (the real "won't reconnect" cause) — VERIFIED (GNOME)

A second process running Bluetooth discovery monopolizes the controller's one LE
scanner and blocks the watch's reconnect — the cause of the long ConnectTimeout
saga.

```sh
bluetoothctl show | grep Discovering    # 'yes' while a BT settings panel is open
```

- Open a desktop Bluetooth settings/pairing panel → `Discovering: yes` → the
  watch can't reconnect. Within ~1 min stoandl warns and sends a **"Pebble blocked
  by a Bluetooth scan"** notification.
- Close it → `Discovering: no` → the watch reconnects within a second.

### 3c. Service restart — VERIFIED (GNOME)

`systemctl --user restart stoandl` with the watch in range.

- **Expected:** reconnects in ~5–10 s (`connect() starting` → `connected and
  services resolved`), no churn. (Not ~60 s — that was the 0x3e-flap re-arm bug,
  fixed.)

### 3d. No process restart while away — VERIFIED (GNOME)

Leave the watch away for **20+ min** with host Bluetooth **on**.

- **Expected:** the daemon stays up (there is no `exitProcess` watchdog), BlueZ
  holds the standing intent at ~zero cost, and it reconnects when the watch
  returns.

  ```sh
  systemctl --user status stoandl    # "Active: … since" unchanged; never restarted
  grep -iE "wedged|exiting for systemd" /tmp/stoandl.log   # none
  ```

### 3e. Unpaired-on-watch recovery — VERIFIED (GNOME, except the action button)

Forget the host in the **watch's** Bluetooth settings (a one-sided bond: the host
still holds it, the watch doesn't).

- **Expected:** within ~25 s stoandl detects the churn (BlueZ re-establishing a
  dead link every few seconds — `Broken-bond detector …`) and sends a **"Pebble
  won't stay connected"** notification with a **Re-pair** button.
- Tap **Re-pair** (with the watch in pairing mode) → it re-pairs. Or run
  `stoandl repair <name>` (substring, e.g. `stoandl repair B349`).
- `stoandl list` shows known watches + state. `stoandl pair` must NOT disturb a
  second watch that's merely out of range (multi-watch safety).
- ⚠️ **Still to verify:** the **Re-pair action button** depends on the
  notification server supporting actions — confirmed on GNOME, **unverified on
  Plasma Mobile**.

### 3f. Pairing removed on the host — VERIFIED (GNOME, except the action button)

The inverse of 3e: remove the pairing **on the host** while the watch still holds
its side — BlueZ then has no bond, so the watch can never reconnect without a
fresh pair.

```sh
bluetoothctl remove <MAC>     # external bond loss; the watch is NOT touched
```

> The *clean* way to forget a watch is `stoandl unpair` (removes it from both
> BlueZ and stoandl's DB). This test is the messy case where an *external*
> removal leaves stoandl's DB and the watch out of sync.

- **Expected:** the connect loop fails (`failed to connect … FailedToConnect`,
  `isBonded check failed …`); within ~15 s stoandl logs `Host bond lost for
  <name> …`, sends a **"Pebble pairing removed"** notification (with a **Pair**
  button), and **forgets** the watch — which stops the retry loop. The radio then
  goes quiet (no scan storm — see 3g).
- **Recover:** unpair the host on the **watch** too, then tap **Pair** (or run
  `stoandl pair`) → it re-pairs and reconnects; data flows.
- Must **not** fire for a healthy watch merely out of range (its bond is intact →
  `isBonded` stays true), nor transiently during `systemctl restart bluetooth`
  (BlueZ reloads bonds within the grace).

### 3g. Idle radio is quiet (scan-gate) — VERIFIED (GNOME)

The discovery scan runs **only while a pairing window is open** — a bonded watch
reconnects via BlueZ auto-connect with no app scan, so scanning is needed only to
discover an *unbonded* watch during pairing.

- With **no watch connected and no pairing window** (idle, just-unpaired, or a
  just-forgotten watch from 3f), the log shows **no** repeating `Starting BLE
  scan` and a `btmon` capture stays quiet — not a flood of advertising reports.
- `stoandl pair` / the **Pair** button still kicks discovery within ~1 s of
  opening the window (`Starting BLE scan` → `pebble match count 0 -> 1`).
- Sanity: this must not break 3a — a healthy watch still reconnects after
  out-of-range/airplane **without** any `Starting BLE scan` (BlueZ does it).

### 3h. Full pass on the phone (Plasma Mobile)  ⚠️ STILL TO TEST

The whole of section 3 has only been exercised on a GNOME desktop. The real
deployment target is the phone, so re-run **3a–3g there**:

- Reconnect after airplane/out-of-range (3a) and after `restart` (3c).
- Confirm there's **no competing discovery** on the phone in normal use
  (`bluetoothctl show | grep Discovering` → `no`); if Plasma Mobile keeps a
  scanner running, that's the same collision (3b) and needs handling there.
- Confirm the **action buttons** render and `ActionInvoked` fires — both the
  Re-pair (3e) and the Pair (3f) buttons.
- Confirm the idle radio stays quiet (3g) and `stoandl pair` still discovers.
- Notifications, weather, and PKJS still flow after a phone-side reconnect.

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
