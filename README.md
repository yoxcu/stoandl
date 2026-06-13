# stoandl

> **⚠️ Early work in progress.** Notification sync, app/locker management, backup/restore and PKJS are working; phone-call and calendar-sync support are written but untested on real hardware. Expect rough edges and breaking changes.

> Built with heavy assistance from [Claude](https://claude.ai) (Anthropic's AI).

Headless Pebble smartwatch companion app / bridge for Linux / postmarketOS — a background
daemon that bridges D-Bus desktop notifications to a Pebble watch over BLE.

*Stoandl* is Bavarian dialect for "Steinchen" (little stone / pebble).

## What it does

- Forwards desktop notifications (`org.freedesktop.Notifications`) to the watch over BLE
- Manages the watch locker — list, launch, install (`.pbw`) and remove apps & watchfaces
- Backs up and restores your locker, app cache and PKJS/Clay settings
- Syncs weather to the watch's Weather app and as sunrise/sunset timeline pins (Open-Meteo — free, no account)
- Bridges desktop media players (MPRIS) to the watch's Music app — now-playing plus play/pause, next/previous and volume from the watch
- Syncs calendar events (and their reminders) to the watch's timeline as native pins — DE-agnostic (local `.ics`, iCal feeds or CalDAV), reusing the calendars your desktop already keeps where it can
- Configures the watch's advanced settings (quick-launch buttons, backlight, ambient-light, …) — the ones the official app exposes but the watch menus don't
- Reconnects automatically — after a watch disconnect, daemon restart, or coming back into range; reconnection is handed to BlueZ's own background auto-connect, so the watch links up the instant it's reachable with no polling and no restarts
- Runs as a background daemon with no UI

It also runs PKJS companion scripts, serves Clay config pages, and has (untested) phone-call support.
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

> BLE is driven entirely through BlueZ over D-Bus — no glibc-only native
> libraries — so stoandl runs on **musl** (postmarketOS / Alpine) as well as
> glibc distros. Verified end-to-end (pair, connect, notifications) on both a
> glibc laptop and a OnePlus 6 running postmarketOS.

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

```sh
stoandl pair                 # pair a new watch (opens a ~2 min window)
stoandl list                 # known watches and their connection state
stoandl repair B349          # re-pair ONE watch by name/substring (forgets just it, then pairs)
stoandl unpair               # forget the watch on this host (e.g. moving it elsewhere)
```

If you forget the host **on the watch** (one-sided bond), stoandl notices the watch endlessly
re-connecting and dropping, and sends a notification with a one-tap **Re-pair** button — or run
`stoandl repair <name>`. `pair` never disturbs other watches, so it's safe with multiple Pebbles.

Conversely, if the pairing is removed **on this computer** (e.g. `bluetoothctl remove`, while the
watch still holds its side), stoandl forgets the now-unusable watch and notifies you that the only
way back is to unpair it on the watch too, then `stoandl pair` — a half-removed bond can't be
restored. (To forget a watch cleanly in the first place, use `stoandl unpair`, not `bluetoothctl`.)

> **Won't reconnect?** The most common cause is *another process running Bluetooth discovery* — an
> open Bluetooth settings panel or pairing window (GNOME/KDE), or a stray `bluetoothctl scan on`.
> Discovery monopolizes the adapter's single LE scanner, so BlueZ can't issue the watch's connection
> and it never links up. Check with `bluetoothctl show | grep Discovering`; if it's `yes` and you
> didn't start it, close the scanner — the watch reconnects within a second. stoandl logs a warning
> (and sends a desktop notification) when it detects this.

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
java -jar build/libs/stoandl-*-all.jar
```

## Managing apps

With the daemon running and a watch connected, the `stoandl` CLI talks to it over D-Bus:

```sh
stoandl apps                 # list watchfaces and apps in the locker
stoandl launch <name|uuid>   # launch an app or watchface on the watch
stoandl remove <name|uuid>   # uninstall an app or watchface from the locker
stoandl sideload app.pbw     # install a .pbw onto the connected watch
stoandl config [app]         # open a PKJS app's Clay config page (launches it if needed)
stoandl settings [filter]    # list the watch's advanced settings (quick-launch, backlight, …)
stoandl set-setting <id> <v> # set one, e.g. set-setting lightAmbientThreshold 200
stoandl version              # version of the running daemon (and this CLI)
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

## Calendar

stoandl syncs upcoming calendar events to the watch's **timeline** as native pins — DE-agnostically
from local `.ics` files (reusing e.g. Calindori on Plasma Mobile via `calendar.discover`), or from
iCal feed URLs / CalDAV. It's off until you set a `calendar.*` source in the config.

```sh
stoandl calendar list                 # synced calendars + enabled state
stoandl calendar disable <id|name>    # stop syncing one calendar (enable to undo)
stoandl calendar sync                 # force a re-read now
stoandl calendar dump <file|url>      # parse + print events offline (no daemon/watch needed)
```

→ [docs/configuration.md#calendar](docs/configuration.md#calendar) — sources, the reuse-your-DE
story (and why GNOME/KDE *online* accounts need an iCal/CalDAV URL for now), recurrence/timezone
handling, and the egress note.

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

The `install.sh` script builds the fat JAR, installs the service + `stoandl` CLI,
and (re)starts it:

```sh
./install.sh                     # build + install on this machine
./install.sh -d                  # also enable DEBUG logging
./install.sh --remote user@host  # build locally, scp + install over SSH
```

`--remote` is handy for a phone (e.g. postmarketOS) where building on-device is
slow — it builds the architecture-independent JAR on your fast machine and pushes
it. (It uses `ssh -t` so `sudo` can prompt for a password.)

Or install manually:

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
