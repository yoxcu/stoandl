package de.yoxcu.stoandl.secret

import de.yoxcu.stoandl.dbus.SECRETS_BUS_NAME
import de.yoxcu.stoandl.dbus.SECRETS_OBJECT_PATH
import de.yoxcu.stoandl.dbus.SecretCollection
import de.yoxcu.stoandl.dbus.SecretItem
import de.yoxcu.stoandl.dbus.SecretService
import de.yoxcu.stoandl.dbus.SecretValue
import de.yoxcu.stoandl.util.softOpenSessionBus
import io.github.oshai.kotlinlogging.KotlinLogging
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.types.Variant

private val log = KotlinLogging.logger {}

/**
 * [SecretStore] backed by the freedesktop Secret Service (the system keyring) over the session bus.
 *
 * Every operation opens a short-lived private session-bus connection, does its work, and closes it —
 * secret ops are rare (once per CalDAV account at each sync, plus user-initiated edits), so this avoids
 * holding/leaking a long-lived connection and any staleness. ALL failure modes throw (so
 * [LayeredSecretStore] falls back to the file store): the service isn't running, there's no default
 * collection, or the collection/item is LOCKED and unlocking would need an interactive prompt a
 * headless daemon can't answer. We never call `Prompt.Prompt()`.
 *
 * The secret VALUE only ever goes in the `(oayays)` Secret struct; the lookup attributes (`a{ss}`,
 * stored in cleartext by the server) carry only `{service: stoandl, ref: <ref>}`.
 *
 * Untested against a live keyring in CI — verified on hardware. A marshaling/protocol bug can only
 * make this throw, which degrades cleanly to the file store; it cannot break calendar sync.
 */
class SecretServiceStore : SecretStore {

    // Never throws (interface contract): any keyring failure / lock returns null so the layered store
    // tries the file next.
    override fun get(ref: String): String? = try {
        withConnection { conn ->
            val service = conn.service()
            val session = service.OpenSession("plain", Variant("")).result
            val found = service.SearchItems(attrs(ref))
            val items = found.unlocked.ifEmpty {
                if (found.locked.isEmpty()) return@withConnection null
                val unlocked = service.Unlock(found.locked)
                // A non-`/` prompt means an interactive unlock — not possible headless; treat as absent.
                if (unlocked.prompt.path != "/") return@withConnection null
                unlocked.unlocked
            }
            val itemPath = items.firstOrNull()?.path ?: return@withConnection null
            val item = conn.getRemoteObject(SECRETS_BUS_NAME, itemPath, SecretItem::class.java)
            String(item.GetSecret(session).value)
        }
    } catch (e: Exception) {
        log.debug { "Keyring lookup of $ref failed: ${e.message}" }
        null
    }

    override fun put(ref: String, value: String): SecretBackend = withConnection { conn ->
        val service = conn.service()
        val session = service.OpenSession("plain", Variant("")).result
        val collPath = service.ReadAlias("default").path
        if (collPath == "/" || collPath.isBlank()) {
            // No default collection (a fresh profile) — creating one needs an interactive prompt.
            throw IllegalStateException("no default keyring collection")
        }
        val collection = conn.getRemoteObject(SECRETS_BUS_NAME, collPath, SecretCollection::class.java)
        val props = mapOf(
            "org.freedesktop.Secret.Item.Label" to Variant("stoandl: $ref"),
            "org.freedesktop.Secret.Item.Attributes" to Variant(attrs(ref), "a{ss}"),
        )
        val secret = SecretValue(session, ByteArray(0), value.toByteArray(), "text/plain")
        val res = collection.CreateItem(props, secret, true)
        if (res.item.path == "/") {
            // Locked collection returns no item + a prompt path — can't complete headless.
            throw IllegalStateException("keyring collection is locked")
        }
        SecretBackend.KEYRING
    } ?: throw IllegalStateException("secret service unavailable")

    // Never throws (interface contract): best-effort clear of any matching items.
    override fun remove(ref: String) {
        try {
            withConnection { conn ->
                val service = conn.service()
                service.OpenSession("plain", Variant(""))
                val found = service.SearchItems(attrs(ref))
                (found.unlocked + found.locked).forEach { path ->
                    try {
                        conn.getRemoteObject(SECRETS_BUS_NAME, path.path, SecretItem::class.java).Delete()
                    } catch (e: Exception) {
                        log.debug { "Could not delete keyring item ${path.path}: ${e.message}" }
                    }
                }
            }
        } catch (e: Exception) {
            log.debug { "Keyring remove($ref) failed: ${e.message}" }
        }
    }

    private fun attrs(ref: String) = mapOf("service" to "stoandl", "ref" to ref)

    private fun DBusConnection.service(): SecretService =
        getRemoteObject(SECRETS_BUS_NAME, SECRETS_OBJECT_PATH, SecretService::class.java)

    /** Run [block] with a short-lived session-bus connection, always closed. Returns null when the bus
     *  can't be opened; lets the [block]'s own exceptions propagate (so the layered store falls back). */
    private fun <T> withConnection(block: (DBusConnection) -> T): T? {
        val conn = softOpenSessionBus() ?: return null
        return try {
            block(conn)
        } finally {
            try { conn.close() } catch (e: Exception) { log.debug { "secret-bus close failed: ${e.message}" } }
        }
    }
}
