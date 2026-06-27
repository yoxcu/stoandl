package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant

/**
 * Minimal dbus-java bindings for the freedesktop Secret Service (`org.freedesktop.secrets`) — the
 * standard Linux keyring (gnome-keyring on Phosh/GNOME, ksecretd/kwallet on Plasma, KeePassXC, …).
 * Reached directly over the session bus with the dbus-java 5 already in the project (the `swiesend`
 * library would drag a conflicting dbus-java 4.x onto the classpath that drives all of stoandl's BLE +
 * notification D-Bus, so it's deliberately avoided).
 *
 * Only the handful of members [de.yoxcu.stoandl.secret.SecretServiceStore] needs are declared. We use
 * the `plain` session algorithm (no transport encryption): the session bus is a local AF_UNIX socket
 * and the spec marks `plain` as RECOMMENDED-to-be-supported by every server. The headless/locked case
 * (a user service started before any interactive login) is handled by the store, not here — any call
 * that would need an interactive prompt is treated as "unavailable" and falls back to the file store.
 *
 * Spec: https://specifications.freedesktop.org/secret-service/latest/
 */

const val SECRETS_BUS_NAME = "org.freedesktop.secrets"
const val SECRETS_OBJECT_PATH = "/org/freedesktop/secrets"

/** The `(oayays)` Secret transfer struct: the session it's encoded for, encryption parameters (empty
 *  for `plain`), the secret value bytes, and a MIME content type. */
class SecretValue(
    @field:Position(0) val session: DBusPath,
    @field:Position(1) val parameters: ByteArray,
    @field:Position(2) val value: ByteArray,
    @field:Position(3) val contentType: String,
) : Struct()

/** `(v output, o result)` from [SecretService.OpenSession]. */
class OpenSessionResult(
    @field:Position(0) val output: Variant<*>,
    @field:Position(1) val result: DBusPath,
) : Tuple()

/** `(ao unlocked, ao locked)` from [SecretService.SearchItems]. */
class SearchItemsResult(
    @field:Position(0) val unlocked: List<DBusPath>,
    @field:Position(1) val locked: List<DBusPath>,
) : Tuple()

/** `(ao unlocked, o prompt)` from [SecretService.Unlock] — a non-`/` prompt path means an interactive
 *  unlock would be required (not possible headless). */
class UnlockResult(
    @field:Position(0) val unlocked: List<DBusPath>,
    @field:Position(1) val prompt: DBusPath,
) : Tuple()

/** `(o item, o prompt)` from [SecretCollection.CreateItem]. */
class CreateItemResult(
    @field:Position(0) val item: DBusPath,
    @field:Position(1) val prompt: DBusPath,
) : Tuple()

@DBusInterfaceName("org.freedesktop.Secret.Service")
interface SecretService : DBusInterface {
    fun OpenSession(algorithm: String, input: Variant<*>): OpenSessionResult
    fun SearchItems(attributes: Map<String, String>): SearchItemsResult
    fun Unlock(objects: List<DBusPath>): UnlockResult
    fun ReadAlias(name: String): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Collection")
interface SecretCollection : DBusInterface {
    fun CreateItem(properties: Map<String, Variant<*>>, secret: SecretValue, replace: Boolean): CreateItemResult
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
interface SecretItem : DBusInterface {
    fun GetSecret(session: DBusPath): SecretValue
    fun Delete(): DBusPath
}
