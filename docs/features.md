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
- [x] **Watchapp / watchface sideload** — `stoandl sideload <app.pbw>`
- [x] **PKJS (PebbleKit JS)** — companion scripts run in GraalJS (XHR, AppMessage, webhooks)
- [x] **App configuration pages (Clay)** — `stoandl settings [app]` serves the config page via a local proxy
- [x] **BLE pairing / bonding** — headless auto-confirm BlueZ agent (Numeric Comparison / MITM / SC)
- [x] **Automatic reconnect** — bonded reconnect after watch disconnect or daemon restart

### Implemented but UNTESTED

⚠️ The phone-call feature set is written but **not yet verified against real telephony**. Only the
watch-side path is confirmed — `stoandl fakecall ring|end` correctly drives the native call screen
and Answer/Decline round-trip on a Pebble Time 2. Everything that depends on ModemManager or the
config file below is untested on hardware and may need fixing.

- [x] **Phone call notifications** — ModemManager (system bus) → `currentCall` → native Pebble call screen; watch Answer/Hangup drive `Accept()`/`Hangup()`. _Untested against a real modem._
- [x] **Missed-call sync** — unanswered incoming calls become timeline pins via libpebble3's `MissedCallSyncer` (backed by an in-memory `SystemCallLog` fed by the call monitor). _Untested._
- [x] **Caller-ID resolution** — DE-agnostic vCard lookup (`contacts.vcard_paths`, suffix-matched), with the dialer's own notification title as fallback. See [configuration.md](configuration.md). _Untested._
- [x] **Notification filtering** — `notification.blocklist` drops chosen apps; `call.dialer_apps` suppresses the dialer's redundant call notification. See [configuration.md](configuration.md). _Untested._

### Roadmap / not yet implemented

- [ ] **Notification actions / reply** — only dismiss is handled; reply & canned responses return "Not supported"
- [ ] **Time / timezone sync** — handled by libpebble3 but not actively managed by stoandl
- [ ] **Calendar sync / timeline pins** — `WebServices` is a no-op; no calendar source wired
- [ ] **Weather** — no provider wired
- [ ] **Music / now-playing control** — could bridge MPRIS over D-Bus → `MusicService`
- [ ] **Find my phone / find my watch**
- [ ] **App store / locker / app management** — `fetchLocker()` stubbed
- [ ] **Firmware updates** — `checkForFirmwareUpdate()` stubbed
- [ ] **Health / activity sync** (steps, sleep, HR) — likely out of scope (headless, no dashboard)
- [ ] **Voice / dictation** — transcription provider explicitly stubbed (`Failed`)
- [ ] **Send text / quick SMS** — no messaging integration
- [ ] **Data logging** (PebbleKit datalog API)
- [ ] **Bluetooth Classic transport** — intentionally out of scope; see [bt-classic-scope.md](../bt-classic-scope.md)
