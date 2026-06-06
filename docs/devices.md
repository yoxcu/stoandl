# Device Compatibility

stoandl is **BLE-only by design** — it targets battery-constrained Linux phones (postmarketOS),
where Bluetooth Classic's always-on RFCOMM link costs more power than BLE.

## Watch support matrix

| Watch | Platform | Status |
|-------|----------|--------|
| Pebble Time 2 | EMERY | ✅ Works — pairing, bonded reconnect, notifications (confirmed) |
| Pebble 2 | DIORITE | ⚠️ Expected to work (same BLE-native class; untested) |
| Pebble Time / Time Steel | BASALT | ⚠️ Best-effort — pairs, but the data channel comes up only intermittently (~1 in 5) |
| Pebble Time Round | CHALK | ⚠️ Same class as BASALT (untested; expect the same flakiness) |
| original Pebble / Pebble Steel | APLITE | ❌ Classic-only hardware — not supported |

**BLE-native watches (Pebble 2 / Time 2) work reliably. Older classic watches (Time / Time Steel) are best-effort over BLE.**

## Why older watches are flaky over BLE

The PPoGATT data channel needs the **watch** to discover the phone's GATT service and subscribe
to it after pairing. On modern firmware (e.g. Pebble Time 2 on Core Devices PebbleOS) this
happens reliably. On the **old firmware of the Time / Time Steel** (e.g. Rebble `v4.4.3-rbl`),
the watch does this only intermittently: roughly 4 of 5 fresh pairs it bonds and encrypts but
never discovers/subscribes to the PPoGATT service, causing a timeout. This is a **race inside
the watch firmware** with no influence available from the phone side.

Those watches' reliable transport is Bluetooth Classic, which stoandl deliberately doesn't
implement — see [`bt-classic-scope.md`](../bt-classic-scope.md) for the full design if anyone
ever wants to add it.

**Workaround:** forget the pairing on both watch and phone, then re-pair; repeat until the data
channel comes up (often a few tries).

Full diagnosis is in [`debug.md`](../debug.md).

## Bluetooth Classic scope

Bluetooth Classic is intentionally out of scope — it's the reliable transport for classic Pebbles
(APLITE/BASALT/CHALK), but stoandl targets battery-constrained phones where the always-on RFCOMM
link costs more power than BLE. Full design notes (feasibility, work items, risks) are in
[`bt-classic-scope.md`](../bt-classic-scope.md).
