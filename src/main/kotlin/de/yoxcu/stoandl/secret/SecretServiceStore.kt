package de.yoxcu.stoandl.secret

import de.yoxcu.stoandl.dbus.SECRETS_BUS_NAME
import de.yoxcu.stoandl.dbus.SECRETS_OBJECT_PATH
import de.yoxcu.stoandl.dbus.SecretCollection
import de.yoxcu.stoandl.dbus.SecretItem
import de.yoxcu.stoandl.dbus.SecretPrompt
import de.yoxcu.stoandl.dbus.SecretService
import de.yoxcu.stoandl.dbus.SecretValue
import de.yoxcu.stoandl.util.softOpenSessionBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

private const val PROMPT_IFACE = "org.freedesktop.Secret.Prompt"
private const val PROMPT_TIMEOUT_MS = 5_000L

/**
 * [SecretStore] backed by the freedesktop Secret Service (the system keyring) over the session bus.
 *
 * Every operation opens a short-lived private session-bus connection, does its work, and closes it —
 * secret ops are rare (once per CalDAV account at each sync, plus user-initiated edits), so this avoids
 * holding/leaking a long-lived connection and any staleness. ALL failure modes throw (so
 * [LayeredSecretStore] falls back to the file store): the service isn't running, there's no default
 * collection, or the collection is LOCKED and its unlock prompt doesn't complete within
 * [PROMPT_TIMEOUT_MS]. Reading runs the unlock prompt like libsecret does: KWallet locks an idle
 * wallet, and ksecretd then answers every lookup with a prompt that, when the wallet password comes
 * from the login (PAM), completes on its own. On a desktop it can show the keyring's unlock dialog; a
 * prompt nobody answers in time is dismissed. Writing into a locked collection still fails over to the
 * file store.
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
                when {
                    unlocked.prompt.path == "/" -> unlocked.unlocked
                    runPrompt(conn, unlocked.prompt) -> service.SearchItems(attrs(ref)).unlocked
                    else -> return@withConnection null
                }
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

    /** KWallet's ksecretd isn't D-Bus-activatable and starts with the session, often after the daemon:
     *  wait for the name's owner ([SECRETS_BUS_NAME]), announced by NameOwnerChanged. */
    override suspend fun awaitLateBackend(timeout: Duration): Boolean = withContext(Dispatchers.IO) {
        val conn = softOpenSessionBus() ?: return@withContext false
        val appeared = CompletableDeferred<Unit>()
        // Registered before the owner check, so an owner taking the name in between is not missed.
        val handler = DBusSigHandler<DBus.NameOwnerChanged> { sig ->
            if (sig.name == SECRETS_BUS_NAME && sig.newOwner.isNotEmpty()) appeared.complete(Unit)
        }
        try {
            conn.addSigHandler(DBus.NameOwnerChanged::class.java, handler)
            val dbus = conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            if (dbus.NameHasOwner(SECRETS_BUS_NAME)) return@withContext false
            log.info { "Waiting up to $timeout for the keyring ($SECRETS_BUS_NAME)" }
            (withTimeoutOrNull(timeout) { appeared.await() } != null).also { up ->
                log.info { if (up) "Keyring ($SECRETS_BUS_NAME) is up" else "No keyring ($SECRETS_BUS_NAME) within $timeout" }
            }
        } catch (e: Exception) {
            log.debug { "Waiting for the keyring failed: ${e.message}" }
            false
        } finally {
            try { conn.close() } catch (e: Exception) { log.debug { "secret-bus close failed: ${e.message}" } }
        }
    }

    private fun attrs(ref: String) = mapOf("service" to "stoandl", "ref" to ref)

    /** Run [prompt] and wait for its `Completed` signal: true when it completed undismissed, false when
     *  dismissed or not done within [PROMPT_TIMEOUT_MS] (then it is dismissed, so no dialog lingers). */
    private fun runPrompt(conn: DBusConnection, prompt: DBusPath): Boolean {
        val done = CompletableFuture<Boolean>()
        // No sender in the rule: dbus-java matches it against the unique name, so a well-known one never fires.
        val rule = DBusMatchRuleBuilder.create()
            .withType("signal")
            .withInterface(PROMPT_IFACE)
            .withMember("Completed")
            .withPath(prompt.path)
            .build()
        val handler = DBusSigHandler<DBusSignal> { msg ->
            done.complete(msg.getParameters()?.getOrNull(0) == false)
        }
        conn.addGenericSigHandler(rule, handler)
        val remote = conn.getRemoteObject(SECRETS_BUS_NAME, prompt.path, SecretPrompt::class.java)
        return try {
            remote.Prompt("")
            done.get(PROMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS).also { ok ->
                log.debug { "Keyring unlock prompt ${if (ok) "completed" else "dismissed"}" }
            }
        } catch (e: TimeoutException) {
            log.info { "Keyring unlock prompt not answered within ${PROMPT_TIMEOUT_MS / 1000} s; dismissed" }
            runCatching { remote.Dismiss() }
            false
        } finally {
            runCatching { conn.removeGenericSigHandler(rule, handler) }
        }
    }

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
