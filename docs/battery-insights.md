# Battery insights

The official Pebble Core app has a "Battery" screen — a cloud WebView (`<host>/m/battery`) gated behind
a Firebase account and the Memfault analytics opt-in, rendering insights the backend computes from
uploaded telemetry. stoandl has no cloud, so it **reimplements the insights locally** from data the
watch already sends.

## Two sources

| | `heartbeat` (primary) | `gatt` (fallback) |
|---|---|---|
| Data | soc (centi-%), voltage (mV), firmware's own time-to-empty, measured charge signal | battery level (integer %) |
| Source | analytics native-heartbeat over DataLogging (hourly) | BLE GATT 0x180F battery level (on change) |
| Transport | BLE **and** Bluetooth Classic | BLE only |
| Disconnects | backfilled (flash-buffered on the watch, drains on reconnect) | gaps (real-time only) |
| Config | `battery.heartbeat` | `battery.history` |
| Store | `~/.config/stoandl/battery/heartbeat/<serial>.ndjson` | `~/.config/stoandl/battery/<watchKey>.ndjson` |

The heartbeat is the source of truth. The read layer (`BatteryHistory`/`BatteryInsights`) uses it
whenever it has decoded data for a watch and falls back to the GATT series otherwise — so there's one
unified surface, no double-counting. The GATT series is the guaranteed-to-work baseline (it uses an API
stoandl already reads and hardware-proved via `stoandl watch battery`) for when the heartbeat hasn't
arrived yet or its layout can't be decoded.

Everything is **local-only** — nothing is uploaded (the official app forwards the same heartbeat blob
to its cloud; stoandl decodes it on-device instead).

## How the heartbeat capture works (zero fork change)

PebbleOS emits one `native_heartbeat_record` per hour over the DataLogging Service (system tag 87,
all-zero UUID), unconditionally on shipping firmware — no account or analytics opt-in required.
libpebble3 already routes those items to `WebServices.uploadAnalyticsHeartbeat`, which stoandl owns
(`StoandlWebServices` in `PebbleIntegration.kt`). It used to be a no-op that dropped the blob; now it
hands it to `HeartbeatStore`.

### Wire format

`struct PACKED native_heartbeat_record` (little-endian ARM, copied raw to the DLS byte array), verified
against `coredevices/PebbleOS@main` (`src/fw/services/analytics/native.c`,
`include/pbl/services/analytics/analytics.def`):

```
header (29 B):  version:u8 @0 (=1) | timestamp:u64 @1 | build_id:u8[20] @9
battery block:  soc_pct:u32 @102 (÷ scale:u16 @106 = 100  → percent)
                soc_pct_drop:u32 @108 (÷ scale @112 = 100)
                voltage:u32 @114 (÷ scale @118 = 1000  → volts)
                voltage_delta:i32 @120 (÷ scale @124 = 1000)
                tte_s:u32 @126 | charge_time_ms:u32 @130 | discharge_duration_ms:u32 @134
total sizeof = 523 B  (== one uploadAnalyticsHeartbeat payload)
```

The same 523-byte record carries 91 analytics metrics in total (the battery block is 7 of them). The
richer views below decode a further subset on demand (`HeartbeatStore.decodeActivity`, re-read from the
stored raw blob so it **backfills across existing history**):

```
subsystem on-time (TIMER, u32 ms):  backlight @138 | vibrator @146 | speaker @154 | hrm @174
                                    phone_call @314 | watchface @326 | bt_connected @515
intensity (u32 %):                  backlight @142 | vibrator_strength @150 | speaker_volume @162
cpu residency (SCALED %, ÷ scale @+4 = 100):  cpu_running @198
per-task cpu (SCALED %):            app @244 | worker @238 | kernel_main @226 | kernel_bg @232
                                    bt_host @250 | bt_controller @256 | bt_hci @262
event counts (u32):                 notification_received @302 | notification_received_dnd @306
                                    phone_call_incoming @310
```

The full 91-metric layout is in `analytics.def`; see the `stoandl-heartbeat-record-layout` note for the
complete offset map.

### Decode guard (defensive)

The blob layout is firmware-version-specific: any reordered/added metric in `analytics.def` shifts
every offset, and the header version byte does **not** necessarily change. So `HeartbeatStore` decodes
only when the layout is trusted — `size == 523 && version == 1`, the on-wire scale fields equal the
compile-time constants (100 / 1000), and the values are physically plausible (soc 0–100 %, voltage
3.0–4.5 V). On any mismatch it **captures the raw blob** (base64) + header (version, `build_id`,
timestamp) instead of emitting a guessed value, and logs a warning. Every record keeps its raw bytes,
so the file is a lossless local capture — an unrecognized firmware build can be finalized from a
`stoandl watch battery heartbeat --raw` dump against the DWARF layout tool
(`tools/analytics_heartbeat_layout.py`) for that exact build.

## CLI

```sh
stoandl watch battery                       # live level (unchanged)
stoandl watch battery insights [--watch N]  # summary: %, voltage, time-remaining, discharge, cycles
stoandl watch battery history [--since 24h] [--watch N]   # the sparkline series
stoandl watch battery activity [--since 24h] [--watch N]  # per-interval drop + notification counts
stoandl watch battery power [--since 24h] [--watch N]     # estimated battery-drain attribution (the "pie")
stoandl watch battery heartbeat [--watch serial] [--limit N] [--raw]   # decoded heartbeats (offline)
```

`history`/`insights`/`activity`/`power` are daemon-computed (the same data the GUI's Battery page reads
via `BatteryHistory`/`BatteryInsights`/`BatteryActivity`/`BatteryPower`). `heartbeat` reads the NDJSON
files directly, so it works with no daemon — use it to confirm B decodes on real hardware.

## Derived charts (GUI Battery page)

Three views the official cloud screen showed, rebuilt locally from the fields above — all read-side, no
new capture, and they backfill from already-stored records:

- **Battery drain (bar).** Per-interval SoC drop — the firmware's own `soc_pct_drop`, or the
  consecutive-sample delta for the GATT fallback. `BatteryActivity`.
- **What drew power (pie).** A battery-drain attribution donut: System (always-on floor), Display
  (backlight), Vibration, Speaker, Heart rate, Bluetooth, CPU. Built in two steps: **(1)** each
  subsystem's active time is weighted by an estimated average current (`MA_*` in `HeartbeatStore`) to
  turn on-time into charge drawn (on-time × intensity for analog loads, CPU-active fraction × interval
  for compute; Bluetooth uses BT-stack CPU time, not idle-connected time); **(2)** each interval's own
  measured `soc_pct_drop` is split across its subsystems by that weight, so the slices sum to the
  window's real measured drain (`estDrainPct`, percent of battery) and `sharePct` is each one's share.
  A window with no measured discharge falls back to the unanchored charge model (`estDrainPct = 0`).
  Still an **estimate**, not metered energy: the record carries no per-subsystem mAh, and the currents
  are representative constants — see the power-model note below. Heartbeat source only. `BatteryPower`.

  > **Power model (tunable estimate).** The record carries only on-time / CPU-residency, never energy,
  > so `power()` weights each subsystem by a representative average current (`MA_SYSTEM`, `MA_BACKLIGHT`,
  > `MA_VIBRATION`, `MA_SPEAKER`, `MA_HRM`, `MA_BLE`, `MA_CPU` in `HeartbeatStore`). Only the *ratios*
  > between them shape the pie — the absolute magnitude is pinned by the measured `soc_pct_drop` — and
  > getting them wrong only re-skews shares. The **`System` floor** (`MA_SYSTEM` × the whole interval)
  > is load-bearing: without it the always-on idle drain (MCU sleep, LCD retention) has nowhere to go
  > and is misattributed to whichever load has the longest on-time (usually the HRM). Set `MA_SYSTEM = 0`
  > to drop it. Calibrate the constants against a hardware `analytics native_metrics_dump` or the
  > official app's own breakdown for the same window; note per-model differences (Basalt/Chalk/Diorite/
  > Emery) aren't yet split out. **Caveat:** the `hrm` on-time offset (`@174`) is firmware-source-derived
  > and not hardware-verified — if the HRM slice still looks large after weighting, confirm `hrmMs` is
  > genuinely near-hour-scale (continuous HR) and not an offset slip via `native_metrics_dump`.
- **Notification overlay.** Faint bands on the % chart mark hours that received notifications
  (`notification_received_count`), denser where there were more — the battery/notification correlation
  the official combined graph hinted at. `BatteryActivity`.

## Config

```ini
battery.heartbeat = true      # primary source — decode the hourly analytics heartbeat
battery.history = true        # fallback source — log the BLE level on change
battery.retention_days = 90   # prune both stores past this window
```

Both default on and apply live (via `SetConfig`; the GUI exposes them as toggles). Turn `battery.history`
off to skip the redundant GATT series once you trust the heartbeat; turn `battery.heartbeat` off to
avoid persisting the analytics blob.

## Hardware verification (Strategy B)

The heartbeat layout is verified from firmware source but **not yet on hardware**. Confirm on the watch:

1. **Frames arrive.** After ~an hour connected, `stoandl watch battery heartbeat` should list records.
   (If none: is the watch on shipping firmware, not PRF? Are tag-87 DLS frames traversing the link?)
2. **They decode.** Records should show `decoded` values, not `UNDECODED`. An `UNDECODED (size=… version=…)`
   line means the running firmware's layout differs — grab `--raw` and re-derive offsets against the
   DWARF tool for that `build_id`.
3. **Values match.** The decoded soc / voltage should track what the watch itself reports. Charging
   (`charge_time_ms > 0`) should flip when it's on the charger.
4. **Richer fields sanity-check.** The `activity`/`power` offsets are verified from firmware source but
   not yet on hardware. `stoandl watch battery activity`/`power` should show plausible drops and a
   breakdown that moves with real usage (a heavy-notification hour, backlight-heavy use, etc.). The
   firmware console `analytics native_metrics_dump` prints key=value per metric for a direct cross-check;
   if offsets drift for a build, gate on its `build_id`.

Until confirmed, the GATT fallback keeps `insights`/`history` working, so the feature is never empty.
The `activity` drop also works on the GATT fallback (consecutive-sample delta); `power` needs the
heartbeat.
