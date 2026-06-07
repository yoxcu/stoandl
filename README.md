# stoandl

> **⚠️ Early work in progress.** Only notification sync is implemented. Expect rough edges and breaking changes.

> Built with heavy assistance from [Claude](https://claude.ai) (Anthropic's AI).

Headless Pebble smartwatch companion app / bridge for Linux / postmarketOS — a background
daemon that bridges D-Bus desktop notifications to a Pebble watch over BLE.

*Stoandl* is Bavarian dialect for "Steinchen" (little stone / pebble).

## What it does

- Forwards desktop notifications (`org.freedesktop.Notifications`) to the watch over BLE
- Reconnects automatically after watch-initiated disconnects or daemon restarts
- Runs as a background daemon with no UI

It also sideloads watchapps, runs PKJS companion scripts, and serves Clay config pages.
→ [docs/features.md](docs/features.md) — full feature list, comparison with other companion apps, and roadmap.

## Compatibility

BLE-only by design. Works reliably with BLE-native watches (Pebble 2 / Time 2); older dual-mode
watches (Time, Time Steel) are best-effort over BLE.

| Watch | Platform | Status |
|-------|----------|--------|
| Pebble Time 2 | EMERY | ✅ Works |
| Pebble 2 | DIORITE | ⚠️ Expected to work (untested) |
| Pebble Time / Time Steel | BASALT | ⚠️ Flaky over BLE (~1 in 5 pairings succeed) |
| Pebble Time Round | CHALK | ⚠️ Same class as BASALT (untested) |
| original Pebble / Pebble Steel | APLITE | ❌ Classic-only — not supported |

→ [docs/devices.md](docs/devices.md) — root causes, workarounds, Bluetooth Classic scope.

## Requirements

- JDK 21
- BlueZ (bluetoothd running), in LE-only mode (see below)
- D-Bus session bus with a notification daemon (dunst, mako, GNOME, etc.)

## Bluetooth setup

Some Pebbles advertise as dual-mode — BlueZ tries a Classic connection first and never falls back
to LE. Put the adapter in **LE-only mode** to force BLE. (Firmware v4.12.0+ on BLE-native watches
fixes the advertising bug; LE-only mode is not needed there.)

```sh
# temporary (until reboot)
sudo btmgmt power off && sudo btmgmt bredr off && sudo btmgmt power on
```

Persistent — `/etc/bluetooth/main.conf`:

```ini
[General]
ControllerMode = le
```

Then `sudo systemctl restart bluetooth`. Note: disables Bluetooth Classic for the whole adapter.

## Pairing

On first connection the watch shows a **6-digit code**. stoandl auto-accepts on the Linux side —
just confirm the code on the watch. Subsequent reconnects are automatic.

## Build

```sh
./gradlew shadowJar   # → build/libs/stoandl-<version>-all.jar
```

## Run

```sh
./gradlew run
```

Or with the fat JAR:

```sh
java \
  --add-opens=org.freedesktop.dbus/org.freedesktop.dbus.connections.base=ALL-UNNAMED \
  --add-opens=org.freedesktop.dbus/org.freedesktop.dbus.connections.transports=ALL-UNNAMED \
  -jar build/libs/stoandl-*-all.jar
```

## Managing apps

With the daemon running and a watch connected, the `stoandl` CLI talks to it over D-Bus:

```sh
stoandl apps                 # list watchfaces and apps in the locker
stoandl launch <name|uuid>   # launch an app or watchface on the watch
stoandl remove <name|uuid>   # uninstall an app or watchface from the locker
stoandl sideload app.pbw     # install a .pbw onto the connected watch
stoandl settings [app]       # open a running PKJS app's Clay config page
```

`launch`/`remove` match a watch app by UUID or by (case-insensitive) name — exact name first,
then substring. If the name is ambiguous the command lists the candidates so you can pick the
UUID. `apps` flags each entry as `active` (current watchface), `sideloaded`, `config`
(has a settings page) or `system`. System apps cannot be removed.

## Backup & restore

stoandl keeps your whole setup — the locker, the cached `.pbw` binaries, app order, the active
watchface, and PKJS/Clay settings — under `~/.config/stoandl/`. None of it is tied to a specific
watch, so it survives unpairing: when you re-pair a watch, libpebble3 re-syncs the locker back
onto it automatically. Back that state up (or move it to another machine) with:

```sh
stoandl backup [out.tar.gz]   # default: ./stoandl-backup-<timestamp>.tar.gz
stoandl restore <in.tar.gz>   # stop the daemon first; --force to override
```

For a guaranteed-consistent snapshot, stop the daemon before `backup` (it's safe to run live,
but the SQLite DB is captured mid-write). `restore` moves any existing `~/.config/stoandl/`
aside to `stoandl.old-<timestamp>` rather than overwriting it, and refuses to run while the
daemon is up.

Note: settings a watchapp writes *directly on the watch* (the C `persist_write` API, as opposed
to Clay/PKJS config) live only on the watch and are not part of this backup.

## Logging

Logs go to `/tmp/stoandl.log` (rolling, 5 MB × 3) and stdout. Default level is INFO.

For full BLE/protocol traces, set `STOANDL_LOG=DEBUG`:

```sh
STOANDL_LOG=DEBUG ./gradlew run
```

For the systemd service, add to the unit or a drop-in:

```ini
[Service]
Environment=STOANDL_LOG=DEBUG
```

## Install (systemd user service)

```sh
sudo install -Dm644 build/libs/stoandl-*-all.jar /usr/lib/stoandl/stoandl.jar
sudo install -Dm644 packaging/stoandl.service /usr/lib/systemd/user/stoandl.service
systemctl --user daemon-reload
systemctl --user enable --now stoandl
```

## libpebble3

Depends on a patched fork of [matejdro/libpebble3](https://github.com/matejdro/libpebble3)
(`stoandl` branch), included as a git submodule at `libs/libpebble3`. Run
`git submodule update --init --recursive` after cloning. Wired via Gradle composite build — no
separate Maven publish needed.
