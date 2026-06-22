# PebbleOS changelog — stoandl review log

This is the **persistent memory** for the `pebbleos-changelog` skill (`.claude/skills/pebbleos-changelog/`).
Each review reads the watermark below, triages only PebbleOS firmware changelog entries **newer** than it,
records a disposition for each, then bumps the watermark. Don't re-triage rows already in the log.

- **Changelog source (machine-readable):** `https://notion-api.splitbee.io/v1/page/25efbb55ea84801da04bfcf73c9346e1`
  (the human page `https://ndocs.repebble.com/PebbleOS-Changelog-25efbb55ea84801da04bfcf73c9346e1` is
  JS-rendered Notion — `WebFetch` of it returns only "Notion"; use the JSON endpoint).
- **Version cross-check:** `gh release list -R coredevices/PebbleOS`.

**Last reviewed: v4.17.0 (2026-06-19).**

Disposition key: 🟢 actionable · 🔵 already handled · 🟡 workaround-obviating fix / maintenance (HW re-test) · ⚪ watch-side-only · ⚫ irrelevant.

## Review log

First full review — 2026-06-21 (covered everything up to v4.17.0). Method: grounded fan-out (one agent per
theme cross-referencing the stoandl + libpebble3 code, then an adversarial verify pass). All five workflow
themes returned `verdict: confirmed`; HR + language packs were investigated by hand after a session-limit.

| First seen | Changelog item(s) | Disposition | Notes / next step |
| --- | --- | --- | --- |
| v4.17.0 (+core19/21/31/35) | **GATT "Service Changed" indication support** (0x2A05 in Generic Attribute 0x1801); GATT Battery Service exposure, BAS value fix, CCCD persistence, GATT notification fixes | 🔵 already handled | The watch is the GATT *server* for its own standard services (Battery 0x180F); **BlueZ — not stoandl — is the caching client** that consumes the Service Changed indication (auto removes/re-adds services + rewrites `/var/lib/bluetooth`). stoandl's `discover()` just reads BlueZ's current view via `GetManagedObjects()`, so v4.17 silently makes post-firmware-flash re-discovery work for free. stoandl reads only Battery today (stable since core19) → not hitting the stale-cache failure mode. **No code change.** Optional low-value hardening: a defensive re-discover after a flash for hosts on *old* BlueZ lacking Service Changed handling. **Never** delete `/var/lib/bluetooth` cache directly (bluetoothd-owned). `BatteryWatcher.kt`, `BluezBle.jvm.kt:483-542`. |
| v4.12.0 (+v4.9.175/163/108/100/76, core31/13/22) | **BLE-only advertisement (BR/EDR-Not-Supported)** = PR #1441 (Flags 0x02→0x06); + pairing-retry/disconnect/reset/airplane/single-phone fixes | 🟡 workaround-obviating | PR #1441 **resolves the single-adapter conflict** stoandl documented ("LE-only for Time 2 vs BR/EDR for Classic"). README already says "v4.12.0+ fixes the advertising bug, rarely needed" (L77/L79); only tightening left: add "(pre-4.12 BLE-native watches only)" to README L95-96. Classic scanner unaffected (CoD gate is robust to the flag flip). All other BT fixes are watch-internal and obviate **no** stoandl host-side workaround (churn detector, supervision-timeout handling, reconnect loop stay necessary). **Action: HW-confirm once the user's Time 2 is on v4.12+ that one default adapter serves both watches**, then the README tightening. |
| v4.9.111 (+v4.9.108/100/91/71, v4.17, core) | **Notification** custom icon/vibe/color + settings-sync; per-app vibe patterns; quick-launch notif wipe; QT toggle/30s timeout; open-in-app; dismissed-removal (STATE_READ) | 🔵 already handled | stoandl styles per-app icon/color/vibe **host-side at send time** (`WatchNotifier.kt:170-172`), drives every WatchPref generically (`settings list/set`), and dismisses via `markForDeletion` — safe because `TimelineNotification` is `sendDeletions=false` (no BlobDB delete sent; diverges safely from upstream's new STATE_READ model). No per-app notif **settings menu** on current fw → `notification.sync_to_watch` stays OFF. **Minor:** reword the `stoandl.conf.example` "surfaces nowhere" comment → "redundant with host-side filtering/styling". One TESTING note to reconfirm watch→desktop dismiss round-trips on STATE_READ firmware. |
| v4.17.0 (+v4.9.183/152/91) | **Quiet Time / DND**: auto-dismiss during QT, QT touch toggle, motion-backlight disable, notifications toggle | 🔵 already handled | All keyed to the 5 existing `dnd*` WatchPrefs (`dndManuallyEnabled/SmartEnabled/MotionBacklight/InterruptionsMask/ShowNotifications`) — all listable/settable via `stoandl settings`, and `dndManuallyEnabled` is mirrored by `dnd.sync`. "Auto-dismiss during QT" = the existing no-timeout behavior, no new pref. The new **QT touch toggle** is a convenient way to **HW-verify the one open unknown** (watch→host back-sync of manual QT) — a test opportunity, not code. No conflict with host-side mute. |
| v4.9.171 (+v4.11, v4.17, v4.9.183) | **Speaker API** (notes/tracks/PCM streaming, volume, playback limits), alarm sounds | ⚪ watch-side-only | The Pebble protocol has **no phone→watch audio endpoint** — the only audio endpoint flows watch→phone (mic/dictation, `AudioStream` DataTransfer). The Speaker API is a watchapp C-SDK capability driven by on-watch syscalls; **not host-drivable** without shipping a `.pbw`. Can't improve `findwatch` (reuses the call/ring path) or enable host-side "find my phone". **No opportunity.** |
| v4.17.0 | **Ukrainian language pack** (+ translation improvements) | 🟡 submodule bump + rebuild | stoandl's catalog is generated at build by `generateLanguagePackCatalog`, extracting the `LanguagePacksJson` manifest from the fork's `pebble` module (single source of truth, no checked-in JSON). Current submodule (`4caa27da`, 2026-06-17) does **not** yet list `uk_UA`. To surface it: bump libpebble3 to a commit whose `pebble` manifest includes Ukrainian (i.e. once coredevices adds the curated board/version/name metadata — the .pbl alone on binaries.rebble.io can't drive the catalog), then rebuild — **no hand-editing**. Verify with `stoandl language list` after the next bump. |
| v4.17.0 (+v4.9.175, core) | **Health / HR**: step-tracking improvements, HR-recovery automation, HR-monitor duration, wear detection | 🔵 already handled (+ small 🟢) | Recent changelog HR items are watch-side; stoandl's health sync already ingests HR samples (datalog tag 85) and exposes resting/avg/zones. **NB:** "live HR over a Pebble GATT char" is from the *old Pebble SDK 4.3* doc, **not** this changelog — libpebble3 has **no** live-HR GATT watcher (no 0x180D/0x2A37; only `BatteryWatcher` reads a watch-hosted standard service), so a real-time stream would need a fork `HrmWatcher`. **Cheap win:** libpebble3 already has `getLatestHeartRateReading()` (latest stored sample) → a `stoandl hr` convenience is wiring-only, independent of the changelog. |

## Carry-forward actions (surfaced by this review, not yet done)

- 🟡 **README tightening** (L95-96): scope the "LE-only vs BR/EDR can't coexist on one adapter" note to
  *pre-4.12 BLE-native watches* — PR #1441 (v4.12.0) fixed the advertising flag. Pair with a HW re-test
  once the user's Time 2 is on v4.12+.
- 🟢 **`stoandl hr`** (optional, low): surface libpebble3's `getLatestHeartRateReading()` as a one-line
  CLI/D-Bus convenience (pattern-twin of `stoandl battery`). Wiring-only.
- 🟡 **Language catalog**: after the next `libs/libpebble3` submodule bump, run `stoandl language list` to
  confirm Ukrainian (`uk_UA`) is present once upstream adds the curated metadata.
- ⚪ **conf.example wording**: "surfaces nowhere" → "redundant with host-side filtering/styling" for
  `notification.sync_to_watch` (cosmetic; the setting correctly stays OFF).
