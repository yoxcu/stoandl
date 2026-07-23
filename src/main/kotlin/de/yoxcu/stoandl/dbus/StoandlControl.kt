package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal

const val STOANDL_BUS_NAME = "de.yoxcu.stoandl"
const val STOANDL_OBJECT_PATH = "/de/yoxcu/stoandl"

@DBusInterfaceName("de.yoxcu.stoandl.Control")
interface StoandlControl : DBusInterface {
    /** The version of the running daemon (from `git describe` at build time). */
    fun Version(): String

    /** Install a `.pbw` from [path] onto the watch. Returns a status-prefixed string
     *  (`ok:<msg>` / `error:<msg>` / `notready:<msg>`) so the real failure reason — e.g. an
     *  invalid .pbw — reaches the CLI instead of a bare boolean. */
    fun SideloadApp(path: String): String

    /** List the apps in the watch locker. Each entry is a tab-separated record:
     *  `uuid \t type \t order \t flags \t title \t developer \t version`, where flags is a comma-joined
     *  subset of {active, sideloaded, config, system, synced} (`synced` = present on the watch;
     *  system apps are always synced) and version is the locker version string (empty if unknown).
     *  Returns an empty list if libPebble is not ready. */
    fun ListApps(): List<String>

    /** The menu icon of the installed app/face with [uuid], extracted LOCALLY from its cached `.pbw`
     *  (no network/appstore) and written as a PNG under `<configDir>/icons/<uuid>.png` (cached after
     *  the first call). Returns `ok:<absolute png path>` on success, `none:` (no cached `.pbw`, no
     *  declared menuIcon, or an undecodable resource), or `notready:` (libPebble not ready). The GUI
     *  fetches this lazily per row rather than bloating [ListApps]. Icons are tiny (~25×25) and often
     *  monochrome glyphs; a watchFACE's menu icon is a small glyph, not a face preview. */
    fun GetAppIcon(uuid: String): String

    /** Launch the app/watchface matching [query] (UUID or case-insensitive name). Returns a
     *  status-prefixed string: `ok:<msg>`, `notfound:<msg>`, `ambiguous:<msg>`,
     *  `notready:<msg>` or `error:<msg>`. */
    fun LaunchApp(query: String): String

    /** Uninstall the app/watchface matching [query] (UUID or case-insensitive name) from the
     *  locker. Same status-prefixed return convention as [LaunchApp]. System apps are refused. */
    fun RemoveApp(query: String): String

    /** The app/watchface currently on the watch's screen (what's actually running now, which may be a
     *  launched watchapp — distinct from the *active watchface* flagged in [ListApps]). Returns
     *  `ok:<title>\t<uuid>` (title is `(unknown)` if the running UUID isn't in the locker), `none:`
     *  (nothing reported / not yet known), or `notready:<msg>` (no watch connected). */
    fun RunningApp(): String

    /** Move the app/watchface matching [query] to locker position [order] (0-based); the change syncs
     *  to the watch's menu via the locker BlobDB. Same status-prefixed return convention as [LaunchApp]. */
    fun SetAppOrder(query: String, order: Int): String

    /** Reset the system apps to their default locker order. Returns `ok:<msg>` or `notready:<msg>`. */
    fun RestoreSystemAppOrder(): String
    /** Returns the configuration URL for the running PKJS app matching [app] (name or UUID).
     *  Returns empty string if no matching app is running or it has no config page. */
    fun OpenConfig(app: String): String
    /** Signal the running PKJS app that the config webview closed with [data]. */
    fun WebviewClose(data: String)

    /** Debug: inject a synthetic incoming (ringing) call so the watch shows the native call
     *  screen. The watch's Answer button transitions it to an active call; Decline/Hangup ends
     *  it. Lets the call path be exercised end-to-end without a real telephony source.
     *  Returns false if libPebble is not ready. */
    fun FakeCallRing(name: String, number: String): Boolean

    /** Debug: clear the current synthetic call (simulates the remote party hanging up). */
    fun FakeCallEnd(): Boolean

    /** "Find my watch": make the connected watch ring continuously (like an incoming call named
     *  "Find My Watch") so a misplaced watch can be located. The ring stops when either button on
     *  the watch's call screen is pressed (Answer or Decline both just silence it — there is no
     *  real call to hold). Returns false if libPebble is not ready (no watch connected). */
    fun FindWatch(): Boolean

    /** Debug: send a synthetic notification ([title] + [body]) to the watch through the normal send
     *  path (so per-app mute, style and the global filters all apply). The host-side counterpart of
     *  [FakeCallRing] for the notification pipeline. Status-prefixed return (`ok:`/`error:`/`notready:`). */
    fun SendTestNotification(title: String, body: String): String

    /** Force an immediate weather fetch for the configured locations and push it to the watch.
     *  Same status-prefixed return convention as [LaunchApp]. Returns `error:` if weather sync is
     *  not enabled (no `weather.locations` configured). */
    fun SyncWeather(): String

    /** List the watch's advanced settings (WatchPrefs). Each entry is a tab-separated record:
     *  `id \t type \t current \t default \t allowed \t flags \t name \t description`. */
    fun ListWatchPrefs(): List<String>

    /** Set a watch setting [id] to [value] (parsed per the pref's type). Status-prefixed return
     *  (`ok:`/`error:`/`notready:`) as for [LaunchApp]. */
    fun SetWatchPref(id: String, value: String): String

    /** Request an immediate calendar re-sync (re-read sources → update timeline pins). Returns
     *  `ok:` once requested; `error:` if calendar sync is not enabled. */
    fun SyncCalendar(): String

    /** List the synced calendars, one per entry, tab-separated
     *  `id \t name \t enabled|disabled \t accountId`. `accountId` is the owning source's id (see
     *  [ListCalendarSources]) so the GUI can group calendars under their CalDAV account / iCal feed /
     *  .ics source; it's `discover` for auto-discovered local calendars and empty if unknown. */
    fun ListCalendars(): List<String>

    /** Enable or disable a calendar by numeric id or name substring. Status-prefixed return
     *  (`ok:`/`notfound:`/`ambiguous:`/`notready:`/`error:`). */
    fun SetCalendarEnabled(query: String, enabled: Boolean): String

    /** List the editable calendar *sources* (the things you add/edit/remove, vs the discovered
     *  calendars from [ListCalendars]). Each entry is tab-separated
     *  `id \t type \t url \t username \t label`, where `type` ∈ {caldav, ical, ics} and `id` is
     *  self-describing (`caldav:<token>` / `ical:<url>` / `ics:<path>`). The password is NEVER included
     *  (write-only). A CalDAV account fans out to many calendars (grouped via the `accountId` column of
     *  [ListCalendars]); an iCal feed / .ics path is a single calendar. Auto-discovery
     *  (`calendar.discover`) is a toggle, not listed here. */
    fun ListCalendarSources(): List<String>

    /** Add a calendar source. [type] ∈ {caldav, ical, ics}; [url] is the account/feed URL or local
     *  path; [username]/[password] apply to CalDAV only (password stored in the system keyring, else a
     *  0600 file — never in config). Persisted and synced live (no restart). Returns
     *  `ok:<id>\t<backend>` (backend = `keyring`/`file`/`none`) or `error:<msg>`. */
    fun AddCalendarSource(type: String, url: String, username: String, password: String): String

    /** Edit a calendar source by [id] (from [ListCalendarSources]). [url]/[username] replace the stored
     *  values; an EMPTY [password] keeps the existing CalDAV password (the GUI leaves it blank unless
     *  changing it), a non-empty one replaces it. Returns `ok:<id>\t<backend|kept|none>`, `notfound:`,
     *  or `error:`. */
    fun UpdateCalendarSource(id: String, url: String, username: String, password: String): String

    /** Remove a calendar source by [id] (and its stored CalDAV password). Persisted and synced live.
     *  Returns `ok:removed`, `notfound:`, or `error:`. */
    fun RemoveCalendarSource(id: String): String

    /** Open a ~2-minute pairing window and start monitoring for a connection. Returns
     *  `ok:` immediately; poll [PairStatus] for the outcome. */
    fun Pair(): String

    /** Return the current pairing status: `pending:<msg>`, `ok:<msg>`, `error:<msg>`, or
     *  `timeout:<msg>`. While pairing, once BlueZ requests numeric-comparison confirmation the message
     *  becomes `pending:Confirm code <NNNNNN> on the watch` — show it so the user can verify it matches
     *  the code on the watch (the phone auto-accepts; the watch-side confirmation is the MITM gate).
     *  Returns `error:No pairing in progress` if [Pair] was never called. */
    fun PairStatus(): String

    /** Answer the numeric-comparison confirmation that [PairStatus] is surfacing as `confirm:<code>`:
     *  [accept] true to bond, false to reject. The phone-side confirmation gates client-initiated
     *  pairing ([Pair]/[Repair]); the user verifies the code matches the watch first. Returns
     *  `ok:accepted`/`ok:declined`, or `error:No pairing confirmation pending` (none outstanding, or it
     *  already timed out). */
    fun ConfirmPairing(accept: Boolean): String

    /** Forget paired watch(es) on this host: libpebble3 forget() (stops auto-connect) plus a BlueZ
     *  RemoveDevice (clears the bond). [watch] empty = unpair ALL (blanket); otherwise a name substring
     *  selects a SINGLE watch (exact-then-unique-substring, like [Repair]). Use on the host you've moved
     *  the watch away from, to stop it endlessly retrying a watch that's now bonded elsewhere.
     *  Status-prefixed return. */
    fun Unpair(watch: String): String

    /** Re-pair a SPECIFIC known watch by name: forget just that watch (clears its state, Trusted
     *  intent and BlueZ bond) and open the pairing window — leaving any other watches untouched
     *  (multi-watch safe). Use when a watch was unpaired on the watch side and now churns. Returns
     *  `ok:` immediately; poll [PairStatus] for the outcome. */
    fun Repair(watch: String): String

    /** Connect a SPECIFIC known watch by name substring (exact-then-unique-substring, like [Repair]).
     *  In single-watch mode this hands the one connection slot to the chosen watch (disconnecting
     *  whatever else is active), so it's how you switch between paired watches without unpairing.
     *  Status-prefixed return. */
    fun Connect(watch: String): String

    /** Whether the host's Bluetooth is on and usable for the watch link. The daemon tracks this from
     *  libpebble3's adapter state AND the presence of `org.bluez.GattManager1` (so it also catches
     *  rfkill / airplane-mode, which leave `Powered=true`). Returns `ok:on` or `ok:off`; the GUI polls it
     *  to show a "Bluetooth is off" state instead of an empty no-watch screen. */
    fun BluetoothStatus(): String

    /** List known watches, one per entry, tab-separated `name\tstate\tbattery\ttransport` (state:
     *  connected / connecting / disconnected; battery is the 0–100 level for a connected watch, else
     *  empty; transport is `ble`/`classic` for a connected watch, else empty). Empty list if none
     *  are known. */
    fun ListWatches(): List<String>

    /** The connected watch's battery level. Status-prefixed: `ok:<name>\t<level>` (0–100),
     *  `unknown:<name>` (connected but no reading yet), or `notready:<msg>` (no watch). */
    fun Battery(): String

    /** Battery %-over-time series for the sparkline (see docs/battery-insights.md). [watch] selects a
     *  watch (empty = the connected one); [sinceEpoch] filters to samples at/after that epoch-second
     *  (0 = all). Returns `ok:` followed by newline-joined `ts\tlevel\tsource\tvoltage` records, oldest
     *  first — `source` is `heartbeat` (primary: hourly analytics, fractional %, `voltage` in volts) or
     *  `gatt` (fallback: BLE level, integer %, empty `voltage`). `ok:` with an empty body when the watch
     *  has no samples yet; `notready:<msg>` when battery capture is disabled. */
    fun BatteryHistory(watch: String, sinceEpoch: Long): String

    /** Derived battery insights for the GUI Battery card. [watch] selects a watch (empty = connected).
     *  Returns `ok:` + 12 tab-separated fields:
     *  `name, level, charging(0|1), dischargePerHour, hoursRemaining, chargeSessions7d, lastChargedEpoch,
     *  min24h, max24h, sampleCount, voltage, source`. `hoursRemaining`/`voltage` are empty when unknown
     *  (`voltage` only comes from the `heartbeat` source; `hoursRemaining` prefers the firmware's own
     *  estimate). `unknown:<name>` when there's too little data yet; `notready:<msg>` when disabled. */
    fun BatteryInsights(watch: String): String

    /** Per-interval battery activity for the drop-per-hour bar and the notification overlay. [watch]
     *  selects a watch (empty = connected); [sinceEpoch] filters to records at/after that epoch-second
     *  (0 = all). Returns `ok:` + newline-joined `ts\tdrop\tnotif\tnotifDnd` records, oldest first —
     *  `drop` is the SoC percent consumed over that interval (the firmware's own `soc_pct_drop` for the
     *  `heartbeat` source, else the consecutive-sample delta), `notif`/`notifDnd` are the count of
     *  notifications received that interval (total / while in Quiet Time; always 0 for the `gatt`
     *  fallback, which carries no counters). `ok:` with an empty body when there are no records;
     *  `notready:<msg>` when battery capture is disabled. */
    fun BatteryActivity(watch: String, sinceEpoch: Long): String

    /** Battery-drain attribution for the "what drew power" pie (heartbeat source only). [watch] selects
     *  a watch (empty = connected); [sinceEpoch] filters records (0 = all). Returns `ok:` +
     *  newline-joined `category\testDrainPct\tsharePct` slices, largest share first. Each subsystem's
     *  active time is weighted by an estimated average current and the interval's measured SoC drop is
     *  apportioned by that weight, so `estDrainPct` is a percent of battery (slices sum to the window's
     *  measured drop; 0 when the window has no measured discharge) and `sharePct` is its share — a
     *  modeled **estimate**, not metered energy. Categories: System (always-on floor), Display
     *  (backlight), Vibration, Speaker, Heart rate, Bluetooth, CPU. `ok:` with an empty body when
     *  there's no heartbeat data (e.g. GATT-only watch); `notready:<msg>` when battery capture is
     *  disabled. */
    fun BatteryPower(watch: String, sinceEpoch: Long): String

    /** Structured details for the connected watch (the GUI's watch-details dialog). Returns
     *  `ok:name\tcode\tmodel\tplatform\ttransport\tfirmware\tserial\tbattery\tlastSync` — transport is
     *  the human label `Bluetooth LE`/`Bluetooth Classic`; code is the BLE-name suffix (empty if none);
     *  battery is the 0–100 level (empty if unknown); lastSync is a relative "x ago" of the last
     *  connection. `notready:` when no watch is connected. */
    fun WatchDetails(): String

    /** Rename a known watch. [query] selects it (exact-then-unique-substring of its display name, like
     *  [Connect]/[Repair]); [nickname] is the new name (must be non-empty) and takes precedence in the
     *  watch's display name immediately. Status-prefixed (`ok:`/`notfound:`/`error:`/`notready:`). */
    fun SetWatchNickname(query: String, nickname: String): String

    /** Request a fresh health/activity sync from the connected watch and re-project the export. Returns
     *  `ok:<msg>` once the request is fired (data streams back asynchronously), or `notready:<msg>`. */
    fun SyncHealth(): String

    /** The latest stored heart-rate reading — the most recent non-zero HR sample in the health DB, which
     *  fills from the watch on connect via `health.sync`. Status-prefixed: `ok:<bpm>\t<epochSec>`
     *  (epochSec = when the reading was taken), `none:` (no HR sample stored yet), or `notready:<msg>`.
     *  Not a live GATT stream — freshness follows the synced samples. */
    fun HeartRate(): String

    /** The watch's health *profile* (its own activity-tracking configuration, written to the watch's
     *  HealthParams BlobDB — distinct from the read-only data sync). Each entry is tab-separated
     *  `key \t value`; keys: `tracking`, `activity_insights`, `sleep_insights`, `hrm`, `hrm_interval`
     *  (`10min`/`30min`/`1h`/`off`), `units` (`metric`/`imperial`), `height_cm`, `weight_kg`, `age`,
     *  `gender` (`female`/`male`/`other`), `resting_hr`, `max_hr`. Empty if libPebble isn't ready. */
    fun GetHealthProfile(): List<String>

    /** Set one [key] of the watch's health profile (see [GetHealthProfile] for keys) to [value] and
     *  sync it to the watch. Status-prefixed return (`ok:`/`error:`/`notready:`). */
    fun SetHealthProfile(key: String, value: String): String

    /** Health summary for the GUI Health screen over a period, computed from the synced health DB
     *  (works whether or not a watch is currently connected). [periodType] ∈ `day`|`week`|`month`;
     *  [offset] = how many periods back (0 = current; clamped ≥ 0) — `day`: today − offset days;
     *  `week`: the 7 days ending today − offset·7; `month`: the calendar month offset months back
     *  (current month is 1st → today). Returns `ok:` + 20 tab-separated fields:
     *  `stepsTotal, stepsAvgPerDay, stepsTypical, distanceKm, kcal, activeMin, sleepTotalMin,
     *  sleepDeepMin, sleepLightMin, sleepTypicalMin, sleepBedtime, sleepWakeup, hrAvg,
     *  hrResting, hrCurrent, hrMin, hrMax, hrAvailable(yes|no), daysWithData, lastSync`, or `notready:`.
     *  For `day` the step/sleep/HR fields are that day's; for `week`/`month` they are per-day averages
     *  (`stepsAvgPerDay`, `sleepTotalMin`/`Deep`/`Light` = avg per night) with `stepsTotal` the period
     *  sum; `stepsTypical`/`sleepTypicalMin` are the typical *daily* figures; `sleepBedtime`/`Wakeup`
     *  (epoch sec) and `hrCurrent` are populated only for `day` (0 otherwise — the GUI hides them).
     *  Pebble doesn't model REM (light = total − deep). There is no step-goal (dropped). */
    fun GetHealthSummary(periodType: String, offset: Int): String

    /** A health time-series for the GUI Health charts, for the period selected by [periodType]
     *  (`day`|`week`|`month`) + [offset] (periods back, 0 = current; clamped ≥ 0; see [GetHealthSummary]
     *  for the windows). [metric] is one of:
     *  - `steps` → `day`: 24 rows `hour(0–23)\tsteps`, the hourly step buckets for the day; `week`/`month`:
     *    one row per day `label\tsteps\ttypical` (typical = that weekday's typical daily total, empty if none).
     *  - `sleep` → `day`: that night's timeline, one row per interval `startFraction\twidthFraction\tisDeep`
     *    (0|1), fractions of an 18 h window (6 PM → noon); `week`/`month`: one row per night
     *    `label\ttotalMin\tdeepMin`.
     *  - `heart` → `day`: the **minute-level** samples `minuteOfDay\tbpm` (0–1439, only recorded minutes);
     *    `week`/`month`: one row per day `label\tavgBpm` (empty avg = no reading that day).
     *  Unknown metric → empty list. */
    fun GetHealthSeries(metric: String, periodType: String, offset: Int): List<String>

    /** Start flashing a local firmware bundle (`.pbz` at absolute [path]) onto the connected watch.
     *  The flash runs asynchronously; returns `ok:` once kicked off (poll [FirmwareStatus]), or
     *  `error:`/`notready:`. */
    fun SideloadFirmware(path: String): String

    /** Current firmware-update state of the connected watch: `idle:`, `downloading:<asset>`,
     *  `waiting:`, `inprogress:<percent>`, `reboot:` (success — watch rebooting), `failed:<reason>`,
     *  or `notready:` (no watch). Poll this after [SideloadFirmware]/[UpdateFirmware]. */
    fun FirmwareStatus(): String

    /** Check the source matching the watch's generation (GitHub for Core devices, cohorts.rebble.io
     *  for classic) for firmware matching its board. Returns
     *  `ok:<board>\t<current>\t<latest>\t<asset>\t<yes|no>\t<source>\t<changelogUrl>` (the 7th field
     *  is the PebbleOS changelog page, for a GUI "What's new" link — a constant, present on both `ok:`
     *  branches), `noasset:<board>\t<current>\t<source>`, or `disabled:`/`notready:`/`error:`. Requires
     *  the matching source enabled — `firmware.github` or `firmware.cohorts` (opt-in egress). */
    fun CheckFirmware(): String

    /** Check the matching source and, if newer firmware is available for the watch's board, download
     *  and start flashing it. Returns `ok:<board>\t<current>\t<latest>\t<asset>` once started (poll
     *  [FirmwareStatus]), `uptodate:`/`noasset:`/`busy:`, or `disabled:`/`notready:`/`error:`. */
    fun UpdateFirmware(): String

    /** Install a local `.pbl` language pack (absolute [path]) onto the connected watch. The transfer
     *  runs asynchronously; returns `ok:` once kicked off (poll [LanguageStatus]), or
     *  `error:`/`notready:`. */
    fun SideloadLanguage(path: String): String

    /** List the catalog language packs available for the connected watch's board, system-locale
     *  first. Each entry is tab-separated: `id \t isoLocal \t displayName \t installed(yes|no) \t
     *  source(rebble|github)`. Empty list if no watch is connected or the catalog is empty. */
    fun ListLanguages(): List<String>

    /** Auto-pick a catalog pack for [query] (an ISO locale like `de_DE`/`de`, a language name, or a
     *  catalog id; blank = the daemon's system locale), download it and install it. Returns
     *  `ok:<displayName>` once kicked off (poll [LanguageStatus]), `notfound:`, `disabled:` (needs
     *  `language.download = true`, opt-in egress), `notready:`, or `error:`. */
    fun InstallLanguage(query: String): String

    /** Current language-pack install state of the connected watch: `idle:`, `downloading:<name>`,
     *  `installing:<percent>`, `done:<name>` (just finished), `failed:<reason>`, or `notready:`.
     *  Poll this after [SideloadLanguage]/[InstallLanguage]. */
    fun LanguageStatus(): String

    /** Capture the connected watch's screen and write it as a PNG to the absolute [path]. Blocks until
     *  the (chunked) transfer completes — a couple of seconds. Returns `ok:<path>\t<width>\t<height>`,
     *  `notready:<msg>` (no watch), or `error:<msg>` (timeout, watch busy, or write failed). */
    fun TakeScreenshot(path: String): String

    /** Dump the watch's firmware logs to the absolute [path] as text. Blocks until the (multi-generation)
     *  transfer completes — a handful of seconds. Returns `ok:<path>`, `notready:<msg>`, or `error:<msg>`. */
    fun GatherLogs(path: String): String

    /** Fetch an existing coredump off the watch to the absolute [path]. Returns `ok:<path>`,
     *  `none:<msg>` (no coredump on the watch), `notready:<msg>`, or `error:<msg>`. */
    fun GetCoreDump(path: String): String

    /** The connected watch's metadata as a human-readable text block (model, firmware, board, serial,
     *  language, capabilities …). Returns `ok:<text>` or `notready:<msg>`. */
    fun WatchInfoText(): String

    /** Factory-reset the connected watch: wipe it back to out-of-box state (bonds, apps, settings) and
     *  reboot. Irreversible — the watch needs re-pairing afterwards. Fire-and-forget (no completion
     *  ack; the BLE link drops as the watch wipes). Returns `ok:<msg>`, `notready:<msg>` (no watch),
     *  or `error:<msg>`. The destructive confirmation is the CLI's job. */
    fun FactoryReset(): String

    /** Reboot the connected watch into its recovery (PRF) firmware — for un-bricking a bad normal
     *  firmware (reflash from PRF with `firmware`). Fire-and-forget (the link drops as the watch
     *  reboots). Returns `ok:<msg>`, `notready:<msg>`, or `error:<msg>`. */
    fun ResetIntoRecovery(): String

    /** Round-trip liveness check: send a PING to the watch and await the echoed cookie, exercising the
     *  full PPoG/protocol path (not just the BLE link state). Returns `ok:<msg with latency>`,
     *  `notready:<msg>` (no watch), or `error:<msg>` (timeout / cookie mismatch). */
    fun Ping(): String

    /** Ask the watch to capture a fresh core dump (a RAM snapshot for crash diagnosis). The watch
     *  reboots to write it to flash (the link drops like a reset); pull it afterwards with
     *  [GetCoreDump]. Fire-and-forget. Returns `ok:<msg>`, `notready:<msg>`, or `error:<msg>`. */
    fun ForceCoreDump(): String

    /** List the per-app notification store (apps lazy-tracked as they notify). Each entry is
     *  tab-separated: `name \t muteLabel \t lastNotifiedEpochSeconds` (muteLabel is one of
     *  `never`/`always`/`weekdays`/`weekends` or `muted-until <instant>`). Empty if per-app
     *  notifications are disabled or no app has notified yet. */
    fun NotifList(): List<String>

    /** Set the mute state for the app matching [query] (substring of name/package). [spec] is
     *  `always`/`never`/`weekdays`/`weekends` or a duration (`30m`/`1h`/`2d`) for a temporary mute.
     *  Status-prefixed return (`ok:`/`notfound:`/`ambiguous:`/`error:`). */
    fun NotifSetMute(query: String, spec: String): String

    /** Apply [spec] (same grammar as [NotifSetMute]) to every tracked app. Status-prefixed return. */
    fun NotifSetMuteAll(spec: String): String

    /** Set per-app notification styling for the app matching [query], applied host-side to its
     *  outgoing notifications: [color] (a TimelineColor name), [icon] (a TimelineIcon enum name), and
     *  [vibe] (a preset `short`/`long`/`double`/`triple`/`pulse`, or a CSV of on/off ms). For each
     *  value: empty = leave unchanged; `default` = reset. Status-prefixed return. */
    fun NotifSetStyle(query: String, color: String, icon: String, vibe: String): String

    /** List the global notification filters (a host-side allow/block list applied at the send choke
     *  point, independent of per-app mute). Each entry is tab-separated `pattern \t action` where action
     *  is `allow` or `block`. An `allow` filter is a whitelist (bypasses block filters, the master
     *  forwarding switch and per-app mute); a `block` filter drops matching notifications. */
    fun NotifListFilters(): List<String>

    /** Add (or replace, by exact [pattern]) a notification filter. [pattern] is a Java regex (inline
     *  flags like `(?i)` work), matched against the notification's app name, title and body. [action] is
     *  `allow` or `block` (anything else is treated as `block`). Status-prefixed return (`ok:`/`error:`
     *  for an empty/uncompilable pattern). Takes effect immediately (live). */
    fun NotifAddFilter(pattern: String, action: String): String

    /** Remove every filter with this exact [pattern]. Status-prefixed return (`ok:`/`notfound:`). */
    fun NotifRemoveFilter(pattern: String): String

    /** Start the developer connection: a LAN WebSocket server (port 9000) that bridges the Pebble SDK
     *  (`pebble install --phone <ip>`) / CloudPebble to the connected watch over BLE for installing and
     *  live-debugging apps. The server binds all interfaces with no auth, so it's started on demand.
     *  Returns `ok:9000` (server port), `notready:<msg>` (no watch), or `error:<msg>`. */
    fun StartDevConnection(): String

    /** Stop the developer connection server. Returns `ok:`, `notready:<msg>`, or `error:<msg>`. */
    fun StopDevConnection(): String

    /** Whether the developer connection server is running. Returns `ok:active`/`ok:inactive` or
     *  `notready:<msg>`. */
    fun DevConnectionStatus(): String

    /** Installed/enabled extensions, one tab-separated row each: `name \t installed|missing \t
     *  enabled|disabled \t running|stopped \t config \t description \t author \t version`, where
     *  config ∈ {none, schema} (`schema` = the manifest declares a `configSchema`; the `url` backend
     *  isn't implemented), description is the manifest's one-line summary, and author/version are the
     *  manifest's `author`/`version` (all empty if not declared). */
    fun ExtList(): List<String>

    /** Install an extension from an archive (.tar.gz/.tgz/.tar/.zip) into `<configDir>/ext/<name>/`:
     *  extracts it, sideloads a bundled `.pbw` if present, enables it, and hotplug-starts it.
     *  Status-prefixed return. */
    fun ExtInstall(path: String): String

    /** Stop [name], remove it from `extensions.enabled`, and delete its files. If [keepConfig] is true
     *  and the extension has a `config` file, that file is kept (so a later reinstall restores its
     *  settings); everything else is removed. The CLI prompts for this. Status-prefixed. */
    fun ExtUninstall(name: String, keepConfig: Boolean): String

    /** Enable [name] (add to `extensions.enabled`) and hotplug-start it. Status-prefixed. */
    fun ExtEnable(name: String): String

    /** Disable [name] (remove from `extensions.enabled`) and stop it; files are kept. Status-prefixed. */
    fun ExtDisable(name: String): String

    /** Restart [name]'s process. Status-prefixed. */
    fun ExtRestart(name: String): String

    /** The current settings of extension [name] as a JSON object of string values, read from its
     *  `<configDir>/ext/<name>/config` file (for the GUI's native config form). Returns `ok:<json>`,
     *  `ok:{}` when it has no config yet, or `notfound:`. */
    fun ExtGetConfig(name: String): String

    /** Merge [payloadJson] (a JSON object of key→value) into extension [name]'s `config` file — an
     *  atomic write that preserves comments and unchanged keys (so unsent secrets aren't clobbered) —
     *  then restart it if running. Status-prefixed (`ok:`/`notfound:`/`error:`). */
    fun ExtSetConfig(name: String, payloadJson: String): String

    /** The typed config schema of extension [name] (its manifest `configSchema`) as a JSON array, for
     *  the GUI's native config form. `ok:<json-array>` of `{key,type,label,secret?,options?}`, `none:`
     *  (no schema declared), or `notfound:`. */
    fun ExtConfigSchema(name: String): String

    /** The config entry point for extension [name] when it ships the **`url` config backend** — a hosted
     *  settings page the GUI opens in a browser. stoandl has no embedded HTTP server, so the `url` backend
     *  isn't implemented and this never returns a URL today: `error:<msg>` when the extension declares a
     *  `configSchema` (the GUI renders that as a native form via [ExtConfigSchema] instead), `none:` when
     *  it has no config, or `notfound:`. Kept for contract parity; wire it up if a URL backend is added. */
    fun ExtOpenConfig(name: String): String

    /** Per-feature sync/bridge status for the GUI Settings/Sync screen: one tab-separated
     *  `service\tenabled\tavailable\tlastSync` for {notifications, weather, calendar, music, health,
     *  dnd}, reflecting live runtime state. `available` is false for weather/calendar when no source is
     *  configured (the toggle is then greyed); `lastSync` is `live`/`off`/`no source` (and the dnd mode). */
    fun GetSyncStatus(): List<String>

    /** Current now-playing state from the desktop MPRIS bridge, for the GUI Sync screen's Music row.
     *  Returns `ok:<playing|paused>\t<player>\t<track>` (track = `Title — Artist`, may be empty),
     *  `none:` (music control on but nothing playing / no player), or `notready:` (music control off). */
    fun MusicStatus(): String

    /** Turn a sync service on/off at runtime: [service] is one of {notifications, weather, calendar,
     *  music, health, dnd}. Persists the backing key and **starts/stops the live service** (no restart) —
     *  the dnd boolean maps to its mode (true→both, false→off; the direction stays editable in Settings).
     *  Status-prefixed return (`ok:<service> enabled|disabled`, `notfound:` for an unknown service). */
    fun SetSyncEnabled(service: String, enabled: Boolean): String

    /** Current values of the curated daemon-config keys the GUI Settings screen shows — one
     *  tab-separated `key\tvalue` each (see [GetConfigSchema] for the matching type/label/options),
     *  read off the live (reloaded-on-write) config store. */
    fun GetConfig(): List<String>

    /** Schema for the curated daemon-config keys: one tab-separated `key\ttype\tlabel\toptions\tdesc`
     *  each (type `toggle`|`combo`; `options` is a CSV for combos). Paired with [GetConfig]. */
    fun GetConfigSchema(): List<String>

    /** Set one curated daemon-config [key] to [value] (a toggle's `true`/`false` or a combo's option
     *  label, per [GetConfigSchema]), persisted to `stoandl.conf`. The change is reloaded and the affected
     *  subsystem re-reconciled, so it **takes effect live** (no restart). `notfound:` for an unknown key,
     *  `error:` for an invalid value or a write failure. */
    fun SetConfig(key: String, value: String): String

    // --- Signals -----------------------------------------------------------------------------------
    // The daemon EMITS these (the first place it does — it otherwise only consumes signals). They are a
    // reactive layer ON TOP OF the poll methods, not a replacement: the daemon is not D-Bus-activated, so
    // a late/reconnecting client can miss a signal — clients re-sync via the matching method on connect
    // and keep a slow fallback poll. A signal is a nested DBusSignal subclass (dbus-java derives the
    // member name from the class and the interface from this @DBusInterfaceName); the daemon emits it via
    // `serviceConn.sendMessage(StoandlControl.X(STOANDL_OBJECT_PATH, …))`.

    /** Poke emitted when the set / connection-state / battery / transport of known watches changes.
     *  Carries no payload — the client re-calls [ListWatches] (the source of truth). */
    class WatchesChanged(path: String) : DBusSignal(path)

    /** Firmware-flash progress. [phase] is the status kind (`downloading`/`waiting`/`inprogress`/
     *  `reboot`/`failed`/`idle`/`notready` — same vocabulary as [FirmwareStatus]); [percent] is 0–100
     *  while `inprogress`, else `-1`; [detail] is the asset name / failure reason (empty while inprogress).
     *  Emitted on every phase change and every percentage tick. */
    class FirmwareProgress(path: String, val phase: String, val percent: Int, val detail: String) :
        DBusSignal(path, phase, percent, detail)

    /** Poke emitted when the locker (installed apps/faces) or the active watchface changes — including
     *  changes made on the watch or by another client. Carries no payload — the client re-calls [ListApps]. */
    class LockerChanged(path: String) : DBusSignal(path)

    /** Poke emitted when a calendar sync adds or drops calendars — after a source is added/removed/edited
     *  (the sync runs ~5 s later, plus CalDAV-discovery network time) or a periodic re-read changes the
     *  set. Carries no payload — the client re-calls [ListCalendars] (and [ListCalendarSources]). Lets the
     *  GUI update exactly when the new calendars are ready instead of racing the async sync. */
    class CalendarsChanged(path: String) : DBusSignal(path)

    /** Language-pack install progress (the [FirmwareProgress] twin for `language install`/`sideload`).
     *  [phase] ∈ {downloading,installing,done,idle,failed,notready} (same vocabulary as [LanguageStatus]);
     *  [percent] is 0–100 while `installing`, else `-1`; [detail] is the language name / failure reason. */
    class LanguageProgress(path: String, val phase: String, val percent: Int, val detail: String) :
        DBusSignal(path, phase, percent, detail)

    /** Poke emitted when an extension's installed/enabled/running state changes — enable/disable/restart/
     *  install/uninstall (or a settings change that stops/starts it), incl. from the CLI or another
     *  client. Re-call [ExtList]. The coarse companion to [ExtensionStateChanged]. */
    class ExtensionsChanged(path: String) : DBusSignal(path)

    /** Per-extension runtime state transition — the finer companion to [ExtensionsChanged] that surfaces
     *  UNSOLICITED changes the poll can't catch: [state] is `ready` (handshake done / running), `exited`
     *  (process ended unexpectedly, restarting after backoff), or `quarantined` (gave up after rapid
     *  failures — won't restart until `ExtRestart`). Lets the GUI show a crashed/quarantined extension. */
    // The property is `extensionName`, not `name`: a `val name` getter would clash with DBusSignal's
    // inherited getName(). The wire args are positional (extensionName, state → `ss`), so the property
    // name doesn't affect the signature.
    class ExtensionStateChanged(path: String, val extensionName: String, val state: String) :
        DBusSignal(path, extensionName, state)
}
