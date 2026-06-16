@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.datalog

import de.yoxcu.stoandl.util.toNdjson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.datalogging.DataLogRecord
import io.rebble.libpebblecommon.datalogging.Datalogging
import io.rebble.libpebblecommon.packets.DataItemType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

private val log = KotlinLogging.logger {}

/**
 * Persists datalog frames from custom watchapps (the PebbleKit "DataLogging" surface) to disk.
 *
 * libpebble3 already handles the protocol — ACK/NACK, session tracking, health/system tags. The
 * only gap is that frames from *custom* apps (arbitrary UUID + tag) had nowhere to go; the fork
 * now re-emits them on [Datalogging.records] and this subscriber is their sink.
 *
 * Layout: one append-only NDJSON file per `(app UUID, tag)`:
 *
 *     <baseDir>/<uuid>/<tag>.ndjson
 *
 * Each line is one logged item. The watch only timestamps the *session* (not individual items), so
 * each line carries `session_ts` (watch session-open time) plus `rx` (when we received the frame);
 * an app that wants per-item time logs it inside its own payload.
 *
 *     {"rx":1718200000,"session_ts":1718199990,"type":"UInt","value":42}
 *     {"rx":1718200000,"session_ts":1718199990,"type":"Int","value":-7}
 *     {"rx":1718200000,"session_ts":1718199990,"type":"ByteArray","bytes":"aGVsbG8="}
 *
 * Files are append-only and never rotated here — datalog is normally low-volume, but a chatty
 * sensor-logging app can grow them without bound; prune manually if needed.
 */
class DatalogStore(
    private val datalogging: Datalogging,
    private val scope: CoroutineScope,
    private val baseDir: File = defaultDir(),
) {
    fun start() {
        datalogging.records
            .onEach { record -> runCatching { append(record) }.onFailure { log.warn(it) { "datalog write failed" } } }
            .launchIn(scope)
        log.info { "Datalog capture enabled → ${baseDir.path}" }
    }

    private fun append(r: DataLogRecord) {
        val dir = File(baseDir, r.uuid.toString())
        dir.mkdirs()
        val file = File(dir, "${r.tag}.ndjson")
        val rx = System.currentTimeMillis() / 1000
        val text = splitItems(r.data, r.itemSize.toInt()).map { item -> line(r, item, rx) }.toNdjson()
        if (text.isNotEmpty()) file.appendText(text)
        log.debug { "datalog ${r.uuid} tag=${r.tag} +${r.data.size}B type=${r.itemType} (${r.itemsLeft} left)" }
    }

    private fun line(r: DataLogRecord, item: ByteArray, rx: Long): JsonObject = buildJsonObject {
        put("rx", rx)
        put("session_ts", r.timestamp.toLong())
        put("type", r.itemType.name)
        when (r.itemType) {
            // data_logging items are little-endian; decode 1/2/4-byte ints, else keep raw bytes.
            DataItemType.UInt -> put("value", decodeLeInt(item, signed = false))
            DataItemType.Int -> put("value", decodeLeInt(item, signed = true))
            else -> put("bytes", Base64.getEncoder().encodeToString(item))
        }
    }

    companion object {
        fun defaultDir(): File = File(de.yoxcu.stoandl.config.StoandlConfig.configDir(), "datalog")

        /** Split a SendDataItems payload into fixed [itemSize] chunks; a non-multiple tail is kept
         *  as a final short item rather than dropped (so nothing is silently lost). */
        internal fun splitItems(data: ByteArray, itemSize: Int): List<ByteArray> {
            if (itemSize <= 0 || itemSize >= data.size) return listOf(data)
            val out = ArrayList<ByteArray>(data.size / itemSize + 1)
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + itemSize, data.size)
                out.add(data.copyOfRange(offset, end))
                offset = end
            }
            return out
        }

        /** Little-endian integer of up to 4 bytes. UInt fits in a Long; Int sign-extends. */
        internal fun decodeLeInt(b: ByteArray, signed: Boolean): Long {
            var v = 0L
            for (i in b.indices.reversed()) v = (v shl 8) or (b[i].toLong() and 0xFF)
            val bits = b.size * 8
            return if (signed && bits in 1..63 && (v and (1L shl (bits - 1))) != 0L) v - (1L shl bits) else v
        }
    }
}
