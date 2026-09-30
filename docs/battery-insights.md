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

`struct PACKED native_heartbeat_record` (little-endian ARM, copied raw to the DLS byte array), from
PebbleOS `src/fw/services/analytics/native.c`: a 29 B header, then every metric of
`include/pbl/services/analytics/analytics.def` in declaration order. Which metrics a release emits —
and so every offset — changes between releases; `HeartbeatLayout.kt` holds the metric table and
derives the offsets for each released layout, and stoandl reads every field **by name** through it.
On current firmware (≥ 4.33.0) the record is **567 B / version 3 with 101 metrics**:

```
header (29 B):  version:u8 @0 (=3) | timestamp:u64 @1 | build_id:u8[20] @9
battery block:  soc_pct:u32 @102 (÷ scale:u16 @106 = 100  → percent)
                soc_pct_drop:u32 @108 (÷ scale @112 = 100)
                voltage:u32 @114 (÷ scale @118 = 1000  → volts)
                voltage_delta:i32 @120 (÷ scale @124 = 1000)
                tte_s:u32 @126 | charge_time_ms:u32 @130 | discharge_duration_ms:u32 @134
total sizeof = 567 B  (== one uploadAnalyticsHeartbeat payload)
```

The battery block is 7 of the 101 metrics. The richer views below read a further subset on demand
(`HeartbeatStore.decodeActivity`, re-read from the stored raw blob so it **backfills across existing
history**). Offsets on fw ≥ 4.20 (523, 527 and 567 B layouts):

```
subsystem on-time (TIMER, u32 ms):  backlight @138 | vibrator @146 | speaker @154 | hrm @174
                                    phone_call @314 | watchface @326
                                    bt_connected @515 (@519 in 527 B / v1)
intensity (u32 %):                  backlight @142 | vibrator_strength @150 | speaker_volume @162
cpu residency (SCALED %, ÷ scale @+4 = 100):  cpu_running @198
per-task cpu (SCALED %):            app @244 | worker @238 | kernel_main @226 | kernel_bg @232
                                    bt_host @250 | bt_controller @256 | bt_hci @262
event counts (u32):                 notification_received @302 | notification_received_dnd @306
                                    phone_call_incoming @310
```

The complete offset map for all metrics — every released layout, with notes on which are worth
decoding next — is in **[heartbeat-metrics.md](heartbeat-metrics.md)**.

### Decode guard (defensive)

The blob layout is firmware-release-specific: any added metric in `analytics.def` shifts every offset
**after it**, and the header version byte does **not** necessarily change. So `HeartbeatStore` decodes
only when the layout is trusted — `(size, version)` names a released layout in `HeartbeatLayouts`,
the on-wire scale fields equal the compile-time constants (100 / 1000), and the values are physically
plausible (soc 0–100 %, voltage 3.0–4.5 V). On any mismatch it **captures the raw blob** (base64) +
header (version, `build_id`, timestamp) instead of emitting a guessed value, and logs a warning. Every
record keeps its raw bytes, so the file is a lossless local capture — a new firmware layout is added
from PebbleOS source with `tools/hb_layouts_from_source.py`, or, without the source, recovered offline
from records already on disk with `tools/hb_relayout_probe.py`.

**Because the raw bytes are always kept, adding a layout is retroactive**: `readDecoded()` re-decodes
any row stored with `decoded=false`, and the activity views re-read every row's raw blob, so records
stranded by an unknown layout recover on the next read — no file rewrite or migration.

Known layouts — every release tag's `analytics.def`, walked to its record size (the full list is in
[heartbeat-metrics.md](heartbeat-metrics.md#layout-versioning--read-this-before-adding-offsets)). Records
are decoded from fw 4.10.0 on: 4.9.x firmware never actually packed the record, so its naturally aligned
records are kept raw only:

| size | version | first release | notes |
| ---- | ------- | ------------- | ----- |
| 507–515 B | 1 | 4.10.0 … 4.13.0 | two early layouts; the battery block sits at @94 and @102, so they decode only because every field is read by name |
| 523 B | 1 | 4.20.0 | 91 metrics |
| 527 B | 1 | 4.26.0 (PebbleOS `31e3ea8e1`, 2026-07-14) | inserted `ppog_reversed` @467 (92 metrics). Of the fields the views read, only `connectivity_connected_time_ms` moves: 515 → 519 |
| 523 B | 2 | 4.32.0 (PebbleOS `5ef38b9e9`, 2026-07-22) | removed `settings_power_mode` and bumped the version byte; back to 523 B with a different tail than 523 B / v1 |
| **567 B** | **3** | **4.33.0** (through 4.38.2) | appended ten metrics (101); nothing the views read moved from 523 B / v2 |

⚠️ **523 B/v1 and 523 B/v2 are the same size with different tail layouts** — which is exactly why
upstream bumped the version byte, and why `HeartbeatLayouts` is keyed on `(size, version)` rather than
size alone. Never decode one with the other's offsets.

### Unknown layouts still yield battery insights

A record whose `(size, version)` is not in the table is **not** discarded. The battery block sits near
the front of `analytics.def` (since 4.13 every metric has been added behind it) and is
*self-describing*: every scaled metric carries its `u16` scale immediately after the value. So
`decode()` reads an unknown layout at the newest known layout's battery offsets and lets it prove
itself there — both scale pairs must equal their compile-time constants (100/100, 1000/1000), soc and
voltage must be physically plausible, and `charge_ms + discharge_ms` must be ≈ the one-hour reporting
interval. Seven independent structural constraints holding at once is verification rather
than a guess; failing any one still refuses and captures the raw blob. The event is logged once per
unknown `(size, version)` at INFO.

`decodeActivity()` deliberately does **not** do this: its fields extend past the region that moves
(e.g. `connectivity_connected_time_ms`), so they cannot be validated in place. The power/activity view
therefore stays strict and simply goes quiet until the layout is added — run
`tools/hb_layouts_from_source.py` on the PebbleOS source and add the lines it prints (history
backfills itself). This is what kept the views dark on fw 4.33.0 through 4.38.2.

## CLI

```sh
stoandl watch battery                       # live level (unchanged)
stoandl watch battery insights [--watch N]  # summary: %, voltage, time-remaining, discharge, cycles
stoandl watch battery history [--since 24h] [--watch N]   # the sparkline series
stoandl watch battery activity [--since 24h] [--watch N]  # per-interval drop + notification counts
stoandl watch battery power [--since 24h] [--watch N]     # estimated battery-drain attribution (the "pie")
stoandl watch battery heartbeat [--watch serial] [--limit N] [--raw]   # decoded heartbeats (offline)
stoandl watch battery heartbeat --all [--watch serial]  # every metric of the newest record (offline)
```

`history`/`insights`/`activity`/`power` are daemon-computed (the same data the GUI's Battery page reads
via `BatteryHistory`/`BatteryInsights`/`BatteryActivity`/`BatteryPower`). `heartbeat` reads the NDJSON
files directly, so it works with no daemon — use it to confirm B decodes on real hardware. `--all` is
the same full-record view the GUI's **Debug → Heartbeat** page shows (via `HeartbeatInfo` /
`HeartbeatMetrics`).

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
  > to drop it. Calibrate the constants against a hardware `analytics native metrics_dump` or the
  > official app's own breakdown for the same window; note per-model differences (Basalt/Chalk/Diorite/
  > Emery) aren't yet split out. **Caveat:** the `hrm` on-time offset (`@174`) is firmware-source-derived
  > and not hardware-verified — if the HRM slice still looks large after weighting, confirm `hrmMs` is
  > genuinely near-hour-scale (continuous HR) and not an offset slip via `analytics native metrics_dump`.
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
   line means the running firmware's layout differs — grab `--raw`, note `size`/`version`, and add the
   layout from that release's `analytics.def` (`tools/hb_layouts_from_source.py`).
   `stoandl watch battery heartbeat --all` should list every metric (101 on fw ≥ 4.33), not "not in
   the verified table".
3. **Values match.** The decoded soc / voltage should track what the watch itself reports. Charging
   (`charge_time_ms > 0`) should flip when it's on the charger.
4. **Richer fields sanity-check.** The `activity`/`power` offsets are verified from firmware source but
   not yet on hardware. `stoandl watch battery activity`/`power` should show plausible drops and a
   breakdown that moves with real usage (a heavy-notification hour, backlight-heavy use, etc.). The
   firmware console `analytics native metrics_dump` prints key=value per metric for a direct cross-check;
   if offsets drift for a build, gate on its `build_id`.

Until confirmed, the GATT fallback keeps `insights`/`history` working, so the feature is never empty.
The `activity` drop also works on the GATT fallback (consecutive-sample delta); `power` needs the
heartbeat.
