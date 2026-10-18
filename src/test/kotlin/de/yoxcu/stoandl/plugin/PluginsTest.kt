@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.plugin

import de.yoxcu.stoandl.ext.ExtensionPlugin
import de.yoxcu.stoandl.ext.ExtensionPluginManifest
import de.yoxcu.stoandl.ext.extensionPluginUuid
import de.yoxcu.stoandl.secret.FileSecretStore
import io.rebble.libpebblecommon.database.entity.LockerAppPermission
import io.rebble.libpebblecommon.permissions.AppPermissionStatus
import io.rebble.libpebblecommon.permissions.PermissionStatus
import io.rebble.libpebblecommon.plugin.CellGenerations
import io.rebble.libpebblecommon.plugin.Connections
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** stoandl's side of plugins: extension-served plugins, secure storage, phone state, permission wording. */
class PluginsTest {
    private val manifestJson = """
        {"description":"Rooms with unread messages","sources":[{"category":"messages","items":["room"],
          "properties":{"name":["shortText"],"unread":["numericValue","shortText"]},"supportsMultiple":true,
          "callerPermissions":[{"name":"Notifications"}],"suggestedRefreshIntervalSec":60}],
         "actions":[{"name":"mark_read","description":"Mark a room read","targets":["messages/room"]}]}
    """.trimIndent()

    @Test
    fun `an extension's plugin block parses like a pbw manifest's`() {
        val m = assertNotNull(ExtensionPluginManifest.parse(Json.parseToJsonElement(manifestJson)))
        assertEquals("messages", m.sources.single().category)
        assertEquals(listOf("mark_read"), m.actions.map { it.name })
        assertNull(ExtensionPluginManifest.parse(Json.parseToJsonElement("{}")), "nothing served: no plugin")
        assertEquals(extensionPluginUuid("matrix"), extensionPluginUuid("matrix"))
        assertNotEquals(extensionPluginUuid("matrix"), extensionPluginUuid("hue"))
    }

    @Test
    fun `reads and actions go over the extension's pipe`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val plugin = ExtensionPlugin("matrix", assertNotNull(ExtensionPluginManifest.parse(Json.parseToJsonElement(manifestJson))), "Matrix",
            request = { method, params ->
                calls += "$method ${params["category"]?.jsonPrimitive?.content ?: params["action"]?.jsonPrimitive?.content}"
                if (method == "readSource") Json.parseToJsonElement("""{"pluginUuid":"x","instances":[{"instanceId":"!a","properties":{"name":{"shortText":{"text":"Family"}}}}]}""")
                else Json.parseToJsonElement("""{"ok":true,"text":"Done"}""")
            },
            changes = MutableSharedFlow(),
        )
        assertTrue(plugin.serves("messages", "room"))
        val env = plugin.observe("messages", "room").first()
        assertEquals(plugin.pluginUuid.toString(), env.pluginUuid)
        assertEquals("!a", env.instances.single().instanceId)
        assertEquals(true, plugin.invoke("mark_read", JsonObject(emptyMap())).ok)
        assertEquals(false, plugin.invoke("nope", JsonObject(emptyMap())).ok)
        assertEquals(listOf("readSource messages", "invokeAction mark_read"), calls)
        assertEquals(PluginKind.EXTENSION, kindOf(plugin))
    }

    @Test
    fun `plugin secrets are kept per plugin and destroyed together`() = runBlocking<Unit> {
        val store = FileSecretStore(Files.createTempFile("secrets", "").toFile())
        val secrets = PluginSecretStore(store)
        val a = secrets.forPlugin(Uuid.random()); val b = secrets.forPlugin(Uuid.random())
        a.set("token", "t-a"); a.set("refresh", "r-a"); b.set("token", "t-b")
        assertEquals("t-a", a.get("token"))
        a.destroy()
        assertNull(a.get("token")); assertNull(a.get("refresh"))
        assertEquals("t-b", b.get("token"))
    }

    @Test
    fun `phone state maps NetworkManager and ModemManager values`() {
        assertEquals(Connections.WIFI, connectionOf("802-11-wireless", 70))
        assertEquals(Connections.CELLULAR, connectionOf("gsm", 70))
        assertEquals(Connections.NONE, connectionOf("802-11-wireless", 20))
        assertNull(connectionOf("802-3-ethernet", 70))
        assertEquals(CellGenerations.FOUR_G, cellGenerationOf(1L shl 14))
        assertEquals(CellGenerations.FIVE_G, cellGenerationOf((1L shl 14) or (1L shl 15)))
        assertEquals(CellGenerations.THREE_G, cellGenerationOf(1L shl 8))
        assertEquals(CellGenerations.TWO_G, cellGenerationOf(1L shl 4))
        assertEquals(CellGenerations.NONE, cellGenerationOf(0))
    }

    @Test
    fun `permission keys read as words`() {
        assertEquals("Send data to api.example.com", labelOf("Internet/api.example.com"))
        assertEquals("Your location", labelOf("Location"))
        assertEquals("Control your lights (via Hue)", labelOf("abc:HomeControl", mapOf("abc:HomeControl" to "Control your lights (via Hue)")))
    }

    private val appA = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val appB = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private fun row(app: Uuid, key: String, granted: Boolean? = null, at: Long = 0, seen: Long? = null) =
        LockerAppPermission(app, key, granted, requestedAtMs = at, seenAtMs = seen)

    @Test
    fun `perms list shows the effective status, blocked where libpebble3 lists none`() {
        val rows = listOf(row(appA, "Location"), row(appA, "Internet/x.org"), row(appA, "Calendar", granted = true),
            row(appA, "Contacts", granted = false), row(appA, "LegacyPebbleKit", granted = true))
        val statuses = listOf(
            AppPermissionStatus("Location", PermissionStatus.AllowedAutomatically),
            AppPermissionStatus("Internet/x.org", PermissionStatus.Waiting),
            AppPermissionStatus("Calendar", PermissionStatus.Granted),
            AppPermissionStatus("Contacts", PermissionStatus.Denied),
        )
        assertEquals(
            listOf("Calendar" to "granted", "Contacts" to "denied", "Internet/x.org" to "waiting",
                "LegacyPebbleKit" to "blocked", "Location" to "auto"),
            permissionRecords(rows, statuses).map { (r, s) -> r.permission to s },
        )
    }

    @Test
    fun `pending covers apps that aren't running, oldest first, and keeps put-off requests`() {
        val rows = listOf(
            row(appB, "Location", at = 30),
            row(appA, "Internet/x.org", at = 20, seen = 25),
            row(appA, "Location", at = 10),
            row(appA, "Calendar", at = 5),             // allowed automatically: not pending
            row(appB, "Contacts", granted = true, at = 1),
        )
        val statuses = mapOf(
            appA to listOf(AppPermissionStatus("Internet/x.org", PermissionStatus.Waiting),
                AppPermissionStatus("Location", PermissionStatus.Waiting),
                AppPermissionStatus("Calendar", PermissionStatus.AllowedAutomatically)),
            appB to listOf(AppPermissionStatus("Location", PermissionStatus.Waiting),
                AppPermissionStatus("Contacts", PermissionStatus.Granted)),
        )
        val pending = pendingRequests(rows, statuses, running = setOf(appB))
        assertEquals(listOf("Location@A", "Internet/x.org@A", "Location@B"),
            pending.map { "${it.row.permission}@${if (it.row.appUuid == appA) "A" else "B"}" })
        assertEquals(listOf(false, false, true), pending.map { it.running })
        assertEquals(listOf(null, 25L, null), pending.map { it.row.seenAtMs })
        assertTrue(pendingRequests(rows, emptyMap(), emptySet()).isEmpty(), "an app libpebble3 doesn't know asks nothing")
    }
}

class LocalPluginOAuthTest {
    @Test
    fun `a connector needs a client, both endpoints and a loopback redirect`() {
        val ok = mapOf("client_id" to "abc", "authorize_url" to "https://todoist.com/oauth/authorize",
            "token_url" to "https://todoist.com/oauth/access_token", "redirect_uri" to "http://127.0.0.1:47811/oauth", "scope" to "data:read_write")
        val c = assertNotNull(OAuthConnector.parse("todoist", ok))
        assertEquals("data:read_write", c.scope)
        assertNull(OAuthConnector.parse("todoist", ok - "client_id"))
        assertNull(OAuthConnector.parse("todoist", ok + ("redirect_uri" to "https://example.com/cb")), "only loopback redirects")
    }

    @Test
    fun `refreshing without a stored token asks to sign in again`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("oauth").toFile()
        java.io.File(dir, "todoist.conf").writeText("""
            client_id = abc
            authorize_url = https://todoist.com/oauth/authorize
            token_url = https://127.0.0.1:1/token
            redirect_uri = http://127.0.0.1:47811/oauth
        """.trimIndent())
        val oauth = LocalPluginOAuth(dir, FileSecretStore(Files.createTempFile("s", "").toFile())) { _, _ -> }
        assertNotNull(oauth.connector("todoist"))
        assertNull(oauth.connector("../etc"))
        val e = runCatching { oauth.refresh("p", "todoist", "nohandle") }.exceptionOrNull()
        assertEquals("AUTH_REQUIRED", (e as io.rebble.libpebblecommon.plugin.PluginNativeException).code)
    }
}
