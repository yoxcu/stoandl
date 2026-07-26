<p align="center"><img src="docs/stoandl-icon.png" alt="stoandl" width="120"></p>

# stoandl

> **⚠️ Work in progress — but broad.** Most of what the Android/iOS Pebble companion apps do is
> already here — [docs/features.md](docs/features.md) has the full list. Some of it is verified on
> real hardware, some is written but not yet hardware-tested; the feature list marks which, and lists
> the remaining gaps. Expect occasional rough edges and breaking changes.

> Built with heavy assistance from [Claude](https://claude.ai) (Anthropic's AI).

Headless Pebble smartwatch companion app / bridge for Linux / postmarketOS — a background
daemon that bridges your Linux desktop to a Pebble watch over Bluetooth: **BLE** for modern watches
(Pebble 2 / Time 2) and **Bluetooth Classic** for classic-era ones (Pebble Time / Time Steel …).

*Stoandl* is Bavarian dialect for "Steinchen" (little stone / pebble).

## What it does

At its core stoandl forwards your desktop notifications (`org.freedesktop.Notifications`) to the
watch — but it does most of what the official Android/iOS Pebble companion apps do: the locker,
weather, music, calendar, firmware flashing, language packs, health sync, battery insights and
screenshots. All headless — from the CLI, with no phone, no account and no UI.

It's also **extensible**: an [extension system](docs/extensions.md) lets you plug in your own
host-side integrations — Matrix, Signal, SMS, "find my phone", anything — in any language, driving
watch notifications (with replies and actions) and optionally shipping their own watchapp. A Matrix
client and a find-my-phone example ship in the box.

→ **[docs/features.md](docs/features.md)** — the full feature list, each with its CLI usage, a
comparison with the other companion apps, and what's not yet implemented.

## Desktop / mobile GUI (optional)

The daemon is fully headless — but there's also an optional **[Kirigami](https://develop.kde.org/frameworks/kirigami/)
GUI** ([`gui/`](gui/), a separate repo: [yoxcu/stoandl-gui](https://github.com/yoxcu/stoandl-gui)).
It's a pure D-Bus client of the `de.yoxcu.stoandl.Control` interface — no code shared with the daemon —
and is **convergent**: the same app runs on a KDE/GNOME desktop and on Plasma Mobile. Five screens —
**Watch, Health, Apps, Alerts, Settings** — cover pairing, firmware updates, the locker + extensions,
health charts, battery insights, notification rules, calendars and the full `stoandl.conf`.

**Screenshots, build & install:** see the GUI repo's README — it has
[screenshots](https://github.com/yoxcu/stoandl-gui#screenshots), the native Qt6
[build](https://github.com/yoxcu/stoandl-gui#build), and **Flatpak** / postmarketOS `.apk`
[releases](https://github.com/yoxcu/stoandl-gui/releases).

## Compatibility

BLE-native watches (Pebble 2 / Time 2) connect over **BLE** and work reliably. Classic-era watches
(Pebble Time / Time Steel) are flaky over BLE — their reliable native transport is **Bluetooth
Classic**, which stoandl supports as an experimental transport.

| Watch | Platform | Transport | Status |
|-------|----------|-----------|--------|
| Pebble Time 2 | EMERY | BLE | ✅ Works |
| Pebble 2 | DIORITE | BLE | ⚠️ Expected to work (untested) |
| Pebble Time / Time Steel | BASALT | Bluetooth Classic | ✅ Works — experimental, hardware-verified on a Time Steel (flaky over BLE) |
| Pebble Time Round | CHALK | Bluetooth Classic | ⚠️ Same class as BASALT (untested) |
| original Pebble / Pebble Steel | APLITE | Bluetooth Classic | ⚠️ Classic-only hardware (untested) |

→ [docs/devices.md](docs/devices.md) — root causes, workarounds, transport details, and how to
enable the Bluetooth Classic transport.

## Requirements

- **JDK 25 to run.** The Bluetooth Classic transport reaches the kernel through `java.lang.foreign`,
  finalized in JDK 22. Make sure `/usr/bin/java` (and `java` on `PATH`) is JDK 25 on the machine that
  runs the daemon — e.g. Arch: `archlinux-java set java-25-openjdk`.
- **JDK 21 to build** (in addition to 25). Gradle 8.14 cannot run on JDK 25, so the build runs on 21
  while the Kotlin toolchain compiles against 25 — install both; see [Build](#build).
- BlueZ (bluetoothd running) — the default dual-mode adapter is fine, no special configuration needed.
- D-Bus session bus with a notification daemon (dunst, mako, GNOME, etc.)

> BLE is driven entirely through BlueZ over D-Bus, and the Bluetooth Classic transport reaches the
> kernel via `java.lang.foreign` (no JNI/JNA) — so stoandl loads **no glibc-only native library at
> runtime** and runs on **musl** (postmarketOS / Alpine) as well as glibc distros. (The fat JAR still
> *bundles* kable's btleplug/JNA blobs as an unused transitive dependency — kable's types back the
> shared BLE abstraction, but its native backend is never instantiated on JVM/Linux, which uses
> BlueZ.) BLE is verified end-to-end (pair, connect, notifications) on a glibc laptop and a OnePlus 6
> running postmarketOS; the FFM Classic path is musl-clean by construction but not yet
> hardware-verified on musl.

## Build

After cloning, pull in the libpebble3 fork (a git submodule wired via Gradle composite build — no
separate Maven publish needed):

```sh
git submodule update --init --recursive
```

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

**Installing the postmarketOS `.apk`.** A tagged release also ships an aarch64 pmOS `.apk` and the
public signing key (`mick@yoxcu.de-*.rsa.pub`). Trust the key once and `apk add` installs it — and
every future release — without `--allow-untrusted`:

```sh
# from the release page, download the .apk and the matching .rsa.pub, then:
doas cp mick@yoxcu.de-*.rsa.pub /etc/apk/keys/   # trust the signing key (once)
doas apk add ./stoandl-*.apk
```

(Or skip the key with `doas apk add --allow-untrusted ./stoandl-*.apk`.)

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

On first connection the watch shows a **6-digit code**; confirm it on the watch and stoandl
auto-accepts on the Linux side. See the [pairing feature](docs/features.md#ble-pairing--bonding) for
`stoandl watch pair` and friends.

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

## Logging & reporting bugs

Logs go to `/tmp/stoandl.log` (rolling, 5 MB × 3) and stdout. Default level is INFO.

**Filing a bug? Run with debug logging and attach the log** — a `STOANDL_LOG=DEBUG` trace (full
BLE/protocol packets, PPoG handshake, PKJS lifecycle) is by far the most useful thing you can include:

```sh
STOANDL_LOG=DEBUG ./gradlew run
```

For the systemd service, add to the unit or a drop-in (`install.sh -d` does this for you):

```ini
[Service]
Environment=STOANDL_LOG=DEBUG
```

Then reproduce the problem and grab `/tmp/stoandl.log`. `stoandl support` packages that log together
with watch info and a **secret-redacted** copy of your config into a `.tar.gz` ready to attach — see
[the support bundle](docs/features.md#watch-logs--support-bundle). Review the archive before sharing:
the watch logs can still contain personal data.
