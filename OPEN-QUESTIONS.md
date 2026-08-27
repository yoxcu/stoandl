# Open questions

Decisions from the unattended settings-parity session (2026-08-27) that are genuinely yours to make. I
made a conservative choice for each and carried on; none of these blocks anything.

---

## 1. `music.enabled`, `health.sync` and `dnd.sync` each have two controls

Each appears **both** as a Sync-screen master toggle (`SetSyncEnabled`) and as a row on Settings →
Daemon configuration (`SetConfig`). Both write the same `stoandl.conf` key through the same store, so
they cannot disagree — it is duplicated *presentation*, not duplicated state.

**What I did:** left both. Removing either is a UX judgement call, not a correctness fix: the Sync screen
also shows availability and last-sync, which a flat schema row cannot express, while the Daemon
configuration page is where you go to see *everything*.

**If you want one:** dropping them from `GUI_CONFIG_FIELDS` is a three-line change and both GUIs follow
automatically (the schema drives them). The cost is that `stoandl daemon list` would then stop showing
those keys, so I'd rather keep them.

## 2. `firmware.notify` is not under `alerts.enabled`

The new `alerts.*` family gates stoandl's own desktop alerts. The firmware-update alert is the obvious
fifth member, but it is **not** included, because it also drives a *watch* notification with a working
Update button — folding it under `alerts.enabled` would make one switch mean two different things
depending on the surface.

**What I did:** kept `firmware.notify` as the single switch for that event, and said so in the KDoc, the
conf.example, `docs/settings-parity.md` and the Alerts screen's footer text.

**Alternative** (if you'd rather have one master for "stoandl talking to me at all"): make
`alerts.enabled` gate the desktop half of the firmware alert too, leaving `firmware.notify` owning the
watch half. That is defensible but strictly more confusing to explain, which is why I didn't.

## 3. `extension.<name>.<key>` in `stoandl.conf` silently overrides what the GUI writes

Found while mapping, **not introduced by this session, and not fixed by it.**

`ExtensionDef` merges extension settings as *manifest defaults < the extension's own `config` file <
`stoandl.conf`*. The GUI's `ExtSetConfig` writes the **config file**. So a stale
`extension.<name>.<key>` line in `stoandl.conf` silently wins over whatever the user just saved in the
GUI, with no way for the GUI to see or clear it.

**What I did:** documented it in `docs/configuration.md` (the `extension.<name>.<key>` row now warns) and
in `docs/settings-parity.md` §3. I did not change the precedence — that is a behaviour change for
existing installs and needs your call.

**Options:** (a) leave it, documented; (b) have `ExtGetConfig` report the effective value plus an
"overridden in stoandl.conf" flag so the GUI can show it; (c) flip the precedence so the file wins.
(b) is the honest one.

## 4. `watch.<prefId>` in `stoandl.conf` silently reverts GUI changes on reconnect

Same shape, same session-found-not-fixed status. `config.watchPrefs` is re-applied **authoritatively** on
every fresh connect, so a conf entry overwrites a `SetWatchPref` made from the GUI at the next
reconnect — the GUI change appears to work, then quietly reverts.

**What I did:** fixed the *adjacent* bug (adding the first `watch.*` key used to need a restart, because
the on-connect hook's registration was gated on the map being non-empty at startup — now the hook is
always registered and the map read inside it). Documented the precedence in `docs/settings-parity.md` §3.

**Not fixed:** the precedence itself. "Config is authoritative" is a deliberate design decision recorded
in the code comment; changing it is your call. If you want the GUI to win, the cleanest fix is for
`SetWatchPref` to also upsert the matching `watch.<prefId>` conf key, so the two stores stop disagreeing.

## 5. `classic.discover` — I changed this twice; the final state is "on"

Worth flagging because I got it wrong first. `defaults()` said `true` while `load()` used a bare
`parseBool`, which reads an **absent** key as `false` — so behaviour differed between "no config file"
(on) and "config file that omits the key" (off).

I first "fixed" it by flipping `defaults()` to false. That was the wrong direction: commit `cb89049`
("default classic.discover on"), `stoandl.conf.example` and `docs/configuration.md` all say **on**, so
the parse was the bug. Final state: `defaults()` stays `true` and `load()` now uses
`?: true`, matching the docs and the commit's intent. The KDoc, which still said "Off by default", was
corrected too.

**Confirm this is what you want** — it is the only behaviour change in this session that alters a default
for existing users with a config file that omits the key.

---

## Smaller things, noted without action

- **`FormTextFieldDelegate` throws `ReferenceError: i18ndc is not defined`** at runtime in the Kirigami
  front-end (its character-counter label calls `i18ndc`, and this app deliberately links no KF6 C++).
  Pre-existing, cosmetic (the counter is invisible), and affects `HealthProfileSettingsPage`, `AppsPage`
  and `CalendarsSettingsPage`. The new headless smoke harness surfaces it as ~22 lines of stderr noise.
  My new settings rows sidestep it by building on `AbstractFormDelegate` instead — the same reason
  `FormColorDelegate` is already avoided. Worth doing the same to the other three if the noise bothers
  you.
- **`stoandl daemon` is the name I picked** for the new config CLI. `config` was taken (it is the
  PKJS/Clay *watchapp* settings page), and `daemon` matches the GUI page's own name, "Daemon
  configuration". Rename freely — it is one `when` branch and one help row.
- **`calendar.sync_interval` applies from the next tick**, so up to one old interval of lag. Deliberate:
  restarting the ticker on every write would reset the countdown and could starve the sync under
  repeated edits. Say if you'd rather it restart immediately.
