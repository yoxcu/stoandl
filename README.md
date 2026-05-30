# stoandl

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

## Requirements

- JDK 21
- BlueZ (bluetoothd running)
- A D-Bus session bus with a notification daemon (dunst, mako, GNOME notifications, etc.)
- A Pebble watch (tested with Pebble Time)

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
