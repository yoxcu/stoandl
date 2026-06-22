# GUI daemon-hooks — implementation plan

The Kirigami GUI (`gui/` submodule) is built against ~18 D-Bus methods the daemon doesn't have yet,
catalogued in `gui/docs/handoff/drift-report.md` and implemented in its Python mock
(`gui/tools/mock_stoandl.py`). This plan maps each hook to its **real** libpebble3/stoandl data source,
classifies effort, and flags the decisions that need a human before coding. Source: a 5-agent grounded
pass over the mock + the daemon code (one agent per screen), 2026-06-22.

**Headline findings**

1. **Zero libpebble3 fork changes.** Every hook is stoandl outer-repo work (`StoandlControl.kt` decls +
   `PebbleIntegration` impl + per-feature controls + `Main.kt` CLI). The fork is untouched.
2. **`StoandlControlImpl` doesn't currently receive the `StoandlConfig` object** (ctor at
   `PebbleIntegration.kt:1685-1707`). Three hooks (`GetSyncStatus`, `GetConfig`, `SetConfig`) need a config
   ref (or a snapshot map) threaded in — do this once, up front.
3. **Runtime service on/off is the only genuinely hard part** and the drift-report **understates** it: not
   just the Koin `single<>` services (music = `MprisMusicControl`, calendar = `LinuxSystemCalendar`,
   notifications) have no teardown — even the "ref-held" weather/dnd/health services launch into the shared
   daemon `scope` with no `stop()`. Config is also read once at startup (no live reload). So *true* runtime
   stop for **any** service is new lifecycle work.
4. **The config is not introspectable.** Types/defaults/descriptions are hand-spread across the
   `StoandlConfig` data-class KDoc, `defaults()`, the `parseXxx()` reads, **and** the hand-maintained
   `packaging/stoandl.conf.example`. `GetConfigSchema` needs a curated schema table — watch the
   single-source-of-truth drift risk (a 5th place to keep in sync).
5. **A shared atomic `key = value` config writer** should be extracted from
   `ExtensionManager.setEnabled()` (today hardwired to the `extensions.enabled` line, `ExtensionManager.kt:325-357`)
   and share its lock — reused by `SetConfig` **and** `SetSyncEnabled`.
6. **The extension `url` config backend doesn't exist** (no embedded HTTP server anywhere in `src/`). Ship
   the **schema** backend only (native QML form); `ExtOpenConfig` returns `none:`/`error:` until/unless an
   HTTP server is justified.
7. **Quiet-hours and Filters are new host-side subsystems**, not watch-synced. Cleanest actuation for both
   is a drop-gate inside `WatchNotifier.push()` (the single send choke point), parallel to per-app mute —
   no fork change, no `dnd.sync` collision.

**Effort tally:** 6 already exist (confirm only) · 7 wiring · 2 trivial · ~7 design (mostly the write-path
+ the two new subsystems). Nothing needs the fork.

---

## Phase 0 — already done / confirmed (no work)

`StartDevConnection`/`StopDevConnection`/`DevConnectionStatus`, `GetCoreDump`, `NotifList`,
`NotifSetMute`/`NotifSetMuteAll`/`NotifSetStyle` already exist and byte-match the mock. `ListWatches`,
`ListApps`, `ExtList`, `CheckFirmware`, `ListWatchPrefs`/`SetWatchPref`, firmware/language/screenshot/logs
flows are present and consumed as-is. `HeartRate()` (added this session) supplies `currentHr`.

## Phase 1 — trivial + wiring, no open design (do first)

| Hook | Effort | Data source (cite) | Note |
|---|---|---|---|
| `ListWatches` +`transport` field | wiring | `ConnectedPebbleDevice as ActiveDevice).usingBtClassic` → `ble`\|`classic`; gate on `is ConnectedPebbleDevice` so `connecting`/disconnected emit `''` (`PebbleDevice.kt:30`, `WatchManager.kt:404`) | Append 4th field at `PebbleIntegration.kt:2356`; update `StoandlControl.kt:106` KDoc |
| `WatchDetails()` | wiring | Connected-device lookup like `Battery()`; fields from `dev.watchInfo` (model=`color.uiDescription`, platform=`platform.watchType.codename`, fw=`runningFwVersion.stringVersion`, serial, battery) + `dev.lastConnected` + transport label | **Decision: `code` field** — no libpebble3 source (mock fabricates it). |
| `SetWatchNickname(s,s)` | wiring | `KnownPebbleDevice.setNickname()` (`PebbleDevice.kt:78`) via the existing `matchOneWatch` resolver (`PebbleIntegration.kt:2344`) | `displayName()` already prefers nickname → reflects immediately |
| `CheckFirmware` +`changelogUrl` | trivial | Constant = the changelog page (`docs/pebbleos-changelog-review.md:8`); append to the two `ok:` branches (`FirmwareControl.kt:145-146`) | Per-release URL would need source-model work; constant matches the mock 1:1 |
| `ListApps` +`synced` flag | trivial | `LockerWrapper.NormalApp.sync` already in the iterated object (`LockerWrapper.kt:76`); `PebbleIntegration.kt:1933` | **Decision: system apps** — mock marks them `synced`; `SystemApp` has no `sync` field |
| `GetHealthSummary()` | wiring | 19 fields from `HealthDataApi` exactly as `HealthExporter.buildDay`/Core `HealthViewModel` already compute (`LibPebble.kt:148`); `hrAvailable` = `color.supportsHrm()` (`WatchColor.kt:62`); `currentHr` = the new `HeartRate()` value | **Decisions: `sleepRemMin`** (REM not modeled) + **`lastSync` bookkeeping** |
| `GetHealthSeries(steps\|sleep\|heart)` | wiring | Weekly `getDailyAggregates`/per-day `getDailySleepSession`; heart = 24 hourly buckets over `getHealthDataForRange`; `[]` when `!supportsHrm`; `''` value = no-data day | Reuse the summary's `supportsHrm` probe so the two never disagree |
| `GetSyncStatus()` | wiring | enabled bits already on the loaded `StoandlConfig` (notif/weather/calendar/music/health/dnd); refs already held (`weatherSyncRef`/`calendarSyncRef`/`healthExporterRef`/`dndSync`) | Needs the **config ref threaded in**; `lastSync` → placeholder for v1 (see below) |
| `GetConfig()` | wiring | Read the curated schema keys off the in-memory `StoandlConfig`; serialize enum→label etc. | Depends on the schema key set; needs the config ref |
| `ExtGetConfig(s)` | wiring | `readConfigFile(<extDir>/<name>/config)` (`ExtensionDef.kt:78-92`), already computed path | Type-coerce per schema if present, else string values |
| `ExtSetConfig(s,s)` | wiring | Atomic write of `<extDir>/<name>/config` reusing `setEnabled`'s temp+`ATOMIC_MOVE` pattern; optional `ExtRestart` after | Merge (not replace) per mock; don't clobber unchanged secrets |

## Phase 2 — design (needs a decision or a new subsystem)

| Hook | Why design | Recommended scope |
|---|---|---|
| `ExtList` +`config`+`description` | manifest has neither a config-kind discriminator nor a description today (`ExtensionDef.kt:104`) | Add `description` + `config: none\|url\|schema` (or derive `schema` from a `configSchema` array) to `manifest.json`; parse in `readManifest`. Append fields (back-compat: CLI reads cols 0-3 only) |
| `ExtConfigSchema(s)` | no typed schema in the manifest (config is a flat `String` map) | Add a typed `configSchema:[{key,type,label,secret?,options?}]` to the manifest; its presence is also the `schema` config-kind signal. JSON in the status tail (the one place this contract uses JSON) |
| `ExtOpenConfig(s)` | **no HTTP server exists** in stoandl | **Defer the `url` backend.** Implement the method but return `none:`/`error:`/`notfound:` only; ship the GUI on the schema form |
| `GetConfigSchema()` | config not introspectable; would be a hand-authored 4th source of truth | **Curated subset** (the GUI-relevant scalar keys, like the mock's 5) kept adjacent to `StoandlConfig`; flag the sync risk. Full-surface or schema-as-source-of-truth = a later refactor |
| `SetConfig(s,s)` | "apply" half blocked by load-once config (no live reload) | Extract a shared atomic `key=value` upsert (share `ExtensionManager`'s lock); return `ok:` with **"restart to take effect"** for non-live keys, best-effort live apply for the few that can |
| `SetSyncEnabled(s,b)` | no teardown for Koin singles; ref-held services have no `stop()` | v1: conf-rewrite + **best-effort live *start*** for weather/dnd/health/calendar-refresh; music/calendar/notifications return `ok:` with restart-needed. True runtime-stop = refactor those off `single<>` into ref-held child-scoped services (separate task) |
| `NotifGetQuietHours`/`SetQuietHours`/`SetQuietNow` | new host-side scheduled subsystem (watch has no time-window QT pref; `dnd.sync` is boolean-only) | Persist `from/to/enabled` (shared config writer) + a scheduler coroutine; **actuate via a drop-gate in `WatchNotifier.push()`** (no `dnd.sync` collision). `SetQuietNow` (transient 1h/morning override + timer) is nearly trivial; ship it first |
| `NotifListFilters`/`AddFilter`/`RemoveFilter` | new host-side global filter subsystem | libpebble3's `NotificationRuleDao` is the wrong shape (per-app, block-only, Android-only enforcement). **stoandl-owned global allow/block list** + a regex gate in `WatchNotifier.push()`. No fork change, but real precedence design (allow vs block vs per-app-mute order) |

---

## Decisions needed before building (with recommendations)

1. **WatchDetails `code` field** — no real source. → **Recommend: emit empty** (GUI tolerates `''`); or last-4 of `btAddress`.
2. **`ListApps` system-app `synced`** — → **Recommend: treat `SystemApp` as always-synced** (matches mock).
3. **`sleepRemMin`** — REM is not modeled anywhere (only total + deep). → **Recommend: emit `0`, `sleepLightMin = total − deep`.**
4. **`lastSync` (health + sync status)** — no stored sync-moment. → **Recommend: stamp a `@Volatile lastSync` on each sync; ship `GetSyncStatus.lastSync` as a placeholder ("enabled"/"never") in v1.**
5. **`GetConfigSchema` scope** — → **Recommend: curated GUI-relevant subset**, kept next to `StoandlConfig`.
6. **`SetConfig`/`SetSyncEnabled` apply semantics** — → **Recommend: conf-rewrite + "restart to take effect" for non-live keys; best-effort live *start* for weather/dnd/health.** (No service can be cleanly *stopped* at runtime today — honest limitation.)
7. **Extension config backend** — → **Recommend: schema-only for v1; defer the `url`/HTTP-server backend.**
8. **Quiet-hours actuator & Filters store** — → **Recommend: both as drop-gates in `WatchNotifier.push()`; filters in a stoandl-owned global store** (not the per-app, block-only libpebble3 DAO).

## Suggested sequencing

- **Batch A — DONE (Phase 1, no decisions but #1–#4):** transport field, `WatchDetails`, `SetWatchNickname`,
  `changelogUrl`, `ListApps.synced`, `GetHealthSummary`/`GetHealthSeries`, `ExtGetConfig`/`ExtSetConfig`.
  Thread the config ref in; add `GetSyncStatus`/`GetConfig` (placeholder `lastSync`). → unblocks Watch,
  Health, most of Settings-read, and ext config save. **Pulled most of Batch B forward** (`ExtList`
  `config`+`description`, `ExtConfigSchema`, manifest `description`/`configSchema` parsing).
- **Batch B — DONE (manifest + schema):** the manifest parsing + `ExtList` fields + `ExtConfigSchema`
  landed in Batch A; the remaining `ExtOpenConfig` stub (returns `none:`/`error:`/`notfound:` only — the
  `url`/HTTP backend stays deferred per decision #7) is now wired, so the extension-config surface is
  complete. The `configKindOf` helper in `ExtensionManager` is the single source of truth for the
  `schema`/`none` kind used by `ExtList`, `ExtConfigSchema`, and `ExtOpenConfig`.
- **Batch C (config write):** shared atomic `key=value` writer → `SetConfig` (restart-needed semantics).
- **Batch D (lifecycle/subsystems):** `SetSyncEnabled` (start-only v1), quiet-hours, filters.

Each batch is independently shippable; the GUI degrades gracefully on any not-yet-present method (it polls
and tolerates `notready`/`error`).
