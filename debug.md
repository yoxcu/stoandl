# Pebble BLE Connection Debug — Diagnosis & State

_Last updated 2026-06-04 (session "stoandl"). Supersedes the earlier reAddServices/CCCD notes._

## Symptom

Connecting a **Pebble Time Steel** (identity `B0:B4:48:B6:1E:81`, firmware **4.4.3-rbl**) over
BLE is intermittent: ~1 in 6 fresh pairings reaches a working PPoG session and forwards
notifications; the rest connect, bond, encrypt — then drop after ~20–40 s. Every bonded
reconnect (e.g. after airplane-mode toggle) fails.

## How it was diagnosed

Correlated host captures: `sudo btmon -w X.snoop` + `./gradlew run | tee X.log`, decoded in the
sandbox with `btmon -r`. Captures kept in repo root: `good.snoop/good.log` (the rare success),
`bad.snoop/bad.log` and `fix.snoop/fix.log` (typical failures).

## What actually happens (evidence)

1. **The drop is ours, not the watch's and not RF.** btmon shows the link bonds + encrypts fine
   (supervision timeout healthy at 6 s), then libpebble's `Negotiator` times out after 20 s
   (`negotiation timed out` → `negotiation failed: disconnecting`) and **the phone** sends the
   HCI Disconnect (MGMT "terminated by local host"). Not a supervision timeout, not 0x13 from the
   watch.

2. **PPoG never starts because the watch never engages our GATT server.** In a failing run the
   watch does **zero** service discovery of the phone and **zero** CCCD subscribe / RESET_REQUEST
   write. Decisive counts (watch→phone): good run = 3 `Read By Group Type` + Find Information +
   2 Write (CCCD + RESET); bad run = **0** of each (only reads Device Name). Phone (`BluezGattServer`,
   service `10000000-328e…`, char `10000001`) exposes everything correctly; the watch just never asks.

3. **Root cause is upstream of PPoG/GATT: the watch never accepts the phone as a bonded gateway.**
   The `ConnectivityStatus` the *watch* reports is, on every failing connection:
   `paired = false  hasBondedGateway = false` — even after `Pair() completed` and `encrypted = true`.
   In the one good run's reconnect it was `hasBondedGateway = true`. Until the watch considers
   itself gateway-bonded it refuses to discover/engage PPoG. The GATT-cache symptom is downstream
   of this.

## Why this watch is the wrong target for a BLE-only daemon

- The **Pebble Time Steel is BASALT**, a *classic* (dual-mode) Pebble. The official/Rebble app talks
  to it over **Bluetooth Classic (BR/EDR)**; BLE is its *secondary* transport. (Two BT entries
  exist: `Pebble XXXX` = classic, `Pebble-LE XXXX` = BLE.)
- This daemon is **BLE-only** (`supportsBtClassic = false` in `LibPebbleModule.jvm.kt`,
  `reversedPPoG = false`). So we're forcing the non-primary transport on this watch.
- In libpebble, gateway-pairing is gated by `WatchType.needsPairingTrigger()`:
  `BASALT = false`, `EMERY = true`, `DIORITE = true`. So for the Time Steel the **pairing-trigger
  write is skipped entirely** (no `makePairingTriggerValue`, no write to char `00000002`) — which
  matches the observed `hasBondedGateway = false`. BLE-native watches (Pebble 2 = DIORITE,
  **Time 2 = EMERY**, Time Round = CHALK… note CHALK=false) get the trigger written.

- Corroboration: Gadgetbridge requires pairing the **non-LE (classic) entry first** for dual-mode
  Pebbles (issue #3597), and Time Steel BLE pairing is a known pain point (#3596) — classic is the
  primary/expected transport for this watch.

### Strong hypothesis / next test
A **Pebble Time 2** (EMERY, firmware 4.9.178) is BLE-native (BLE is its *only* transport) and
`needsPairingTrigger(EMERY) = true`, so the gateway-pairing write should fire and the bond should
take. **Test the Time 2** — it is very likely to work where the Time Steel can't with this daemon.

## Open question to confirm with logging
`needsPairingTrigger()` reads `scopeProps.color.platform`, but **no watch platform is detected in
the failing logs** (connection dies before negotiation). Need to confirm what platform the daemon
believes it has *before* pairing (from scan record? default?). If misdetected, even the Time 2
could skip the trigger. The next capture (root=DEBUG, see below) will show
`Requesting pairing: needsPairingTrigger = …` and `makePairingTriggerValue …`.

## Time 2 (EMERY, fw 4.9.178) result — different failure: wrong BT transport

`time2.snoop/log` (2026-06-05): the Time 2 **never connects over LE**. Sequence:
`Add Device (BR/EDR ED:86:0A:D4:B3:49)` → `Create Connection (0x01|0x0005)` [CLASSIC] →
`Connect Complete: Page Timeout (0x04)` → `Connect Failed`; then LE *scanning* but **0 LE Create
Connections** → Kable/btleplug 60 s `ConnectTimeout`. Zero ATT/SMP — never reached GATT, no pairing
popup. So `reversedPPoG` is irrelevant here (we never reach PPoG).

Cause: BlueZ has the Time 2 **typed as a BR/EDR device**, so `Device1.Connect()` (Kable's btleplug
backend → BlueZ) pages it over classic and never falls back to LE. Contrast: Time Steel connected
via LE (2 LE-connect events). The daemon's JVM classic scanner is a no-op (`emptyFlow()`), so this
is BlueZ's own knowledge of the device — likely a stale BR/EDR entry.

Note the client/server split: **client (phone→watch) = Kable + btleplug (Rust)**; **server
(BluezGattServer) = BlueZ D-Bus**. btleplug does not LE-fallback after a classic page timeout.

ROOT CAUSE (confirmed): Time 2 advertises **Public address + Flags 0x02** (BR/EDR-Not-Supported bit
NOT set → claims dual-mode). `bluetoothctl info` = "not available" (NOT a stale bond). BlueZ pages
it over classic and btleplug never falls back to LE. This stack can't force LE per-connection
(no equivalent of Android `connectGatt(TRANSPORT_LE)`).

FIX: put the controller in **LE-only mode** (daemon is BLE-only anyway):
`sudo btmgmt power off && sudo btmgmt bredr off && sudo btmgmt power on` (test), or persistent
`/etc/bluetooth/main.conf` → `[General] ControllerMode = le` + restart bluetooth. Then BlueZ must
connect LE. Caveat: disables ALL classic on the adapter (other devices here are BLE, so fine).
Only after the Time 2 connects over LE can the gateway-pairing / reversedPPoG question be tested.

## Time 2 over LE (after LE-only mode) — SMP MITM pairing, no agent

After `ControllerMode=le`, the Time 2 connects over LE, discovers services, and (EMERY ⇒
`needsPairingTrigger=true`) **writes the pairing trigger** + calls `Pair()`. But bonding fails:
`createBond Pair() ended: No reply within specified time`, ATT 0x0e. snoop SMP shows
**LE Secure Connections + MITM** (`Auth: Bonding, MITM, SC`) → public-key exchange → Confirm/Random
→ watch terminates (`0x13`) / `SMP Pairing Failed (0x05)`. The watch DOES show its pairing popup
(user confirmed); the official Android app shows a 6-digit code — this is **Numeric Comparison**.

Root cause: **the daemon registered no BlueZ pairing agent**, so BlueZ had nothing to answer
`RequestConfirmation` → pairing times out. (Time Steel bonded because it used older LE *Legacy*
pairing, no agent needed.)

FIX (implemented, stoandl-side, no PebbleOS/BlueZ change): `BluezPairingAgent.kt` — an
`org.bluez.Agent1` exported via dbus-java, registered as the **default** agent with capability
**DisplayYesNo**, auto-accepting `RequestConfirmation`/`RequestAuthorization`/`AuthorizeService`.
Wired into `PebbleIntegration.init()`. User confirms the matching code on the watch; phone
auto-accepts. If logs show `RequestPasskey` instead (watch-displays/phone-enters Passkey Entry),
that can't be answered headlessly and we'd revisit.

## Dead ends (do not revisit)

- **Service Changed via reAddServices** (the kept experiment, see below): proven not to work for
  the failing case. btmon shows **zero Service Changed indications transmitted** — because the
  watch did no discovery, it never subscribed to *anything* (incl. Service Changed), so there is no
  CCCD channel to deliver the indication through. Catch-22.
- **CCC-file / setfacl bond-DB editing** (prior session): BlueZ ignores CCC files for externally
  -registered GATT apps. Confirmed irrelevant.
- **Waiting longer for the watch to act**: it stays fully passive; waiting just delays the timeout.

## Outcome & decisions

- **BLE-native watches work (Time 2 confirmed).** The fix was a **headless BlueZ pairing agent**
  (`BluezPairingAgent.kt`, `DisplayYesNo`, auto-confirm) — newer firmware needs Secure-Connections/MITM
  pairing, which timed out with no agent. With it: pair (confirm on watch) → PPoG → notifications, incl.
  bonded reconnect. Committed.
- **Service-Changed experiment: reverted.** The grace-then-`reAddServices` attempt never helped (it
  fired 0× on the Time 2 and can't reach the Time Steel — catch-22) and is gone; submodule is back to
  baseline.
- **BASALT force-pairing-trigger: tried and reverted.** Forcing `needsPairingTrigger=true` for BASALT
  made the *bond* complete but **not** PPoG — the watch still skipped GATT discovery (the firmware race),
  so no payoff.
- **Time Steel firmware fix: not deployable.** `coredevices/PebbleOS` has no snowy/basalt board (tags
  v4.9+); the Time Steel runs Rebble's binary-patched `v4.4.3-rbl`; `google/pebble` is readable but
  won't compile. So the watch-side race can't be fixed from our side.
- **Bluetooth Classic: parked (intentional).** It's the reliable transport for classic watches, but it's
  more power-hungry than BLE and stoandl targets battery-constrained phones — so it's out of scope. Full
  design retained in `bt-classic-scope.md` if ever wanted.
- **Upstream PebbleOS advertising-flag fix: filed + PR'd.** Once a watch runs firmware with it, the
  LE-only controller workaround is no longer needed for BLE-native watches.
- **Logging:** `logback.xml` restored to INFO (DEBUG was diagnostic-only).

## Key files / refs

- Connection flow: `libs/libpebble3/…/connection/bt/ble/pebble/PebbleBle.kt`
- Pairing/gateway: `…/pebble/PebblePairing.kt` (`needsPairingTrigger()`, `makePairingTriggerValue`)
- GATT server: `…/jvmMain/…/transport/GattServer.jvm.kt`
- Transport/pairing config: `…/jvmMain/…/di/LibPebbleModule.jvm.kt`, `…/ble/BlePlatformConfig.kt`
- Sandbox build (NEVER `./gradlew` in /workspace — it's a bind mount of the host repo; use
  `~/check-build.sh` which builds an isolated copy). btmon decodes snoops offline: `btmon -r X.snoop`.
