# Open questions

Decisions from the unattended settings-parity session (2026-08-27). **All eight were answered by the
author on 2026-08-27 and are now implemented** — this file is kept as the record of what was decided and
why, not as a to-do list.

| # | Question | Decision |
| --- | --- | --- |
| 1 | Dual controls for `music.enabled` / `health.sync` / `dnd.sync` | **Keep both** |
| 2 | Fold `firmware.notify` under `alerts.enabled`? | **No — keep it separate** |
| 3 | `extension.<name>.<key>` overriding the GUI | **Flip the precedence** — the config file now wins |
| 4 | `watch.<prefId>` reverting the GUI | **Sync back only already-pinned keys** |
| 5 | `classic.discover` default | **Confirmed on** |
| 6 | `i18ndc` runtime noise | **Fixed** — new shared `FormTextRow` component |
| 7 | `stoandl daemon` CLI name | **Keep** |
| 8 | `calendar.sync_interval` next-tick lag | **Changed** — the ticker now restarts on an interval change |

---

## 1. `music.enabled`, `health.sync` and `dnd.sync` each have two controls

Each appears **both** as a Sync-screen master toggle (`SetSyncEnabled`) and as a row on Settings →
Daemon configuration (`SetConfig`). Both write the same `stoandl.conf` key through the same store, so
they cannot disagree — it is duplicated *presentation*, not duplicated state.

**Decided: keep both.** Removing either is a UX judgement call, not a correctness fix: the Sync screen
also shows availability and last-sync, which a flat schema row cannot express, while the Daemon
configuration page is where you go to see *everything*. Dropping them from `GUI_CONFIG_FIELDS` would also
remove them from `stoandl daemon list/set`, which reads the same list. No code change.

## 2. `firmware.notify` is not under `alerts.enabled`

The new `alerts.*` family gates stoandl's own desktop alerts. The firmware-update alert is the obvious
fifth member, but it is **not** included, because it also drives a *watch* notification with a working
Update button — folding it under `alerts.enabled` would make one switch mean two different things
depending on the surface.

**Decided: keep it separate.** `firmware.notify` remains the single switch for that event, documented in
the KDoc, `conf.example`, `docs/settings-parity.md` and the Alerts screen's footer text. No code change.

## 3. `extension.<name>.<key>` in `stoandl.conf` silently overrides what the GUI writes

Found while mapping; **not introduced by this session.**

`ExtensionDef` merged extension settings as *manifest defaults < the extension's own `config` file <
`stoandl.conf`*. The GUI's `ExtSetConfig` writes the **config file**. So a stale
`extension.<name>.<key>` line in `stoandl.conf` silently won over whatever the user had just saved in the
GUI, with no way for the GUI to see or clear it.

**Decided: flip the precedence.** Settings now merge as *manifest defaults < `stoandl.conf` < the
extension's own `config` file*, so the layer the GUI and `stoandl ext` write wins. A leftover conf key
that the file also sets is logged once at resolve time (`… is now overridden by …/config — remove it from
stoandl.conf`) so it can be cleaned up rather than sitting there invisibly.

`cmd` deliberately keeps its OLD precedence (stoandl.conf first): an explicit `cmd` override is how you
rescue an extension whose own config is broken, so stoandl.conf has to stay authoritative for it.

⚠️ **This is a behaviour change for existing installs** that relied on stoandl.conf overriding the file.
The log line is how you find them.

## 4. `watch.<prefId>` in `stoandl.conf` silently reverts GUI changes on reconnect

Same shape, also pre-existing. `config.watchPrefs` is re-applied **authoritatively** on every fresh
connect, so a conf entry overwrote a `SetWatchPref` made from the GUI at the next reconnect — the GUI
change appeared to work, then quietly reverted.

An *adjacent* bug was fixed during the session regardless: adding the first `watch.*` key used to need a
restart, because the on-connect hook's registration was gated on the map being non-empty at startup. The
hook is now always registered and the map read inside it.

**Decided: sync back, but only already-pinned keys.** `SetWatchPref` now also rewrites `watch.<id>` in
`stoandl.conf` — **only when that key is already there**. So a pinned pref can no longer revert a GUI/CLI
change, while a pref you have *not* pinned is left alone, keeping `watch.*` an opt-in pin list rather than
turning it into a mirror of every pref you touch. "Config is authoritative on connect" is unchanged, so a
pinned pref still wins over a change made on the watch. Read+write happen under the shared conf lock, and
a failure to update the file never fails the pref write (the pref itself already applied).

## 5. `classic.discover` — I changed this twice; the final state is "on"

Worth recording because I got it wrong first. `defaults()` said `true` while `load()` used a bare
`parseBool`, which reads an **absent** key as `false` — so behaviour differed between "no config file"
(on) and "config file that omits the key" (off).

I first "fixed" it by flipping `defaults()` to false. That was the wrong direction: commit `cb89049`
("default classic.discover on"), `stoandl.conf.example` and `docs/configuration.md` all say **on**, so
the parse was the bug. The KDoc, which still said "Off by default", was corrected too.

**Decided: confirmed on.** Final state stands: `defaults()` is `true` and `load()` uses `?: true`,
matching `conf.example`, `docs/configuration.md` and commit `cb89049`.

---

## Smaller things

- **`FormTextFieldDelegate`'s `ReferenceError: i18ndc is not defined`** — **fixed.** All twelve usages
  across `HealthProfileSettingsPage`, `CalendarsSettingsPage` and `AppsPage` now use a new shared
  `FormTextRow.qml`, built on `AbstractFormDelegate` (the same reason `FormColorDelegate` is avoided).
  `GeneralSettingsPage`'s hand-rolled equivalent was folded into it too, so there is one component rather
  than two. It also gains a `description` line, which `FormTextFieldDelegate` has no property for. Its
  one API difference: the text is `value`, not `text`, because `AbstractFormDelegate` derives from
  `T.ItemDelegate`, which already owns `text`. Verified: 22 runtime errors → **0**.
- **`stoandl daemon` as the config CLI name** — **kept.** `config` was taken (it is the PKJS/Clay
  *watchapp* settings page), and `daemon` matches the GUI page's own name, "Daemon configuration".
  Renaming later is one `when` branch and one help row.
- **`calendar.sync_interval` now applies at once** — **changed.** The ticker is keyed on a `StateFlow` of
  the interval (`flatMapLatest`), so changing the value cancels the pending delay and starts counting the
  new one. The starvation risk I was worried about is handled by `StateFlow` conflating equal values: a
  calendar write that does *not* change the interval leaves the countdown running.
