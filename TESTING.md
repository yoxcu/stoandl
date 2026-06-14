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

### 4b. Weather timeline pins

Pins are on by default whenever weather is enabled (`weather.pins = true`).

| # | Test | Command / Step | Expected |
|---|------|----------------|----------|
| 4.20 | Pins appear | one location set, `stoandl weather`, open the watch timeline (future) | Log: `Weather pins updated: 6 pin(s) across 3 day(s)`. Timeline shows a **Sunrise** and a **Sunset** pin for today/tomorrow/+2 (firmware may only surface the next ~2–3 days). Each pin shows high/low + a weather icon. |
| 4.21 | Day/night split | open a Sunrise vs Sunset pin | Sunrise pin uses the daytime condition/icon; Sunset pin the overnight one (they can differ, e.g. sunny day → clear night). |
| 4.22 | Primary priority | `weather.locations = Munich:48.137:11.575, Mühldorf:48.25:12.52`, `stoandl weather` | Only **one** set of 6 pins, for Munich (first entry). Opening a pin lists Mühldorf's temps in the detail view. No duplicate Mühldorf pins. |
| 4.23 | GPS wins | `weather.gps = true` + a fixed location, get a fix, `stoandl weather` | Pins follow the GPS current location (primary); the fixed location appears in the detail view. |
| 4.24 | Toggle off | `weather.pins = false`, restart, `stoandl weather` | Log: `Weather pins cleared`; the weather pins disappear from the timeline. The Weather app still updates. |
| 4.25 | Stale clear | remove a location so its day has no data | That day's pins are deleted (not left stale); `delete` is issued for the empty slot. |

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
| 5.17 | Per-app mute (drop) | `stoandl notif mute <app>`, trigger that app's notification | It's dropped host-side. Log: `Muted notification from … (always)`. (See §5.18 for the full per-app suite.) |

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

## 5.6 Music / now-playing control  ✅ Verified on hardware (now-playing display + play/pause/next/prev/volume from the watch)

Bridges desktop media players (MPRIS over the session bus) to the watch's native **Music** app:
now-playing display plus play/pause, next/previous and volume from the watch. On by default; set
`music.enabled = false` to disable.

> **Why this needs `OSType.Android`:** the Pebble firmware gates its music handling on the phone's OS
> type (iOS → AMS; Android → the legacy `MUSIC_CONTROL` endpoint libpebble3 drives). The JVM default
> `OSType.Unknown` makes the firmware never engage music — the Music app launches but stays blank, never
> sends `GetCurrentTrack`, and ignores its buttons (the watch PPoG-acks our packets but discards them).
> `PebbleIntegration` overrides `PlatformFlags` to `OSType.Android` to fix this. Root-caused 2026-06-12
> by diffing a btmon snoop of stoandl against a working Android `btsnoop_hci.log` — the only wire
> difference was `platformFlags` (`0x00000002` Android vs `0x00000000` Unknown). If music ever goes
> blank/dead again, that handshake byte is the first thing to check.

**Test driver:** any MPRIS player works — `mpv some.mp3`, VLC, Spotify, a browser playing audio, or
drive one with `playerctl play-pause` / `metadata`. Open the **Music** app on the watch to see it.

| # | Test | Command / Step | Expected |
|---|------|----------------|----------|
| 5.31 | Now-playing shows | start playback in a desktop player, then open the **Music** app on the watch | Watch shows the track title / artist (and album). Log: `MPRIS music monitor: connected to session bus` then `MPRIS player added: <name>`. |
| 5.32 | Live track change | skip to the next track in the desktop player | The watch updates to the new title/artist within ~1–2 s (no watch button press needed). |
| 5.33 | Play / pause from watch | press the watch's play/pause | Desktop playback toggles. Log: `MPRIS playPause → org.mpris.MediaPlayer2.<player>`. |
| 5.34 | Next / previous from watch | press next / previous on the watch | Desktop player skips track. Log: `MPRIS next → …` / `MPRIS previous → …`. |
| 5.35 | Volume from watch (default = system) | press volume up / down on the watch | The **master/output** volume changes (default `music.volume = system`). Startup log names the backend: `Music volume: system via wpctl` (or `pactl`/`amixer`). Per press: `system volume up (wpctl)`. Works regardless of which player is active. |
| 5.36 | Target follows the active player | play in player A, then start playing in player B | The watch follows whichever is actively **Playing** (B). Pause B while A still plays → the watch switches back to A. |
| 5.37 | Player quits | close the desktop player | Watch now-playing clears. Log: `MPRIS player removed: …`. |
| 5.38 | Reconnect survives | stop, then restart the desktop player | Watch picks the player back up (the monitor re-enumerates / catches `NameOwnerChanged`). |
| 5.39 | `playerctld` not duplicated | run `playerctld` alongside a real player | Only the real player drives the watch; `playerctld` (a proxy) is skipped — no duplicate now-playing. |
| 5.40 | Disabled | set `music.enabled = false`, restart | Log: `Music control disabled (music.enabled=false)`. The watch Music app shows nothing from the desktop; no `MPRIS …` log lines. |
| 5.41 | Player volume mode | set `music.volume = player`, restart, press volume | The **active player's** volume changes if it implements MPRIS `Volume` (mpv/VLC/Spotify); log `MPRIS volume up → NN% (…)`. A player without it → debug `… exposes no Volume property`, no crash. |
| 5.42 | Custom volume command | set `music.volume_up_command` / `music.volume_down_command` (e.g. `pactl set-sink-volume @DEFAULT_SINK@ +5%`), restart, press volume | Log: `Music volume: system (custom command)` then `system volume up (custom command)`; the command runs. |
| 5.43 | No backend found | `music.volume = system` on a host with no `wpctl`/`pactl`/`amixer` (and no override) | Log warns `music.volume=system but none of wpctl / pactl / amixer found on PATH — falling back to per-player volume`; volume buttons then act on the player. |

---

## 5.7 Calendar sync  ✅ Verified on hardware (local .ics + CalDAV incl. auto-discovery, pins, reminders, enable/disable, deletion) — ⚠️ iCal-URL feeds (5.58) and local DE-discovery (5.60) still to test

Syncs desktop calendar **events** to the watch **timeline** as native calendar pins (title, time,
location, recurring marker). libpebble3's `PhoneCalendarSyncer` does the pin work; stoandl supplies
events via the Linux `SystemCalendar` reader (local `.ics`, discovery, iCal feed URLs, CalDAV). Off
until a `calendar.*` source is set. **5.7a needs no watch or daemon** — do it first.

```sh
tail -f /tmp/stoandl.log | grep -iE "Calendar sync|PhoneCalendarSyncer|Got .* calendars from device|REPORT|iCal feed"
```

### Setup — a test calendar

Generate one with dates relative to *today* (GNU `date`), so every event lands in the sync window:

```sh
cat > /tmp/stoandl-test.ics <<EOF
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//stoandl//test//EN
X-WR-CALNAME:stoandl test
BEGIN:VEVENT
UID:t-timed
DTSTAMP:20200101T000000Z
DTSTART:$(date -u -d '+35 minutes' +%Y%m%dT%H%M%SZ)
DTEND:$(date -u -d '+65 minutes' +%Y%m%dT%H%M%SZ)
SUMMARY:Dentist
LOCATION:123 Main St
BEGIN:VALARM
ACTION:DISPLAY
TRIGGER:-PT30M
DESCRIPTION:Reminder
END:VALARM
END:VEVENT
BEGIN:VEVENT
UID:t-allday
DTSTAMP:20200101T000000Z
DTSTART;VALUE=DATE:$(date -u -d 'today' +%Y%m%d)
DTEND;VALUE=DATE:$(date -u -d 'tomorrow' +%Y%m%d)
SUMMARY:Public holiday
END:VEVENT
BEGIN:VEVENT
UID:t-weekly
DTSTAMP:20200101T000000Z
DTSTART:$(date -u -d 'today 09:00' +%Y%m%dT%H%M%SZ)
DTEND:$(date -u -d 'today 09:30' +%Y%m%dT%H%M%SZ)
RRULE:FREQ=WEEKLY
EXDATE:$(date -u -d 'today +7 days 09:00' +%Y%m%dT%H%M%SZ)
SUMMARY:Weekly standup
END:VEVENT
BEGIN:VEVENT
UID:t-daily
DTSTAMP:20200101T000000Z
DTSTART:$(date -u -d 'tomorrow 20:00' +%Y%m%dT%H%M%SZ)
DTEND:$(date -u -d 'tomorrow 20:15' +%Y%m%dT%H%M%SZ)
RRULE:FREQ=DAILY;COUNT=3
SUMMARY:Medication
END:VEVENT
END:VCALENDAR
EOF
```

(busybox `date` on the phone doesn't do relative strings — generate this on the dev box, or dump one
of your real `.ics` files instead.)

This fixture is tuned for two on-watch checks: **Dentist** starts **+35 min** from generation with a
−30 min alarm, so its **reminder fires ~5 min after you create the file**; and **Public holiday** is an
**all-day event for today**, so it should appear right away (well inside the watch's ~2–3-day timeline view).

### 5.7a Offline parser (no watch/daemon — verifies recurrence, EXDATE, all-day, timezone, reminders)

`stoandl calendar dump <file|url>` expands events into the window (yesterday → +30 d) and prints them.
(Before install, use `java -jar build/libs/stoandl-*-all.jar calendar dump <file>`.)

```sh
stoandl calendar dump /tmp/stoandl-test.ics
```

| # | Test | Command / Step | Expected |
|---|------|----------------|----------|
| 5.44 | Generated fixture | `stoandl calendar dump /tmp/stoandl-test.ics` | **9 occurrence(s)**: Dentist ×1 (today, ~now+35 min, `@123 Main St  reminders: 30m`), Public holiday ×1 (today, `(all-day)`), Weekly standup ×4 (`recurring`), Medication ×3 (`recurring`). One line each, sorted by start. |
| 5.44r | Reminders parsed | (same output) | The Dentist line shows `reminders: 30m` (its `-PT30M` VALARM). Multiple VALARMs list each (e.g. `1440m,10m`); a `RELATED=END` alarm on a 1 h event shows `-45m`; an absolute trigger shows the minutes before start. |
| 5.45 | EXDATE honoured | (same output) | "Weekly standup" appears at today, +14 d, +21 d, +28 d — **the +7-day one is absent** (5 instances would be in-window; EXDATE drops it to 4). |
| 5.46 | Timezone / DST | dump a real `.ics` containing a `DTSTART;TZID=…` event (feeds ship a `VTIMEZONE`) | Time is converted to the host's local zone (e.g. `09:00 America/New_York` → `13:00` on a UTC host); **no `Unsupported unit` warning** in the log. |
| 5.47 | Malformed input | `stoandl calendar dump /etc/hostname` | Logs `Failed to parse iCalendar …`, prints `Parsed OK but 0 occurrences …` — no stack trace, no crash. |
| 5.48 | URL source | `stoandl calendar dump <a published .ics URL>` | Same as a file, fetched over HTTP(S). A bad URL/host warns and yields 0 occurrences. |

### 5.7b On hardware (needs a watch)

Point a source at the fixture and restart:

```ini
calendar.ics_paths = /tmp/stoandl-test.ics
calendar.sync_interval = 30
```

For DEBUG sync logs: `STOANDL_LOG=DEBUG` (see [README](README.md#logging)). On the watch, open the
**timeline** by pressing **up** (past) / **down** or select (future) from the watchface.

| # | Test | Command / Step | Expected |
|---|------|----------------|----------|
| 5.49 | Startup gate | restart with a source set, then with none | Log: `Calendar sync enabled (timeline pins; refresh every 30m …)`; with nothing set: `Calendar sync disabled (set calendar.ics_paths / discover / ical_urls / caldav …)`. |
| 5.50 | Pins appear | connect the watch | The watch's **future timeline** shows the fixture events as calendar pins (title, time, location). DEBUG log: `Got N calendars from device … New Pin: <uuid> … Synced … calendars to DB`. |
| 5.51 | List | `stoandl calendar list` | One row per calendar: `id  name  enabled`. (`stoandl-test` for the fixture.) |
| 5.52 | Disable / enable | `stoandl calendar disable <id\|name>` then `stoandl calendar enable <id\|name>` | Disabling drops that calendar's pins on the next sync (DEBUG: `Deleting pin …`); enabling brings them back. Persists across restarts. |
| 5.53 | Force sync | `stoandl calendar sync` | Prints `Calendar re-sync requested`; pins refresh within ~5 s. |
| 5.54 | Live `.ics` change | add/edit an event in `/tmp/stoandl-test.ics` (e.g. re-run the generator) | A re-sync fires within seconds with no restart (filesystem watch); the new/changed pin appears. |
| 5.55 | Event removed | delete an event from the source, wait for a sync | Its pin is removed from the watch (DEBUG: `Deleting pin … no longer exists in calendar`). |
| 5.56 | All-day & recurring | (from the fixture) | The all-day "Public holiday" (today) shows right away — confirms all-day pins render; each "Medication"/"Weekly standup" instance is its own pin at the right time. |
| 5.57 | Re-sync ≠ duplicate | `stoandl calendar sync` twice | No duplicate pins (backingId is stable); the watch timeline is unchanged. |
| 5.58 | iCal URL ⚠️ _untested_ | set `calendar.ical_urls = <published .ics URL>`, restart | That calendar's events sync; log shows the fetch. Opt-in egress only. |
| 5.59 | CalDAV (single collection) | set `calendar.caldav = <collection-url>\|<user>\|<pass>`, restart | That collection's in-window events sync; log shows a `REPORT`. Opt-in egress. Bad URL/creds warns, syncs nothing (no crash). |
| 5.59d | CalDAV (auto-discover) | set `calendar.caldav = <account/principal-url>\|<user>\|<pass>` (e.g. SOGo `https://host/SOGo/dav/<user>/`), restart | Log: `CalDAV <url>: N calendar(s) [names…]` — the PROPFIND walk found **all** the account's calendars; each shows in `stoandl calendar list` and syncs. Disable any you don't want. |
| 5.60 | Discovery (Plasma Mobile) ⚠️ _untested_ | on the phone, `calendar.discover = yes` with Calindori in use | Calindori's local `.ics` (`~/.local/share/calindori`) are found and synced with no explicit paths. |
| 5.61 | Reminder fires | (from the fixture — Dentist is +35 min out with a −30 min alarm; `calendarReminders` is on by default) | **~5 min after you generate the fixture**, the watch shows/vibrates a reminder for "Dentist". DEBUG: a `TimelineReminder` is inserted for the pin. |

### Not covered / known limitations (don't file these as bugs)

- **No RSVP write-back** — accept/decline from the watch isn't wired up.
- **GNOME (EDS) / KDE (Akonadi) online calendars aren't read natively** — reach Google/Nextcloud/MS via `calendar.ical_urls` or `calendar.caldav`.
- **A singly-edited occurrence of a recurring event shows at its original time** (detached `RECURRENCE-ID` overrides are skipped to avoid duplicates).
- CalDAV is **Basic-auth only** (no Digest/OAuth); credentials are plaintext in the config. (Account-URL auto-discovery of all calendars *is* supported.)

---

## 5.8 Datalog capture (PebbleKit DataLogging)  ⚠️ UNVERIFIED (needs a watchapp that logs data)

The watch→phone DataLogging protocol (ACK/NACK, session tracking) was already implemented in
libpebble3; the only gap was that frames from **custom** apps (any non-health, non-system UUID/tag)
had nowhere to go. The fork now re-emits them on `Datalogging.records` and stoandl's `DatalogStore`
appends them as NDJSON: `~/.config/stoandl/datalog/<uuid>/<tag>.ndjson` (one item per line). Off by
default — set `datalog.enabled = true` and restart. Needs a watchapp that calls
`data_logging_create()` / `data_logging_log()` (e.g. a fitness/sensor logger).

| # | Test | Command / Steps | Expected |
|---|------|-----------------|----------|
| 5.70 | Disabled by default | fresh config, `stoandl datalog list` | `No datalog captured yet…`; log shows `Datalog capture disabled (datalog.enabled=false)`. |
| 5.71 | Enable | `datalog.enabled = true`, restart | Log: `Datalog capture enabled → …/datalog`. |
| 5.72 | Capture a session | run a watchapp that logs data; trigger a flush (or reconnect) | A file appears at `~/.config/stoandl/datalog/<app-uuid>/<tag>.ndjson`; log (DEBUG) shows `datalog <uuid> tag=<n> +<N>B type=…`. |
| 5.73 | `list` | `stoandl datalog list` | Aligned table: APP UUID / TAG / LINES / SIZE / UPDATED, one row per captured `(uuid, tag)`. |
| 5.74 | `dump` | `stoandl datalog dump <uuid-substring> [tag]` | Prints the NDJSON. UUID arg is a case-insensitive substring; ambiguous match lists candidates and exits non-zero. |
| 5.75 | `tail` | `stoandl datalog tail <uuid-substring> [tag] -n 5` | Last 5 lines only. |
| 5.76 | Item decode | inspect a dumped line | `type:"UInt"`/`"Int"` lines carry a numeric `value` (little-endian); `"ByteArray"` lines carry base64 `bytes`. Each line also has `session_ts` (watch session-open time) and `rx` (receive time). |
| 5.77 | No daemon needed | stop the daemon, `stoandl datalog list/dump` | Still works — the CLI reads the files directly (like `calendar dump`). |
| 5.78 | Background sync | log data while the app is **not** running, then reconnect | Data still captured — the daemon-level sink doesn't depend on the app's PKJS runtime being alive (the reason option 2/PKJS was rejected). |

**Known limitation:** files are append-only and never rotated — a chatty sensor app can grow them
without bound. Prune manually if needed.

---

## 5.9 Time / timezone sync  ⚠️ UNVERIFIED (needs a watch)

The watch clock is set at connect by libpebble3's negotiator (`SetUTC` = unix time + UTC offset +
timezone name) and on a watch-initiated `GetTimeUtcRequest`. stoandl adds proactive re-sync when the
**host** timezone changes mid-connection, via a `org.freedesktop.timedate1` `PropertiesChanged`
watcher on the system bus (`TimedateTimeChanged`, overriding libpebble3's no-op JVM `TimeChanged`).
No config — always on. Changing the timezone needs privilege (`timedatectl` uses polkit).

| # | Test | Command / Steps | Expected |
|---|------|-----------------|----------|
| 5.80 | Monitor starts | start the daemon | Log (INFO): `Time-change monitor started (org.freedesktop.timedate1 → watch clock re-sync)`. (Absent only if there's no system bus / no timedated — then connect-time sync still works.) |
| 5.81 | Clock correct at connect | connect a watch, check its time | Matches the host wall clock and local offset (this is the negotiator path, always worked). |
| 5.82 | Timezone change re-syncs | with the watch connected, `sudo timedatectl set-timezone America/New_York` (then set it back) | Log (DEBUG): `timedate1 changed — re-syncing watch clock` then libpebble's `updateTime`; the watch's displayed time shifts to the new offset within a second or two — **without** disconnecting/reconnecting. |
| 5.83 | NTP toggle | `sudo timedatectl set-ntp true` (or false) | Same `timedate1 changed` re-sync fires (harmless extra `SetUTC`). |
| 5.84 | No system bus | n/a in normal use | If the system bus is unavailable the monitor logs a DEBUG `unavailable` line and is skipped; connect-time sync is unaffected. |

**Known limitation:** timedated does **not** signal a plain DST rollover (the `Timezone` property is
unchanged — only the offset moves) nor a bare `timedatectl set-time` wall-clock step, so those still
wait for the next reconnect (or a watch-initiated time request). Catching them would need a
`CLOCK_REALTIME` discontinuity watch (`timerfd` `TFD_TIMER_CANCEL_ON_SET`), not done here.

---

## 5.10 Find my watch  ⚠️ UNVERIFIED (needs a watch)

`stoandl findwatch` rings a misplaced watch by injecting a synthetic incoming call named "Find My
Watch" into the same `currentCall` path the telephony integration uses (`FindWatch` in
`PebbleIntegration`). The watch rings continuously like a real call until a button on the call screen
is pressed; Answer and Decline both just clear the call (there is nothing to hold). Host→watch only,
no `.pbw`, no egress, no config — always available while a watch is connected.

| # | Test | Command / Steps | Expected |
|---|------|-----------------|----------|
| 5.90 | Ring | with a watch connected, run `stoandl findwatch` | CLI prints `Ringing watch — press a button on the watch to silence it`. The watch shows a call screen titled **Find My Watch** and vibrates/rings continuously. Log (INFO): `[findwatch] ringing watch (cookie=…)`. |
| 5.91 | Silence via Decline | on the watch's call screen, press **Decline** | The ring stops; the call screen dismisses. Log: `[findwatch] watch declined — ring silenced (cookie=…)`. |
| 5.92 | Silence via Answer | run `findwatch` again, press **Answer** instead | The ring stops the same way (no active call is established). Log: `[findwatch] watch answered — ring silenced (cookie=…)`. |
| 5.93 | No watch connected | run `stoandl findwatch` with no watch | CLI prints `Daemon not ready (no watch connected?)` and exits non-zero. Log (WARN): `FindWatch: libPebble not ready`. |

---

## 5.11 Firmware updates  ⚠️ UNVERIFIED (needs a watch) — ⚠️ the one genuinely risky test

Flashes watch firmware over BLE. **(a)** Local sideload of a `.pbz` (`stoandl firmware <file>`); **(b)**
GitHub check/update (`firmware check` / `update`) which downloads the bundle matching the watch's board
from `coredevices/PebbleOS` releases (gated by `firmware.github`, opt-in egress). Both go through
libpebble3's `FirmwareUpdater`, which runs pre-flash safety checks (board/CRC/slot) and **refuses a
mismatched bundle before sending anything**. Pebbles also keep a recovery (PRF) firmware, so a failed
flash drops to recovery rather than bricking — but treat this as the highest-risk feature and test on a
non-critical watch first, on charger, kept in range.

`FirmwareControl` (stoandl) orchestrates; `GithubFirmwareSource` resolves/downloads. The flash itself is
async — the CLI polls `FirmwareStatus` and renders a progress bar.

**Get a bundle for (a):** download `normal_<board>_<version>.pbz` for your watch's board from
<https://github.com/coredevices/PebbleOS/releases> (e.g. `normal_obelix_pvt_…` for a Pebble Time 2).
A bundle for the *wrong* board is the safe way to exercise the safety-check path.

| # | Test | Command / Steps | Expected |
|---|------|-----------------|----------|
| 5.110 | Wrong-board safety check | `stoandl firmware <a-.pbz-for-another-board>` | Flash is refused; CLI ends with `Firmware update failed: Firmware board does not match watch board: …`. Watch is untouched (no transfer started). |
| 5.111 | Local sideload (same version) | `stoandl firmware <correct .pbz, same version as running>` | Progress bar runs to 100%, then `Done — watch rebooting to apply the firmware.` Watch reboots and comes back on the same version. Log: `FWUpdate-… Firmware update completed, waiting for reboot`. |
| 5.112 | Local sideload (newer/older) | `stoandl firmware <correct .pbz, different version>` | Same as above; watch boots the flashed version (check `stoandl firmware check` or the watch's About screen). |
| 5.113 | No watch connected | `stoandl firmware <file.pbz>` with no watch | CLI prints `No watch connected` and exits non-zero. |
| 5.114 | GitHub check (disabled) | `stoandl firmware check` with `firmware.github` unset/false | CLI prints the `disabled:` hint about setting `firmware.github = true`. No network call made. |
| 5.115 | GitHub check (enabled) | set `firmware.github = true`, restart daemon, `stoandl firmware check` | Prints `Watch board / Running / Latest on repo` and either `→ Update available (…)` or `→ Up to date.` Classic Pebbles instead print the "no firmware published for board" note. |
| 5.116 | GitHub update | `stoandl firmware update` when an update is available | CLI prints `Updating <board>: <cur> → <latest> (…)`, then `Downloading …`, then the progress bar to `Done — watch rebooting…`. Watch boots the new version. |
| 5.117 | GitHub update (up to date) | `stoandl firmware update` when already current | CLI prints `… is current (latest …)`; nothing is flashed. |
| 5.118 | Interrupted transfer | start a flash, then walk the watch out of range mid-transfer | CLI eventually reports a failure (or times out at 10 min); the watch falls back to recovery/its prior firmware rather than bricking. Re-running the flash in range recovers. |
| 5.119 | Update notification on connect | with `firmware.github = true` and an update available, connect the watch | Within a few seconds the **watch** shows a notification titled **Firmware update** ("<new> is available (you're on <cur>)…") with **Update** and **Dismiss** buttons. Log: `Sent firmware-update notification to watch: <cur> → <new>`. |
| 5.11a | Update button flashes | on test 5.119's notification, press **Update** | Watch shows an "Updating…" result; the daemon downloads + flashes (same progress as 5.116, just driven from the watch). Log: `Watch-triggered firmware update: ok:…`. |
| 5.11b | No re-nag | disconnect/reconnect within a day (same available version) | The notification is **not** re-sent (throttled to once/day; only a *newer* version re-notifies). |
| 5.11c | Notify off | set `firmware.notify = false`, restart, connect with an update available | No watch notification; `firmware check`/`update` on the CLI still work. Log at startup: `Firmware update notifications off …`. |

---

## 5.12 Geolocation to watchapps  ⚠️ UNVERIFIED (needs a watch + a location-using watchapp)

Exposes the device's GeoClue2 position to watchapps via libpebble3's `SystemGeolocation` hook: PKJS
companion scripts call the standard `navigator.geolocation` API and location-aware sports/GPS apps use
the same fix. Gated by `geolocation.enabled` (off by default). On JVM this needed a small libpebble3
fork addition — the GraalJS PKJS runtime now routes `_PebbleGeo` through the shared
`GeolocationInterface` (previously a no-op `GraalGeolocationStub`) — plus a stoandl `SystemGeolocation`
override backed by `GeoClueLocationProvider` (the same provider weather uses).

**Prerequisites:**
- `geolocation.enabled = true` in `~/.config/stoandl/stoandl.conf`, daemon restarted.
- GeoClue allow-list entry for `stoandl` in `/etc/geoclue/geoclue.conf` (same one as `weather.gps`; see
  [configuration.md](docs/configuration.md#geolocation)). Confirm GeoClue can get a fix on the host first
  (e.g. `weather.gps = true` shows a current-location weather entry, or use a GeoClue demo agent).
- A watchapp that requests location. Easiest is a tiny PKJS test app — a `pkjs/index.js` that on
  `ready` calls `navigator.geolocation.getCurrentPosition(p => console.log('geo', JSON.stringify(p.coords)), e => console.log('geo err', e.message))`
  — sideloaded with `stoandl sideload <app.pbw>`. A real sports/GPS watchapp also exercises it.

| # | Test | Command / Steps | Expected |
|---|------|-----------------|----------|
| 5.120 | Disabled (default) | `geolocation.enabled` unset, launch a location-using watchapp | The PKJS callback gets an **error** "Not supported on Linux" (the no-op binding). No GeoClue client is created. |
| 5.121 | getCurrentPosition | with `geolocation.enabled = true`, launch the test app | Log: `GeoClue client started (… desktopId=stoandl)` on first request, then the app's `geo {…}` console line with real `latitude`/`longitude` (and `accuracy`/`altitude` when GeoClue provides them). `GeolocationInterface` debug line `getCurrentPosition(...)`. |
| 5.122 | No fix yet | enable geolocation but with GeoClue unable to fix (e.g. no GPS/Wi-Fi) | Callback gets an **error** "No location fix available"; log notes `GeoClue: no location fix yet`. Does not hang. |
| 5.123 | Not allow-listed | `geolocation.enabled = true` but no `geoclue.conf` entry | Error returned to the app; log warns GeoClue init failed / location unavailable with the allow-list hint. |
| 5.124 | watchPosition / clearWatch | app calls `navigator.geolocation.watchPosition(...)`, later `clearWatch(id)` | Repeated success callbacks at roughly the requested interval; after `clearWatch` they stop. |
| 5.125 | Real GPS watchapp | install a sports/GPS-style watchapp, start an activity | The watchapp shows/uses live coordinates from the watch (no phone-app GUI needed). |

---

## 5.13 Language packs  ⚠️ UNVERIFIED (needs a watch)

Installs a firmware language pack (`.pbl`) onto the watch, changing its notification/UI language and
loading the fonts a script needs. **(a)** `stoandl language list` shows the catalog packs for the
connected watch's board; **(b)** `stoandl language sideload <file.pbl>` installs a local pack (offline);
**(c)** `stoandl language install <locale|name|id>` auto-picks from the catalog and downloads+installs
(gated by `language.download`, opt-in egress). All three go through libpebble3's
`ConnectedPebbleDevice.installLanguagePack(...)` — PutBytes-transfers the file as `lang`, same machinery
as firmware/app sideload. The catalog is the official Core app's manifest bundled as the resource
`language-packs.json`. `LanguageControl` (stoandl) orchestrates; the install is async — the CLI polls
`LanguageStatus` and renders a progress bar.

Boards are matched as the official app does: **Core devices (Pebble 2 Duo / Time 2) use the Diorite
`silk` packs**, classic Pebbles use their own board revision (e.g. a Time Steel → `snowy_s3`). To revert
to English, install the watch's `en_US` pack (or the matching board's English entry).

**Get a pack for (b):** any `.pbl` from the catalog's URLs — e.g. download one from
`https://binaries.rebble.io/lp/…` (run `stoandl language list` to see which apply to your watch).

| # | Test | Command / Steps | Expected |
|---|------|-----------------|----------|
| 5.130 | List (watch connected) | `stoandl language list` | A table of packs for the watch's board, system locale first, `[community]` marking GitHub-sourced ones. ~15 rows for a Core device. The currently-installed pack (if any) is marked `*`. |
| 5.131 | List falls back to catalog (no watch / no daemon) | `stoandl language list` with no watch connected (and again with the daemon stopped) | Instead of erroring, prints "No watch connected — showing the full catalog (every board)" then the whole catalog (17 languages, `LOCALE / BOARDS / LANGUAGE`, board counts, `[community]` tags). Works even with the daemon down (offline). |
| 5.132 | Sideload a local pack | `stoandl language sideload <file.pbl>` | Progress bar runs to 100%, then `Done — installed …`. The watch's notifications/menus switch to that language. Log: `installLanguagePack done`. |
| 5.133 | Wrong file type | `stoandl language sideload <something.pbw>` | CLI rejects with "Not a language pack (expected a .pbl)"; nothing sent. |
| 5.134 | No such file | `stoandl language sideload /tmp/nope.pbl` | CLI prints `No such file: …` and exits non-zero. |
| 5.135 | Install (disabled) | `stoandl language install de_DE` with `language.download` unset/false | CLI prints the `disabled:` hint about setting `language.download = true`. No network call. |
| 5.136 | Install by locale | set `language.download = true`, restart, `stoandl language install de_DE` | CLI prints `Installing Deutsch (German) v…`, then `Downloading …`, then the bar to `Done`. Watch UI is German. |
| 5.137 | Install by name | `stoandl language install French` | Resolves to the French pack and installs it (same flow as 5.136). |
| 5.138 | Install — no match | `stoandl language install xx_XX` | CLI prints `No language pack matching 'xx_XX' …` and exits non-zero. Nothing sent. |
| 5.139 | Install — system locale default | `stoandl language install` (no arg) with `LANG=de_DE.UTF-8` | Picks the pack matching the daemon's locale (German here). |
| 5.13a | Installed flag round-trips | after a successful install, `stoandl language list` | The just-installed locale now shows `*`. (`WatchInfo.language`/`languageVersion` reflect it.) |
| 5.13b | Status while idle | `stoandl language status` after things settle | Prints `Idle (no language-pack install in progress)`. |
| 5.13c | Community pack | `stoandl language install ja_JP` (or `he_IL`) | Downloads from the GitHub URL (not Rebble) and installs; the watch renders the CJK/Hebrew font. |

---

## 5.14 Disconnect logging: reason + no duplicates  ⚠️ UNVERIFIED (needs a watch)

Diagnostics-only change in the libpebble3 fork (`BluezBle.jvm.kt`, `BluezGattConnector`). Two fixes:

1. **Disconnect reason in the log.** The `device reported Connected=false (link dropped)` line now
   carries the BlueZ disconnect reason — `Timeout — out of range` vs `Authentication — auth failure /
   broken bond` (and a few others) — captured from the `org.bluez.Device1.Disconnected(reason, message)`
   signal (BlueZ ≥ 5.83; same signal the broken-bond churn detector keys off). This is the discriminator
   that turns an overnight-log triage from a btmon snoop dig into a single `grep`. On BlueZ < 5.83 the
   suffix is simply absent (no signal).
2. **No more doubled drop lines.** Each `link dropped` used to log twice (≈830 lines for ≈415 real
   drops in the 2026-06-14 overnight log): the per-connection scope was cancelled by the reconnect
   `cleanup()` before the teardown coroutine could close the signal handler, so handlers (and their
   `DBusConnection`s) leaked and all fired on the next drop. Teardown now runs via
   `_disconnected.invokeOnCompletion` on an independent `cleanupScope`, closing both handlers and the
   connection exactly once — also fixing the per-session connection leak.

**How to observe:** tail `/tmp/stoandl.log` (or `stoandl.log`) while forcing disconnects. No watch GUI
needed — just a connected, bonded watch.

| # | Test | Command / Steps | Expected |
|---|------|-----------------|----------|
| 5.140 | Reason on out-of-range | connect a watch, then walk it out of range until it drops | A **single** `device reported Connected=false (link dropped, reason: Timeout — out of range)` per drop. (Older BlueZ < 5.83: `(link dropped)` with no reason — still single.) |
| 5.141 | Reason on broken bond | with a connected watch, unpair it **on the watch** | Drop line reads `… reason: Authentication — auth failure / broken bond`; the existing "Pebble won't stay connected" churn-detector notification still fires (5.83+). |
| 5.142 | No duplicate lines | over a flappy session (marginal range), `grep -c 'link dropped' stoandl.log` vs distinct timestamps | Each drop logs **once**; no two `link dropped` lines share the same millisecond. |
| 5.143 | No connection leak | run the daemon through many connect/drop cycles, then check open D-Bus connections / fds for the process (`ls /proc/<pid>/fd | wc -l`) | fd count stays roughly flat across cycles (no steady growth from leaked `DBusConnection`s). |

---

## 5.15 Watch screenshots  ✅ Verified on hardware (captures the watch screen to a PNG)

`stoandl screenshot [path]` captures the connected watch's screen to a PNG on the host. libpebble3's
`ScreenshotService` does the protocol work over the SCREENSHOT endpoint — request, reassemble the chunked
transfer, decode the 1-bit (classic) or 8-bit (colour) framebuffer. The fork adds a raw-pixel accessor
(`ConnectedPebble.Screenshot.takeScreenshotPixels()` → ARGB `IntArray`) because the existing
`takeScreenshot()` returns a Compose `ImageBitmap` that is a null stub on the JVM/desktop build. stoandl's
`ScreenshotControl` encodes those pixels to PNG with a tiny `Deflater`-based `PngEncoder` (no `java.awt`/
`ImageIO`, so it works on musl/postmarketOS) and writes the file. Purely local — no network, no egress
opt-in. The capture is a one-shot (~couple of seconds, 5 s watch-side timeout); the D-Bus call blocks on it.

The CLI resolves the target against its own cwd and sends an absolute path (the daemon writes the file and
its cwd is `$HOME`). Default name is `pebble-screenshot-<timestamp>.png` in the caller's cwd; an explicit
file or directory is honoured, and `.png` is appended if missing.

| # | Test | Command / Steps | Expected |
|---|------|-----------------|----------|
| 5.150 | Default capture | connect a watch, `stoandl screenshot` | Prints `Capturing watch screen…` then `Saved <cwd>/pebble-screenshot-<time>.png (<w>×<h>)`. The PNG opens in an image viewer and shows the watch's current screen, right colours and orientation. Log: `Saved <w>×<h> screenshot → …`. |
| 5.151 | Explicit path | `stoandl screenshot /tmp/watch.png` | Writes exactly `/tmp/watch.png`. |
| 5.152 | Path without extension | `stoandl screenshot /tmp/watch` | Writes `/tmp/watch.png` (`.png` appended). |
| 5.153 | Directory target | `stoandl screenshot /tmp/` | Writes `/tmp/pebble-screenshot-<time>.png`. |
| 5.154 | No watch connected | `stoandl screenshot` with no watch | CLI prints `No watch connected` and exits non-zero; no file written. |
| 5.155 | Colour vs B&W board | run on a colour watch (Basalt/Chalk/Emery) and, if available, a classic 1-bit Pebble | Colour board → full-colour PNG; 1-bit board → crisp black-and-white PNG. Dimensions match the watch (e.g. 144×168, 200×228). |
| 5.156 | Back-to-back captures | run `stoandl screenshot` twice in a row | Both succeed (the `Busy` guard releases between captures); two distinct files. |

---

## 5.16 Watch logs / support bundle  ◻️ Bundle assembly + redaction + no-watch degradation verified (real bundle 2026-06-14) — ⚠️ watch-side capture (logs/info/coredump) still needs a watch

> Evidence: a `stoandl support` run on hardware (no watch connected) produced a valid bundle whose
> `bundle-notes.txt` recorded `watch info/logs unavailable: No watch connected`, included the daemon
> log + version + `stoandl.conf` with the CalDAV password redacted to `***`. So tests 5.164 (resilient,
> watch pieces omitted) and 5.166 (redaction) are confirmed; 5.160–5.163, 5.165 (watch-connected
> paths) remain to run.

`stoandl logs [path]` dumps the watch's firmware logs to a text file; `stoandl support [out.tar.gz]`
packages a full support bundle for sharing with a maintainer. libpebble3 already implements both over the
wire — `CommonConnectedDevice` is `ConnectedPebble.Logs` (`gatherLogs()` streams the firmware log
generations over the LOG_DUMP endpoint) and `ConnectedPebble.CoreDump` (`getCoreDump()` over GET_BYTES),
backed by real common services with **no JVM stub** (unlike the screenshot path), and exposes `WatchInfo`
directly. So this is pure stoandl wiring (`LogsControl` + three D-Bus methods + CLI), no fork change.

The support bundle is **resilient**: it always gathers the host-side pieces it can read directly — the
daemon log (`/tmp/stoandl*.log`) and the **secret-redacted** `stoandl.conf` — even with no daemon or watch,
and folds in the watch's firmware logs + `watch-info.txt` (and, with `--coredump`, a coredump) when a watch
is reachable. Anything missing is recorded in `bundle-notes.txt` inside the archive instead of aborting.
Config secrets (CalDAV passwords, credentials/tokens in URLs) are redacted by `sanitizeConfig`. Purely
local — no network, no egress opt-in.

| # | Test | Command / Steps | Expected |
|---|------|-----------------|----------|
| 5.160 | Default log dump | connect a watch, `stoandl logs` | Prints `Gathering watch logs…` then `Saved <cwd>/pebble-logs-<time>.txt`. File begins `# Device logs:` with `=== Generation: N ===` blocks and `LEVEL TIMESTAMP file:line> message` lines. |
| 5.161 | Explicit path / ext | `stoandl logs /tmp/w.txt` and `stoandl logs /tmp/w` | First writes exactly `/tmp/w.txt`; second appends `.txt`. A directory target writes `pebble-logs-<time>.txt` inside it. |
| 5.162 | No watch | `stoandl logs` with no watch connected | CLI prints `No watch connected` and exits non-zero; no file written. |
| 5.163 | Support bundle (full) | with a watch connected, `stoandl support` | Prints a checklist (`watch logs: included`, `watch info`/via file, `daemon log: N file(s)`, `config: included …`), then `Wrote <cwd>/stoandl-support-<time>.tar.gz (<size>)`. Extract: contains `watch-logs.txt`, `watch-info.txt`, `daemon-logs/`, `stoandl.conf`, `version.txt`, `bundle-notes.txt`. |
| 5.164 | Support bundle (no daemon) | stop the daemon, `stoandl support` | Still succeeds. `bundle-notes.txt` notes the watch pieces were omitted; the archive still has `daemon-logs/` (if any) + redacted `stoandl.conf` + `version.txt`. |
| 5.165 | Coredump opt-in | `stoandl support --coredump` | If the watch has a coredump, `coredump.bin` is included and notes say `coredump: included`; otherwise `coredump: none on the watch`. Without the flag, no coredump is fetched. |
| 5.166 | Config redaction | put a `calendar.caldav = https://dav.example/|me|s3cr3t` line in `stoandl.conf`, `stoandl support`, inspect the bundled `stoandl.conf` | The password field is `***` (`…|me|***`); URL userinfo and `?token=`/`?key=` params elsewhere are `***`; a header comment warns secrets were redacted. Non-secret keys are unchanged. |
| 5.167 | Output path forms | `stoandl support /tmp/`, `stoandl support /tmp/b` , `stoandl support /tmp/b.tgz` | Directory → `stoandl-support-<time>.tar.gz` inside it; bare name → `b.tar.gz`; `.tgz`/`.tar.gz` honoured as-is. |

---

## 5.17 Factory reset / reset to recovery  ⚠️ UNVERIFIED (needs a watch — DESTRUCTIVE)

`stoandl reset recovery` reboots the watch into its recovery (PRF) firmware; `stoandl reset factory`
wipes the watch back to out-of-box state. Both ride libpebble3's `ConnectedPebble.Debug`
(`resetIntoPrf()` / `factoryReset()`), which send a single RESET-endpoint packet — no JVM stub, no fork
change, pure stoandl wiring (`DebugControl` + two D-Bus methods + CLI). Fire-and-forget: the call returns
as soon as the packet is queued and the watch drops the BLE link as it reboots/wipes, so there's no
completion ack — confirm the outcome on the watch itself. The factory reset is irreversible, so the CLI
requires a typed `yes` confirmation (skippable with `--yes`/`-y`); the daemon just executes.

> ⚠️ These genuinely reset the watch. `reset factory` erases all apps, settings and the host pairing
> (re-pair afterwards). Run them last in a test session. `reset recovery` is recoverable — reflash a
> normal firmware from PRF with `stoandl firmware …`.

| # | Test | Command / Steps | Expected |
|---|------|-----------------|----------|
| 5.170 | Reset to recovery | connect a watch, `stoandl reset recovery` | Prints `Recovery reboot sent to <name> — the watch will reboot into PRF`. The watch reboots and comes up in PRF (the recovery firmware screen / red Pebble logo). Reconnect and `stoandl firmware <normal.pbz>` restores it. Log: `Sent reset-into-recovery (PRF) to <name>`. |
| 5.171 | `prf` alias | `stoandl reset prf` | Same as `reset recovery`. |
| 5.172 | Factory reset confirm prompt | `stoandl reset factory`, then type `yes` | Prompts `Factory-reset the watch? … Type 'yes' to confirm:`; on `yes` prints `Factory reset sent to <name> …` and the watch wipes + reboots to out-of-box (needs re-pairing). |
| 5.173 | Factory reset abort | `stoandl reset factory`, then type `no` (or empty/Enter) | Prints `Aborted.`; no packet sent, watch untouched. |
| 5.174 | Factory reset `--yes` | `stoandl reset factory --yes` | No prompt; wipes immediately. (Use with care.) |
| 5.175 | No watch connected | `stoandl reset recovery` / `reset factory --yes` with no watch | CLI prints `No watch connected` and exits non-zero; nothing sent. |
| 5.176 | Bad subcommand | `stoandl reset`, `stoandl reset foo` | Prints `Usage: stoandl reset <factory|recovery> [--yes]` and exits non-zero. |

---

## 5.18 Per-app notification settings  ✅ Host-side mute + lazy-load verified — ⚠️ wrist "Mute" action + styling to verify on hardware

Wires libpebble3's per-app `NotificationAppItem` store (dormant on JVM). Every desktop app that
notifies is lazily tracked, and its mute state is enforced **host-side** in the notification listener
(`DbusNotificationListenerConnection` + `isMutedNow()`) before `sendNotification` — the same enforcement
point Android uses. `stoandl notif` manages the store via `NotificationAppsControl` over the control bus.
With `notification.sync_to_watch` on, the list also syncs to the watch (Koin override of
`PlatformConfig(syncNotificationApps = true)`) and wrist toggles write back via libpebble3's `handleWrite`.

The **host-side** half (tests 5.180–5.187) needs only the daemon — no watch. The **wrist "Mute" action**
and **per-app styling** (5.189–5.191) need a watch but no sync. The opt-in BlobDB push
(`notification.sync_to_watch`, off by default) only feeds a per-app *settings menu* that current
Core/PebbleOS firmware does not have — see 5.192.

**Prerequisite:** daemon running; `notification.per_app = true` (default). Emit desktop notifications
with `notify-send "<App>" "<msg>"` (the `<App>` becomes the app name). Verify drops in the log
(`Muted notification from <app>`) and on the watch.

| # | Test | Command / Steps | Expected |
|---|------|-----------------|----------|
| 5.180 | Lazy-load | `notify-send "Element" "hi"`, then `stoandl notif list` | `Element` appears with mute `never` and a recent "last notified"; watch shows the notification. Log: `Tracking new notification app 'Element'`. |
| 5.181 | Mute always | `stoandl notif mute Element`, then `notify-send "Element" "hi"` | No notification on the watch; `notif list` shows `Element … always`. Log: `Muted notification from Element (always)`. |
| 5.182 | Unmute | `stoandl notif unmute Element`, then `notify-send "Element" "hi"` | Notification reaches the watch again; `notif list` shows `never`. |
| 5.183 | Weekday/weekend schedule | `stoandl notif mute Slack weekdays` | On a Mon–Fri, a `Slack` notification is dropped; on Sat/Sun it's delivered (and vice-versa for `weekends`). `notif list` shows `weekdays`. |
| 5.184 | Temporary mute | `stoandl notif mute Discord 1h`; notify → dropped; wait past expiry (or use `5s`) → notify | Dropped while active (`notif list` shows `muted-until …`); delivered again after expiry, with no manual unmute. |
| 5.185 | Mute all / unmute all | `stoandl notif mute-all`, then `unmute-all` | All tracked apps flip to `always`, then back to `never`; `notif list` reflects both. |
| 5.186 | Substring match errors | `stoandl notif mute zzz` (no match); a substring matching ≥2 apps | `notfound:`/`ambiguous:` status; nothing changed. |
| 5.187 | Persistence | mute an app, `systemctl --user restart stoandl`, `stoandl notif list` | Mute state survives the restart (Room store). |
| 5.189 | Wrist "Mute" action | connect a watch, trigger an app notification, open its action menu on the watch | A **"Mute *app*"** action appears (alongside Dismiss). Selecting it shows "Muted" on the watch; `stoandl notif list` now shows that app `always`; the next notification from it is dropped host-side. Log: `Muted '<app>' from watch action`. No `sync_to_watch` needed. |
| 5.190 | Unmute after wrist-mute | `stoandl notif unmute <app>` (muting from the wrist gives no notification to unmute from) | App delivers again; `notif list` shows `never`. |
| 5.191 | Per-app styling | `stoandl notif style <app> --color Red --icon NotificationElement --vibe double`, then trigger a notification | The notification renders with the red background, the chosen icon, and the double-buzz vibration on the watch. `--color default` etc. resets each; unknown values fall back silently. |
| 5.192 | Settings menu (firmware-absent) | `notification.sync_to_watch = true`, restart, connect a watch | Records DO sync (log `BlobDB - insert: CannedResponses [<app>]`), but **no per-app settings menu exists on this firmware** (Settings → Notifications is global only). The toggle therefore defaults **off**; muting is via the action menu (5.189), which needs no sync. Re-test only against firmware that adds a per-app menu. |

---

## 5.19 Developer connection  ⚠️ UNVERIFIED (needs a watch + the Pebble SDK)

Brings up libpebble3's developer connection (`ConnectedPebble.DevConnection` → `DevConnectionManager`),
which starts a LAN WebSocket server on **port 9000** (`DevConnectionServer`) that relays raw
Pebble-protocol frames to/from the connected watch, installs `.pbw` bundles, and streams PKJS logs —
the bridge the Pebble SDK (`pebble install --phone <ip>`) / CloudPebble's "phone" mode talk to. stoandl
pins `WatchConfig.lanDevConnection=true` so the transport picks the LAN server (not the token-gated
CloudPebble proxy), exposes `DeveloperControl` over three control-bus methods, and the CLI prints the
host LAN address + a security warning.

**Security:** the server binds `0.0.0.0:9000` with **no authentication** — while running, anyone on the
network can install apps and relay protocol traffic to the watch. Off by default; started explicitly
(or via opt-in `developer.autostart`).

**Prerequisite:** daemon running, a connected watch, and the Pebble SDK (`pebble` CLI) on the same LAN.

| # | Test | Command / Steps | Expected |
|---|------|-----------------|----------|
| 5.193 | Start | connect a watch, `stoandl developer start` | Prints `Developer connection started (port 9000).`, a `pebble install --phone <ip>` hint for each detected LAN address, and the 0.0.0.0/no-auth warning. Daemon log: `Developer connection started (LAN WebSocket server on 0.0.0.0:9000)` and `Starting server for … on port 9000`. |
| 5.194 | Status (active) | `stoandl developer status` | `Developer connection is active (port 9000).` |
| 5.195 | SDK install | from another machine: `pebble install --phone <host-ip>` in a watchapp project | The app installs onto the watch and launches; PKJS `console.log` output streams back to the SDK. (This is the core acceptance test.) |
| 5.196 | Live debug / logs | `pebble logs --phone <host-ip>` while the app runs | App + PKJS logs stream to the terminal. |
| 5.197 | Stop | `stoandl developer stop`, then `stoandl developer status` | `Developer connection stopped.` then `… inactive.`; port 9000 no longer accepts connections. Daemon log: `Developer connection stopped`. |
| 5.198 | Survives reconnect only with autostart | with `developer.autostart=false`: start, then move the watch out of range and back | After reconnect, `developer status` shows `inactive` (the server lived in the dropped connection scope) — must `developer start` again. With `developer.autostart=true` (restart daemon), it comes back up automatically on reconnect (log: `Developer connection autostart on …`). |
| 5.199 | No watch | `stoandl developer start` / `status` with no watch connected | Prints `No watch connected` and exits non-zero; no server started. |
| 5.19a | Bad subcommand | `stoandl developer`, `stoandl developer foo` | Prints `Usage: stoandl developer <start | stop | status>` and exits non-zero. |

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
