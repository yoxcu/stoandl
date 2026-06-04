# BLE Bonded Reconnect Debug Summary

## Problem
Pebble watch (Time Steel, identity addr `B0:B4:48:B6:1E:81`) connects fine on fresh pairing
but fails on every bonded reconnect with `TimeoutInitializingPpog`.

Architecture: `reversedPPoG=false` — phone is BLE peripheral (GATT server), watch is GATT client.
Phone exposes PPoG characteristic; watch writes to it and subscribes for notifications (CCCD).

## Root cause
On bonded reconnect the watch sends **zero** WriteValue or StartNotify calls — confirmed by logs
(no `WriteValue: X bytes` or `StartNotify` lines between GATT discovery and timeout).
The watch trusts the peripheral to restore its CCCD subscription.

BlueZ does **not** restore CCCD for externally-registered GATT apps (`RegisterApplication`) on
reconnect. Without CCCD active, BlueZ silently drops our `PropertiesChanged` notifications
(RESET_REQUEST never reaches the watch → 10s timeout → `TimeoutInitializingPpog`).

## Dead ends (do not revisit)

### CCC file approach
Tried writing `/var/lib/bluetooth/<hci>/<device>/ccc` with CCCD handle range 0x000a–0x1000.
**Failed:** BlueZ ignores CCC file entries for external `RegisterApplication` GATT objects.
The file mechanism only works for BlueZ's own internal characteristics (e.g. Service Changed).
Even manually copying the file to the identity-address directory did nothing.

### setfacl / permissions
`bluetooth.service` has `ProtectSystem=strict` + `StateDirectoryMode=0700`.
`ExecStartPost` drop-ins run in the service's mount namespace — setfacl changes don't persist
to the host view. A separate `bluetooth-acl.service` also had timing issues with bluetoothd
creating device dirs with explicit `chmod(0700)` during pairing. Moot anyway since CCC files
don't work for external apps.

### Passive wait for StartNotify
Tried waiting 3s / 15s for watch to call StartNotify on its own. Watch never does — it
expects notifications to be "already on" via CCCD restoration.

## What works: reAddServices → Service Changed

`gattServerManager.reAddServices()` (UnregisterApplication + RegisterApplication) causes BlueZ
to send a **Service Changed** indication to the watch. The watch responds by re-discovering
our GATT service and re-writing the CCCD descriptor, which causes BlueZ to call `StartNotify`
on our application. After that, `notifySubscribed=true` and PropertiesChanged is delivered.

Evidence in `good.log`: reAddServices at 08:55:54, StartNotify at 08:55:56 (~2s later) → full
PPoG session established, data flowing.

## Current code state

**`PebbleBle.kt`** (both paths now call reAddServices when `!notifySubscribed`):

```kotlin
// Bonded reconnect path:
if (!gattServerManager.notifySubscribed) {
    gattServerManager.reRegisterApplication()
    val subscribed = gattServerManager.waitForSubscription(NOTIFY_SUBSCRIBE_TIMEOUT)
    logger.d { "alreadyBonded: notifySubscribed=$subscribed" }
}
phoneInitiatesReset = true

// After fresh pairing path (same pattern):
if (!gattServerManager.notifySubscribed) {
    gattServerManager.reRegisterApplication()
    val subscribed = gattServerManager.waitForSubscription(NOTIFY_SUBSCRIBE_TIMEOUT)
    logger.d { "after pairing: notifySubscribed=$subscribed" }
}
phoneInitiatesReset = false

companion object {
    private val CONNECTIVITY_UPDATE_TIMEOUT = 10.seconds
    private val NOTIFY_SUBSCRIBE_TIMEOUT = 5.seconds  // was 3s
}
```

**Important caveat**: reAddServices changes GATT handle numbers on every call. There was
previously a "death spiral" where handles kept changing and the watch could never establish
a stable CCCD. The current approach avoids this by calling reAddServices **once per
connection attempt** and then waiting for StartNotify. The watch updates its handle cache
via the re-discovery triggered by Service Changed.

## Current status

- Initial pairing: **confirmed working** (good.log shows full PPoG session)
- Bonded reconnect: **NOT YET TESTED** — the mechanism is coded but no successful bonded
  reconnect log exists yet. This is the next thing to test.

## Key files
- `libs/libpebble3/libpebble3/src/commonMain/kotlin/.../pebble/PebbleBle.kt` — connection flow
- `libs/libpebble3/libpebble3/src/jvmMain/kotlin/.../transport/GattServer.jvm.kt` — GATT server,
  `reAddServices()`, `StartNotify()`, `sendData()`
- `good.log` — successful initial pairing session log
- Run with: `./gradlew run` (see CLAUDE.md for required JVM flags)
- **Always ask before running** — user requirement
