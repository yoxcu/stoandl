# Device Compatibility

stoandl connects BLE-native watches (Pebble 2 / Time 2) over **BLE**, and classic-era watches
(Pebble Time / Time Steel) over **Bluetooth Classic** (BR/EDR) — their reliable native transport.
The Classic transport is experimental (hardware-verified on a Time Steel) and off by default; see
[Bluetooth Classic](../README.md#bluetooth-classic-classic-era-watches) in the README to enable it.

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
**Classic** address and expects the persistent link over BR/EDR. After a drop its BLE often never
comes back — BLE is effectively a dead end for these watches. Their reliable transport is **Bluetooth
Classic**, which stoandl now implements (see below).

**BLE workaround (if you can't use Classic):** forget the pairing on both watch and phone, then
re-pair; repeat until the data channel comes up (often a few tries).

Full diagnosis is in [`debug.md`](../debug.md).

## Bluetooth Classic

stoandl now has an **experimental Bluetooth Classic (BR/EDR, RFCOMM/SPP) transport** for classic-era
Pebbles — hardware-verified on a Pebble Time Steel: discover → pair → connect → the full Pebble
protocol → automatic reconnect after out-of-range / airplane mode. It's off by default and additive:
BLE-native watches are unaffected.

How it works: the host is the RFCOMM **client**, dialing the watch's SPP service
(`00001101-0000-1000-8000-00805F9B34FB`) over a secure socket that reuses the stored BR/EDR link key.
Reconnect pages the watch's fixed address (no advertising, no BLE bootstrap), so it survives airplane
mode. A BR/EDR inquiry runs only while a pairing window (`stoandl pair`) is open; a bonded watch needs
no scan. The transport spans this repo and the libpebble3 fork (BlueZ RFCOMM socket via a
`java.lang.foreign` (FFM) `AF_BLUETOOTH` socket — no native library, so it ships no glibc-only blob —
the BR/EDR scanner, and Classic pairing over D-Bus).

See [Bluetooth Classic](../README.md#bluetooth-classic-classic-era-watches) in the README to enable
it and [docs/configuration.md](configuration.md#bluetooth-classic) for the config keys. The original
design notes (feasibility, work items, risks) are in [`bt-classic-scope.md`](../bt-classic-scope.md).
