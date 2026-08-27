# Firmware gaps — PebbleOS v4.31 → v4.36.2

Gap analysis from the settings-parity session (2026-08-27). The previous analysis was based on
**v4.30.0**; the newest firmware is **v4.36.2** (2026-08-26). Per-item dispositions are appended to
[docs/pebbleos-changelog-review.md](docs/pebbleos-changelog-review.md); this file is the *implementation*
view — what was done, what was deferred, and why.

**Nothing from this batch was implemented.** That is the finding, not an omission: every actionable item
below is blocked on either a libpebble3 submodule bump (which the project gates behind hardware
verification) or on hardware running 4.36. There were no low-risk, self-contained wins to take, and the
brief was explicit that a half-implementation is worse than a deferral.

## Sources

- Changelog: the Notion page via `https://notion-api.splitbee.io/v1/page/25efbb55ea84801da04bfcf73c9346e1`.
  Only **v4.31.2, v4.33.2 and v4.36.0** carry published notes since the watermark.
- `https://api.github.com/repos/coredevices/PebbleOS/releases` — **every** GitHub release body in this
  range is empty, so v4.31.1, v4.32.0, v4.33.0, v4.33.1, v4.34.0, v4.35.0, v4.36.1, v4.36.2 and the
  v4.27.1 backport have nothing to triage yet. Re-check next run in case notes are backfilled.
- Network egress worked from this container, so nothing was skipped for connectivity.

Grounded against the daemon (`/workspace/src`), the libpebble3 fork (`libs/libpebble3`, pinned at
`4156262d`, **2026-07-23** — i.e. it predates every firmware release in this range) and both GUIs.

---

## Deferred — actionable, blocked on an upstream bump

### 1. Notification images  ·  v4.36.0  ·  large

> "Notification images now shown (Android only, requires Pebble app >1.10)"

stoandl identifies as `OSType.Android`, so "Android only" does **not** exclude it — this is the most
interesting item in the batch. It is a **two-part gap**, and both parts are missing:

- **Watch side.** `TimelineAttribute` (`libpebble3/.../packets/blobdb/Timeline.kt:269`) defines 39
  attributes, up to id `0x33` (`NotificationFilteringRules`). None of them is an image/bitmap.
  `TinyIcon`/`SmallIcon`/`LargeIcon`/`Icon` all carry an icon *code* — a reference to a built-in
  firmware resource — not pixels. So the fork cannot express an image today.
- **Host side.** `IncomingNotification` (`src/.../dbus/DbusNotificationMonitor.kt:37`) is
  `(id, appName, summary, body)`. The monitor's `Notify` signature takes the `hints` map
  (`DbusNotificationMonitor.kt:153`) and **discards it entirely** — including `image-data` (the
  `(iiibiiay)` inline bitmap) and `image-path`.

**Sketch.** (a) Bump libpebble3 past whatever upstream commit adds the attribute, and confirm the wire
format — most likely a new attribute id carrying a PNG or a Pebble GBitmap. (b) Extend
`IncomingNotification` with the image, extracting `image-path` first (cheap, a file path) and
`image-data` second (raw bitmap in the hints Variant). (c) Convert and downscale to the watch's
resolution and depth — stoandl already has `icons/GBitmap.kt` and `screenshot/PngEncoder.kt`, so the
pixel plumbing exists in-tree, in the right direction (it currently decodes GBitmap→PNG; this needs the
inverse). (d) A `notification.images` config key, default off — it is a real bandwidth cost over PPoGATT
and a privacy consideration (notification images can be photos).

**Blocked on:** the libpebble3 bump. Do not attempt the host half first — without a verified wire format
it is guesswork.

### 2. Album art in the Music app  ·  v4.36.0  ·  large

> "Album art in Music app (toggle in phone → Watch → Music)"

`MusicTrack` (`libpebble3/.../endpointmanager/musiccontrol/MusicTrack.kt:8`) is
`title/artist/album/length/trackNumber/totalTracks`, and `toPacket()` maps it to
`MusicControl.UpdateCurrentTrack` — text only. Grepping the whole fork for `albumArt`, `album_art` and
`artwork` returns nothing. So there is no phone→watch artwork path at the pinned commit.

The **host half is available**: MPRIS exposes `mpris:artUrl` in its metadata, which
`src/.../dbus/MprisMusicControl.kt` already reads metadata from — it just doesn't pull that key.

The changelog's "toggle in phone → Watch → Music" also implies a new **watch pref** for it, which folds
into item 3.

**Sketch.** Same shape as item 1, and probably shares the image-encoding work: bump libpebble3, read
`mpris:artUrl`, fetch/decode (it is usually a `file://` URL to a cached JPEG/PNG), downscale to the
watch's Music-app art size, send. A `music.album_art` key, default off, for the same bandwidth reason.

**Blocked on:** the libpebble3 bump.

### 3. New watch settings from 4.33/4.36  ·  small, but gated  ·  **affects the settings surface**

> v4.36.0: "Album art in Music app (toggle in phone → Watch → Music)", "Music app controls require
> double tap", "Plain menu rows open with single tap", "Inertial flings for menus and scroll views",
> "Dynamic backlight dark-room brightness per mode"
> v4.33.2: "Touch control now enabled across OS", "Health assignable to any Quick Launch button"

**The crux, and the reason this matters for a settings-parity session:** stoandl drives watch settings
*generically* — `ListWatchPrefs`/`SetWatchPref` enumerate whatever libpebble3 exposes, and both GUIs
render one widget per *type*, not per id. So a new firmware pref costs **zero stoandl code**… but only
once libpebble3 knows about it.

And libpebble3's pref list is a **hardcoded enum**, not something read off the watch:
`libpebble3/.../database/entity/WatchPrefEntity.kt` defines 45 prefs across five enums — `BoolWatchPref`
(19), `QuicklaunchWatchPref` (8), `EnumWatchPref` (12), `NumberWatchPref` (5), `RgbColorWatchPref` (1).
None of the 4.33/4.36 prefs above is present at the pinned commit.

So: **every new firmware pref requires a libpebble3 submodule bump to appear at all** — the same shape as
the Ukrainian language-pack item from review 1. After a bump, both GUIs pick the new prefs up
automatically and `watch.<prefId>` in `stoandl.conf` works for them immediately.

**Blocked on:** the submodule bump, which memory records as HW-verification-gated (the fork sits on
`stoandl-rebased`, rebased onto `coredevices/master`, not yet promoted). This is the **highest
value-for-effort item in the batch** once that lands.

**Probably already fine:** *"Health assignable to any Quick Launch button"* is a firmware-side
restriction being lifted, not a new pref — the quick-launch pref value is a UUID
(`QuickLaunchSetting(enabled, uuid)`), and both GUIs build the option list from `ListApps`, so Health
should already be selectable. _TBT_ on 4.33.2+.

---

## Risk to an existing feature — needs a hardware check

### 4. The new weather app  ·  v4.36.0  ·  ⚠️ could break weather sync

> "New weather app by Grim (needs Pebble app >1.10)"

This is the one item that could **regress** something stoandl already ships. The weather BlobDB record is
**versioned**: `WeatherAppEntity` (`libpebble3/.../database/entity/WeatherAppEntity.kt:67`) writes
`version: UByte = 3u`, followed by a fixed field layout (current/today/tomorrow temps + types, update
time, current-location flag, location name, short forecast).

If the replacement watch app expects a **version 4** record — new fields, a different layout — a
version-3 record may render partially, or be rejected. "Needs Pebble app >1.10" says the official
companion was updated in lockstep, which is consistent with a format bump.

**This cannot be settled from the code**: libpebble3 is pinned before 4.36 existed, so its record is by
definition the old one. What would settle it: (a) a watch on 4.36.x with `stoandl weather` pushed, and
look at the watch's Weather app; or (b) reading libpebble3 upstream commits after 2026-07-23 for a
`WeatherAppEntity` version bump.

**Recommended:** run TESTING §5.x weather verification before updating a daily-driver watch to 4.36, and
treat a weather regression on 4.36 as expected-until-bumped rather than a stoandl bug.

---

## Workaround-obviating — do not remove anything without a hardware re-test

### 5. NimBLE 1.10.0 · fast-advertising battery drain  ·  v4.31.2 / v4.33.2

> v4.31.2: "NimBLE updated to 1.10.0", "Power consumption bug fixes"
> v4.33.2: "Battery drain from fast advertising fixed"

stoandl carries a lot of reconnect/pairing engineering, but most of it is **BlueZ-side or host-side and
firmware-independent**: the stale-bond reaper, the broken-bond detector, the external-discovery
(scanner-hogging) detector, the suspend/GATT-app re-registration, the 420 ms LE supervision timeout
(adapter-wide, a BlueZ knob). A watch-side BLE stack bump does not touch those.

The advertising fix is plausibly relevant to the **overnight out-of-range flapping** noted in memory, but
"plausibly" is the whole claim — and a firmware fix only helps once the watch is actually on 4.33.2+.

**Recommended:** no code change. When the user's watches are on 4.33.2+, re-run the reconnect rows in
TESTING and see whether the flapping profile changed; only then consider relaxing anything. Per the
project's convention this stays _TBT_.

---

## Already handled — no action

- **Battery/heartbeat layout risk.** `battery.heartbeat` decoding is guarded on the exact
  `(size, version)` pair — `HeartbeatLayout.kt` (part of the uncommitted heartbeat work, not on this
  branch) whitelists `(523,1)`, `(527,1)`, `(523,2)` and
  nothing else. An unknown layout sets `known = false`, decodes **no** metrics, and stores the blob raw.
  So a 4.36 record with a changed layout degrades to "nothing decoded, here's why" rather than to wrong
  numbers — which is exactly the intended behaviour, and the GUI's Heartbeat page already explains it.
  If 4.36 does change the layout, adding a row to that table is the whole fix.
- **"Battery readings no longer truncated on nPM1300"** — a firmware-side measurement fix. It improves
  the accuracy of what stoandl reads (GATT level and the heartbeat SoC/voltage metrics); no host change.
  It does mean any power-model calibration should be done on 4.36+, not before.
- **"Unserved HRM subscribers no longer drain battery"** — relevant background for the pending
  power-pie HRM calibration (memory: the `hrm@174` offset check): the HRM drain profile on 4.36+ is
  deliberately different from earlier firmware, so calibrate against one firmware generation, not across.
- **"Heart-rate variability API on Pebble Time 2"** (v4.33.2) — a watchapp C-SDK capability. No
  phone-side HRV path in the health model; watch-side only unless upstream adds a datalog tag for it.
- **"Notification storage crashes fixed"**, **"Blank white notification popups fixed"**,
  **"Alarm/notification sounds no longer reboot watch"** — watch-side stability fixes that make stoandl's
  existing notification path more reliable with no change on our side.

## Watch-side only / irrelevant

Inertial flings for menus and scroll views · plain menu rows open with single tap · fast-forward/rewind
icons · music controls require double tap · workout app stays pinned · snooze survives DST · quick-launch
combos for timeline apps · left-hand quick launch · touch control enabled across OS · Round 2 UI tweaks ·
speaker pop before low-volume tones · dynamic backlight dark-room brightness · LECO '5' readability ·
compass calibration in weak fields · emoji at large text size · SDK 128 KB app limit for emery/gabbro.

---

## Summary

| # | Item | Disposition | Blocked on | Effort once unblocked |
| --- | --- | --- | --- | --- |
| 1 | Notification images | deferred, actionable | libpebble3 bump (no image attribute) | large — wire format + hints extraction + bitmap encode + a new config key |
| 2 | Album art | deferred, actionable | libpebble3 bump (MusicTrack is text-only) | large — shares the encode work with #1 |
| 3 | New watch prefs (4.33/4.36) | deferred, actionable | libpebble3 bump (prefs are a hardcoded enum) | **near-zero stoandl code** — best value in the batch |
| 4 | New weather app | risk to an existing feature | hardware on 4.36 | unknown until the format is known |
| 5 | NimBLE 1.10 / advertising fix | workaround-obviating | hardware on 4.33.2+ | removal only, after a re-test |

**One recommendation above the others:** bump `libs/libpebble3`. It is the single blocker on three of the
five items, and for item 3 it is the *entire* implementation.
