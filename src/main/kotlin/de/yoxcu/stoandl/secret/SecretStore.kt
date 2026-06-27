package de.yoxcu.stoandl.secret

import de.yoxcu.stoandl.util.LenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64

private val log = KotlinLogging.logger {}

/** Which backend actually holds a secret — surfaced (logs + the CRUD status tail) so the user can tell
 *  whether a password really reached the system keyring or fell back to the local 0600 file. */
enum class SecretBackend { KEYRING, FILE }

/**
 * A tiny secret store keyed by an opaque [ref] (e.g. `caldav:ab12cd`). It exists so a CalDAV password
 * never lives in `stoandl.conf` (and so never lands in a backup tarball or support bundle).
 *
 * Implementations must NEVER throw from [get]/[remove] — return null / no-op on any failure — so a
 * missing or locked backend degrades to "no secret" (the calendar just fails auth until the keyring is
 * unlocked or the password is re-entered) rather than breaking the daemon. [put] returns the backend
 * that actually stored the value.
 */
interface SecretStore {
    fun get(ref: String): String?
    fun put(ref: String, value: String): SecretBackend
    fun remove(ref: String)
}

/**
 * Fallback store: a single 0600 JSON file (`ref → base64(value)`) under the stoandl config dir, kept
 * OUT of the backup set (see `doBackup`'s `--exclude`) so secrets never land in a backup tarball or the
 * support bundle. Used when the system keyring is unavailable or locked — the common headless-phone
 * case. Base64-encoded so arbitrary bytes/newlines in a password are safe; the file permissions, not
 * encryption, are the at-rest protection here.
 */
class FileSecretStore(private val file: File) : SecretStore {
    private val lock = Any()

    override fun get(ref: String): String? = read()[ref]?.let(::decode)

    override fun put(ref: String, value: String): SecretBackend = synchronized(lock) {
        val map = read(); map[ref] = encode(value); write(map); SecretBackend.FILE
    }

    override fun remove(ref: String): Unit = synchronized(lock) {
        val map = read(); if (map.remove(ref) != null) write(map)
    }

    private fun read(): MutableMap<String, String> = synchronized(lock) {
        if (!file.isFile) return mutableMapOf()
        try {
            val obj = LenientJson.parseToJsonElement(file.readText())
            (obj as? JsonObject)?.entries
                ?.associateTo(mutableMapOf()) { (k, v) -> k to ((v as? JsonPrimitive)?.contentOrNull ?: "") }
                ?: mutableMapOf()
        } catch (e: Exception) {
            log.warn { "Could not read secrets file ${file.path}: ${e.message}" }
            mutableMapOf()
        }
    }

    private fun write(map: Map<String, String>) {
        file.parentFile?.mkdirs()
        val json = JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        ownerOnly(tmp)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        ownerOnly(file)
    }

    private fun encode(v: String) = Base64.getEncoder().encodeToString(v.toByteArray())
    private fun decode(v: String) = try { String(Base64.getDecoder().decode(v)) } catch (e: Exception) { v }

    /** Restrict to owner read/write (0600). POSIX where available; best-effort chmod otherwise. */
    private fun ownerOnly(f: File) = try {
        Files.setPosixFilePermissions(
            f.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    } catch (e: Exception) {
        f.setReadable(false, false); f.setReadable(true, true)
        f.setWritable(false, false); f.setWritable(true, true)
    }
}

/**
 * Keyring-first, file-fallback. [put] tries the system keyring (the freedesktop Secret Service) and
 * only writes to the [file] store when the keyring is unavailable or locked — so a password reaches the
 * OS keyring whenever the session is unlocked, and otherwise lands in the 0600 file. [get] checks the
 * keyring then the file (a secret written while the keyring was down still resolves). [remove] clears
 * both. On a successful keyring write any stale file copy is dropped so a secret lives in exactly one
 * place.
 */
class LayeredSecretStore(private val keyring: SecretStore, private val file: FileSecretStore) : SecretStore {
    override fun get(ref: String): String? = keyring.get(ref) ?: file.get(ref)

    override fun put(ref: String, value: String): SecretBackend = try {
        keyring.put(ref, value).also {
            file.remove(ref)
            log.info { "Secret $ref stored in the system keyring (org.freedesktop.secrets)" }
        }
    } catch (e: Exception) {
        log.info { "System keyring unavailable (${e.message}); storing secret $ref in the local 0600 file" }
        file.put(ref, value)
    }

    override fun remove(ref: String) {
        try { keyring.remove(ref) } catch (e: Exception) { log.debug { "keyring remove($ref) failed: ${e.message}" } }
        file.remove(ref)
    }
}
