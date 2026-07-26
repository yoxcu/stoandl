# Device Compatibility

stoandl connects BLE-native watches (Pebble 2 / Time 2) over **BLE**, and classic-era watches
(Pebble Time / Time Steel) over **Bluetooth Classic** (RFCOMM/SPP) — their reliable native transport.
The Classic transport is experimental (hardware-verified on a Time Steel) and on by default; see
[Bluetooth Classic](#bluetooth-classic) below to enable it.

## Watch support matrix

| Watch | Platform | Transport | Status |
|-------|----------|-----------|--------|
| Pebble Time 2 | EMERY | BLE | ✅ Works — pairing, bonded reconnect, notifications (confirmed) |
| Pebble 2 | DIORITE | BLE | ⚠️ Expected to work (same BLE-native class; untested) |
| Pebble Time / Time Steel | BASALT | Bluetooth Classic | ✅ Works over Classic — experimental, hardware-verified on a Time Steel (flaky over BLE) |
| Pebble Time Round | CHALK | Bluetooth Classic | ⚠️ Same class as BASALT (untested) |
| original Pebble / Pebble Steel | APLITE | Bluetooth Classic | ⚠️ Classic-only hardware (untested) |

**BLE-native watches (Pebble 2 / Time 2) connect over BLE. Classic-era watches (Time / Time Steel) connect over Bluetooth Classic — over BLE they're only best-effort (see below).**

## Why older watches are flaky over BLE

The PPoGATT data channel needs the **watch** to discover the phone's GATT service and subscribe
to it after pairing. On modern firmware (e.g. Pebble Time 2 on Core Devices PebbleOS) this
happens reliably. On the **old firmware of the Time / Time Steel** (e.g. Rebble `v4.4.3-rbl`),
the watch does this only intermittently: roughly 4 of 5 fresh pairs it bonds and encrypts but
never discovers/subscribes to the PPoGATT service, causing a timeout. This is a **race inside
the watch firmware** with no influence available from the phone side.

There's more to it than the discovery race: the watch pairs once over BLE, then hands the host its
**Classic** address and expects the persistent link over Bluetooth Classic. After a drop its BLE often
never comes back — BLE is effectively a dead end for these watches. Their reliable transport is
**Bluetooth Classic**, which stoandl now implements (see below).

**BLE workaround (if you can't use Classic):** forget the pairing on both watch and phone, then
re-pair; repeat until the data channel comes up (often a few tries).

Full diagnosis is in [`debug.md`](../debug.md).

## Bluetooth Classic

> **Experimental.** Works and is hardware-verified on a Pebble Time Steel, but it's newer and less
> battle-tested than the BLE path. On by default, but idle when no classic watch is paired.

Classic-era Pebbles (Pebble Time / Time Steel — and, by class, the original Pebble / Steel and Time
Round) connect reliably only over **Bluetooth Classic** (RFCOMM/SPP), their native transport. Their
BLE path is the firmware-side race above. stoandl can talk to these watches over Bluetooth Classic
directly; the BLE path is untouched, so BLE-native watches (Time 2 / Pebble 2) keep using BLE.

What works (hardware-verified on a Time Steel): discover → pair → connect → the full Pebble protocol →
automatic reconnect after the watch goes out of range or into airplane mode. The protocol layer is
transport-agnostic, so every feature in [features.md](features.md) — notifications, the locker, health,
datalog, calendar, music, firmware, … — works the same over Classic. The lone caveat is the battery
*level* read-out `stoandl watch battery`: it rides a BLE GATT service, so it's BLE-only — but the
richer `stoandl watch battery insights` ride the analytics heartbeat over datalog, which works over
Classic too.

How it works: the host is the RFCOMM **client**, dialing the watch's SPP service
(`00001101-0000-1000-8000-00805F9B34FB`) over a secure socket that reuses the stored link key.
Reconnect pages the watch's fixed address (no advertising, no BLE bootstrap), so it survives airplane
mode. An inquiry runs only while a pairing window (`stoandl watch pair`) is open; a bonded watch needs
no scan, so the radio stays quiet the rest of the time. The transport spans this repo and the
libpebble3 fork (the BlueZ RFCOMM socket via a `java.lang.foreign` (FFM) socket — no native library,
so it ships no glibc-only blob — native L2CAP SDP channel resolution, the scanner, and Classic pairing
over D-Bus).

### Using it

It's **on by default** (experimental) — no config needed. It stays idle when no classic watch is
paired (the BR/EDR inquiry runs only while a pairing window is open; a bonded watch reconnects by
paging its fixed address), so it costs nothing on a BLE-only setup. To turn it off, set
`classic.discover = false` in `~/.config/stoandl/stoandl.conf`.

Just pair as usual:

```sh
stoandl watch pair                 # opens a pairing window; inquires for BLE and classic watches alike
# then confirm the matching 6-digit code ON THE WATCH — stoandl auto-confirms on the host side
```

With `classic.discover` on, a known watch reconnects on its own afterwards: stoandl pages its fixed
address, so no advertising is needed and it survives airplane mode / out-of-range.

`connect`, `unpair [name]`, `repair` and `list` all work for classic watches just like BLE ones —
`unpair` forgets a classic watch by its address even while it's connected. Only one watch is connected
at a time; `stoandl watch connect <name>` hands the active slot to another known watch. (Only
`stoandl watch battery` differs — see the battery caveat above.)

> **Pairing falls back to manual?** Pairing a dual-mode watch occasionally creates an LE bond instead
> of the link key RFCOMM needs. If the watch pairs but won't connect, bond it explicitly with
> `btmgmt pair -t bredr <mac>`, then (re)start the daemon.

See [configuration.md](configuration.md#bluetooth-classic) for the config keys. The original design
notes (feasibility, work items, risks) are in [`bt-classic-scope.md`](../bt-classic-scope.md).

## Won't reconnect?

The most common cause is *another process running Bluetooth discovery* — an open Bluetooth settings
panel or pairing window (GNOME/KDE), or a stray `bluetoothctl scan on`. Discovery monopolizes the
adapter's single LE scanner, so BlueZ can't issue the watch's connection and it never links up. Check
with `bluetoothctl show | grep Discovering`; if it's `yes` and you didn't start it, close the scanner —
the watch reconnects within a second. stoandl logs a warning (and sends a desktop notification) when it
detects this.
