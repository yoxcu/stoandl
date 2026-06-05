# stoandl

> **⚠️ Early work in progress.** Only notification sync is implemented so far. Expect rough edges, missing features, and breaking changes.

> **Note:** This project was built with heavy assistance from [Claude](https://claude.ai) (Anthropic's AI). Treat the code accordingly — review carefully before deploying.

Headless Pebble smartwatch companion daemon for Linux and postmarketOS.

*Stoandl* is Bavarian dialect for "Steinchen" (little stone / pebble).

## What it does

- Connects to a Pebble watch over BLE using BlueZ
- Forwards desktop notifications (from any app via `org.freedesktop.Notifications`) to the watch
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

stoandl is **BLE-only** (it does not implement Bluetooth Classic). Pebble support therefore depends on the watch's transport:

| Watch | Platform | Status |
|-------|----------|--------|
| Pebble Time 2 | EMERY | ✅ Works — pairing, bonded reconnect, notifications (confirmed) |
| Pebble 2 | DIORITE | ⚠️ Expected to work (same BLE-native class; untested) |
| Pebble Time / Time Steel | BASALT | ❌ Connects & bonds but the PPoG data channel doesn't come up yet (gateway-pairing not triggered for these models) — work in progress |
| Pebble Time Round | CHALK | ❌ Same as above (untested) |
| original Pebble / Pebble Steel | APLITE | ❌ Classic-only hardware — not reachable over BLE; would need Bluetooth Classic support |

In short: **BLE-native watches (Pebble 2 / Time 2) work; classic-primary watches don't yet.** See `debug.md` for the full investigation.

## Requirements

- JDK 21
- BlueZ (bluetoothd running), **in LE-only mode** (see below)
- A D-Bus session bus with a notification daemon (dunst, mako, GNOME notifications, etc.)
- A BLE-native Pebble (tested with Pebble Time 2; see [Compatibility](#compatibility))

## Bluetooth setup (required: LE-only mode)

stoandl connects over BLE only, but some Pebbles advertise as dual-mode (BR/EDR + LE). On such a watch BlueZ will try a **Bluetooth Classic** connection first, which times out and never falls back to LE — so the watch never connects. Put the controller in **LE-only mode** so BlueZ always uses LE:

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

Logs are written to `/tmp/stoandl.log`.

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
- Stripped Android/iOS targets to speed up JVM builds

The fork is included as a git submodule under `libs/libpebble3` and wired in via Gradle composite build — no separate Maven publish step needed.
