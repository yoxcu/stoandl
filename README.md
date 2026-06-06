# stoandl

> **⚠️ Early work in progress.** Only notification sync is implemented so far. Expect rough edges, missing features, and breaking changes.

> **Note:** This project was built with heavy assistance from [Claude](https://claude.ai) (Anthropic's AI). Treat the code accordingly — review carefully before deploying.

Headless Pebble smartwatch companion daemon for Linux and postmarketOS.

*Stoandl* is Bavarian dialect for "Steinchen" (little stone / pebble).

## What it does

- Connects to a Pebble watch over BLE using BlueZ
- Forwards desktop notifications (from any app via `org.freedesktop.Notifications`) to the watch
- Reconnects automatically after watch-initiated disconnects or daemon restarts
- Runs as a background daemon with no UI

## How it works

```
Desktop apps
    │  Notify() method call on org.freedesktop.Notifications
    ▼
D-Bus session bus
    │  BecomeMonitor (passive copy)
    ▼
stoandl ──► libpebble3 ──► BlueZ ──► Pebble watch (BLE/PPoG)
```

**Notification monitoring** — stoandl uses D-Bus `BecomeMonitor` to receive a passive copy of every `Notify` method call on the session bus. The original call still reaches the notification daemon normally (screen notifications are unaffected).

**Watch connection** — [libpebble3](https://github.com/matejdro/libpebble3) handles the BLE stack: BlueZ GATT server (phone-as-peripheral, `reversedPPoG=false`), PPoG (Pebble Protocol over GATT) framing, and the Pebble protocol itself. stoandl scans for a known watch and reconnects automatically.

## Compatibility

stoandl is **BLE-only by design** — it targets battery-constrained Linux phones (postmarketOS),
where Bluetooth Classic's always-on RFCOMM link costs more power than BLE. Bluetooth Classic is
therefore **intentionally out of scope** (a scoping note lives in `bt-classic-scope.md` if anyone
ever wants it). Watch support depends on the watch's transport and firmware:

| Watch | Platform | Status |
|-------|----------|--------|
| Pebble Time 2 | EMERY | ✅ Works — pairing, bonded reconnect, notifications (confirmed) |
| Pebble 2 | DIORITE | ⚠️ Expected to work (same BLE-native class; untested) |
| Pebble Time / Time Steel | BASALT | ⚠️ Best-effort — pairs, but the data channel comes up only intermittently (~1 in 5); see cause below |
| Pebble Time Round | CHALK | ⚠️ Same class as BASALT (untested; expect the same flakiness) |
| original Pebble / Pebble Steel | APLITE | ❌ Classic-only hardware — no usable BLE data path; not supported |

**In short: BLE-native watches (Pebble 2 / Time 2) work reliably. Older classic watches (Time / Time
Steel) are best-effort over BLE.**

### Why the older watches are flaky over BLE

The PPoGATT data channel needs the **watch** to discover the phone's GATT service and subscribe to it
after pairing. On the modern firmware that BLE-native watches run (e.g. Pebble Time 2 on Core Devices
PebbleOS), this happens reliably — including on reconnect. On the **old firmware of the Time / Time
Steel** (e.g. Rebble `v4.4.3-rbl`), the watch does this only intermittently: roughly 4 of 5 fresh
pairs, it bonds and encrypts but never discovers/subscribes to the phone's PPoGATT service, so the
connection times out. The two outcomes are byte-for-byte identical up to that point — it's a
**race inside the watch firmware**, with no influence available from the phone side. Those watches'
reliable transport is Bluetooth Classic, which stoandl deliberately doesn't implement (see above).

Workaround for a Time / Time Steel: forget the pairing on both watch and phone, then re-pair; repeat
until the data channel comes up (often a few tries). See `debug.md` for the full investigation.

## Requirements

- JDK 21
- BlueZ (bluetoothd running), **in LE-only mode** (see below)
- A D-Bus session bus with a notification daemon (dunst, mako, GNOME notifications, etc.)
- A BLE-native Pebble (tested with Pebble Time 2; see [Compatibility](#compatibility))

## Bluetooth setup (required: LE-only mode)

stoandl connects over BLE only, but some Pebbles advertise as dual-mode (BR/EDR + LE) — they set the
advertising flags without "BR/EDR Not Supported". BlueZ then tries a **Bluetooth Classic** connection
first, which times out and never falls back to LE, so the watch never connects. This is a watch
firmware advertising bug, fixed in [PebbleOS#1441](https://github.com/coredevices/PebbleOS/pull/1441)
and included since **firmware v4.12.0** — if your watch runs v4.12.0 or later, LE-only mode is not
needed. For older firmware, put the controller in **LE-only mode** so BlueZ always uses LE:

```sh
# temporary (until reboot), to try it out:
sudo btmgmt power off
sudo btmgmt bredr off
sudo btmgmt power on
```

Make it persistent in `/etc/bluetooth/main.conf`:

```ini
[General]
ControllerMode = le
```

then `sudo systemctl restart bluetooth`. Note this disables Bluetooth Classic for the whole adapter (fine for a headless companion host, but it will stop classic devices like BT speakers from working on that adapter).

## Pairing

On first connection the watch shows a **6-digit code**. stoandl registers a headless BlueZ pairing agent (`DisplayYesNo`, auto-confirm), so you only need to **confirm the code on the watch** — there is no prompt on the Linux side. Subsequent reconnects are automatic (no re-pairing).

## Building

```sh
./gradlew jar
```

The fat JAR is written to `build/libs/stoandl-<version>.jar`.

## Running

```sh
./gradlew run
```

Or with the fat JAR:

```sh
java \
  --add-opens=org.freedesktop.dbus/org.freedesktop.dbus.connections.base=ALL-UNNAMED \
  --add-opens=org.freedesktop.dbus/org.freedesktop.dbus.connections.transports=ALL-UNNAMED \
  -jar build/libs/stoandl-*.jar
```

The `--add-opens` flags are needed so stoandl can silence dbus-java's automatic replies on BecomeMonitor connections (otherwise the daemon closes the connection after each notification).

## Logging

Logs go to `/tmp/stoandl.log` (rolling, 5 MB × 3) and stdout.

The default level is **INFO**: startup, BLE scan, watch connected, notifications forwarded, and PKJS lifecycle events (JS bridge init, webhook fired, status sent back). That's typically 10–20 lines per session — enough to follow what's happening without noise.

For full BLE/protocol packet traces, set `STOANDL_LOG=DEBUG`:

```sh
# dev run
STOANDL_LOG=DEBUG ./gradlew run

# fat JAR
STOANDL_LOG=DEBUG java --add-opens=... -jar stoandl.jar
# or equivalently
java -DSTOANDL_LOG=DEBUG --add-opens=... -jar stoandl.jar
```

For the systemd service, add to the unit or a drop-in:

```ini
[Service]
Environment=STOANDL_LOG=DEBUG
```

then `systemctl --user daemon-reload && systemctl --user restart stoandl`.

## systemd user service (postmarketOS)

Copy the JAR and install the service:

```sh
sudo install -Dm644 build/libs/stoandl-*.jar /usr/lib/stoandl/stoandl.jar
sudo install -Dm644 packaging/stoandl.service /usr/lib/systemd/user/stoandl.service
systemctl --user daemon-reload
systemctl --user enable --now stoandl
```

## libpebble3

stoandl depends on a fork of [matejdro/libpebble3](https://github.com/matejdro/libpebble3) (`micropebble` branch) with JVM/Linux-specific patches:

- BlueZ GATT server implementation (phone acts as BLE peripheral)
- PPoG handshake fixes for the Linux BLE stack
- BlueZ pairing and bonding handling
- Reliable reconnect: scan-result identifier is promoted so known devices are never spuriously forgotten at startup
- Graceful disconnect: sends a PPoG reset to the watch on shutdown so it re-initiates the session cleanly on the next connection
- Stripped Android/iOS targets to speed up JVM builds

The fork is included as a git submodule under `libs/libpebble3` and wired in via Gradle composite build — no separate Maven publish step needed.
