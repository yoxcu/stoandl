# Analytics heartbeat — full metric map

Every hour PebbleOS logs one `native_heartbeat_record` over the DataLogging Service (system tag 87,
all-zero UUID). `HeartbeatStore` persists the **entire blob** as base64 in
`<config>/battery/heartbeat/<serial>.ndjson`, so **all** of the metrics below are already captured on
disk, and all of them are decoded for the debug dump (`stoandl watch battery heartbeat --all`, the
GUI's Debug → Heartbeat page). Using more of them in a view is pure read-side work (no fork change, no
new capture) and applies **retroactively to existing history**.

[battery-insights.md](battery-insights.md) covers the 7 battery fields and the power model; this file
is the complete map.

## Provenance

Derived from `include/pbl/services/analytics/analytics.def` in
[`coredevices/PebbleOS`](https://github.com/coredevices/PebbleOS) — the X-macro list that
`src/fw/services/analytics/native.c` expands into `struct PACKED native_heartbeat_record` — at every
release tag. Offsets are computed, not guessed: header is
`version:u8 @0 | timestamp:u64 @1 | build_id:u8[20] @9` (29 B), then metrics in declaration order
with `UNSIGNED`/`SIGNED`/`TIMER` = 4 B, `SCALED_*` = value 4 B + `u16` scale, `STRING(len)` = `len + 1`.

`tools/hb_layouts_from_source.py <PebbleOS checkout>` does that walk for every `vX.Y.Z` tag and prints
the layouts, each metric's first release, and (`--kotlin`) the lines for `HeartbeatLayout.kt`;
`--offsets <tag>` prints one release's full offset map. It also reports any `(size, version)` two
releases fill differently (none so far). Run it after a firmware release that touches
`analytics.def`. When the source isn't available, `tools/hb_relayout_probe.py` recovers the
battery-block shift empirically from stored raw blobs — the record's `build_id` is a GNU build-id,
**not** a git SHA, so it cannot be looked up directly.

## Layout versioning — read this before adding offsets

The record is **not** fixed. Adding a metric to `analytics.def` shifts every field after it, and for
thirteen layouts the version byte did not change. Every released layout, as `HeartbeatLayout.kt`
has them:

| size | version | first release | metrics | change |
| ---- | ------- | ------------- | ------: | ------ |
| 310 B | 1 | 4.9.158 | 50 | first native heartbeat |
| 419 B | 1 | 4.9.160 | 65 | + `fw_version`, `last_reboot_reason`, `touch_event_count`, ten `task_cpu_*_pct`, `settings_power_mode`, `settings_motion_sensitivity` |
| 427 B | 1 | 4.9.166 | 67 | + `gesture_tap_count`, `gesture_double_tap_count` |
| 431 B | 1 | 4.9.167 | 68 | + `app_tick_timer_second_subscribed` |
| 451 B | 1 | 4.9.168 | 73 | + five `speaker_*` metrics |
| 459 B | 1 | 4.9.170 | 75 | + `battery_tte_s`, `sifli_ipc_not_idle_count` |
| 467 B | 1 | 4.9.172 | 77 | + `watchface_crash_count`, `watchface_crash_revert_count` |
| 495 B | 1 | 4.9.177 | 84 | + `memory_largest_free_pct`, six `ble_disconnect_*_count` |
| 499 B | 1 | 4.9.179 | 85 | + `uptime_s` |
| 507 B | 1 | 4.9.184 | 87 | + `settings_backlight_intensity_pct`, `settings_backlight_timeout_s` |
| 515 B | 1 | 4.13.0 | 89 | + `stack_free_app_syscall_bytes`, `stack_free_worker_syscall_bytes` |
| 523 B | 1 | 4.20.0 | 91 | + `touch_driver_wake_cnt`, `settings_touch_enabled` |
| 527 B | 1 | 4.26.0 (`31e3ea8e1`, 2026-07-14) | 92 | **+ `ppog_reversed` @467, same version byte** |
| 523 B | 2 | 4.32.0 (`5ef38b9e9`, 2026-07-22) | 91 | **− `settings_power_mode`** (was @487), version bumped to 2 |
| **567 B** | **3** | **4.33.0** (`df6d08bd`…`68415258`) | **101** | **ten metrics appended**, version bumped to 3; unchanged through 4.38.2 |

A row covers every later release up to the next row, backports included (v4.27.2/3 and v4.30.2/3 send
527 B / v1). Untagged development builds between releases can send layouts that are not in the table
(the v3 metrics landed in five commits, 531 → 567 B); they are rejected, not decoded.

Consequences that have already bitten us:

1. **523 B/v1 and 523 B/v2 are the same size with different tail layouts.** Size alone is not a safe
   discriminator — `HeartbeatLayouts` is keyed on `(size, version)`.
2. The 2026-07-14 growth silently stranded every record for days: the decoder required `size == 523`,
   so insights kept replaying the last good decode (reporting 59 % while the watch was at 15 %).
3. The 567 B / v3 record went unnoticed from fw 4.33.0 (2026-08-06) until the review of 2026-09-28:
   it had no row, so the power pie, drain bars and notification overlay were empty while the battery
   block kept decoding through the structural fallback.
4. Before 4.13 even the battery block sat elsewhere (`battery_soc_pct` @49 in 4.9.158, @86 from
   4.9.160, @90, @94). No offset is stable across all releases, which is why stoandl reads every
   field **by name** through the layout and hardcodes none.

## The metrics

102 metrics across all releases; 101 in the current 567 B / v3 layout. Offsets are given for the four
layouts since 4.20; `—` means the metric does not exist in that layout. For older releases run
`tools/hb_layouts_from_source.py <PebbleOS> --offsets v4.9.184` (or the release in question).
✅ marks the 28 the battery views read; `stoandl watch battery heartbeat --all` and the
`HeartbeatMetrics` D-Bus method decode all of them.

| # | metric | type | since | 523/v1 | 527/v1 | 523/v2 | **567/v3** | used |
|---|--------|------|-------|-------:|-------:|-------:|-------:|:----:|
| 1 | `memory_pct_max` | u32 | 4.9.158 | 29 | 29 | 29 | **29** |  |
| 2 | `memory_largest_free_pct` | u32 | 4.9.177 | 33 | 33 | 33 | **33** |  |
| 3 | `stack_free_kernel_main_bytes` | u32 | 4.9.158 | 37 | 37 | 37 | **37** |  |
| 4 | `stack_free_kernel_background_bytes` | u32 | 4.9.158 | 41 | 41 | 41 | **41** |  |
| 5 | `stack_free_newtimers_bytes` | u32 | 4.9.158 | 45 | 45 | 45 | **45** |  |
| 6 | `stack_free_app_syscall_bytes` | u32 | 4.13.0 | 49 | 49 | 49 | **49** |  |
| 7 | `stack_free_worker_syscall_bytes` | u32 | 4.13.0 | 53 | 53 | 53 | **53** |  |
| 8 | `utc_offset_s` | i32 | 4.9.158 | 57 | 57 | 57 | **57** |  |
| 9 | `fw_version` | char[33] | 4.9.160 | 61 | 61 | 61 | **61** |  |
| 10 | `last_reboot_reason` | u32 | 4.9.160 | 94 | 94 | 94 | **94** |  |
| 11 | `uptime_s` | u32 | 4.9.179 | 98 | 98 | 98 | **98** |  |
| 12 | `battery_soc_pct` | u32+scale | 4.9.158 | 102 | 102 | 102 | **102** | ✅ |
| 13 | `battery_soc_pct_drop` | u32+scale | 4.9.158 | 108 | 108 | 108 | **108** | ✅ |
| 14 | `battery_voltage` | u32+scale | 4.9.158 | 114 | 114 | 114 | **114** | ✅ |
| 15 | `battery_voltage_delta` | i32+scale | 4.9.158 | 120 | 120 | 120 | **120** | ✅ |
| 16 | `battery_tte_s` | u32 | 4.9.170 | 126 | 126 | 126 | **126** | ✅ |
| 17 | `battery_charge_time_ms` | u32 ms | 4.9.158 | 130 | 130 | 130 | **130** | ✅ |
| 18 | `battery_discharge_duration_ms` | u32 ms | 4.9.158 | 134 | 134 | 134 | **134** | ✅ |
| 19 | `backlight_on_time_ms` | u32 ms | 4.9.158 | 138 | 138 | 138 | **138** | ✅ |
| 20 | `backlight_avg_intensity_pct` | u32 | 4.9.158 | 142 | 142 | 142 | **142** | ✅ |
| 21 | `vibrator_on_time_ms` | u32 ms | 4.9.158 | 146 | 146 | 146 | **146** | ✅ |
| 22 | `vibrator_avg_strength_pct` | u32 | 4.9.158 | 150 | 150 | 150 | **150** | ✅ |
| 23 | `speaker_on_time_ms` | u32 ms | 4.9.168 | 154 | 154 | 154 | **154** | ✅ |
| 24 | `speaker_play_count` | u32 | 4.9.168 | 158 | 158 | 158 | **158** |  |
| 25 | `speaker_avg_volume_pct` | u32 | 4.9.168 | 162 | 162 | 162 | **162** | ✅ |
| 26 | `speaker_preempted_count` | u32 | 4.9.168 | 166 | 166 | 166 | **166** |  |
| 27 | `speaker_stream_underrun_count` | u32 | 4.9.168 | 170 | 170 | 170 | **170** |  |
| 28 | `hrm_on_time_ms` | u32 ms | 4.9.158 | 174 | 174 | 174 | **174** | ✅ |
| 29 | `button_pressed_count` | u32 | 4.9.158 | 178 | 178 | 178 | **178** |  |
| 30 | `touch_event_count` | u32 | 4.9.160 | 182 | 182 | 182 | **182** |  |
| 31 | `gesture_tap_count` | u32 | 4.9.166 | 186 | 186 | 186 | **186** |  |
| 32 | `gesture_double_tap_count` | u32 | 4.9.166 | 190 | 190 | 190 | **190** |  |
| 33 | `touch_driver_wake_cnt` | u32 | 4.20.0 | 194 | 194 | 194 | **194** |  |
| 34 | `cpu_running_pct` | u32+scale | 4.9.158 | 198 | 198 | 198 | **198** | ✅ |
| 35 | `cpu_sleep0_pct` | u32+scale | 4.9.158 | 204 | 204 | 204 | **204** |  |
| 36 | `cpu_sleep1_pct` | u32+scale | 4.9.158 | 210 | 210 | 210 | **210** |  |
| 37 | `cpu_sleep2_pct` | u32+scale | 4.9.158 | 216 | 216 | 216 | **216** |  |
| 38 | `sifli_ipc_not_idle_count` | u32 | 4.9.170 | 222 | 222 | 222 | **222** |  |
| 39 | `task_cpu_kernel_main_pct` | u32+scale | 4.9.160 | 226 | 226 | 226 | **226** | ✅ |
| 40 | `task_cpu_kernel_background_pct` | u32+scale | 4.9.160 | 232 | 232 | 232 | **232** | ✅ |
| 41 | `task_cpu_worker_pct` | u32+scale | 4.9.160 | 238 | 238 | 238 | **238** | ✅ |
| 42 | `task_cpu_app_pct` | u32+scale | 4.9.160 | 244 | 244 | 244 | **244** | ✅ |
| 43 | `task_cpu_bt_host_pct` | u32+scale | 4.9.160 | 250 | 250 | 250 | **250** | ✅ |
| 44 | `task_cpu_bt_controller_pct` | u32+scale | 4.9.160 | 256 | 256 | 256 | **256** | ✅ |
| 45 | `task_cpu_bt_hci_pct` | u32+scale | 4.9.160 | 262 | 262 | 262 | **262** | ✅ |
| 46 | `task_cpu_new_timers_pct` | u32+scale | 4.9.160 | 268 | 268 | 268 | **268** |  |
| 47 | `task_cpu_pulse_pct` | u32+scale | 4.9.160 | 274 | 274 | 274 | **274** |  |
| 48 | `task_cpu_idle_pct` | u32+scale | 4.9.160 | 280 | 280 | 280 | **280** |  |
| 49 | `accel_sample_count` | u32 | 4.9.158 | 286 | 286 | 286 | **286** |  |
| 50 | `accel_shake_count` | u32 | 4.9.158 | 290 | 290 | 290 | **290** |  |
| 51 | `accel_double_tap_count` | u32 | 4.9.158 | 294 | 294 | 294 | **294** |  |
| 52 | `accel_peek_count` | u32 | 4.9.158 | 298 | 298 | 298 | **298** |  |
| 53 | `notification_received_count` | u32 | 4.9.158 | 302 | 302 | 302 | **302** | ✅ |
| 54 | `notification_received_dnd_count` | u32 | 4.9.158 | 306 | 306 | 306 | **306** | ✅ |
| 55 | `phone_call_incoming_count` | u32 | 4.9.158 | 310 | 310 | 310 | **310** | ✅ |
| 56 | `phone_call_time_ms` | u32 ms | 4.9.158 | 314 | 314 | 314 | **314** | ✅ |
| 57 | `low_power_time_ms` | u32 ms | 4.9.158 | 318 | 318 | 318 | **318** |  |
| 58 | `stationary_time_ms` | u32 ms | 4.9.158 | 322 | 322 | 322 | **322** |  |
| 59 | `watchface_time_ms` | u32 ms | 4.9.158 | 326 | 326 | 326 | **326** | ✅ |
| 60 | `watchface_name` | char[33] | 4.9.158 | 330 | 330 | 330 | **330** |  |
| 61 | `watchface_uuid` | char[40] | 4.9.158 | 363 | 363 | 363 | **363** |  |
| 62 | `watchface_crash_count` | u32 | 4.9.172 | 403 | 403 | 403 | **403** |  |
| 63 | `watchface_crash_revert_count` | u32 | 4.9.172 | 407 | 407 | 407 | **407** |  |
| 64 | `pfs_space_free_kb` | u32 | 4.9.158 | 411 | 411 | 411 | **411** |  |
| 65 | `flash_spi_write_bytes` | u32 | 4.9.158 | 415 | 415 | 415 | **415** |  |
| 66 | `flash_spi_erase_bytes` | u32 | 4.9.158 | 419 | 419 | 419 | **419** |  |
| 67 | `ble_adv_short_intvl_time_ms` | u32 ms | 4.9.158 | 423 | 423 | 423 | **423** |  |
| 68 | `ble_adv_long_intvl_time_ms` | u32 ms | 4.9.158 | 427 | 427 | 427 | **427** |  |
| 69 | `ble_conn_itvl_min_time_ms` | u32 ms | 4.9.158 | 431 | 431 | 431 | **431** |  |
| 70 | `ble_conn_itvl_mid_time_ms` | u32 ms | 4.9.158 | 435 | 435 | 435 | **435** |  |
| 71 | `ble_conn_itvl_max_time_ms` | u32 ms | 4.9.158 | 439 | 439 | 439 | **439** |  |
| 72 | `ble_disconnect_conn_spvn_tmo_count` | u32 | 4.9.177 | 443 | 443 | 443 | **443** |  |
| 73 | `ble_disconnect_rem_user_term_count` | u32 | 4.9.177 | 447 | 447 | 447 | **447** |  |
| 74 | `ble_disconnect_conn_term_local_count` | u32 | 4.9.177 | 451 | 451 | 451 | **451** |  |
| 75 | `ble_disconnect_lmp_ll_rsp_tmo_count` | u32 | 4.9.177 | 455 | 455 | 455 | **455** |  |
| 76 | `ble_disconnect_conn_establishment_count` | u32 | 4.9.177 | 459 | 459 | 459 | **459** |  |
| 77 | `ble_disconnect_other_count` | u32 | 4.9.177 | 463 | 463 | 463 | **463** |  |
| 78 | `ppog_reversed` | u32 | 4.26.0 | — | 467 | 467 | **467** |  |
| 79 | `settings_health_tracking_enabled` | u32 | 4.9.158 | 467 | 471 | 471 | **471** |  |
| 80 | `settings_health_hrm_enabled` | u32 | 4.9.158 | 471 | 475 | 475 | **475** |  |
| 81 | `settings_health_hrm_measurement_interval` | u32 | 4.9.158 | 475 | 479 | 479 | **479** |  |
| 82 | `settings_health_hrm_activity_tracking_enabled` | u32 | 4.9.158 | 479 | 483 | 483 | **483** |  |
| 83 | `settings_power_mode` | u32 | 4.9.160 (gone in 4.32.0) | 483 | 487 | — | — |  |
| 84 | `settings_motion_sensitivity` | u32 | 4.9.160 | 487 | 491 | 487 | **487** |  |
| 85 | `settings_backlight_intensity_pct` | u32 | 4.9.184 | 491 | 495 | 491 | **491** |  |
| 86 | `settings_backlight_timeout_s` | u32 | 4.9.184 | 495 | 499 | 495 | **495** |  |
| 87 | `settings_touch_enabled` | u32 | 4.20.0 | 499 | 503 | 499 | **499** |  |
| 88 | `app_message_sent_count` | u32 | 4.9.158 | 503 | 507 | 503 | **503** |  |
| 89 | `app_message_received_count` | u32 | 4.9.158 | 507 | 511 | 507 | **507** |  |
| 90 | `app_tick_timer_second_subscribed` | u32 | 4.9.167 | 511 | 515 | 511 | **511** |  |
| 91 | `connectivity_connected_time_ms` | u32 ms | 4.9.158 | 515 | 519 | 515 | **515** | ✅ |
| 92 | `connectivity_expected_time_ms` | u32 ms | 4.9.158 | 519 | 523 | 519 | **519** |  |
| 93 | `ble_conn_slave_lat0_time_ms` | u32 ms | 4.33.0 | — | — | — | **523** |  |
| 94 | `ble_conn_param_update_count` | u32 | 4.33.0 | — | — | — | **527** |  |
| 95 | `accel_stream_recovery_count` | u32 | 4.33.0 | — | — | — | **531** |  |
| 96 | `unexpected_reboot_count` | u32 | 4.33.0 | — | — | — | **535** |  |
| 97 | `battery_temp_c` | i32+scale | 4.33.0 | — | — | — | **539** |  |
| 98 | `i2c_transfer_error_count` | u32 | 4.33.0 | — | — | — | **545** |  |
| 99 | `ble_conn_itvl_other_time_ms` | u32 ms | 4.33.0 | — | — | — | **549** |  |
| 100 | `drv_init_fail_flags` | u32 | 4.33.0 | — | — | — | **553** |  |
| 101 | `battery_soc_pct_min` | u32+scale | 4.33.0 | — | — | — | **557** |  |
| 102 | `touch_gated_touchdown_count` | u32 | 4.33.0 | — | — | — | **563** |  |

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

### New in 567 B / v3 (fw ≥ 4.33)

| metric | why |
| ------ | --- |
| `battery_temp_c` | Battery temperature, °C (the wire is milli-°C with scale 1000; 0 on platforms without a sensor). Explains cold-weather drain and voltage sag. |
| `battery_soc_pct_min` | Lowest state of charge within the hour. The hourly `battery_soc_pct` snapshot misses brief deep dips (brownout depth). |
| `ble_conn_slave_lat0_time_ms` | Time connected at slave latency 0. The interval buckets cannot tell latency 0 from latency 3, which is ~4× the radio duty; this is the missing input for the power model's Bluetooth slice. |
| `ble_conn_param_update_count` | Connection-parameter update churn — the host renegotiating the link. |
| `ble_conn_itvl_other_time_ms` | Time at an interval outside the watch's table (e.g. a central-imposed 7.5 ms). Before 4.33 it was counted in `ble_conn_itvl_max_time_ms`, so that bucket overstates on older firmware. |
| `unexpected_reboot_count` | 1 on the first heartbeat after a reboot whose reason is in the error family (watchdog and above). A direct crash counter. |
| `accel_stream_recovery_count`, `i2c_transfer_error_count`, `drv_init_fail_flags` | Driver health: accelerometer stream recoveries, I²C errors, and a bitmask of drivers that failed to initialise this boot. |
| `touch_gated_touchdown_count` | Touches on the idle watchface that the interaction gate treated as accidental. |

### Gotchas when adding any of these

- **Read by name, never by offset.** `HeartbeatLayouts.of(record)` gives the record's layout, and
  `layout.u32(record, "hrm_on_time_ms")` (or `i32`/`scale`/`scaled`) reads a field. It returns null
  when that release does not emit the metric, and throws for a name that is in no release (a typo).
- **String fields** (`fw_version`, `watchface_name`, `watchface_uuid`) are fixed-width
  `char[len + 1]` and must be null-trimmed — not the `u32` path.
- **Scaled fields** carry their divisor inline as a `u16` immediately after the value; divide by the
  wire scale rather than a hardcoded constant (and treat scale 0 as "absent").
- **A new firmware layout** is one `M(...)` line per added metric (with its `since`) plus one
  `Layout(...)` row; `tools/hb_layouts_from_source.py --kotlin` prints both. The unit tests
  (`HeartbeatLayoutTest`) then need that layout's landmark offsets. The self-validating fallback in
  `decode()` covers only the battery block of an unknown layout; it cannot vouch for anything else.
