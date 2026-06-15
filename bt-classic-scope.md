# Scope: Bluetooth Classic (BR/EDR) support for stoandl (JVM/Linux, BlueZ)

> **Status: IMPLEMENTED (experimental).** This was the original design doc; the transport now exists
> and is hardware-verified on a Pebble Time Steel (since v0.4.0). For how to use it see
> [Bluetooth Classic](README.md#bluetooth-classic-classic-era-watches) in the README and
> [docs/devices.md](docs/devices.md); this file is kept as the design record. Where the
> implementation diverged from the plan below: the RFCOMM socket is obtained via a **JNA
> AF_BLUETOOTH socket** (not `Profile1`/`ConnectProfile` FD-passing, which BlueZ rejects for an
> external client), the channel is resolved via a native **L2CAP SDP** query, and pairing is done
> **up-front** during the pairing window rather than inside the connect attempt.

Goal: reliably support the classic-primary Pebbles (Pebble Time, **Time Steel**, original Pebble/Steel)
whose BLE path is a firmware-side ~1/5 race. BT Classic (RFCOMM/SPP) is their native, reliable transport.

## Feasibility verdict: MEDIUM, well-bounded

- The architecture is **already scaffolded** in libpebble3 commonMain, with a **complete Android
  reference** to copy from.
- Over Classic there is **no PPoGATT** — the RFCOMM byte stream plugs *directly* into the Pebble
  protocol layer. Conceptually simpler than the BLE path (no PPoG state machine, no GATT-discovery race).
- The one novel hard piece — getting an RFCOMM socket FD from BlueZ on the JVM — is **feasible**:
  dbus-java 5.2.0 + native-unixsocket transport supports FD passing (`Profile1.NewConnection(fd)`).

## How it plugs in (already wired in commonMain)

- Transport routing exists: `LibPebbleModule.kt` maps `PebbleBtClassicIdentifier → PebbleBtClassic
  (TransportConnector) → BtClassicConnector`.
- `PebbleBtClassic` + `BtClassicConnector` interface exist; **the JVM `BtClassicConnector` is missing.**
- Data boundary: `PebbleProtocolStreams { inboundPPBytes, outboundPPBytes }`. The connector pumps the
  RFCOMM socket into/out of these (exactly like Android's `AndroidBtClassicConnector`, lines ~55-75).
- Pebble Classic uses the **standard SPP UUID `00001101-0000-1000-8000-00805F9B34FB`** (not custom).
- Discovery name filter (from Android): `^Pebble(?: Time(?: Le)?)? [0-9A-Fa-f]{4}$`.

## The BlueZ RFCOMM mechanism (the core new code)

1. Export an `org.bluez.Profile1` object; `ProfileManager1.RegisterProfile(path, SPP_UUID, {Role:"client", ...})`.
2. Trigger the link: `Device1.ConnectProfile(SPP_UUID)` on the target device (or rely on auto-connect).
3. BlueZ establishes RFCOMM and calls our `Profile1.NewConnection(device, fd, props)` with a **Unix FD**.
4. Convert the dbus-java `FileDescriptor` → `java.io.FileDescriptor` → `FileInputStream`/`FileOutputStream`.
5. Pump: socket→`inboundPPBytes`, `outboundPPBytes`→socket (two coroutines, Dispatchers.IO), as Android does.
6. Disconnect on fd EOF / `Device1` Connected=false / `Profile1.RequestDisconnection`.

## Work items (JVM/BlueZ)

| # | Item | Where | Size | Notes |
|---|------|-------|------|-------|
| 1 | `PebbleBtClassicIdentifier` actual + `String.asPebbleBtClassicIdentifier()` | `PebbleDevice.jvm.kt` | S | wrap MAC / `/org/bluez/hciX/dev_..` path (mirror BLE identifier) |
| 2 | Classic pairing actuals: `isBondedClassic`, `createBondClassic`, `getBluetoothClassicDevicePairEvents` | `Pairing.jvm.kt` | S–M | adapt the existing BLE `createBond`/pair-events code (Device1.Pair / Paired / PropertiesChanged). Pairing **agent already exists** (BluezPairingAgent). |
| 3 | `BluezClassicScanner : ClassicScanner` | new jvm file | M | adapter `StartDiscovery` (BR/EDR), enumerate via ObjectManager/InterfacesAdded, filter by name regex, emit `PebbleScanResult` |
| 4 | `BluezBtClassicConnector : BtClassicConnector` | new jvm file | **M–L** | the RFCOMM/Profile1/FD core above; the main effort + uncertainty |
| 5 | DI wiring | `LibPebbleModule.jvm.kt` | S | bind `BtClassicConnector`, real `ClassicScanner`, `supportsBtClassic = true` |
| 6 | stoandl integration | `PebbleIntegration.kt` | S | also start classic discovery in the scan loop; autoconnect already handles identifiers generically |

## Key risks / unknowns

1. **FD → Java stream plumbing** (medium, solvable): converting dbus-java's `FileDescriptor` to a usable
   stream may need a helper/reflection and possibly another `--add-opens`. This is the spike to do first.
2. **LE-only vs Classic adapter conflict (the biggest practical tension):** BT Classic requires BR/EDR
   **enabled** on the adapter, but the **BLE-native watches (Time 2) currently need LE-only mode**
   (`ControllerMode=le`) to dodge the dual-mode-advertising classic-page bug. On one adapter you can't
   have both until the **upstream PebbleOS advertising-flag fix** (see `issue.md`) lands on the BLE-native
   watches. Options: (a) land that firmware fix → run BR/EDR enabled → support both; (b) a second BT
   adapter dedicated per transport; (c) one transport per deployment.
3. **Profile/connection direction:** handle both phone-initiated (`ConnectProfile`) and watch-initiated
   (incoming `NewConnection`) cases.
4. **Classic SSP pairing method:** our `DisplayYesNo` agent should cover BR/EDR pairing too, but verify
   (BR/EDR may use `RequestConfirmation`/`RequestPasskey` differently).
5. **Discovery power/timing:** the daemon currently only LE-scans; need BR/EDR inquiry without disrupting
   BLE.

## Effort estimate

~**3–5 focused days** for someone comfortable with BlueZ/D-Bus:
- Items 1,2,5,6: ~1 day total.
- Item 3 (scanner): ~0.5–1 day.
- Item 4 (connector + FD plumbing + debugging): ~1.5–2 days (the real variable).
- Integration + on-hardware testing: ~1 day.

## Recommended sequence

1. **Spike the FD plumbing first** (highest risk): minimal `RegisterProfile(SPP)` + `ConnectProfile` to the
   Time Steel, receive the fd in `NewConnection`, and print bytes. If bytes flow, the rest is mechanical.
2. Identifier (1) → pairing (2) → scanner (3) → full connector (4) → DI (5) → stoandl (6).
3. Decide the LE-only-vs-classic adapter strategy (risk 2) before shipping both transports.

## Notes

- Classic watches: APLITE/BASALT/CHALK (`WatchType.supportsBtClassic() == true`). `LegacyBtClassicMigrator`
  already exists to move these to the classic transport in the DB.
- This is purely additive to the JVM target; BLE-native support (Time 2 / Pebble 2) is unaffected except
  for the adapter-mode tension in risk 2.
