# Analytics heartbeat — full metric map

Every hour PebbleOS logs one `native_heartbeat_record` over the DataLogging Service (system tag 87,
all-zero UUID). `HeartbeatStore` persists the **entire blob** as base64 in
`<config>/battery/heartbeat/<serial>.ndjson`, so **all** of the metrics below are already captured on
disk — decoding more of them is pure read-side work (no fork change, no new capture) and applies
**retroactively to existing history**.

[battery-insights.md](battery-insights.md) covers the 7 battery fields and the power model; this file
is the complete map.

## Provenance

Derived by parsing `include/pbl/services/analytics/analytics.def` in
[`coredevices/PebbleOS`](https://github.com/coredevices/PebbleOS) — the X-macro list that
`src/fw/services/analytics/native.c` expands into `struct PACKED native_heartbeat_record`. Offsets are
computed, not guessed: header is `version:u8 @0 | timestamp:u64 @1 | build_id:u8[20] @9` (29 B), then
metrics in declaration order with `UNSIGNED`/`SIGNED`/`TIMER` = 4 B, `SCALED_*` = value 4 B + `u16`
scale, `STRING(len)` = `len + 1`.

To regenerate after a firmware bump: fetch `analytics.def` at that build's revision and re-run the
walk above (`tools/hb_relayout_probe.py` recovers offsets empirically from stored raw blobs when the
source isn't available — the record's `build_id` is a GNU build-id, **not** a git SHA, so it cannot be
looked up directly).

## Layout versioning — read this before adding offsets

The record is **not** fixed. Adding a metric to `analytics.def` shifts every field after it, and the
version byte does not necessarily change:

| size | version | firmware | change |
| ---- | ------- | -------- | ------ |
| 523 B | 1 | before 2026-07-14 | 91 metrics — the original reference layout |
| 527 B | 1 | `31e3ea8e1` (2026-07-14) | **+`ppog_reversed` @467** → 92 metrics, **same version byte** |
| 523 B | 2 | `5ef38b9e9` (2026-07-22) | **−`settings_power_mode`** (was @487) → 91 metrics, version bumped to 2 |

Two consequences that have already bitten us:

1. **523 B/v1 and 523 B/v2 are the same size with different tail layouts.** Size alone is not a safe
   discriminator — `HeartbeatStore.LAYOUTS` is keyed on `(size, version)`.
2. The 2026-07-14 growth silently stranded every record for days: the decoder required `size == 523`,
   so insights kept replaying the last good decode (reporting 59 % while the watch was at 15 %). Fields
   **below the first insertion point are stable**, which is why the battery block survived untouched.

Practical rule: everything **below @467** has been stable across all three layouts. Everything **at or
above @467** (the BLE disconnect counters, `settings_*`, `app_*`, `connectivity_*`) moves between
layouts and must be read through the `LAYOUTS` table.

## The metrics

92 metrics in the 527 B/v1 layout (your firmware if you updated after 2026-07-14). Offsets are given
for all three known layouts; `—` means the metric does not exist in that layout. ✅ marks the 27
stoandl currently decodes.

| # | metric | type | 523/v1 | **527/v1** | 523/v2 | decoded |
|---|--------|------|-------:|-----------:|-------:|:-------:|
| 1 | `memory_pct_max` | u32 | 29 | **29** | 29 |  |
| 2 | `memory_largest_free_pct` | u32 | 33 | **33** | 33 |  |
| 3 | `stack_free_kernel_main_bytes` | u32 | 37 | **37** | 37 |  |
| 4 | `stack_free_kernel_background_bytes` | u32 | 41 | **41** | 41 |  |
| 5 | `stack_free_newtimers_bytes` | u32 | 45 | **45** | 45 |  |
| 6 | `stack_free_app_syscall_bytes` | u32 | 49 | **49** | 49 |  |
| 7 | `stack_free_worker_syscall_bytes` | u32 | 53 | **53** | 53 |  |
| 8 | `utc_offset_s` | i32 | 57 | **57** | 57 |  |
| 9 | `fw_version` | char[33] | 61 | **61** | 61 |  |
| 10 | `last_reboot_reason` | u32 | 94 | **94** | 94 |  |
| 11 | `uptime_s` | u32 | 98 | **98** | 98 |  |
| 12 | `battery_soc_pct` | u32+scale | 102 | **102** | 102 | ✅ |
| 13 | `battery_soc_pct_drop` | u32+scale | 108 | **108** | 108 | ✅ |
| 14 | `battery_voltage` | u32+scale | 114 | **114** | 114 | ✅ |
| 15 | `battery_voltage_delta` | i32+scale | 120 | **120** | 120 | ✅ |
| 16 | `battery_tte_s` | u32 | 126 | **126** | 126 | ✅ |
| 17 | `battery_charge_time_ms` | u32 ms | 130 | **130** | 130 | ✅ |
| 18 | `battery_discharge_duration_ms` | u32 ms | 134 | **134** | 134 | ✅ |
| 19 | `backlight_on_time_ms` | u32 ms | 138 | **138** | 138 | ✅ |
| 20 | `backlight_avg_intensity_pct` | u32 | 142 | **142** | 142 | ✅ |
| 21 | `vibrator_on_time_ms` | u32 ms | 146 | **146** | 146 | ✅ |
| 22 | `vibrator_avg_strength_pct` | u32 | 150 | **150** | 150 | ✅ |
| 23 | `speaker_on_time_ms` | u32 ms | 154 | **154** | 154 | ✅ |
| 24 | `speaker_play_count` | u32 | 158 | **158** | 158 |  |
| 25 | `speaker_avg_volume_pct` | u32 | 162 | **162** | 162 | ✅ |
| 26 | `speaker_preempted_count` | u32 | 166 | **166** | 166 |  |
| 27 | `speaker_stream_underrun_count` | u32 | 170 | **170** | 170 |  |
| 28 | `hrm_on_time_ms` | u32 ms | 174 | **174** | 174 | ✅ |
| 29 | `button_pressed_count` | u32 | 178 | **178** | 178 |  |
| 30 | `touch_event_count` | u32 | 182 | **182** | 182 |  |
| 31 | `gesture_tap_count` | u32 | 186 | **186** | 186 |  |
| 32 | `gesture_double_tap_count` | u32 | 190 | **190** | 190 |  |
| 33 | `touch_driver_wake_cnt` | u32 | 194 | **194** | 194 |  |
| 34 | `cpu_running_pct` | u32+scale | 198 | **198** | 198 | ✅ |
| 35 | `cpu_sleep0_pct` | u32+scale | 204 | **204** | 204 |  |
| 36 | `cpu_sleep1_pct` | u32+scale | 210 | **210** | 210 |  |
| 37 | `cpu_sleep2_pct` | u32+scale | 216 | **216** | 216 |  |
| 38 | `sifli_ipc_not_idle_count` | u32 | 222 | **222** | 222 |  |
| 39 | `task_cpu_kernel_main_pct` | u32+scale | 226 | **226** | 226 | ✅ |
| 40 | `task_cpu_kernel_background_pct` | u32+scale | 232 | **232** | 232 | ✅ |
| 41 | `task_cpu_worker_pct` | u32+scale | 238 | **238** | 238 | ✅ |
| 42 | `task_cpu_app_pct` | u32+scale | 244 | **244** | 244 | ✅ |
| 43 | `task_cpu_bt_host_pct` | u32+scale | 250 | **250** | 250 | ✅ |
| 44 | `task_cpu_bt_controller_pct` | u32+scale | 256 | **256** | 256 | ✅ |
| 45 | `task_cpu_bt_hci_pct` | u32+scale | 262 | **262** | 262 | ✅ |
| 46 | `task_cpu_new_timers_pct` | u32+scale | 268 | **268** | 268 |  |
| 47 | `task_cpu_pulse_pct` | u32+scale | 274 | **274** | 274 |  |
| 48 | `task_cpu_idle_pct` | u32+scale | 280 | **280** | 280 |  |
| 49 | `accel_sample_count` | u32 | 286 | **286** | 286 |  |
| 50 | `accel_shake_count` | u32 | 290 | **290** | 290 |  |
| 51 | `accel_double_tap_count` | u32 | 294 | **294** | 294 |  |
| 52 | `accel_peek_count` | u32 | 298 | **298** | 298 |  |
| 53 | `notification_received_count` | u32 | 302 | **302** | 302 | ✅ |
| 54 | `notification_received_dnd_count` | u32 | 306 | **306** | 306 | ✅ |
| 55 | `phone_call_incoming_count` | u32 | 310 | **310** | 310 | ✅ |
| 56 | `phone_call_time_ms` | u32 ms | 314 | **314** | 314 | ✅ |
| 57 | `low_power_time_ms` | u32 ms | 318 | **318** | 318 |  |
| 58 | `stationary_time_ms` | u32 ms | 322 | **322** | 322 |  |
| 59 | `watchface_time_ms` | u32 ms | 326 | **326** | 326 | ✅ |
| 60 | `watchface_name` | char[33] | 330 | **330** | 330 |  |
| 61 | `watchface_uuid` | char[40] | 363 | **363** | 363 |  |
| 62 | `watchface_crash_count` | u32 | 403 | **403** | 403 |  |
| 63 | `watchface_crash_revert_count` | u32 | 407 | **407** | 407 |  |
| 64 | `pfs_space_free_kb` | u32 | 411 | **411** | 411 |  |
| 65 | `flash_spi_write_bytes` | u32 | 415 | **415** | 415 |  |
| 66 | `flash_spi_erase_bytes` | u32 | 419 | **419** | 419 |  |
| 67 | `ble_adv_short_intvl_time_ms` | u32 ms | 423 | **423** | 423 |  |
| 68 | `ble_adv_long_intvl_time_ms` | u32 ms | 427 | **427** | 427 |  |
| 69 | `ble_conn_itvl_min_time_ms` | u32 ms | 431 | **431** | 431 |  |
| 70 | `ble_conn_itvl_mid_time_ms` | u32 ms | 435 | **435** | 435 |  |
| 71 | `ble_conn_itvl_max_time_ms` | u32 ms | 439 | **439** | 439 |  |
| 72 | `ble_disconnect_conn_spvn_tmo_count` | u32 | 443 | **443** | 443 |  |
| 73 | `ble_disconnect_rem_user_term_count` | u32 | 447 | **447** | 447 |  |
| 74 | `ble_disconnect_conn_term_local_count` | u32 | 451 | **451** | 451 |  |
| 75 | `ble_disconnect_lmp_ll_rsp_tmo_count` | u32 | 455 | **455** | 455 |  |
| 76 | `ble_disconnect_conn_establishment_count` | u32 | 459 | **459** | 459 |  |
| 77 | `ble_disconnect_other_count` | u32 | 463 | **463** | 463 |  |
| 78 | `ppog_reversed` | u32 | — | **467** | 467 |  |
| 79 | `settings_health_tracking_enabled` | u32 | 467 | **471** | 471 |  |
| 80 | `settings_health_hrm_enabled` | u32 | 471 | **475** | 475 |  |
| 81 | `settings_health_hrm_measurement_interval` | u32 | 475 | **479** | 479 |  |
| 82 | `settings_health_hrm_activity_tracking_enabled` | u32 | 479 | **483** | 483 |  |
| 83 | `settings_power_mode` | u32 | 483 | **487** | — |  |
| 84 | `settings_motion_sensitivity` | u32 | 487 | **491** | 487 |  |
| 85 | `settings_backlight_intensity_pct` | u32 | 491 | **495** | 491 |  |
| 86 | `settings_backlight_timeout_s` | u32 | 495 | **499** | 495 |  |
| 87 | `settings_touch_enabled` | u32 | 499 | **503** | 499 |  |
| 88 | `app_message_sent_count` | u32 | 503 | **507** | 503 |  |
| 89 | `app_message_received_count` | u32 | 507 | **511** | 507 |  |
| 90 | `app_tick_timer_second_subscribed` | u32 | 511 | **515** | 511 |  |
| 91 | `connectivity_connected_time_ms` | u32 ms | 515 | **519** | 515 | ✅ |
| 92 | `connectivity_expected_time_ms` | u32 ms | 519 | **523** | 519 |  |

## What's worth decoding next

Grouped by the stoandl problem it would actually help with.

### Connection debugging (highest value)

| metric | why |
| ------ | --- |
| `ble_disconnect_conn_spvn_tmo_count` | Supervision timeout — the watch-side counter for link loss / out-of-range. This is the same event behind the overnight-flapping investigation. |
| `ble_disconnect_rem_user_term_count` | The remote side hung up deliberately (≠ went out of range). |
| `ble_disconnect_conn_term_local_count` | The watch terminated the link locally. |
| `ble_disconnect_lmp_ll_rsp_tmo_count` / `..._conn_establishment_count` / `..._other_count` | The rest of the HCI reason split. |
| `connectivity_connected_time_ms` ÷ `connectivity_expected_time_ms` | A true **connection-uptime %** per hour. We already decode the numerator and discard the denominator. |
| `ppog_reversed` | The watch's own view of the PPoG role — cross-checks the `reversedPPoG=false` pin. |
| `ble_conn_itvl_{min,mid,max}_time_ms`, `ble_adv_{short,long}_intvl_time_ms` | Time spent at each connection/advertising interval — latency vs. battery tradeoff. |

Together these give a **watch-side disconnect-reason discriminator** that does not depend on BlueZ
≥ 5.83's `Device1.Disconnected` reason (see the disconnect-reason note), which is otherwise the only
way to tell "out of range" from "broken bond".

### Settings cross-check (also closes the HRM offset question)

`settings_health_hrm_enabled` + `settings_health_hrm_measurement_interval` +
`settings_health_hrm_activity_tracking_enabled` make the open `hrm_on_time_ms @174` verification
self-contained: **if `settings_health_hrm_enabled == 0`, `hrm_on_time_ms` must be ≈ 0 in that same
record.** No second watch and no serial console required.

The remaining `settings_*` (`motion_sensitivity`, `backlight_intensity_pct`, `backlight_timeout_s`,
`touch_enabled`, `health_tracking_enabled`) are the watch's own view of prefs stoandl also manages via
`WatchPrefs` — a cheap desync check.

### Power-model calibration

`cpu_sleep0_pct` / `cpu_sleep1_pct` / `cpu_sleep2_pct` (we decode only `cpu_running_pct`),
`task_cpu_idle_pct`, `task_cpu_new_timers_pct`, `task_cpu_pulse_pct`, plus `low_power_time_ms` and
`stationary_time_ms`. The power pie's "System" floor is currently inferred; these measure it.
`touch_driver_wake_cnt` counts wake-causing touch IRQs — a real phantom-drain source.

### Device health / diagnostics

`last_reboot_reason` + `uptime_s` (unexpected resets, crash-after-flash), `memory_pct_max` +
`memory_largest_free_pct` (heap pressure / OOM), the five `stack_free_*` headroom gauges,
`pfs_space_free_kb` (free watch storage — useful on the Apps page), `flash_spi_write_bytes` /
`flash_spi_erase_bytes` (flash wear).

### App / UX telemetry

`watchface_name` + `watchface_uuid` (what is actually running; strings), `watchface_crash_count` +
`watchface_crash_revert_count` (sideloaded-face crashes), `app_message_sent_count` /
`app_message_received_count` (PebbleKit + extension throughput), `app_tick_timer_second_subscribed`
(per-second tick subscribers — a classic drain culprit), `button_pressed_count`, `touch_event_count`,
`gesture_tap_count`, `accel_shake_count`, and `speaker_play_count` / `speaker_preempted_count` /
`speaker_stream_underrun_count`.

`fw_version` is a watch-authoritative firmware string carried in the record itself, and `utc_offset_s`
lets the timesync feature **verify** the watch's offset rather than assume it.

### Gotchas when adding any of these

- **String fields** (`fw_version` @61, `watchface_name` @330, `watchface_uuid` @363) are fixed-width
  `char[len + 1]` and must be null-trimmed — not the `u32` path.
- **Scaled fields** carry their divisor inline as a `u16` immediately after the value; divide by the
  wire scale rather than a hardcoded constant (and treat scale 0 as "absent").
- **Anything at or above @467 shifts between layouts** and must go through `LAYOUTS`. The
  self-validating fallback in `decode()` covers only the battery block; it cannot vouch for the tail.
