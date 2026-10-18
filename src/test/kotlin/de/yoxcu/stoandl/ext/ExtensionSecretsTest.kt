package de.yoxcu.stoandl.ext

import de.yoxcu.stoandl.secret.SecretBackend
import de.yoxcu.stoandl.secret.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Extension secret config fields (keyring-backed, masked, migrated) and `keepAwake` holds. */
class ExtensionSecretsTest {
    private class MemStore(val map: MutableMap<String, String> = mutableMapOf()) : SecretStore {
        override fun get(ref: String) = map[ref]
        override fun put(ref: String, value: String): SecretBackend { map[ref] = value; return SecretBackend.FILE }
        override fun remove(ref: String) { map.remove(ref) }
    }

    private val schema = """[
        {"key":"api_key","type":"string","label":"API key","secret":true,"required":true},
        {"key":"model","type":"enum","label":"Model","options":["a","b"]},
        {"key":"pin","type":"int","label":"PIN","secret":true},
        {"key":"history","type":"bool","label":"History"}
    ]"""

    private val extDir = Files.createTempDirectory("ext-secrets").toFile()
    private val store = MemStore()
    private val secrets = ExtensionSecrets(extDir, store)

    @AfterTest fun cleanup() { extDir.deleteRecursively() }

    private fun install(config: String?): File {
        val dir = File(extDir, "assistant").apply { mkdirs() }
        File(dir, "manifest.json").writeText(
            """{"name":"assistant","config":{"model":"a","history":"true","api_key":"from-manifest"},"configSchema":$schema}""")
        config?.let { File(dir, "config").writeText(it) }
        return dir
    }

    @Test
    fun `schema flags take secret only on string fields and required on any`() {
        val f = schemaFlags(schema)
        assertEquals(setOf("api_key"), f.secretKeys)
        assertEquals(setOf("api_key"), f.requiredKeys)
        assertEquals(SchemaFlags.NONE, schemaFlags(null))
        assertEquals(SchemaFlags.NONE, schemaFlags("not json"))
        assertEquals(setOf("t"), schemaFlags("""[{"key":"t","secret":true},{"secret":true}]""").secretKeys, "type defaults to string")
    }

    @Test
    fun `a plaintext secret in the config file moves to the store and its line goes`() {
        val dir = install("# my settings\nmodel = b\napi_key = sk-123  # pasted\nhistory = false\n")
        secrets.migrate("assistant", secrets.flagsOf("assistant").secretKeys)
        assertEquals("sk-123", store.map["ext:assistant:api_key"])
        val text = File(dir, "config").readText()
        assertFalse("sk-123" in text)
        assertTrue("# my settings" in text && "model = b" in text && "history = false" in text)
    }

    @Test
    fun `the config view blanks secrets, lists the stored ones and overlays the file on the manifest`() {
        install("model = b\n")
        assertEquals(mapOf("model" to "b", "history" to "true", "api_key" to "", SECRETS_KEY to ""),
            secrets.configView("assistant"))
        store.map["ext:assistant:api_key"] = "sk-1"
        assertEquals("api_key", secrets.configView("assistant")[SECRETS_KEY])
        assertEquals("", secrets.configView("assistant")["api_key"])
    }

    @Test
    fun `a payload keeps on empty, removes on null, stores otherwise, ignores _secrets`() {
        install(null)
        fun apply(json: String) = secrets.applyPayload("assistant", Json.parseToJsonElement(json).jsonObject)

        assertEquals(mapOf("model" to "b"), apply("""{"api_key":"sk-9","model":"b","_secrets":"x"}"""))
        assertEquals("sk-9", store.map["ext:assistant:api_key"])
        apply("""{"api_key":"","model":"a"}""")
        assertEquals("sk-9", store.map["ext:assistant:api_key"], "an empty secret is left alone")
        apply("""{"api_key":null}""")
        assertNull(store.map["ext:assistant:api_key"])
        assertNull(apply("""{"api_key":"","_secrets":"api_key"}"""), "nothing to change")
    }

    @Test
    fun `the child gets the stored secret, never the manifest default, and required gates the start`() {
        val dir = install(null)
        val flags = secrets.flagsOf("assistant")
        val def = ExtensionDef("assistant", listOf("x"), mapOf("model" to "a", "api_key" to "from-manifest"), dir)

        val (none, _) = withSecrets(def, flags.secretKeys, emptyMap(), emptyMap())
        assertNull(none.config["api_key"])
        assertFalse(configSatisfied(none, flags.requiredKeys))

        val (fromConf, warn) = withSecrets(def, flags.secretKeys, emptyMap(), mapOf("api_key" to "legacy"))
        assertEquals("legacy", fromConf.config["api_key"]); assertEquals(listOf("api_key"), warn)

        val (stored, w2) = withSecrets(def, flags.secretKeys, mapOf("api_key" to "sk-1"), mapOf("api_key" to "legacy"))
        assertEquals("sk-1", stored.config["api_key"]); assertTrue(w2.isEmpty())
        assertTrue(stored.userConfigured, "a stored secret counts as the user's own settings")
        assertTrue(configSatisfied(stored, flags.requiredKeys))

        // Without required keys the old rule holds.
        val plain = def.copy(requiresConfig = true)
        assertFalse(configSatisfied(plain, emptySet()))
        assertTrue(configSatisfied(plain.copy(userConfigured = true), emptySet()))
    }

    // ---- keepAwake --------------------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val taken = AtomicInteger(); private val open = AtomicInteger()
    private val reasons = mutableListOf<String>()
    private val holds = AwakeHolds("assistant", scope) { why ->
        synchronized(reasons) { reasons += why }
        taken.incrementAndGet(); open.incrementAndGet()
        AutoCloseable { open.decrementAndGet() }
    }

    @AfterTest fun stopScope() { scope.cancel() }

    @Test
    fun `keepAwake validates maxMs, trims why and caps the holds`() {
        assertTrue(holds.acquire("x", null).isFailure)
        assertTrue(holds.acquire("x", 0).isFailure)
        assertTrue(holds.acquire("  " + "w".repeat(100), 120_000).isSuccess)
        assertEquals("assistant: " + "w".repeat(80), reasons.single())
        holds.acquire(" ", 1000)
        assertEquals("assistant: extension work", reasons.last())
        holds.acquire(null, 1000); holds.acquire(null, 1000)
        assertEquals("keepAwake: too many holds", holds.acquire(null, 1000).exceptionOrNull()?.message)
        assertEquals(4, open.get())
    }

    @Test
    fun `a hold expires by itself, release is idempotent and releaseAll closes the rest`() {
        holds.acquire("short", 50)
        val t = holds.acquire("long", 60_000).getOrThrow()
        Thread.sleep(400)
        assertEquals(1, open.get(), "the 50 ms hold expired")
        assertTrue(holds.release(t))
        assertFalse(holds.release(t))
        assertFalse(holds.release("unknown"))
        assertEquals(0, open.get())

        holds.acquire("a", 60_000); holds.acquire("b", 60_000)
        holds.releaseAll()
        assertEquals(0, open.get())
        assertEquals(0, holds.size)
        assertEquals(4, taken.get())
    }

    @Test
    fun `after close no new hold is taken`() {
        holds.acquire("a", 60_000)
        holds.close()
        assertEquals(0, open.get())
        assertEquals("keepAwake: extension is stopping", holds.acquire("late", 60_000).exceptionOrNull()?.message)
        assertEquals(1, taken.get())
        assertEquals(0, holds.size)
    }
}
