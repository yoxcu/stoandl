# PebbleOS changelog — stoandl review log

This is the **persistent memory** for the `pebbleos-changelog` skill (`.claude/skills/pebbleos-changelog/`).
Each review reads the watermark below, triages only PebbleOS firmware changelog entries **newer** than it,
records a disposition for each, then bumps the watermark. Don't re-triage rows already in the log.

- **Changelog source (machine-readable):** `https://notion-api.splitbee.io/v1/page/25efbb55ea84801da04bfcf73c9346e1`
  (the human page `https://ndocs.repebble.com/PebbleOS-Changelog-25efbb55ea84801da04bfcf73c9346e1` is
  JS-rendered Notion — `WebFetch` of it returns only "Notion"; use the JSON endpoint).
- **Version cross-check:** `gh release list -R coredevices/PebbleOS`.

**Last reviewed: v4.30.0 (2026-07-22).**

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

### Review 2 — 2026-07-01 (watermark v4.17.0 → v4.20.0)

Newest tag **v4.20.0 (2026-06-30)**. Only **v4.19.0** carries published changelog notes; the
**v4.18.0 / v4.19.1 / v4.20.0** GitHub tags have **empty release bodies** and the Notion changelog jumps
4.17 → 4.19, so there is nothing to triage for those three yet (re-check next run in case the notes get
backfilled). **Nothing actionable for stoandl this round** — v4.19.0 is entirely watch-side / on-watch UX.

| First seen | Changelog item(s) | Disposition | Notes |
| --- | --- | --- | --- |
| v4.19.0 | **Expose alarm service to apps** (apps query available alarms, read-only) | ⚪ watch-side-only | Watchapp C-SDK capability. Grounded: libpebble3 has **no** phone→watch alarm endpoint — only the Alarms *system app* UUID (`SystemAppIDs.ALARMS_APP_UUID`), a `vibeScoreAlarms` WatchPref (already listable/settable via `stoandl settings`) and on-watch `AlarmsLPM`. No host hook to wire. |
| v4.19.0 | **GOTHIC_36 font for non-Latin text**; **RTL text-rendering fixes** | ⚪ watch-side-only | On-watch font / text rendering. Improves how non-Latin & RTL notification/UI text displays; stoandl already sends UTF-8 text — nothing to send differently. Built-in font, not a language pack. |
| v4.19.0 | **Fixed: only the top notification got dismissed in a stack on iOS** | ⚫ irrelevant | iOS ANCS dismissal path. stoandl identifies as `OSType.Android` and dismisses via `markForDeletion` / `CloseNotification` — not the iOS path, so no effect. See `memory/stoandl-notification-dismissal-fix.md`. |
| v4.19.0 | **Music app: show progress bar 5 s on accel tap when hidden** | ⚪ watch-side-only | On-watch Music UX. stoandl already streams now-playing position (MPRIS → `SystemMusicControl`); the display behavior is watch-side. |
| v4.19.0 | Accelerometer sampling tweaks · Touch fixed on PR2 · other minor fixes/cleanups | ⚫ irrelevant | Sensor / hardware / internal; no host surface. |
| v4.18.0 / v4.19.1 / v4.20.0 | *(binary tags — no published changelog notes at review time)* | ⚫ n/a | Empty GitHub release bodies; absent from the Notion changelog. Re-check next run. |

### Review 3 — 2026-07-23 (watermark v4.20.0 → v4.30.0)

Newest tag **v4.30.0 (2026-07-22)**. Two new *documented* versions since the last watermark carry
changelog notes: **v4.23.0 (2026-07-09)** and **v4.30.0 (2026-07-22)**. The changelog still jumps
4.19 → 4.23 → 4.30; the intermediate binary tags (4.20–4.22, 4.24–4.29) have empty release bodies and
no Notion notes (same pattern as Review 2). Method: grounded fan-out — one agent per host-relevant theme
cross-referencing `src` + `libs/libpebble3` + `memory/*.md`, then an adversarial verify pass. **All five
themes returned `verdict: confirmed`.** **Nothing actionable requiring a code change this round** —
everything is already-handled, watch-side-only, or irrelevant. One *new* calibration caveat (battery
insights) folds into an already-open item; one clarification retires part of the Review-1 language
carry-forward (built-in translations ≠ `.pbl` packs).

| First seen | Changelog item(s) | Disposition | Notes |
| --- | --- | --- | --- |
| v4.30.0 | **DND + language changes "apply immediately from the phone"** | 🔵 already-handled | Pure watch-side apply-latency fixes, invisible to the host. DND host→watch is a single generic `setWatchPref(QuietTimeManuallyEnabled=="dndManuallyEnabled")` over the WatchPrefs BlobDB (`DndSync.kt:95-96`), echo-swallowed via `synced` — **no re-push/reconnect hack to drop**. Language switch is a plain `PutBytes` of the `.pbl` as filename `"lang"` with `sendInstall=true` (`LanguagePackInstaller.kt:70-77`), no reboot step. **Not workaround-obviating** (nothing to remove). Optional: HW re-test `dnd.sync` latency on v4.30, doubling as a chance to close the still-open §5.25 **watch→host manual-QT back-sync** unknown. The `LanguageControl` `lastInstall` override targets a libpebble3 `WatchInfo`-snapshot staleness for the `list` display, **not** apply-latency, so this fix leaves it necessary. |
| v4.30.0 | **Better battery life through fewer background wakeups** | ⚪ watch-side-only (🟡 calibration caveat) | The hourly `native_heartbeat_record` cadence (`HEARTBEAT_PERIOD` 3600s) and the 523B/version-1 layout stoandl decodes are **unchanged**; `HeartbeatStore.decode()` is strictly gated `size==523 && version==1` and degrades to the GATT-level `BatteryStore` fallback on any mismatch. **But** the *empirical values* the mA-weighted power model is calibrated against (`soc_pct_drop@108`, `cpu_running/cpu_sleep` residency@198/204) drift **down** — fold this into the **already-open `MA_*` calibration item** (`HeartbeatStore.power()`), and do that calibration against v4.30.0+ firmware. No wiring change. |
| v4.30.0 | **Fixed step counts overflowing for very active users** | 🔵 already-handled | stoandl never ingests an on-watch cumulative counter — it consumes per-MINUTE UByte step deltas (`HealthDataParser.parseStepsData`) into `HealthDataEntity.steps:Int` and **re-aggregates totals host-side** (`HealthDao` `SUM(steps)` → `getTotalSteps`). Immune to a watch-side aggregation overflow. Optional: re-confirm daily totals match the watch UI on v4.30+ during the next HW health-sync test. |
| v4.30.0 | Fixed incoming iOS calls occasionally not handled correctly | ⚫ irrelevant | Firmware ANCS iPhone-call path. stoandl pins `OSType.Android` (`PebbleIntegration.kt:409`) and drives calls via ModemManager → `PhoneControl.IncomingCall` (`PhoneControlManager.kt:43-56`); libpebble3 has **no** ANCS incoming-call handler (only timeline/notification ANCS flags). Never exercised. |
| v4.30.0 | Fixed sounds being cut off at end of playback | ⚪ watch-side-only | No phone→watch audio playback endpoint exists — `AUDIO_STREAMING` is watch→phone dictation only (`AudioStreamService`, inbound `DataTransfer`); stoandl never references it (confirmed by `stoandl-matrix-audio-preview.md`). On-watch speaker rendering. |
| v4.30.0 | Expanded emoji (Noto Emoji); shake-to-light no longer from own vibration; Date & Time scroll-crash fix; "added more bugs to fix later" | ⚫ irrelevant / watch-side | Emoji/font rendering (stoandl sends UTF-8, nothing to send differently), accelerometer/backlight, on-watch settings-UI crash, and a joke. No host surface. |
| v4.23.0 | **Simplified backlight options** (Max Brightness / Standard / Battery Saver / Advanced) | 🔵 already-handled | Every backlight knob is already a host-settable WatchPref surfaced by `stoandl settings` — `lightEnabled`, `lightIntensity` (Low/Med/High/Blinding), `lightAmbientSensorEnabled`, `lightDynamicIntensity`, `lightMotion`, `lightTimeoutMs`, `lightTouch`, `lightColor`, `notifBacklight`, `dndMotionBacklight` (`WatchPrefEntity.kt`, `WatchPrefsControl.kt`). The new preset list is a **firmware-UI picker over existing prefs** — grep found no new backing pref. |
| v4.23.0 | **Added built-in Polish translation** (Cezar Pokorski) | ⚪ watch-side-only | This is a **built-in FIRMWARE translation** (compiled into fw), a *different mechanism* from the Rebble downloadable `.pbl` packs stoandl's `language` feature manages via the `LanguagePacksJson` manifest. Submodule (`cd8c15de`, 2026-07-14) manifest lists neither `pl_PL` nor `uk_UA`, and a built-in translation **never populates that `.pbl` catalog** → a submodule bump would **not** surface Polish in `stoandl language list`. No host-side locale-selection protocol either (only `BoolWatchPref.LanguageEnglish` = English-force toggle, not a picker). Polish is obtained by flashing fw v4.23+ (already supported via `firmware update`) and selecting it **on-watch**. |
| v4.23.0 | **Security fixes for Alloy** (agnosticlines) | ⚪ watch-side-only | "Alloy" = the Moddable/XS **on-watch JavaScript watchapp/watchface framework**, not a BLE/pairing/bonding stack — zero `alloy` hits in either repo. Unrelated to stoandl's reconnect/pairing workarounds (those live in `BluezBle.jvm.kt`/`BluezPairingAgent.kt`) and **distinct from stoandl's own GraalJS PKJS runtime** (different engine). No stoandl code or workaround to obviate. |
| v4.23.0 | Alarm sound preview; improved light-sensor algorithm; Arabic rendering fix + new font | ⚪ watch-side-only / ⚫ irrelevant | Alarm preview is on-watch (no phone→watch audio hook; alarm service is read-only watchapp API per Review 2); light sensor is hardware; Arabic/RTL is on-watch text rendering (stoandl sends UTF-8). |

## Carry-forward actions (surfaced by this review, not yet done)

- 🟡 **README tightening** (L95-96): scope the "LE-only vs BR/EDR can't coexist on one adapter" note to
  *pre-4.12 BLE-native watches* — PR #1441 (v4.12.0) fixed the advertising flag. Pair with a HW re-test
  once the user's Time 2 is on v4.12+.
- 🟢 **`stoandl hr`** (optional, low): surface libpebble3's `getLatestHeartRateReading()` as a one-line
  CLI/D-Bus convenience (pattern-twin of `stoandl battery`). Wiring-only.
- 🟡 **Language catalog**: after the next `libs/libpebble3` submodule bump, run `stoandl language list` to
  confirm Ukrainian (`uk_UA`) is present *once upstream adds it to the `LanguagePacksJson` manifest*.
  **Refined by Review 3:** the changelog distinguishes two mechanisms — **downloadable `.pbl` packs** (what
  stoandl's `language` feature manages, driven by the manifest) vs **built-in firmware translations**
  compiled into the fw and selected on-watch. v4.23.0 **Polish** is explicitly *built-in* → it will **never**
  appear in `stoandl language list` regardless of a submodule bump (flash fw + pick on-watch). This bullet
  therefore only applies to `uk_UA` **if** Rebble/Core actually ships it as a downloadable `.pbl` and adds it
  to the manifest; if Ukrainian is also built-in, drop this item.
- 🟡 **Battery-insights calibration** (new, Review 3): when the `MA_*` per-subsystem currents + `CAT_SYSTEM`
  floor in `HeartbeatStore.power()` are finally calibrated (already an open item), calibrate against **v4.30.0+**
  firmware — "fewer background wakeups" lowers the `soc_pct_drop` / CPU-residency baseline the mA-weighted power
  split is anchored to. The 523B/version-1 heartbeat layout + hourly cadence are unchanged (decode guard degrades
  safely), so this is a calibration concern only, not a wiring change.
- ⚪ **conf.example wording**: "surfaces nowhere" → "redundant with host-side filtering/styling" for
  `notification.sync_to_watch` (cosmetic; the setting correctly stays OFF).

_Review 2 (2026-07-01, v4.18.0 → v4.20.0) added **no** new carry-forward actions — the four above (from
Review 1) remain the only open items. Firmware updates are now **hardware-verified** (v4.19.0's advertising
fix in PR #1441 etc. don't change that); the README-tightening + Time-2-on-v4.12+ re-test is still the
relevant follow-up for the BLE/Classic adapter note._
