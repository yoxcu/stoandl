package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface

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
     *  `uuid \t type \t order \t flags \t title \t developer`, where flags is a comma-joined
     *  subset of {active, sideloaded, config, system}. Returns an empty list if libPebble
     *  is not ready. */
    fun ListApps(): List<String>

    /** Launch the app/watchface matching [query] (UUID or case-insensitive name). Returns a
     *  status-prefixed string: `ok:<msg>`, `notfound:<msg>`, `ambiguous:<msg>`,
     *  `notready:<msg>` or `error:<msg>`. */
    fun LaunchApp(query: String): String

    /** Uninstall the app/watchface matching [query] (UUID or case-insensitive name) from the
     *  locker. Same status-prefixed return convention as [LaunchApp]. System apps are refused. */
    fun RemoveApp(query: String): String
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

    /** List the synced calendars, one per entry, tab-separated `id \t name \t enabled|disabled`. */
    fun ListCalendars(): List<String>

    /** Enable or disable a calendar by numeric id or name substring. Status-prefixed return
     *  (`ok:`/`notfound:`/`ambiguous:`/`notready:`/`error:`). */
    fun SetCalendarEnabled(query: String, enabled: Boolean): String

    /** Open a ~2-minute pairing window and start monitoring for a connection. Returns
     *  `ok:` immediately; poll [PairStatus] for the outcome. */
    fun Pair(): String

    /** Return the current pairing status: `pending:`, `ok:<msg>`, `error:<msg>`, or
     *  `timeout:<msg>`. Returns `error:No pairing in progress` if [Pair] was never called. */
    fun PairStatus(): String

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

    /** List known watches, one per entry, tab-separated `name\tstate\tbattery` (state: connected /
     *  connecting / disconnected; battery is the 0–100 level for a connected watch, else empty).
     *  Empty list if none are known. */
    fun ListWatches(): List<String>

    /** The connected watch's battery level. Status-prefixed: `ok:<name>\t<level>` (0–100),
     *  `unknown:<name>` (connected but no reading yet), or `notready:<msg>` (no watch). */
    fun Battery(): String

    /** Request a fresh health/activity sync from the connected watch and re-project the export. Returns
     *  `ok:<msg>` once the request is fired (data streams back asynchronously), or `notready:<msg>`. */
    fun SyncHealth(): String

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
     *  `ok:<board>\t<current>\t<latest>\t<asset>\t<yes|no>\t<source>`,
     *  `noasset:<board>\t<current>\t<source>`, or `disabled:`/`notready:`/`error:`. Requires the
     *  matching source enabled — `firmware.github` or `firmware.cohorts` (opt-in egress). */
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
     *  enabled|disabled \t running|stopped`. */
    fun ExtList(): List<String>

    /** Install an extension from an archive (.tar.gz/.tgz/.tar/.zip) into `<configDir>/ext/<name>/`:
     *  extracts it, sideloads a bundled `.pbw` if present, enables it, and hotplug-starts it.
     *  Status-prefixed return. */
    fun ExtInstall(path: String): String

    /** Stop [name], remove it from `extensions.enabled`, and delete its files. Status-prefixed. */
    fun ExtUninstall(name: String): String

    /** Enable [name] (add to `extensions.enabled`) and hotplug-start it. Status-prefixed. */
    fun ExtEnable(name: String): String

    /** Disable [name] (remove from `extensions.enabled`) and stop it; files are kept. Status-prefixed. */
    fun ExtDisable(name: String): String

    /** Restart [name]'s process. Status-prefixed. */
    fun ExtRestart(name: String): String
}
