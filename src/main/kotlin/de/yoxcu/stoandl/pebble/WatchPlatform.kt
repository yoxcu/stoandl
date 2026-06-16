package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform

/**
 * Whether [this] board belongs to the modern **Core Devices** firmware generation (Pebble 2 Duo,
 * Pebble Time 2, …) rather than the classic/Rebble-served generation (original Pebble, Pebble Time /
 * Time Steel, Time Round, Pebble 2 / `silk`, …).
 *
 * This single partition decides where stoandl sources over-the-air firmware **and** which language
 * packs apply — the two ecosystems line up exactly:
 *  - **Core devices** → firmware from the PebbleOS GitHub releases (`normal_<board>_<ver>.pbz`) and
 *    the Diorite (`silk`) language packs.
 *  - **Classic / Rebble devices** → firmware from `cohorts.rebble.io` and the board's own packs.
 *
 * Mirrors libpebble3's Core app partition: only the known legacy boards are listed; any newer board
 * defaults to Core (`else -> true`). Note this is firmware-ecosystem, not transport — a Pebble 2
 * (`silk`) connects over BLE yet is a Rebble device, so it correctly routes to cohorts.
 *
 * Hand-synced with the upstream list in `coredevices/pebble`'s
 * `firmware/FirmwareUpdateCheck.kt` (`isCoreDevice`) — that Compose module isn't on stoandl's
 * classpath, so when a board is added upstream it must be mirrored here too.
 */
fun WatchHardwarePlatform.isCoreDevice(): Boolean = when (this) {
    WatchHardwarePlatform.UNKNOWN,
    WatchHardwarePlatform.PEBBLE_ONE_EV_1, WatchHardwarePlatform.PEBBLE_ONE_EV_2,
    WatchHardwarePlatform.PEBBLE_ONE_EV_2_3, WatchHardwarePlatform.PEBBLE_ONE_EV_2_4,
    WatchHardwarePlatform.PEBBLE_ONE_POINT_FIVE, WatchHardwarePlatform.PEBBLE_TWO_POINT_ZERO,
    WatchHardwarePlatform.PEBBLE_SNOWY_EVT_2, WatchHardwarePlatform.PEBBLE_SNOWY_DVT,
    WatchHardwarePlatform.PEBBLE_BOBBY_SMILES, WatchHardwarePlatform.PEBBLE_ONE_BIGBOARD_2,
    WatchHardwarePlatform.PEBBLE_ONE_BIGBOARD, WatchHardwarePlatform.PEBBLE_SNOWY_BIGBOARD,
    WatchHardwarePlatform.PEBBLE_SNOWY_BIGBOARD_2, WatchHardwarePlatform.PEBBLE_SPALDING_EVT,
    WatchHardwarePlatform.PEBBLE_SPALDING_PVT, WatchHardwarePlatform.PEBBLE_SPALDING_BIGBOARD,
    WatchHardwarePlatform.PEBBLE_SILK_EVT, WatchHardwarePlatform.PEBBLE_SILK,
    WatchHardwarePlatform.PEBBLE_SILK_BIGBOARD, WatchHardwarePlatform.PEBBLE_SILK_BIGBOARD_2_PLUS,
    WatchHardwarePlatform.PEBBLE_ROBERT_EVT, WatchHardwarePlatform.PEBBLE_ROBERT_BIGBOARD,
    WatchHardwarePlatform.PEBBLE_ROBERT_BIGBOARD_2,
    -> false
    else -> true
}
