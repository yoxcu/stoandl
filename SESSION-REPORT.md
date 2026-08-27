# Session report — settings parity, notification settings, firmware re-check

Unattended session, 2026-08-27. Branch `feature/settings-parity` (and the same branch in the `gui`
submodule). Nothing pushed; no history rewritten.

**Deliverables:** [docs/settings-parity.md](docs/settings-parity.md) ·
[FIRMWARE-GAPS.md](FIRMWARE-GAPS.md) · this file · [OPEN-QUESTIONS.md](OPEN-QUESTIONS.md)

---

## The short version

The GUI Settings page was already schema-driven — the daemon advertises a list of config fields and both
front-ends render whatever it says. The problem was that the **schema itself** could only express two
widget kinds and a boolean reader, so **17 of the daemon's config keys had no way to be exposed at all**,
and both front-ends were built against a 5-column contract the daemon had outgrown.

So the fix was to grow the schema rather than special-case keys in two GUIs. Along the way, tracing
apply-semantics for the matrix turned up **six keys that persisted, read back changed, and did nothing
until a restart** — the GUI showed a live toggle that was quietly inert. Those are the most consequential
fixes here.

Phase 3 found real firmware movement (v4.30.0 → v4.36.2) but nothing implementable without a libpebble3
bump or hardware, so all of it is deferred with sketches rather than half-built.

| | Before | After |
| --- | --- | --- |
| Config keys on the Settings page | 23 | **45** (all 52 keys reachable; 7 via richer dedicated surfaces) |
| Widget kinds | toggle, combo | + **text, int, list** |
| Schema columns | 5 | **11** (group, apply, min, max, unit, placeholder appended) |
| Exposed keys that silently needed a restart | 8, unmarked | **0 unmarked** — 6 were fixed to be live, 2 genuinely need one and say so |
| Config keys documented in `configuration.md` | 42 of 52 | **52 of 52** |
| CLI access to daemon config | none | `stoandl daemon list/get/set` |
| Daemon unit tests | none | 19 |

---

## What changed

### Phase 1 — GUI settings parity

**Daemon (`config/ConfigSchema.kt`).** `ConfigField` gained `text`, `int` and `list` kinds, a unified
string reader (the old one could only return a `Boolean`, which is why no non-boolean key could be
exposed), per-field `min`/`max`/`unit`/`placeholder`, an optional validator, and a `group`. Parsing moved
into `ConfigField.parse()` and now returns *why* a value was rejected instead of a bare null.

Values that `stoandl.conf` cannot round-trip are rejected at the write path: `#` starts a comment and a
newline splits the line, so either would silently truncate or corrupt the setting **on the next read** —
the one place that failure is catchable is before it is written. `weather.locations` is validated with
`StoandlConfig.parseWeatherLocations`, i.e. the very parser that reads it back, so the GUI cannot persist
entries the daemon would drop at load time; `firmware.github_repo` and `firmware.cohorts_url` check their
shape.

`GetConfigSchema` grew 5 → 11 columns. The first five are untouched and both front-ends index
positionally with a per-column fallback, so old-client/new-daemon and new-client/old-daemon both work.

**Both front-ends.** Parse all eleven columns; group rows into the sections the daemon declares (45 keys
in one flat list was not usable); render `int` as a bounded spin control with its unit and a 500 ms
debounced commit (matching the watch-prefs number row — the spin fires per step and each apply rebuilds
the row); render `text`/`list` with the schema's placeholder; and **say on the row when a key needs a
restart**. Nothing surfaced that before: the column was discarded *and* the daemon's
“(restart stoandl to apply)” tail was invisible because both GUIs only toast on failure.

Kirigami builds int/text/list from `AbstractFormDelegate` rather than `FormSpinBoxDelegate` /
`FormTextFieldDelegate`: neither has a `description` property, and the latter calls `i18ndc()`, which
throws at runtime here (this app deliberately links no KF6 C++ — the same reason `FormColorDelegate` is
already avoided). GTK uses `AdwSpinRow`, and an `AdwActionRow` with an entry suffix for text/list, since
`AdwEntryRow` has no subtitle either.

**Apply-semantics fixes** (the reverse-direction work — GUI options the daemon silently ignored):

| Key(s) | Was | Now |
| --- | --- | --- |
| `firmware.*`, `language.download` | `FirmwareControl`/`LanguageControl` took a config **snapshot** at startup | live getter; firmware sources rebuilt per check |
| `notification.default_mute` | baked into `WatchNotifier` as a `MuteState` | read per newly-tracked app |
| `call.dialer_apps` | snapshotted into the notification bridge | read per notification |
| `contacts.vcard_paths` | snapshotted into `ContactResolver` | read live |
| `calendar.sync_interval` | ticker captured it once | re-sampled each tick |
| `developer.autostart` | gated the on-connect hook's **registration**, so "on" needed a restart *and* "off" kept auto-starting | hook always registered, switch checked inside |
| `weather.gps_desktop_id` | half-live: weather re-read it, but `LiveGeolocation` cached its provider forever, so the *watchapp* path kept the old GeoClue identity | cache keyed on the id |
| `watch.<prefId>` | adding the **first** one never took effect without a restart | hook always registered, map read inside |

Three keys genuinely cannot be applied live (`notification.sync_to_watch` decides a DI binding,
`classic.discover` a transport, `datalog.enabled` a subscriber — all startup wiring). They are marked
`restart` in the schema and surfaced in both GUIs and the CLI.

Also fixed: `classic.discover` disagreed with itself — `defaults()` said `true` while `load()`'s bare
`parseBool` reads an absent key as `false`, so "no config file" and "config file without the key" behaved
differently. **I first fixed this in the wrong direction**; the commit history, `conf.example` and the
docs all say on, so the parse was the bug, not the default. See OPEN-QUESTIONS.md §5.

### Phase 2 — Notification settings

Enumerated every event the daemon can originate (the table is in `docs/settings-parity.md` §4). Five
daemon-generated desktop alerts — pairing lost, link churn, unpaired-on-watch, Bluetooth scan blocking
reconnects, extension needs setup — were **completely ungated**, with no way to silence them.

Added `alerts.enabled` (master) + `alerts.pairing` / `alerts.bluetooth` / `alerts.extensions`, all live,
all default-on. Muting silences the **popup only** — the condition is still logged at WARN and still
lands in `stoandl support`, so a muted alert never hides a diagnosis.

`firmware.notify` is deliberately **not** folded in: it also drives a *watch* notification with an Update
button, so one switch would mean two different things. One switch per event, not two.

On thresholds: none of these events is threshold-based in a user-meaningful way. The two internal
thresholds are debounce constants, not policy. The one event where a threshold *would* mean something —
a low-battery alert — does not exist yet; noted in FIRMWARE-GAPS.md rather than invented here.

**Send test notification** existed but only under Settings → Debug, which is where you look last when
notifications are not arriving. Both front-ends now have it on the **Alerts** screen, next to the
controls that could be suppressing delivery. It deliberately goes through the normal send path, so
per-app mute, the filters and the master switch all apply — the test answers "is anything on this screen
stopping my notifications?" rather than bypassing them to look successful.

### Phase 3 — Firmware re-check

Newest firmware is **v4.36.2** (2026-08-26); the prior analysis was based on **v4.30.0**. Network egress
worked, so nothing was skipped for connectivity. Nine tags in the range have empty GitHub release bodies
and are absent from the Notion changelog; only v4.31.2, v4.33.2 and v4.36.0 carry notes.

**Nothing was implemented, deliberately.** Three actionable items are all blocked on the same thing — a
libpebble3 submodule bump, which this project gates behind hardware verification:

- **Notification images** (4.36.0, "Android only", which stoandl is) — two-part gap, both parts missing:
  libpebble3's `TimelineAttribute` has no image attribute (33 attributes, max id `0x33`; the `*Icon`
  ones carry an icon *code*, not pixels), and stoandl's D-Bus monitor takes the `hints` map and discards
  it, `image-data` included.
- **Album art** (4.36.0) — `MusicTrack` is text-only; no artwork field anywhere in the fork. The host
  half exists (MPRIS publishes `mpris:artUrl`).
- **New watch prefs** (4.33/4.36) — the settings-parity crux and best value-for-effort: stoandl drives
  prefs generically, so a new pref costs *zero* stoandl code — but libpebble3's pref list is a **hardcoded
  enum** (44 prefs), not read off the watch, so a new firmware pref cannot appear at all without a bump.

One **risk to an existing feature**: 4.36.0 replaces the watch's weather app, and the weather BlobDB
record is versioned (`version = 3u`). If the new app wants a v4 record, stoandl's weather may render
partially on 4.36. Not settleable from the code (libpebble3 predates 4.36), so it is flagged to check
before updating a daily driver rather than guessed at.

Already safe: the battery/heartbeat decode is guarded on the exact `(size, version)` pair, so a 4.36
layout change degrades to "nothing decoded, here's why" rather than to wrong numbers.

---

## How it was verified

| Surface | Result |
| --- | --- |
| Daemon `gradle test` | **19/19 pass** (new suite — see below) |
| Daemon `compileKotlin` | clean; no new warnings vs. baseline |
| GTK `cargo test` | **22/22 pass** (3 new schema assertions) |
| GTK `cargo build` | clean, 0 warnings |
| Kirigami `cmake --build` | clean (qmlcachegen compiles every QML file) |
| Kirigami headless, against the mock | `general loaded 45 keys in 14 groups`; all 5 tabs + 7 settings sub-pages instantiated; no QML errors |
| GTK headless (weston), against the mock | `general loaded 45 keys in 14 groups`; all 5 pages; no GTK/Adwaita warnings |
| Both front-ends, **no daemon on the bus** | cycle every page and quit cleanly; placeholder / `0 keys in 0 groups`; watch-dependent tools disabled; no crash |

**The mock was rewritten to match.** It shipped six invented config keys; it now mirrors the daemon's
real 45, emits all eleven columns, and rejects the same values the daemon does — so the GUI's rejection
path is exercised rather than assumed.

**A headless smoke harness was added to the Kirigami front-end** (`STOANDL_SMOKE_MS`, the analogue of the
GTK one), because the settings sub-pages are *pushed*, so they are never instantiated by simply starting
the app — the previous offscreen run proved nothing about them. It paid for itself immediately: it caught
this page calling page methods from delegate property bindings, which resolve to a `QQmlComponent` and
throw — exactly the trap `gui/CLAUDE.md` documents. Per-row display data is now precomputed page-side.

**New Kotlin test suite** (the project's first — kotlin-test only, test-scoped, nothing ships). The
daemon proper needs a watch and a session bus and stays untested; the settings surface does not, and it
is where a mistake is silent: a bad token corrupts the config on the *next* read, and a key that
`StoandlConfig.load()` does not parse is a control the GUI shows, writes, reads back as the old value,
and the daemon ignores forever. The suite covers per-kind parsing, the wire format, and the invariants
that keep the matrix honest — notably a **round-trip test over every exposed key** (write a changed value
through `applyGuiConfig`, re-load the file, assert the schema reads back what was written). It found
nothing today; it is the check that fails the build the next time a key is added to the schema and
forgotten in `load()`.

### Not verified

Everything requiring a watch. Per the project's convention this is recorded as **to-be-tested** in
`TESTING.md` §5.31 (15 rows), not claimed as done. The rows that matter most are the six live-apply
fixes — `firmware.*` applying without a restart is the specific regression to watch — and the `alerts.*`
gating. Also unverified: the old-client/new-daemon compatibility claim, which wants one run of an older
GUI build against this daemon.

---

## Commits

Daemon (`/workspace`), on `feature/settings-parity`:

```
5039f1c chore(gui): bump submodule to ec04b8d
6d6919d docs: firmware re-check v4.30 -> v4.36.2, gap analysis, open questions
4cdd93d feat(cli+docs): stoandl daemon config CLI, settings-parity matrix, doc sync
48127f4 test(config): unit-test the settings schema and its agreement with the parser
28469be feat(config): full daemon-config schema — new field kinds, apply semantics, alerts
```

GUI submodule, on `feature/settings-parity`:

```
ec04b8d fix(smoke): don't fire the send-test when the daemon is down
84dd5d1 docs(gui): the schema contract for GeneralSettingsPage, and the smoke harness
435ebda feat(notifications): send-test action on the Alerts screen, and point at stoandl's own alerts
e62b1dd feat(settings): render the daemon's full config schema in both front-ends
```

**No new runtime dependencies.** The one addition is `kotlin("test")`, test-scoped.

### A note on the working tree

Both repos had **uncommitted work in progress** when this session started (the analytics-heartbeat
metrics feature: `HeartbeatLayout.kt`, `HeartbeatStore.kt`, the `HeartbeatInfo`/`HeartbeatMetrics` D-Bus
methods, the GUI heartbeat pages, `tools/hb_relayout_probe.py`). Six files were touched by both that work
and mine.

I did **not** commit it — that is yours to land. My commits were staged hunk-by-hunk so they contain only
my changes, and the WIP is still in the working tree, byte-identical to how I found it (verified against
the session-start `git diff --stat`: 224 insertions across the same six files).

---

## Follow-up: the open questions, answered

The author answered all eight on 2026-08-27; the decisions and reasoning are now recorded in
[OPEN-QUESTIONS.md](OPEN-QUESTIONS.md), and four of them changed code:

- **Extension config precedence flipped** — the extension's own `config` file (what the GUI and
  `stoandl ext` write) now wins over a stale `extension.<name>.<key>` in `stoandl.conf`, which used to
  silently beat it. A shadowed key is logged once at resolve time so it can be found. `cmd` keeps the old
  order, since it is the escape hatch for an extension whose own config is broken. *Behaviour change for
  installs that relied on the old order.*
- **Pinned watch prefs no longer revert the GUI** — `SetWatchPref` also rewrites `watch.<id>` in
  `stoandl.conf`, but only when that key is already pinned, so `watch.*` stays an opt-in pin list.
- **`calendar.sync_interval` applies at once** — the ticker is keyed on a `StateFlow` of the interval, so
  a change cancels the pending delay. `StateFlow` conflating equal values is what keeps repeated edits to
  other calendar keys from starving the sync.
- **The `i18ndc` noise is gone** — a shared `FormTextRow.qml` replaces all twelve `FormTextFieldDelegate`
  usages (and absorbs `GeneralSettingsPage`'s hand-rolled equivalent, so there is one component, not two).
  22 runtime errors → 0.

Kept as they were: the dual Sync-screen/config-page controls, `firmware.notify` outside `alerts.enabled`,
`classic.discover` on, and the `stoandl daemon` CLI name.

Two more commits on each branch (`8004622`, `9a00bac`; `182af14`). TESTING.md §5.31 gained three rows for
the behaviour changes. Re-verified after: daemon 19/19, GTK 22/22, both front-ends still report
`45 keys in 14 groups` headless.

## What I'd do next

1. **Bump `libs/libpebble3`.** It is the single blocker on three of the five firmware items, and for the
   new watch prefs it *is* the entire implementation — stoandl needs no code, the prefs just appear.
2. **Run TESTING §5.31 on hardware**, especially the six live-apply rows.
3. **Check weather on 4.36** before updating a daily-driver watch.
4. Note the extension-config precedence flip when upgrading an install that pinned
   `extension.<name>.<key>` in `stoandl.conf` — the daemon logs each shadowed key at startup.
