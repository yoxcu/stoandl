# Features & Roadmap

What stoandl supports today and what it doesn't. Doubles as a roadmap — the checkboxes are the
TODO list.

stoandl is a **headless, BLE-only daemon for Linux** — that's the whole point. Every other Pebble
companion (the official Core Devices app, microPebble, Gadgetbridge) is an Android/iOS GUI app
tied to a phone OS and usually a cloud account. stoandl runs as a systemd user service on a Linux
phone (postmarketOS) with no UI and no sign-in. Many gaps below are deliberate consequences of
that (no health dashboard, no account-backed app store); others are genuine TODOs.

## stoandl status

### Working today

- [x] **Notification sync** (desktop → watch) — D-Bus `org.freedesktop.Notifications` → Pebble timeline
- [x] **Notification dismiss** — `Dismiss`/`AncsDismiss` actions mark read on watch + `CloseNotification()` on D-Bus
- [x] **App / watchface management** — `stoandl apps` lists the locker; `launch`, `remove`; install with `sideload <app.pbw>` (alias `add`)
- [x] **Backup & restore** — `stoandl backup` / `restore` of the locker DB, `.pbw` cache and PKJS/Clay settings (`~/.config/stoandl/`)
- [x] **PKJS (PebbleKit JS)** — companion scripts run in GraalJS (XHR, AppMessage, webhooks)
- [x] **App configuration pages (Clay)** — `stoandl settings [app]` serves the config page via a local proxy
- [x] **BLE pairing / bonding** — headless auto-confirm BlueZ agent (Numeric Comparison / MITM / SC)
- [x] **Automatic reconnect** — bonded reconnect after watch disconnect, daemon restart, or coming back into range; a watchdog self-restarts (via systemd) if the native BLE stack wedges

### Implemented — to be tested

Written but not yet verified on hardware. Test plan: [TESTING.md](../TESTING.md). _TBT = to be tested._

- [x] **Weather** — fixed locations (and an optional GeoClue2 GPS "current location") from Open-Meteo (free, no account) pushed to the watch's Weather app; refreshes on an interval and on connect (`stoandl weather` to force). See [configuration.md](configuration.md). _TBT — [TESTING.md §4](../TESTING.md)._
- [x] **Phone call notifications** — ModemManager (system bus) → `currentCall` → native Pebble call screen; watch Answer/Hangup drive `Accept()`/`Hangup()`. _TBT — [TESTING.md §5](../TESTING.md)._
- [x] **Missed-call sync** — unanswered incoming calls become timeline pins via libpebble3's `MissedCallSyncer` (backed by an in-memory `SystemCallLog` fed by the call monitor). _TBT._
- [x] **Caller-ID resolution** — DE-agnostic vCard lookup (`contacts.vcard_paths`, suffix-matched), with the dialer's own notification title as fallback. See [configuration.md](configuration.md). _TBT._
- [x] **Notification filtering** — `notification.blocklist` drops chosen apps; `call.dialer_apps` suppresses the dialer's redundant call notification. See [configuration.md](configuration.md). _TBT._

### Roadmap / not yet implemented

- [ ] **Notification actions / reply** — only dismiss is handled; reply & canned responses return "Not supported"
- [ ] **Time / timezone sync** — handled by libpebble3 but not actively managed by stoandl
- [ ] **Calendar sync / timeline pins** — `WebServices` is a no-op; no calendar source wired
- [ ] **Weather timeline pins** — sunrise/sunset forecast pins (the Weather *app* data is synced; the timeline pins are not yet)
- [ ] **Music / now-playing control** — could bridge MPRIS over D-Bus → `MusicService`
- [ ] **Find my phone / find my watch**
- [ ] **Account-backed app store** — Rebble appstore browse/install and cloud locker sync (`fetchLocker()`) still stubbed; needs an account/token. (Local locker management — list/launch/remove/sideload/backup — is already done; see above.)
- [ ] **Firmware updates** — `checkForFirmwareUpdate()` stubbed
- [ ] **Health / activity sync** (steps, sleep, HR) — likely out of scope (headless, no dashboard)
- [ ] **Voice / dictation** — transcription provider explicitly stubbed (`Failed`)
- [ ] **Send text / quick SMS** — no messaging integration
- [ ] **Data logging** (PebbleKit datalog API)
- [ ] **Bluetooth Classic transport** — intentionally out of scope; see [bt-classic-scope.md](../bt-classic-scope.md)
