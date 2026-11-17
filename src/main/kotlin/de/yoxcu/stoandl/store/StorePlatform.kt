package de.yoxcu.stoandl.store

import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.metadata.WatchType

/**
 * The store's `hardware` value for a connected watch. The store indexes applications by
 * `WatchType.codename` (`aplite`, `basalt`, `chalk`, `diorite`, `emery`, `flint`, `gabbro`), which is
 * exactly the board family — not the firmware `revision` (`v1_5`, `obelix_pvt`, …) used for firmware
 * and language-pack selection. A Time 2 (EMERY, any hardware revision) therefore asks for `emery`.
 */
fun WatchHardwarePlatform.storePlatform(): String = watchType.codename

/** Map the `platform` field of D-Bus `WatchDetails` (a `WatchType` name like `APLITE`) to the store's
 *  lowercase codename; null for an unknown/blank value. */
fun storePlatformFromWatchTypeName(name: String?): String? =
    WatchType.entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }?.codename
