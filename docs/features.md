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
- [x] **App configuration pages (Clay)** — `stoandl config [app]` serves the config page via a local proxy
- [x] **BLE pairing / bonding** — headless auto-confirm BlueZ agent (Numeric Comparison / MITM / SC)
- [x] **Automatic reconnect** — bonded reconnect after watch disconnect, daemon restart, or coming back into range. Reconnection is delegated to BlueZ's own background auto-connect: the watch is marked `Trusted` and a single standing `Device1.Connect()` intent is left in place, so BlueZ links it up the instant it's reachable — no polling, no Connect/Disconnect churn, no process-restart watchdog. (Reconnection needs the adapter's LE scanner free; another process running Bluetooth discovery blocks it — stoandl warns when it detects that.)

### Implemented — to be tested (multi-watch)

- [x] **Multiple concurrent Pebble watches** — `libPebble.watches` is a `List<PebbleDevice>`; scan, auto-connect, notifications, and calls all iterate the full list. Core architecture is multi-watch by design. _TBT — needs two Pebbles to verify. Known gaps before multi-watch is fully usable:_
  - _BlueZ GATT server multi-client: whether BlueZ allows two Pebbles as simultaneous GATT clients to the same application is untested._
  - _CLI commands (`launch`, `sideload`, `remove`, `settings`) target the single connected watch with no disambiguation flag (`--watch <name|address>`). With two watches the behaviour is unspecified._
  - _`stoandl pair` with a bonded-but-absent watch works — the absent watch is `KnownPebbleDevice`, not `ConnectedPebbleDevice`, so the guard doesn't fire, and the `alreadyBonded` snapshot prevents the bond-detector from misfiring on it._
  - _`stoandl pair` with a watch already **connected** is broken: the early-return guard exits immediately with "Watch already connected". Fixing it requires removing that guard and scoping the `connectedJob` to newly connected devices only._

### Implemented — to be tested

Written but not yet verified on hardware. Test plan: [TESTING.md](../TESTING.md). _TBT = to be tested._

- [x] **Weather** — fixed locations (and an optional GeoClue2 GPS "current location") from Open-Meteo (free, no account) pushed to the watch's Weather app; refreshes on an interval and on connect (`stoandl weather` to force). See [configuration.md](configuration.md). _TBT — [TESTING.md §4](../TESTING.md)._
- [x] **Watch settings** — set the "advanced" watch prefs the official app exposes (quick-launch buttons, ambient-light threshold, backlight, vibration, etc.) via `stoandl settings` / `set-setting` or `watch.<id>` config keys, applied authoritatively on connect. See [configuration.md](configuration.md). _TBT._
- [x] **Phone call notifications** — ModemManager (system bus) → `currentCall` → native Pebble call screen; watch Answer/Hangup drive `Accept()`/`Hangup()`. _TBT — [TESTING.md §5](../TESTING.md)._
- [x] **Missed-call sync** — unanswered incoming calls become timeline pins via libpebble3's `MissedCallSyncer` (backed by an in-memory `SystemCallLog` fed by the call monitor). _TBT._
- [x] **Caller-ID resolution** — DE-agnostic vCard lookup (`contacts.vcard_paths`, suffix-matched), with the dialer's own notification title as fallback. See [configuration.md](configuration.md). _TBT._
- [x] **Notification filtering** — `notification.blocklist` drops chosen apps; `call.dialer_apps` suppresses the dialer's redundant call notification. See [configuration.md](configuration.md). _TBT._

### Roadmap / not yet implemented

- [ ] **Notification actions / reply** — ❌ *not viable as a generic feature* ([why](#why-generic-notification-reply-isnt-viable)). Only `Dismiss` works (it rides on `CloseNotification()`, the one method a third party may call on a notification it didn't post); other actions return "Not supported". Per-channel reply (Matrix, SMS) is the viable path — see *Send text / quick reply* below.
- [ ] **Time / timezone sync** — handled by libpebble3 but not actively managed by stoandl
- [ ] **Calendar sync / timeline pins** — `WebServices` is a no-op; no calendar source wired
- [ ] **Weather timeline pins** — sunrise/sunset forecast pins (the Weather *app* data is synced; the timeline pins are not yet)
- [ ] **Music / now-playing control** — could bridge MPRIS over D-Bus → `MusicService`
- [ ] **Find my phone / find my watch**
- [ ] **Account-backed app store** — Rebble appstore browse/install and cloud locker sync (`fetchLocker()`) still stubbed; needs an account/token. (Local locker management — list/launch/remove/sideload/backup — is already done; see above.)
- [ ] **Firmware updates** — `checkForFirmwareUpdate()` stubbed
- [ ] **Health / activity sync** (steps, sleep, HR) — likely out of scope (headless, no dashboard)
- [ ] **Voice / dictation** — transcription provider explicitly stubbed (`Failed`)
- [ ] **Send text / quick reply** — reply to messages from the watch via per-channel backends that bypass the notification bus (see [note](#why-generic-notification-reply-isnt-viable)): **Matrix** via `matrix-commander`/matrix-nio (`--listen` → watch, reply → same room; E2EE handled), and **SMS** via the ModemManager Messaging interface (reuses the telephony integration). No generic per-app reply.
- [ ] **Data logging** (PebbleKit datalog API)
- [ ] **Bluetooth Classic transport** — intentionally out of scope; see [bt-classic-scope.md](../bt-classic-scope.md)

### Why generic notification reply isn't viable

_Investigated 2026-06; parked. Recorded here so it isn't re-litigated._

Replying to a desktop notification means delivering text **back to the originating app**. On the
freedesktop bus that happens via the daemon→app `ActionInvoked` / `NotificationReplied` *signal*,
which clients only accept from the **owner** of `org.freedesktop.Notifications`. stoandl is a passive
`BecomeMonitor` copy, not the owner, so any reply signal it emits is dropped. (Dismiss is the lone
exception: `CloseNotification()` is a real *method* that any client may call on any notification.)

Becoming the owner — a transparent MITM proxy sitting in front of the real daemon — was evaluated and
rejected:

- **GNOME and KDE/Plasma both refuse name takeover.** Each shell owns `org.freedesktop.Notifications`
  *without* `DBUS_NAME_FLAG_ALLOW_REPLACEMENT`, acquired at login before any user service.
  `RequestName(org.freedesktop.Notifications, REPLACE_EXISTING)` returns `3` (exists) on both. The only
  sanctioned way to run a different server is to *manually disable* the shell's built-in notifications —
  which on Plasma Mobile means losing the phone's own notification UI. Unacceptable as a default.
- **xdg-desktop-portal doesn't help.** Its Notification portal is send-side only (app → portal → the
  *one* configured backend → reply routed back to the app); it is not an interception API, and replacing
  the backend is itself a desktop-config change that only catches portal/flatpak apps.
- **Prior art (KDE Connect) can only reply because it is integrated with Plasma's notification-server
  internals** — not a portable mechanism a third-party daemon can reuse.

So a proxy works *only* where no shell server is entrenched (bare wlroots/sxmo compositors, or a desktop
whose server is manually disabled), making it an opt-in niche rather than a feature. Parked.

**The viable path is per-channel reply that bypasses the notification bus** and talks to the messaging
backend directly, where the reply target (room / phone number) is unambiguous — tracked under *Send
text / quick reply*. The watch side is shared by every channel: a `Response` action carrying the canned
replies, with the chosen text arriving back as the action's `Title` attribute. Built once, reused per
channel.
