package de.yoxcu.stoandl.ext

import de.yoxcu.stoandl.voice.tts.Synthesis
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `synthesize` answer as the Go helper (Ext.Synthesize) reads it. */
class SynthesisResultTest {
    @Test
    fun `blocks go out as base64 with the audio length, errors as the error message`() {
        val block = ByteArray(2000) { it.toByte() }
        val r = synthesisResult(Synthesis.Blocks(listOf(block, byteArrayOf(1, 2, 3, 4, 5)), 1000))!!
        assertEquals(setOf("blocks", "ms"), r.keys)
        assertContentEquals(block, Base64.getDecoder().decode(r["blocks"]!!.jsonArray[0].jsonPrimitive.content))
        assertEquals(1000, r["ms"]!!.jsonPrimitive.int)
        assertEquals("/run/x.wav", synthesisResult(Synthesis.WavFile("/run/x.wav", 5))!!["path"]!!.jsonPrimitive.content)
        assertNull(synthesisResult(Synthesis.Error("busy")))
    }
}
