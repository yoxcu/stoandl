# stoandl

> **⚠️ Work in progress — but broad.** Most of what the Android/iOS Pebble companion apps do is already here: see [**What it does**](#what-it-does) below. Some of it is verified on real hardware, some is written but not yet hardware-tested — [docs/features.md](docs/features.md) marks which, and lists the remaining gaps. Expect occasional rough edges and breaking changes.

> Built with heavy assistance from [Claude](https://claude.ai) (Anthropic's AI).

Headless Pebble smartwatch companion app / bridge for Linux / postmarketOS — a background
daemon that bridges your Linux desktop to a Pebble watch over Bluetooth: **BLE** for modern watches
(Pebble 2 / Time 2) and **Bluetooth Classic** for classic-era ones (Pebble Time / Time Steel …).

*Stoandl* is Bavarian dialect for "Steinchen" (little stone / pebble).

## What it does

- Forwards desktop notifications (`org.freedesktop.Notifications`) to the watch
- Extends to any service (Matrix, Signal, SMS, "find my phone", …) via **companion apps** — small host-side programs, in any language, that drive watch notifications (with replies + actions) and optionally ship their own watchapp, no watchface coding required; `stoandl ext` installs and manages them
- Manages the watch locker — list, launch, install (`.pbw`) and remove apps & watchfaces
- Backs up and restores your locker, app cache and PKJS/Clay settings
- Syncs weather to the watch's Weather app and as sunrise/sunset timeline pins (Open-Meteo — free, no account)
- Bridges desktop media players (MPRIS) to the watch's Music app — now-playing plus play/pause, next/previous and volume from the watch
- Syncs calendar events (and their reminders) to the watch's timeline as native pins — DE-agnostic (local `.ics`, iCal feeds or CalDAV), reusing the calendars your desktop already keeps where it can
- Configures the watch's advanced settings (quick-launch buttons, backlight, ambient-light, …) — the ones the official app exposes but the watch menus don't
- Flashes watch firmware — a local `.pbz`, or (opt-in) the latest build for your watch's board, pulled from the right source automatically (PebbleOS GitHub releases for Core devices, cohorts.rebble.io for classic Pebbles), with an optional "update available" notification on the watch and your desktop
- Installs watch language packs — a local `.pbl`, or pick one for your watch from the built-in catalog (the official app's, bundled) and download+install it
- Captures watch screenshots to a PNG — `stoandl screenshot` — for sharing watchfaces and filing bug reports
- Pulls watch logs and builds a support bundle — `stoandl logs` dumps the watch's firmware logs; `stoandl support` packages them with the daemon log + watch info + redacted config into a `.tar.gz` for bug reports
- Resets the watch — `stoandl reset recovery` reboots it into recovery (PRF) firmware to un-brick a bad flash; `stoandl reset factory` wipes it back to out-of-box state
- Reads the watch's battery — the level via `stoandl watch battery` (and inline in `stoandl watch list`), plus local **insights** (`stoandl watch battery insights`/`history`): a charge-level trend, discharge rate, time-to-empty, charge cycles and voltage. Decoded from the watch's own hourly analytics heartbeat, it goes further with a per-hour **drain** bar, an estimated **power-attribution** breakdown (`stoandl watch battery power` — what drew power: display, vibration, Bluetooth, CPU, …) and a **notification-density** overlay on the charge graph — the same data the official app graphs, computed on-device here with no cloud
- Reconnects automatically — after a watch disconnect, daemon restart, or coming back into range. BLE watches are handed to BlueZ's own background auto-connect (no polling); classic-era watches reconnect by paging the watch's fixed address (no advertising needed, so it survives airplane mode). Either way it links up on its own, with no restarts
- Runs as a background daemon with no UI

It also syncs health/activity data, captures custom-app datalogs, mutes notifications per app (including from the wrist), finds a misplaced watch, syncs time/timezone, exposes a Pebble-SDK developer connection, runs PKJS companion scripts + Clay config pages, and has phone-call support (caller ID, missed-call pins).
→ [docs/features.md](docs/features.md) — full feature list, comparison with other companion apps, and what's not yet implemented.

## Compatibility

BLE-native watches (Pebble 2 / Time 2) connect over **BLE** and work reliably. Classic-era watches
(Pebble Time / Time Steel) are flaky over BLE — their reliable native transport is **Bluetooth Classic**
(BR/EDR), which stoandl now supports as an experimental transport (see
[Bluetooth Classic](#bluetooth-classic-classic-era-watches) below).

| Watch | Platform | Transport | Status |
|-------|----------|-----------|--------|
| Pebble Time 2 | EMERY | BLE | ✅ Works |
| Pebble 2 | DIORITE | BLE | ⚠️ Expected to work (untested) |
| Pebble Time / Time Steel | BASALT | Bluetooth Classic | ✅ Works — experimental, hardware-verified on a Time Steel (flaky over BLE) |
| Pebble Time Round | CHALK | Bluetooth Classic | ⚠️ Same class as BASALT (untested) |
| original Pebble / Pebble Steel | APLITE | Bluetooth Classic | ⚠️ Classic-only hardware (untested) |

→ [docs/devices.md](docs/devices.md) — root causes, workarounds, transport details.

## Requirements

- **JDK 25 to run.** The Bluetooth Classic transport reaches the kernel through `java.lang.foreign`,
  finalized in JDK 22. Make sure `/usr/bin/java` (and `java` on `PATH`) is JDK 25 on the machine that
  runs the daemon — e.g. Arch: `archlinux-java set java-25-openjdk`.
- **JDK 21 to build** (in addition to 25). Gradle 8.14 cannot run on JDK 25, so the build runs on 21
  while the Kotlin toolchain compiles against 25 — install both; see [Build](#build).
- BlueZ (bluetoothd running) — the default dual-mode adapter is fine; see [Bluetooth setup](#bluetooth-setup)
- D-Bus session bus with a notification daemon (dunst, mako, GNOME, etc.)

> BLE is driven entirely through BlueZ over D-Bus, and the Bluetooth Classic transport reaches the
> kernel via `java.lang.foreign` (no JNI/JNA) — so stoandl loads **no glibc-only native library at
> runtime** and runs on **musl** (postmarketOS / Alpine) as well as glibc distros. (The fat JAR still
> *bundles* kable's btleplug/JNA blobs as an unused transitive dependency — kable's types back the
> shared BLE abstraction, but its native backend is never instantiated on JVM/Linux, which uses
> BlueZ.) BLE is verified end-to-end (pair, connect, notifications) on a glibc laptop and a OnePlus 6
> running postmarketOS; the FFM Classic path is musl-clean by construction but not yet
> hardware-verified on musl.

## Bluetooth setup

**Most setups need no change.** Leave the adapter in its default dual-mode (BR/EDR + LE): BLE-native
watches (Time 2 / Pebble 2) on current firmware connect over LE, and classic-era watches (Time /
Time Steel) need BR/EDR **enabled** for [Bluetooth Classic](#bluetooth-classic-classic-era-watches).

**LE-only mode (optional — BLE-native watches only).** Some BLE-native Pebbles on *old* firmware
advertise as dual-mode, so BlueZ tries a Classic connection that never falls back to LE. Forcing the
adapter **LE-only** dodges that. Firmware v4.12.0+ fixes the advertising bug, so it's rarely needed —
and **don't** use LE-only mode if you want a classic-era watch over Bluetooth Classic (that needs
BR/EDR on).

```sh
# temporary (until reboot)
sudo btmgmt power off && sudo btmgmt bredr off && sudo btmgmt power on
```

Persistent — `/etc/bluetooth/main.conf`:

```ini
[General]
ControllerMode = le
```

Then `sudo systemctl restart bluetooth`. Note: this disables Bluetooth Classic for the whole adapter,
so an LE-only adapter can serve a BLE watch **or** a classic-era watch — not both at once. This trade-off
only matters for BLE-native watches on **pre-4.12** firmware; on **v4.12.0+** leave the adapter in its
default dual-mode and a single adapter serves both (the advertising bug that made LE-only necessary is
fixed, so you never enter this mode).

## Pairing

On first connection the watch shows a **6-digit code**. stoandl auto-accepts on the Linux side —
just confirm the code on the watch. Subsequent reconnects are automatic.

```sh
stoandl watch pair                 # pair a new watch (opens a ~2 min window; finds BLE and classic watches)
stoandl watch list                 # known watches, their connection state and battery level
stoandl watch battery              # the connected watch's battery level
stoandl watch battery insights     # charge trend, discharge rate, time-to-empty, cycles, voltage
stoandl watch battery history      # the battery %-over-time series (--since 24h / 7d / …)
stoandl watch battery activity     # per-hour battery drain + notifications-received counts
stoandl watch battery power        # estimated usage share: what drew power (display, radio, CPU, …)
stoandl watch connect B349         # connect a specific known watch by name/substring (switches the active watch)
stoandl watch repair B349          # re-pair ONE watch by name/substring (forgets just it, then pairs)
stoandl watch unpair [name]        # forget watches on this host — all of them, or just the named one
```

If you forget the host **on the watch** (one-sided bond), stoandl notices the watch endlessly
re-connecting and dropping, and sends a notification with a one-tap **Re-pair** button — or run
`stoandl watch repair <name>`. `pair` never disturbs other watches, so it's safe with multiple Pebbles.

Conversely, if the pairing is removed **on this computer** (e.g. `bluetoothctl remove`, while the
watch still holds its side), stoandl forgets the now-unusable watch and notifies you that the only
way back is to unpair it on the watch too, then `stoandl watch pair` — a half-removed bond can't be
restored. (To forget a watch cleanly in the first place, use `stoandl watch unpair`, not `bluetoothctl`.)

> **Won't reconnect?** The most common cause is *another process running Bluetooth discovery* — an
> open Bluetooth settings panel or pairing window (GNOME/KDE), or a stray `bluetoothctl scan on`.
> Discovery monopolizes the adapter's single LE scanner, so BlueZ can't issue the watch's connection
> and it never links up. Check with `bluetoothctl show | grep Discovering`; if it's `yes` and you
> didn't start it, close the scanner — the watch reconnects within a second. stoandl logs a warning
> (and sends a desktop notification) when it detects this.

## Bluetooth Classic (classic-era watches)

> **Experimental.** Works and is hardware-verified on a Pebble Time Steel, but it's newer and less
> battle-tested than the BLE path, and off by default.

Classic-era Pebbles (Pebble Time / Time Steel — and, by class, the original Pebble / Steel) connect
reliably only over **Bluetooth Classic** (BR/EDR, RFCOMM/SPP), their native transport. Their BLE path
is a firmware-side race: the watch pairs once over BLE, then hands the host its Classic address and
expects the persistent link over BR/EDR — so over BLE it often never reconnects. stoandl can now talk
to these watches over Bluetooth Classic directly. The BLE path is untouched: BLE-native watches
(Time 2 / Pebble 2) keep using BLE.

What works (hardware-verified on a Time Steel): discover → pair → connect → the full Pebble protocol →
automatic reconnect after the watch goes out of range or into airplane mode. The protocol layer is
transport-agnostic, so everything in [What it does](#what-it-does) — notifications, the locker, health,
datalog, calendar, music, firmware, … — works the same over Classic. (The lone caveat is the battery
*level* read-out `stoandl watch battery`: it rides a BLE GATT service, so it's BLE-only — but the richer
`stoandl watch battery insights` ride the analytics heartbeat over datalog, which works over Classic too.)

### Enabling it

It's off until you opt in, in `~/.config/stoandl/stoandl.conf`:

```ini
classic.discover = true     # discover, pair and connect classic-era Pebbles automatically
```

Make sure the adapter has **BR/EDR enabled** (the default — *not* LE-only mode; see
[Bluetooth setup](#bluetooth-setup)). Then pair as usual:

```sh
stoandl watch pair                 # opens a pairing window; inquires for BLE and classic watches alike
# then confirm the matching 6-digit code ON THE WATCH — stoandl auto-confirms on the host side
```

With `classic.discover` on, a known watch reconnects on its own afterwards: stoandl pages its fixed
address, so no advertising is needed and it survives airplane mode / out-of-range. A BR/EDR inquiry
only runs while a pairing window is open, so the radio stays quiet the rest of the time.

`connect`, `unpair [name]`, `repair` and `list` all work for classic watches just like BLE ones —
`unpair` forgets a classic watch by its address even while it's connected. Only one watch is
connected at a time; `stoandl watch connect <name>` hands the active slot to another known watch. (Only
`stoandl watch battery` differs — see the battery note above.)

> **Pairing falls back to manual?** Pairing a dual-mode watch occasionally creates an LE bond instead
> of the BR/EDR link key RFCOMM needs. If the watch pairs but won't connect, bond it explicitly with
> `btmgmt pair -t bredr <mac>`, then (re)start the daemon.

The transport spans this repo and the [libpebble3 fork](#libpebble3) (the BlueZ RFCOMM socket, the
BR/EDR scanner and Classic pairing). → [docs/devices.md](docs/devices.md) for the full diagnosis and
[docs/configuration.md](docs/configuration.md#bluetooth-classic) for the config keys.

## Build

Gradle 8.14 runs on **JDK 21**, but the Kotlin toolchain compiles libpebble3's `java.lang.foreign`
code against **JDK 25** (auto-detected under `/usr/lib/jvm`) — both must be installed:

```sh
# Arch/Manjaro:  sudo pacman -S jdk21-openjdk jdk-openjdk
# Debian/Ubuntu: sudo apt install openjdk-21-jdk openjdk-25-jdk
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew shadowJar   # → build/libs/stoandl-<version>-all.jar
```

If your default `java` is already JDK 21, the `JAVA_HOME=` prefix is unnecessary. (`install.sh`
applies this pin for you via `BUILD_JAVA_HOME`.)

**Releases.** CI (`.github/workflows/release.yml`) builds the fat JAR on every push, and pushing a
`v*` tag publishes a GitHub Release with the JAR, a deploy tarball (jar + service + `install.sh` +
`conf.example`), and an auto-generated changelog (features / bug fixes). The version is derived from
`git describe --tags`, so the JAR is named for the tag. The Kirigami GUI ([`gui/`](gui/), a separate
repo) has its own release pipeline shipping a Flatpak — see its README.

## Run

The daemon requires a **JDK 25** runtime (see [Requirements](#requirements)). `gradlew` itself still
needs JDK 21, but the `run` task launches the app on the JDK 25 toolchain:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew run
```

Or with the fat JAR (here `java` must be JDK 25):

```sh
java -jar build/libs/stoandl-*-all.jar
```

## Managing apps

With the daemon running and a watch connected, the `stoandl` CLI talks to it over D-Bus:

```sh
stoandl apps                 # list watchfaces and apps in the locker
stoandl apps launch <name|uuid>   # launch an app or watchface on the watch
stoandl apps remove <name|uuid>   # uninstall an app or watchface from the locker
stoandl apps install app.pbw     # install a .pbw onto the connected watch
stoandl config [app]         # open a PKJS app's Clay config page (launches it if needed)
stoandl settings [filter]    # list the watch's advanced settings (quick-launch, backlight, …)
stoandl settings set <id> <v> # set one, e.g. settings set lightAmbientThreshold 200
stoandl version              # version of the running daemon (and this CLI)
```

`launch`/`remove` match a watch app by UUID or by (case-insensitive) name — exact name first,
then substring. If the name is ambiguous the command lists the candidates so you can pick the
UUID. `apps` flags each entry as `active` (current watchface), `sideloaded`, `config`
(has a settings page) or `system`. System apps cannot be removed.

## Extensions (companion apps)

Plug in your own integrations — Matrix, Signal, Discord, SMS, "find my phone", anything — **host-side**,
the way Android PebbleKit companion apps work. An extension is a small program in any language that
stoandl spawns and supervises, talking newline-delimited JSON-RPC over stdio. It can push notifications
to the watch (with named actions and canned replies), receive a callback when you act on the watch and
send the reply back through that service's own account, and/or be a PebbleKit-style companion to a
watchapp UUID it ships (AppMessage send/receive, launch, `.pbw` install) — so no watchapp is required,
but one is supported. A ~40-line per-language helper (e.g. `stoandl_ext.py`) is shipped as a template.

```sh
stoandl ext install matrix.tar.gz   # extract to ~/.config/stoandl/ext/, sideload any bundled .pbw,
                                     # enable + hotplug-start (no daemon restart)
stoandl ext list                    # installed extensions + enabled/running state
stoandl ext enable <name>           # add to the run-list and start (disable to stop + drop)
stoandl ext restart <name>          # restart one after editing it
stoandl ext uninstall <name>        # stop, remove its dir, and remove any watchapp it installed
```

Extensions run as you, like any script — there's no sandbox. The default entry point is `<name>.py`
under `~/.config/stoandl/ext/<name>/`; per-extension settings live in that dir's `config` file (or
override with `extension.<name>.cmd` / a bundled `manifest.json`). `examples/extensions/` vendors working
examples, each in its own repo (submodule): a full **[Matrix](examples/extensions/matrix)** client —
messages on the wrist + canned replies + on-demand image previews, end-to-end-encrypted — and
**find-my-phone**. Set
`extensions.enabled` to the run-list, or just use `stoandl ext`.

→ [docs/extensions.md](docs/extensions.md) — the wire protocol, the helper API, and how to write one.

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
iCal feed URLs / CalDAV. It's off until you add a source. Manage sources from the GUI
(**Settings → Calendars**, which groups a CalDAV account's calendars under it) or the CLI; changes
apply live (no restart). **CalDAV passwords are never stored in `stoandl.conf`** — they go in the
system keyring (`org.freedesktop.secrets`) when one is unlocked, otherwise a 0600 `secrets` file beside
the config (excluded from backups).

```sh
stoandl calendar list                 # synced calendars + enabled state
stoandl calendar sources              # editable sources (CalDAV accounts / iCal feeds / .ics) + ids
stoandl calendar add caldav https://dav.example.com/alice/ alice   # prompts (no echo) for the password
stoandl calendar passwd <id>          # change a stored CalDAV password
stoandl calendar remove <id>          # drop a source (and its stored password)
stoandl calendar disable <id|name>    # stop syncing one calendar (enable to undo)
stoandl calendar sync                 # force a re-read now
stoandl calendar dump <file|url>      # parse + print events offline (no daemon/watch needed)
```

→ [docs/configuration.md#calendar](docs/configuration.md#calendar) — sources, the reuse-your-DE
story (and why GNOME/KDE *online* accounts need an iCal/CalDAV URL for now), recurrence/timezone
handling, and the egress note.

## Firmware

Flash watch firmware. A local bundle works offline with no setup; libpebble3 checks it against the
connected watch (board, CRC, slot) and refuses a mismatched bundle before sending anything.

```sh
stoandl firmware sideload <file.pbz>   # flash a local firmware bundle (shows a progress bar)
stoandl firmware check        # is newer firmware available for this watch?
stoandl firmware update       # download the matching build and flash it
stoandl firmware status       # current firmware-update state
```

`check`/`update` fetch firmware online for the connected watch, choosing the source by its generation:
**Core devices** (Pebble 2 Duo / Pebble Time 2) from the PebbleOS GitHub releases (default
`coredevices/PebbleOS`), **classic Pebbles** (Pebble Time / Time Steel …) from `cohorts.rebble.io`.
Each is opt-in egress, off until you enable it (`firmware.github` / `firmware.cohorts`). The watch's
board maps exactly to the right bundle, so the build is picked automatically; with `firmware.notify`
(on by default once a source is) stoandl also pushes an "update available" notification — to both the
watch and your desktop — each with an Update button that flashes it.

> Flashing is the riskiest thing stoandl does. It's guarded by the pre-flash safety checks and
> Pebble's recovery firmware, but flash on charger and keep the watch in range.

→ [docs/configuration.md#firmware-updates](docs/configuration.md#firmware-updates)

## Language packs

Install a firmware language pack onto the watch — changing its notification/UI language and loading the
fonts a script needs (Cyrillic, CJK, Burmese, Hebrew, …). A local `.pbl` works offline; the built-in
catalog (the official app's manifest, bundled) lets you pick one for your watch and download it.

```sh
stoandl language list                # packs for the connected watch (installed one marked *); the full catalog if none
stoandl language sideload pack.pbl   # install a local .pbl (offline)
stoandl language install de_DE       # pick from the catalog and download+install (needs language.download)
stoandl language status              # current install state
```

`install` downloads from Rebble's CDN (or a community GitHub repo for packs like Japanese/Hebrew) — opt-in
egress, off until you set `language.download = true`. `list` and `sideload` never touch the network.
Core devices (Pebble 2 Duo / Time 2) share the Diorite (`silk`) packs; classic Pebbles use their own board.

→ [docs/configuration.md#language-packs](docs/configuration.md#language-packs)

## Screenshots

Capture the watch's current screen to a PNG on the host — handy for sharing watchfaces or filing bug
reports.

```sh
stoandl screenshot                   # → ./pebble-screenshot-<time>.png
stoandl screenshot watchface.png     # → a name you choose
stoandl screenshot ~/Pictures/       # → a timestamped PNG in that directory
```

Works on colour (Basalt/Chalk/Emery) and 1-bit classic Pebbles alike. Purely local — no network, no
setup. The capture takes a couple of seconds.

## Watch logs & support bundle

Pull the watch's own firmware logs, or package a full diagnostic bundle for a bug report.

```sh
stoandl logs                         # → ./pebble-logs-<time>.txt  (the watch's firmware log)
stoandl logs /tmp/watch.txt          # a name you choose

stoandl support                      # → ./stoandl-support-<time>.tar.gz
stoandl support --coredump           # also pull a coredump off the watch, if it has one
```

The support bundle gathers the watch's firmware logs and metadata, the daemon log (`/tmp/stoandl*.log`),
the stoandl version, and your `stoandl.conf` **with secrets redacted** (CalDAV passwords and any
credentials/tokens in URLs). It's resilient: even with no daemon running or no watch connected it still
collects whatever it can, noting what's missing in `bundle-notes.txt`. Purely local — nothing is uploaded.
Review the archive before sharing: the watch logs can still contain personal data.

## Factory reset & recovery

Reset the connected watch — the companion to the firmware tooling, for un-bricking a bad flash
or wiping the watch for handoff.

```sh
stoandl reset recovery               # reboot the watch into recovery (PRF) firmware
stoandl reset factory                # wipe the watch back to out-of-box state (asks to confirm)
stoandl reset factory --yes          # …skip the confirmation prompt
```

`reset recovery` is recoverable — from PRF, reflash a normal firmware with `stoandl firmware sideload <file.pbz>`.
`reset factory` is **irreversible**: it erases all apps, settings and the host pairing (you'll re-pair
afterwards), so the CLI requires a typed `yes` unless you pass `--yes`. Both are local — nothing is
uploaded, and there's no config to set.

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

> The installed service + `stoandl` wrapper call `/usr/bin/java` / `java`, which **must be JDK 25**
> on the target (see [Requirements](#requirements)). The build runs on JDK 21; `install.sh` pins
> that automatically via `BUILD_JAVA_HOME` (default `/usr/lib/jvm/java-21-openjdk`). On Alpine /
> postmarketOS the `APKBUILD` handles both JDKs and writes absolute JDK-25 paths into the unit.

Or install manually:

```sh
sudo install -Dm644 build/libs/stoandl-*-all.jar /usr/lib/stoandl/stoandl.jar
sudo install -Dm644 packaging/stoandl.service /usr/lib/systemd/user/stoandl.service
systemctl --user daemon-reload
systemctl --user enable --now stoandl
```

## libpebble3

Depends on a patched fork of [coredevices/libpebble3](https://github.com/coredevices/libpebble3)
(the `stoandl` branch at [yoxcu/libpebble3](https://github.com/yoxcu/libpebble3)), included as a git
submodule at `libs/libpebble3`. Run `git submodule update --init --recursive` after cloning. Wired
via Gradle composite build — no separate Maven publish needed.
