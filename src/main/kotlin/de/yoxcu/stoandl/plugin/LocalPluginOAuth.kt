package de.yoxcu.stoandl.plugin

import com.sun.net.httpserver.HttpServer
import de.yoxcu.stoandl.secret.SecretStore
import de.yoxcu.stoandl.util.ConfFile
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.plugin.PluginErrors
import io.rebble.libpebblecommon.plugin.PluginNativeException
import io.rebble.libpebblecommon.plugin.PluginOAuthApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.HexFormat

private val log = KotlinLogging.logger {}

/** How long a sign-in waits for the user to finish in the browser. */
private const val SIGN_IN_TIMEOUT_MS = 5 * 60_000L

/**
 * One OAuth connector the user registered themselves, from `<configDir>/oauth/<slug>.conf`:
 * `client_id`, `authorize_url`, `token_url`, `redirect_uri` (an `http://127.0.0.1:<port>/…` address
 * registered with the provider), optional `client_secret`, `scope` and `extra` (more authorize
 * parameters, `a=b&c=d`).
 */
internal data class OAuthConnector(
    val slug: String,
    val clientId: String,
    val clientSecret: String?,
    val authorizeUrl: String,
    val tokenUrl: String,
    val redirectUri: String,
    val scope: String?,
    val extra: String?,
) {
    companion object {
        fun parse(slug: String, map: Map<String, String>): OAuthConnector? {
            fun req(k: String) = map[k]?.trim()?.takeIf { it.isNotEmpty() }
            val redirect = req("redirect_uri") ?: return null
            if (!redirect.startsWith("http://127.0.0.1:") && !redirect.startsWith("http://localhost:")) return null
            return OAuthConnector(
                slug = slug,
                clientId = req("client_id") ?: return null,
                clientSecret = req("client_secret"),
                authorizeUrl = req("authorize_url") ?: return null,
                tokenUrl = req("token_url") ?: return null,
                redirectUri = redirect,
                scope = req("scope"),
                extra = req("extra"),
            )
        }
    }
}

/**
 * Plugins' `Pebble.oauth` without the official app store's broker: the user registers an OAuth app with
 * the provider and describes it in `<configDir>/oauth/<slug>.conf` (the slug a plugin declares). Sign-in is
 * the authorization-code flow with PKCE in the browser, back to a loopback redirect. The refresh token stays
 * in the secret store; the plugin gets the access token and an opaque handle for [refresh], the same
 * contract as the broker's `broker_refresh`.
 */
class LocalPluginOAuth(
    private val dir: File,
    private val secrets: SecretStore,
    /** Shows the sign-in page: the browser, plus a desktop notification when it can't be opened. */
    private val openUrl: (title: String, url: String) -> Unit,
) : PluginOAuthApi {
    // Built on first sign-in or refresh: a JDK HttpClient starts its selector thread when built, and most
    // hosts never configure a connector.
    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build() }
    private val random = SecureRandom()

    internal fun connector(slug: String): OAuthConnector? {
        if (!slug.matches(Regex("[A-Za-z0-9_.-]+"))) return null
        val f = File(dir, "$slug.conf")
        if (!f.isFile) return null
        return OAuthConnector.parse(slug, ConfFile.read(f))
    }

    override suspend fun authorize(pluginUuid: String, connectorSlug: String): JsonObject {
        val c = connector(connectorSlug) ?: throw PluginNativeException(
            PluginErrors.PLUGIN_UNAVAILABLE,
            "no OAuth connector '$connectorSlug' set up (see ${File(dir, "$connectorSlug.conf")})",
        )
        val verifier = token(32)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = token(16)
        val code = awaitRedirect(c, state, challenge) { url -> openUrl("Sign in for $connectorSlug", url) }
            ?: throw PluginNativeException(PluginErrors.AUTH_REQUIRED, "sign-in wasn't completed")
        val tokens = tokenRequest(c, buildMap {
            put("grant_type", "authorization_code"); put("code", code); put("redirect_uri", c.redirectUri)
            put("code_verifier", verifier)
        })
        log.info { "OAuth: signed in to $connectorSlug for plugin $pluginUuid" }
        return answer(pluginUuid, c, tokens, handle = null)
    }

    override suspend fun refresh(pluginUuid: String, connectorSlug: String, brokerRefresh: String): JsonObject {
        val c = connector(connectorSlug) ?: throw PluginNativeException(PluginErrors.PLUGIN_UNAVAILABLE, "no OAuth connector '$connectorSlug'")
        val ref = refreshRef(pluginUuid, connectorSlug, brokerRefresh)
        val refreshToken = withContext(Dispatchers.IO) { secrets.get(ref) }
            ?: throw PluginNativeException(PluginErrors.AUTH_REQUIRED, "sign in again")
        val tokens = tokenRequest(c, mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken))
        return answer(pluginUuid, c, tokens, handle = brokerRefresh)
    }

    /** The plugin's answer; a new refresh token replaces the stored one under the same handle. */
    private suspend fun answer(pluginUuid: String, c: OAuthConnector, tokens: JsonObject, handle: String?): JsonObject {
        val access = tokens["access_token"]?.jsonPrimitive?.content
            ?: throw PluginNativeException(PluginErrors.AUTH_REQUIRED, "the provider returned no access token")
        val h = handle ?: token(16)
        tokens["refresh_token"]?.jsonPrimitive?.content?.let { rt ->
            withContext(Dispatchers.IO) { secrets.put(refreshRef(pluginUuid, c.slug, h), rt) }
        }
        return buildJsonObject {
            put("access_token", access)
            tokens["expires_in"]?.jsonPrimitive?.longOrNull?.let { put("expires_in", it) }
            put("broker_refresh", h)
        }
    }

    private suspend fun tokenRequest(c: OAuthConnector, form: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val body = (form + buildMap {
            put("client_id", c.clientId)
            c.clientSecret?.let { put("client_secret", it) }
        }).entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val r = http.send(
            HttpRequest.newBuilder(URI(c.tokenUrl)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (r.statusCode() !in 200..299) {
            log.warn { "OAuth token request to ${c.slug} failed: HTTP ${r.statusCode()}" }
            throw PluginNativeException(PluginErrors.AUTH_REQUIRED, "the provider refused (HTTP ${r.statusCode()})")
        }
        Json.parseToJsonElement(r.body()).jsonObject
    }

    /** Serve the connector's redirect address until the provider sends the user back with [state]. */
    private suspend fun awaitRedirect(c: OAuthConnector, state: String, challenge: String, open: (String) -> Unit): String? {
        val redirect = URI(c.redirectUri)
        val got = CompletableDeferred<String?>()
        val server = withContext(Dispatchers.IO) {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), redirect.port), 0)
        }
        server.createContext(redirect.path.ifEmpty { "/" }) { ex ->
            ex.use {
                val q = (ex.requestURI.rawQuery ?: "").split('&').mapNotNull {
                    val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), Charsets.UTF_8)
                }.toMap()
                val ok = q["state"] == state && q["code"] != null
                val msg = if (ok) "Signed in. You can close this tab." else "Sign-in failed: ${q["error"] ?: "unexpected answer"}"
                val bytes = "<!doctype html><meta charset=utf-8><body style=\"font-family:sans-serif;padding:2em\">$msg</body>".toByteArray()
                ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.write(bytes)
                if (q["state"] == state) got.complete(q["code"])
            }
        }
        server.start()
        try {
            val url = c.authorizeUrl + (if ('?' in c.authorizeUrl) "&" else "?") + listOfNotNull(
                "response_type=code", "client_id=" + enc(c.clientId), "redirect_uri=" + enc(c.redirectUri),
                "state=$state", "code_challenge=$challenge", "code_challenge_method=S256",
                c.scope?.let { "scope=" + enc(it) }, c.extra,
            ).joinToString("&")
            open(url)
            return withTimeoutOrNull(SIGN_IN_TIMEOUT_MS) { got.await() }
        } finally {
            server.stop(0)
        }
    }

    private fun refreshRef(pluginUuid: String, slug: String, handle: String) = "oauth/$pluginUuid/$slug/$handle"
    private fun token(bytes: Int) = HexFormat.of().formatHex(ByteArray(bytes).also(random::nextBytes))
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
