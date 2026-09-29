# Firmware gaps: PebbleOS through v4.38.2

This is the implementation view for the `pebbleos-changelog` reviews: what to do, in what order, and how.
The per-item dispositions live in [docs/pebbleos-changelog-review.md](docs/pebbleos-changelog-review.md)
(review 5, 2026-09-28, watermark v4.36.2 → **v4.38.2**). This file replaces the review-4 edition
(v4.31 → v4.36.2), and two of that edition's calls are corrected here:

- The heartbeat layout was **not** handled (§1.1).
- The weather v3 risk was a false alarm, but two *other* weather regressions are real (§1.3, §1.4).

Each item says whether it is implemented. Everything implemented is _TBT_ on hardware until its TESTING row passes.

## Sources and method

- **Changelog.** The Notion changelog carries notes only for **v4.38.1** in this window. v4.37.0, v4.38.0,
  v4.38.2 and five backport tags have empty release bodies.
- **Firmware source.** Because the notes no longer describe the wire, review 5 also diffed the firmware source
  (`/home/vscode/.cache/pebbleos-src`, 326 commits).
- **Upstream libpebble3.** It also compared the fork against upstream libpebble3
  (`/home/vscode/.cache/libpebble3-upstream`, `433fef18`, 517 commits past the fork's base `e4180ffc`).
- **Review.** Each theme had a grounded researcher and an adversarial verifier. Nothing was refuted.

---

## 0. Priorities

| # | What | Why first | Effort | Needs bump? |
| --- | --- | --- | --- | --- |
| 1 | Cherry-pick upstream `90b9caa3` (health parser) into the fork | Data loss: fw 4.38.0/4.38.1 minute records drop **all** steps and HR. A future fw will likely send v14 again, and then it loses everything on every watch | trivial | no |
| 2 | Heartbeat `(567, 3)` layout row | Battery insights' power pie, drain bars and notification overlay have been **dark since fw 4.33** | trivial | no |
| 3 | Weather: UTF-8 string length (`cf8f33e4`) + units from `unitsDistance` | Non-ASCII locations never update; warnings say "Below freezing" on warm days | trivial + small | no |
| 4 | Firmware "latest" = max semver | A backport release can hide newer fw, and a PRF watch would flash the backport | small | no |
| 5 | Small correctness fixes: notification timeout ≥15 s, all-day at UTC midnight, session distance ×100, sleep-card cherry-picks, `textStyle` docs | Each is a wrong result in a shipped feature | trivial each | no |
| 6 | libpebble3 bump to `433fef18` (§3) | Unlocks notification images, album art, weather v4, new prefs, QEMU testing, firmware resume/CRC | 4–6 d + 1–2 d HW | — |
| 7 | Host features independent of the bump (§5) | `pebbleos-translations` packs (4.38 removed built-in languages), Quick Launch actions, MPRIS seek | small each | no |
| 8 | Features on top of the bump (§4) | New prefs → weather v4 → album art → notification images, by value for effort | 0.5–3 d each | yes |

---

## 1. Regressions in shipped features: host-only fixes

### 1.1 Heartbeat record 567 B / v3 (fw ≥4.33.0)

Five commits (`df6d08bd` … `68415258`, all first released in v4.33.0) appended 10 metrics and bumped
`NATIVE_HEARTBEAT_RECORD_VERSION` to 3 (native.c: 2 at v4.32.0, 3 at v4.33.0 through v4.38.2).
`HeartbeatLayout.kt` whitelisted only `(523,1)`, `(527,1)` and `(523,2)`, so `HeartbeatLayouts.of()`
returned null and `decodeActivity()` returned null. The battery block survived through the structural
fallback, so insights and history kept working; the power pie, drain bars and notification overlay
did not.

**Done, to be tested (TESTING 5.29M).** The ten metrics are in `METRICS` in `analytics.def` order, and
`Layout(567, 3)` walks to 567 B. Instead of per-row omit sets (every new metric would have to be added to
every older row), each metric now carries the first release that emits it (`since`, plus `until` for
`settings_power_mode`), and each layout row names its first release. `tools/hb_layouts_from_source.py` derives
both from every PebbleOS release tag and prints the Kotlin lines. Walking every tag also turned up eleven
older released layouts (310 … 515 B, all v1, 4.9.158 … 4.13.0) that had no row. They have rows now, which only
works because `HeartbeatStore` now reads every field by name through the layout (before 4.13 even the battery
block sat elsewhere). No two releases fill the same `(size, version)` differently. Unit tests
(`HeartbeatLayoutTest`, `HeartbeatStoreTest`) check every layout's size, landmark offsets per layout from the
source, a synthetic 567 B record end to end, and that unknown layouts are rejected. History backfills from the
stored raw blobs. Bonus fields (`battery_temp_c`, `battery_soc_pct_min`, …) are in the full dump (`watch
battery heartbeat --all`, `HeartbeatMetrics`); `docs/heartbeat-metrics.md` lists them.

### 1.2 Health minute record v14 (fw 4.38.0/4.38.1), plus overlay bugs

- **Minute records.** Fork `HealthDataParser.kt:47-61,248-254` skips any version outside {5,6,7,8,13}. The DLS
  ACK precedes the parse (`DataLoggingService.kt:69`), so the records are gone from the watch.
- **Upstream fix.** `90b9caa3` (commonMain; its pre-image is byte-identical to the fork, so it cherry-picks
  cleanly) accepts `version > 13 && sampleSize >= 16`, skips the extra bytes, and fixes `VERSION_FW_4_1`
  8 → 12.
- **Overlay kcal.** The same commit fixes the swapped active/resting kcal in activity overlays
  (`HealthDataParser.kt:205-208`) and adds the fw's bit-0 compatibility gate. Nothing rewrites the
  `OverlayDataEntity` rows ingested before the bump, and `HealthExporter` re-projects `activities.ndjson`
  from the DB over `health.export_days`: until those days age out, the file mixes old rows (`active_kcal`
  = the resting value) with new ones. `daily.ndjson` is unaffected (minute data). Release-note it (TESTING
  5.219); a one-off swap of the old rows is possible but needs a reliable "done" marker.
- **Not fixed upstream: session distance.** The overlay distance is `distance_meters` on the wire, but it is
  stored as `distanceCm`, and `HealthExporter.kt:120` divides by 100. Change that line to
  `put("distance_m", s.distanceCm)`. Line `:165` is minute-level and really is cm, so it is fine. Past NDJSON
  rows stay wrong: overlays are consume-once.
- **Sleep card.** Cherry-pick `7de45f8a` then `42cbc5a4`. Fork `HealthStatsSync.kt:107-113` writes epoch
  seconds into the watch's bedtime/wake fields, and `:155-158` writes zero typicals.
- **HW check.** After moving the watch to 4.38.2, see whether the next sync backfills the 4.38.0/4.38.1 gap
  (`MAX(timestamp)` did not advance).

### 1.3 Weather: non-ASCII strings rejected (fw ≥4.34.0)

- **The mismatch.** The fw validator `prv_strings_block_is_valid` (`weather_db.c:50-77`) walks the string
  block. Fork `WeatherAppEntity.kt:90` declares its length as `locationName.length + 2 + forecastShort.length
  + 2`, which counts UTF-16 chars, while `SLongString` writes UTF-8 bytes.
- **Effect.** "München", Nominatim names, or stoandl's own "—" fallback phrase (`WeatherSync.kt:443`) make
  the record `E_INVALID_ARGUMENT`. The row stays dirty with no error surfaced.
- **Fix.** Cherry-pick upstream `cf8f33e4` (helper `serializedWeatherStringsLength()`). Independently, make the
  `else` phrase ASCII ("Unknown").

### 1.4 Weather: warning thresholds follow the watch's `unitsDistance` (fw ≥4.37.0)

- **The mismatch.** `weather_app_layout.c:435-518` converts the °C warning thresholds to °F when
  `unitsDistance` is Miles (the fw default), assuming the phone sent temperatures in that unit.
- **stoandl today.** It picks its unit from `weather.units` (`StoandlConfig.kt:73-74`, `WeatherSync.kt:233`).
- **Fix, and a single-source-of-truth cleanup.** Read the unit from
  `libPebble.healthSettings.first().imperialUnits` (the value `stoandl health profile units` already writes),
  and drop `weather.units` from config, schema, GUI and docs. This is what upstream `e501ff12` did.
- **Done, to be tested (TESTING 4.4-4.4c).** `WeatherSync` reads the unit from
  `healthSettings.imperialUnits` on every sync and re-fetches when it changes. It also writes that value to
  the watch when weather starts: libpebble3 syncs the units row only once something has written it, and
  reads an unwritten row as metric while the watch stays on Miles. `weather.units` is gone from config,
  schema and docs; a leftover line is ignored with a warning. The GUI mock still serves the key.

### 1.5 Firmware check: backports hijack "latest"

- **Why.** Backport releases (v4.9.142.4, v4.27.2/3, v4.30.2/3, published 2026-09-11…15) use GitHub's default
  `make_latest`. `GithubFirmwareSource.kt:51` trusts `/releases/latest`, and the pre-release path takes list
  order (`:44-49`).
- **Fix.** Fetch `/releases?per_page=30` and pick the max semver among non-draft releases (pre-releases per
  config) that have a `normal_<board>` asset. `SEMVER` also truncates 4-part tags (`FirmwareControl.kt:366`).
- **Tests.** Include the PRF case: `needsUpdate` returns true for `isRecovery` (`FirmwareControl.kt:289-290`),
  so a PRF watch would have flashed the backport.
- **Done, to be tested (TESTING 5.11f, 5.11g).** `GithubFirmwareSource` reads `/releases?per_page=30` and
  takes the highest `FirmwareTag` among non-draft releases (pre-releases per `firmware.github_prereleases`)
  that ship a `normal_<board>` bundle; unversioned tags count only when no versioned release qualifies.
  `FirmwareTag` keeps all four numeric parts, for the tag and for the running version, so a watch on
  v4.9.142.3 is offered .4. `FirmwareLatestTest` covers the order, the backports, drafts, pre-releases,
  the board filter and the PRF case.

### 1.6 Smaller ones

- **Notification timeout.** A `notifWindowTimeout` below 15 s makes the notification vanish and cancels the
  vibe on every released fw; the fw clamp `2782836` is only on `main`. Enforce a 15 000 ms minimum in
  `WatchPrefsControl` (the fork's min is 0 at `WatchPrefEntity.kt:533`). The bump brings upstream `912fde2c`.
- **All-day events.** `ICalParser.kt:177` uses `atStartOfDay(ZoneId.systemDefault())`. The fw applies
  `time_local_to_utc` to all-day timestamps (`item.c`), so anchor to **UTC midnight**. Absolute-time VALARMs
  on all-day events (`:203-206`) need the same compensation.
  - **Done, to be tested (TESTING 5.56a).** `ICalParser` anchors dates to UTC midnight and moves an absolute
    alarm on an all-day occurrence into that frame (its host-local wall-clock time, read as UTC); relative
    alarms already were frame-independent. `ICalParserTest` covers both across host zones, and
    `calendar dump` reads all-day dates in UTC.
- **`textStyle` (fw ≥4.38.1).** It is now only a one-shot seed; the real keys `systemTextSize` and
  `notifTextSize` are not phone-syncable. Reword `packaging/stoandl.conf.example:93` and the pref description.
  Raising the missing whitelist entries with PebbleOS is worth a short issue.
- **Dead backlight prefs.** `lightDynamicIntensity` and `dynBacklightMinThreshold` are rejected by every current
  fw, and each rejected row is resent on every connect (`BlobDB.kt:374-384`). Hide them with a small deny-list
  pre-bump, or just take the bump, which removes them. Drop the GUI section-rule special cases (`qml:75`,
  `settings.rs:53`).
- **Built-in languages removed (fw ≥4.38.0).** Add a line to the `firmware update` flow and docs: users of
  built-in de/fr/it/es/pt/nl/ca/pl need a pack afterwards. The real fix is §5.1.

---

## 2. Fork cherry-picks: no full bump needed

These are commonMain, self-contained, and a safer interim than the 517-commit bump.

| Commit(s) | Fixes | Notes |
| --- | --- | --- |
| `90b9caa3` | v14 minute records, overlay kcal swap, bit-0 gate, `VERSION_FW_4_1` | §1.2. Also adds `HealthDataParserTest`. |
| `7de45f8a` → `42cbc5a4` | Watch sleep card (typicals, seconds-of-day) | Order matters. |
| `cf8f33e4` | Weather UTF-8 string length | §1.3. |
| `WatchPrefEntity.kt` diff `e4180ffc..433fef18` (185 lines, one file) | New prefs (QT schedule, `dndAutoDismiss`, `musicShowAlbumArt`, `lightPreset`, `lightDynamicMode`, `unitsWind`, `language`); removes the dead ones; notification-timeout min 15 s | Brings the new `ScheduleWatchPref` type, so `WatchPrefsControl` and both GUIs need the §4.1 work. Trim `WatchLanguage` to Custom/English for fw ≥4.38. |
| fork-only patch | `VibeScore` 15-20 (DoublePulseMedium, PebbleMorse, Heartbeat, DoubleTap, Wave, Imperial) + `dndTouchBacklight` | Not upstream. The patterns exist only on Core boards with fw ≥4.38.0. Also fixes a display bug: a watch-picked id ≥15 decodes to the default, so a pinned `watch.vibeScore*` overwrites it. |

---

## 3. The libpebble3 bump to upstream `433fef18`: runbook

**Status: not attempted.** The overnight session was asked to try it, but the container had run out of process
slots before the first build (see Environment below). Everything here comes from a read-only analysis:
`git merge-tree` plus a probe rebase in a scratch clone.

**Cost:** about 4–6 engineering days plus 1–2 days of hardware testing, for a bump that ships no feature by
itself.

### 3.1 Before starting

- `stoandl backup`. Room goes from schema 38 to 47 (auto-migrations + `MIGRATION_39_40`, which forces a full
  locker re-sync). Downgrades are destructive (`fallbackToDestructiveMigrationOnDowngrade`), so rolling the jar
  back wipes `libpebble3.db`.
- Work in a scratch clone under `/home/vscode/.cache`, never in `/workspace` (bind mount).
- Pre-squash the 8 Rhino-era commits into `889a6498` (GraalJS), and fold `0636519d` into `d3cb437e`.

### 3.2 Textual conflicts (16 files)

| File | Resolution | Difficulty |
| --- | --- | --- |
| `settings.gradle.kts` | Keep the fork's `ANDROID_HOME` gate; put upstream's new `:androidApp`/`:cactus-native` inside it. `:pebble` stays excluded (stoandl only reads `LanguagePackRepository.kt` as text; the marker is still at `:99`). | trivial |
| `gradle/libs.versions.toml` | Take upstream (AGP 9.3.1, Kotlin 2.4.10, Koin 4.2.2, Ktor 3.5.1, serialization/coroutines 1.11.0, kotlinx-io 0.9.1, kermit 2.1.0, atomicfu 0.33.0, kable 0.43.1) but keep `jvm-toolchain = "21"` and its comment. | easy |
| `blobannotations/build.gradle.kts` | Re-implement the gate for the AGP-9 KMP plugin. Configure `android {}` through the extension API: type-safe accessors don't exist for conditionally applied plugins. Drop `iosX64`. | moderate |
| `libpebble3/build.gradle.kts` (9 hunks) | Start from upstream. Re-wrap the android block, android source sets and `kspAndroidMain` in the gate. Keep `jvmToolchain(25)` + `JVM_21`, the `kspKotlinJvm` hook and the jvmMain dbus-java/Graal deps. Keep the iOS targets (Room codegen). | hard |
| `PlatformIdentifier.kt` | `data class BlePlatformIdentifier(val peripheral: Peripheral?, val autoConnect: Boolean = false)`. Keep the fork's `expect`; the JVM actual returns `(null, false)`. | moderate |
| `KableGattClient.{android,ios,jvm}.kt` | Re-apply the fork's actuals with the new signatures. JVM: `refreshServicesNative() = false`, keep `requestMtuNative`. | easy |
| `ConnectionParams.kt` | Keep the fork's DEBUG level. | trivial |
| `ppog/PPoG.kt` | Take upstream's structure (`respondToResetRequest`/`initWithResetRequest`, `run(reversed)`). Re-apply the fork's four changes: re-send ResetComplete on a repeated ResetRequest, inbound baseline resync when `lastSentAck == null`, the `if (!closed)` guard, and a 30 s forward-path timeout (reversed stays 12 s). **Watch the positional `run(Boolean)` call:** the parameter changed meaning. | hard |
| `Datalogging.kt` + `DataLoggingService.kt` | Drop the fork's `records`/`DataLogRecord`. Add `itemType` (and optionally `sessionId`) to upstream's `ThirdPartyDatalogEvent.Batch`: a good upstream PR. | moderate |
| `di/LibPebbleModule.kt` | Keep the fork's `platformGattConnector` binding (plus a `BlePlatformConfig` arg); keep upstream's scoped `BlePlatformIdentifier` and `QemuTransport`. | easy |
| `js/PKJSApp.kt` | Take upstream; change its `catch (Exception)` back to `Throwable` (StackOverflowError). | easy |
| `Pairing.jvm.kt` | Keep the fork's BlueZ implementation; add the `connectionScope` parameter. | trivial |
| `KableBleScanner.jvm.kt` | Keep the fork's body with the new signatures; add a no-op `configureKableCentral`. | easy |
| `GattServer.jvm.kt` (rebase-only stop) | Keep the fork's server + GattManager1 self-heal; add a no-op `removeServices()`. | trivial |

### 3.3 Silent breaks (no conflict marker)

- **`BluezBle.jvm.kt`.** `ConnectedGattClient.subscribeToCharacteristic` gained `onSubscription` (call it after
  `StartNotify` succeeds; reversed PPoG depends on it). It also needs `refreshServicesNative()`: re-run
  `discover()` and return true.
- **`PebbleBle.kt` auto-merges but won't compile.** The fork's post-pairing `registerDevice` references a
  removed `config`. Drop it (upstream covers it); keep the post-bond `discoverServices()`.
- **GATT server lifecycle.** Upstream `1d3574d1`/`379e4b77` make service registration *lazy* (first
  `registerDevice`), and the merge adopts that silently. The watch is sensitive to when the PPoG service appears
  (see fork `3ae71cb0`). Keep eager `addServices` on JVM, and HW-test daemon-restart → reconnect and first
  pair.
- **`PPoGReset` is gone upstream.** The connect-time reset-characteristic write disappears with the rebase.
  HW-test the forward handshake.
- **PKJS.** `JsRunner.signalConfigMessage(requestId, json)` is new and abstract; implement it as a JS eval with
  JSON-encoded args. `PrivatePKJSInterface` needs `pluginRegistry` (inject it via `PKJSModule.jvm.kt`). Port
  upstream's `startup.js` config-message/plugin hooks.
- **JVM Koin module (runtime crash, not a compile error).** Add `single { PhoneBatteryMonitor() }`,
  `single { PhoneNetworkMonitor() }`, `single { PlatformPlugins(emptySet()) }` and
  `single<NotificationImageProvider> { NoNotificationImages() }`. Upstream's JVM module is `TODO()`, so nothing
  upstream catches this. Add a test that resolves the whole Koin graph.

### 3.4 Daemon API drift (one behaviour-neutral commit)

- **`PebbleIntegration.kt:353`.** Change to `BleConfig(legacyReversedPPoG = false, useReversedPpogV2 = false)`.
  Also **pin `LibPebbleConfigFlow`**: upstream `PebbleBle` reads `libPebbleConfigFlow.value.bleConfig`, not
  `BleConfigFlow`, so today's pin would stop protecting the transport choice from persisted Java Preferences.
- **Interface stubs:**
  - `MprisMusicControl` gets `supportsAlbumArt = false`, `getAlbumArt(...) = null` and
    `albumArtUpdated = emptyFlow()`.
  - `LinuxSystemCalendar` gets `createEvent(...) = null` and `defaultCalendarPlatformId() = null`.
- **`StoandlWebServices.checkForFirmwareUpdate(watch, force)`.** Also check the call sites of
  `checkForFirmwareUpdates(force)` and `FirmwareUpdate.checkforFirmwareUpdate(force)`.
- **`DatalogStore.kt:49,67-74`.** Move to `thirdPartyEvents.filterIsInstance<Batch>()` after the `itemType`
  patch. Semantics change: health tags are consumed only when `uuid == SYSTEM_APP_UUID`, the buffer is 256 and
  drops *new* batches when full, and malformed batches are dropped. Re-test §5.8.
- **`WatchPrefsControl.kt:105,161,169,180`.** Add an `is ScheduleWatchPref` branch to each: parse via
  `QuietTimeSchedule.parse`, type `schedule`, allowed `HH:MM-HH:MM`, format `encode()`. Add the type to
  `docs/dbus-interface.md` (ListWatchPrefs record), `gui/tools/mock_stoandl.py` and both GUIs (§4.1).
- **Removed pref ids.** `lightDynamicIntensity`, `langEnglish` and `dynBacklightMinThreshold`: remove them from
  the conf example, GUI section rules and the mock. A pinned `watch.langEnglish` will log "Unknown watch pref";
  mention it in the release notes.
- **`SystemGeolocation.DEFAULT_MAX_AGE`** drops from 30 min to zero, which means more GeoClue requests.
  Pass an explicit `maximumAge` if that matters.
- **Outer build pins** (`build.gradle.kts:2-5,42-49`). Kotlin 2.3.10 → 2.4.10, Koin, Ktor, serialization,
  kotlinx-io, coroutines, kermit.

### 3.5 Toolchain

- **Gradle version.** Upstream's wrapper is Gradle 9.6.1; stoandl's is 8.14.2, and an included build runs on
  the root's Gradle. First try Kotlin 2.4.10 on Gradle 8.14.2 with AGP 9 kept unapplied by the gate. (Done:
  it configures. The wrapper moves to 8.14.4, the minimum KGP 2.5 will accept, which also clears KGP 2.4's
  "deprecated Gradle version" warning.)
- **If that doesn't configure,** do the deferred Gradle 9 bump in the same change: shadow plugin →
  `com.gradleup.shadow`, then APKBUILD, CI and `install.sh`. Upside: Gradle ≥9.1 runs on JDK 25, which ends the
  dual-JDK build.

### 3.6 Hardware regression pass (before promoting)

TESTING §5.23b, plus these rows:

- BLE reconnect, suspend/resume and first pair
- BLE daemon restart **while the watch stays linked** (lazy GATT registration: the PPoG service then appears
  mid-link, and the watch gets a Service Changed): the forward handshake completes within 20 s, no
  `negotiation timed out`
- Classic
- PKJS: `Pebble JS Bridge initialized.`, Clay, AppMessage, XHR, and a timeline-pin app (its XHR to the timeline
  API is intercepted; its `onload` must fire)
- datalog §5.8
- firmware §5.11, including the downgrade through recovery (5.11e)
- weather, music and calendar

**Handle §4.5 (downgrade → PRF) before shipping.** Only then promote to `stoandl`, update `.gitmodules`, and
push both repos.

### 3.7 Afterwards

Upstream the small commonMain patches (datalog `itemType`, the PPoG resend/resync, the nullable-peripheral
`BlePlatformIdentifier`) so the next bump is cheaper.

Extra Large text, notification text size and the new vibe patterns are **not** in upstream yet either.

---

## 4. Features the bump unlocks

### 4.1 New watch prefs: near-zero code

- **Zero code.** Bool/Enum prefs render generically: `dndAutoDismiss` (fw ≥4.37), QT weekday/weekend schedule
  toggles, `musicShowAlbumArt`, `lightPreset`, `lightDynamicMode`, `unitsWind`, `language`.
- **Needs work: the `schedule` type.** It needs a time-range widget in the Kirigami page and GTK
  `settings.rs`. Until then both show a read-only fallback row.
- **Optional polish:**
  - Show the schedule hours only while their toggle is on.
  - Hide the preset-managed backlight prefs unless `lightPreset = Advanced` (upstream app `0d3c8a93`).
  - File `units*` and `language` under Display (today they fall into "Other").
- **HW check:** does a phone-written `lightPreset` apply its bundle, or only store the value?

### 4.2 Weather v4: ~1 d

After the bump the v4 record (minor 5) goes out automatically when the watch advertises bit 24, but with
sentinel extras. To fill it, extend the Open-Meteo request in `WeatherSync`. This is the same endpoint, so
there is no new egress:

- **Daily:** `apparent_temperature_max`, `uv_index_max`, `precipitation_probability_max`,
  `wind_speed_10m_max`, `wind_direction_10m_dominant`, `precipitation_sum`.
- **Hourly:** `uv_index`, `relative_humidity_2m`, `visibility`.
- **Other request parameters:** `forecast_days = 7`, and **always `wind_speed_unit = mph`** (the watch converts).

Map them into the record:

- **Hourly slots:** today's and tomorrow's 24 location-local slots.
- **`utc_offset_seconds`:** divide by 60 into `locationUtcOffsetMin`.
- **Day 0:** put `wmoCode`, humidity, visibility and precip sum on `daily[0]`.
- **Location:** pass lat/lon.

The firmware's `weather_db_v4_example.md` maps every field. This also brings back the storm/hail/fog/UV warning
meaning that today's `WeatherSync.kt:418` (thunderstorm → HeavyRain) loses.

### 4.3 Album art: ~1–1.5 d

- **Wire path.** The watch pulls over Imaging endpoint `0x35` (AlbumArt), and only on emery (166×166) and
  gabbro (260×260). It asks only when all three hold: `musicShowAlbumArt` is on, the phone advertises
  SupportsImageFetch (17), and something is playing.
- **commonMain handles the rest.** `MusicControlManager` registers the handler when
  `supportsAlbumArt == true`. `ImageEncoder.encode(argb, w, h)` does the 16-colour median cut + dither against
  a measured Time 2 palette.
- **Host work:**
  - Read `mpris:artUrl` per player (`MprisMusicControl.kt:274-289` doesn't today).
  - Load it: `file://` and `data:` are local; `http(s)://` needs its own opt-in key, default off.
  - Decode, centre-crop and bilinear-scale, then encode.
  - Emit `albumArtUpdated` when the art URL changes mid-track.
  - Advertise SupportsImageFetch via a `PhoneCapabilities` override.
- **The crux is the decoder.** stoandl avoids AWT/ImageIO for the musl headless JRE (`PngEncoder.kt:10-13`).
  Options:
  - ImageIO headless, verified on the phone
  - a pure-JVM decoder dependency
  - shelling out to `magick`/`ffmpeg`, like `SystemVolume` does
- **No `music.album_art` host key.** The watch pref is the switch; a host key would duplicate it.

### 4.4 Notification images: ~2–3 d, shares the decoder with §4.3

- **Wire path.**
  1. The notification carries `TimelineAttribute` ImageAspectRatio `0x34` (gated on the watch's
     SupportsNotificationImages, bit 18).
  2. The watch then pulls the image over `0x35` via a `NotificationImageProvider`.
- **Host work:**
  - Capture D-Bus `hints` in `DbusNotificationMonitor` (today discarded at `:153`): `image-path` first, then
    `image-data` (`iiibiiay`), then maybe `app_icon`.
  - Keep a small disk cache keyed by item id, and implement the provider.
  - Set the aspect-ratio attribute in `WatchNotifier`.
  - Add a `notification.images` key, default off: bandwidth, and privacy (photos).

### 4.5 Firmware update improvements, and one hazard

- **Free with the bump:**
  - CRC check against the `.pbz` manifest (`4224d7ba`).
  - Watch-side resume on a manual re-run (`c9894f45`).
- **Needs work: auto-resume on reconnect.** It needs the `updateFirmware(FoundUpdate)` path instead of
  `sideloadFirmware(Path)`.
- **⚠️ Downgrades (`2461f781`).**
  - **What happens:** sideloading an *older* `.pbz` on a dual-slot watch (obelix/getafix) reboots it into PRF
    without transferring anything, and stoandl maps that to "reboot:" = success.
  - **Then:** stoandl's own `needsUpdate` is true in PRF, so it offers the **latest** release and one tap
    undoes the downgrade.
  - **Fix:** a distinct status, remember the pending `.pbz`, suppress the latest-release offer while it is
    pending, and re-sideload it in PRF.
  - **Implemented with the bump (daemon), to be tested (TESTING 5.11e):** `FirmwareStatus` reports
    `prf:<version>`, `FirmwareControl` re-sideloads the `.pbz` when that watch reconnects in PRF,
    `UpdateFirmware` answers `busy:` and `maybeNotify` stays quiet meanwhile, and the CLI follows the flash
    across the reconnect. Still open: both GUIs map any post-activity disconnect to success and know no
    `prf` phase.
- **Optional anywhere: digest check.** The GitHub API returns `digest: sha256:…` per asset; verify downloads
  with it. This needs no bump.
- **eng-dash (`dash.repebble.com/api/ota/latest`).** An optional opt-in source that gives release notes and
  staged rollout, but sends the watch serial.

### 4.6 Also in the bump

- **Zero code:**
  - calendar reminders' Dismiss/Snooze menu (`d1cffc9e`) and re-sync-on-edit (`9672cdd0` + `5ee093d0`)
  - PKJS timeline pins keeping all layout attributes (`e01bfa89`, `7aaea16`; this is what makes 4.38.1's larger
    sports cards show anything)
  - PPoG/protocol-runner no longer wedging on slow consumers (`29ffc7cf`)
  - PutBytes race fix (`1a0e0b54`)
- **Needs a `WatchConfig` knob:** calendar reminder vibe override (`77344fc2`).
- **Dev connection:** `startDevConnection(forceLan)` could replace the `lanDevConnection` pin.
- **QEMU emulator transport** (`38fd4c68`, `2b038eb1`, commonMain). v4.38.2 releases ship `qemu_*` images, and
  the fw's serial carrier survived the NimBLE-everywhere change. A `stoandl qemu connect host:port` would give
  **hardware-free testing in this BLE-less sandbox**: notifications, prefs, weather, PKJS.
- **Reverse PPoG v2** (`45894a23`; fw ≥4.24.0, all Core boards on 4.38). The watch hosts PPoGATT, so BlueZ is
  only a GATT client. That could retire the peripheral GATT app and its suspend/re-register wedge class for
  Core watches. Experimental knob, heavy HW testing.

---

## 5. Host features independent of the bump

### 5.1 `pebbleos-translations` language packs

- **Why.** fw 4.38.0 removed every built-in non-English language. Translations now live in
  `coredevices/pebbleos-translations`: 13 universal `.pbl` packs (ca, da, de, en_IL, en_SA, en_TW, es, fr, it,
  nl, pl, pt, zh_CN) plus a `manifest.json` (schemaVersion 1; per locale: `version`, `url`, `sha256`, `size`,
  `translatedStrings`, …). Upstream libpebble3 does not use it.
- **Sketch.**
  - Add a second catalog source behind the existing `language.download` opt-in.
  - Fetch `github.com/coredevices/pebbleos-translations/releases/latest/download/manifest.json`; avoid the
    rate-limited API.
  - Cache it under XDG cache, and fall back to the bundled Rebble catalog offline.
  - Prefer these packs for Core boards on fw ≥4.37.
  - Verify sha256, then use the existing installer.
  - Update detection must know the source: Rebble `de_DE` is v34, while these packs are v2.
- **Unverified:** compatibility with classic or pre-4.37 firmware, so gate it by version until tested.
  `language sideload <file>.pbl` already works today.
- **Carry-forwards.** `uk_UA` isn't published there yet. Arabic UI is still only kaluaim's `ar_SA`: `en_SA` is
  English UI with Arabic glyphs.

### 5.2 Quick Launch actions

- **The gap.** stoandl resolves quick-launch values only against the locker, so no *action* is selectable, and
  the defaults render as raw UUIDs.
- **Sketch.** A static name ↔ UUID table: Quiet Time, Backlight, Motion Backlight, Airplane Mode
  (`SystemAppIDs.kt:27-30`), Timeline Past/Future (`:23-24`), Clear notifications
  `d9c0d758-54bd-45b1-99ac-2a3a889350c9`, and Nothing `de6da17f-1a10-4725-adbb-1efc22e43f04` (fw ≥4.38.0).
  Consult it in `resolveAppUuid`/`appName` (`PebbleIntegration.kt:1353-1357`) and list it in `allowed`.
- **GUIs.** Add an "Actions" group, and label `off` honestly: it means "Unassigned", which opens the QL menu.
- About 30 lines + the GUIs.

### 5.3 Music

- **Handle MPRIS `Seeked`.** Position emits no `PropertiesChanged`, so desktop seeks leave the watch's progress
  wrong. The fw re-polls (`GetAllInfo`) only on connect.
- **Seek instead of skip.** When a player can seek but not skip (podcasts, audiobooks), seek ±15 s. The watch's
  FF/rewind icons (`skipSeeksWithinTrack`) need the bump.

### 5.4 Health

- **Health profile toggle.** Expose `hrm_activity` (`hrmActivityTrackingEnabled`) in `Get/SetHealthProfile`;
  it drives 4.38's scene-adaptive activity HR.
- **Live HR** via the watch's standard GATT Heart Rate service `0x180D`/`0x2A37`: 1 s notify, a per-device
  on-watch permission prompt, HRM watches over BLE only. Implement BlueZ-direct from the daemon, or as a fork
  `HrmWatcher` modelled on `BatteryWatcher`. Default off (battery).
- **"Watch fully charged" desktop alert** (`alerts.battery_full`), from battery level. The GATT level is
  BLE-only; Classic watches need the heartbeat SoC.

---

## Already handled: evidence

- **Weather v3 on the new app.** `weather_db.h:18-33,200-202`: fw ≥4.34.0 parses v3 and v4 on purpose. v3 just
  shows fewer fields.
- **`hrmPreferences` migration (fw 4.38.0).** The fork writes the 3-byte record version-gated
  (`HealthSettings.kt:88-92,249-261`).
- **Screenshot colours (4.38.1).** A fw compositor-freeze/DMA fix; the wire is unchanged.
- **GitHub release assets.** Still attached. eng-dash publishing (`b23a71ec`) was added *alongside*;
  `getafix_evt` bundles were dropped (pre-production board).
- **Long-track progress bar.** A fw 32-bit overflow; stoandl's µs → ms conversions are fine.
- **Health on any Quick Launch.** It is a locker system app, so both GUIs list it.
- **`.pbl` install flow.** Unchanged by the fw i18n rework (PutBytes FILE `lang`).
- **Notification send/dismiss contract.** Unchanged upstream: `sendDeletions = false`, `insertOrReplace`,
  action dispatch.
- **Watch capability flags.** Unchanged v4.36.2 → v4.38.2. The fork lacks bits 17, 18 and 24, which arrive with
  the bump.

---

## Environment: why the bump wasn't attempted, and the fix

- **The symptom.** During review 5 the container hit `pids.max = 512`: 487 zombie `git` processes, all children
  of PID 1.
- **Why zombies pile up.** In this container **PID 1 is `claude` itself, and it never reaps orphaned
  grandchildren**.
- **Where they came from.** The two `blob:none` partial clones under `.cache` run a lazy `git fetch` on every
  `git show` of an unfetched blob. Each fetch triggers auto-maintenance, which *detaches* by default and is
  orphaned onto PID 1. The research agents ran hundreds of such commands.
- **Effect.** Gradle (hundreds of threads) cannot start, and agents' shells fail with EAGAIN. Only a container
  restart clears it.
- **Fixes applied:**
  - `gc.auto = 0`, `maintenance.auto = false` and `*.autoDetach = false` in both clones' `.git/config`.
  - `git config --system {gc,maintenance}.autoDetach false` in `.container/Dockerfile`, so maintenance runs in
    the foreground and is reaped by its parent.
- **Longer term.** An init that reaps (for example `docker run --init`/tini as PID 1) would fix the whole class,
  but the base image and entrypoint are outside this repo.
