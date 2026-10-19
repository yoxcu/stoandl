package de.yoxcu.stoandl.ext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `stoandl ext config`: the settings table, the value checks and the ExtSetConfig payload. */
class ExtConfigCliTest {
    private val fields = configFields("""[
        {"key":"homeserver","type":"string","label":"Homeserver URL","required":true},
        {"key":"password","type":"string","label":"Password","secret":true,"required":true},
        {"key":"notify","type":"enum","label":"Notify on","options":["pushrules","mentions","all"]},
        {"key":"history","type":"bool","label":"History"},
        {"key":"limit","type":"int","label":"Limit"},
        {"label":"no key"}
    ]""")

    @Test
    fun `schema rows become fields, keyless rows are skipped`() {
        assertEquals(listOf("homeserver", "password", "notify", "history", "limit"), fields.map { it.key })
        assertEquals(ExtConfigField("notify", "enum", "Notify on", false, false, listOf("pushrules", "mentions", "all")), fields[2])
        assertEquals("k", configFields("""[{"key":"k"}]""").single().label, "label defaults to the key")
        assertEquals(emptyList(), configFields("nope"))
    }

    @Test
    fun `the table shows values, a secret only as set or not, and the choices`() {
        val values = Json.parseToJsonElement(
            """{"homeserver":"https://m.org","password":"","notify":"mentions","history":"true","limit":"","_secrets":"password"}""").jsonObject
        val rows = extConfigTable(fields, values)
        assertEquals(5, rows.size)
        assertTrue(rows[0].contains("https://m.org") && rows[0].contains("required"), rows[0])
        assertTrue(rows[1].contains("(set)") && rows[1].contains("secret"), rows[1])
        assertTrue(rows[2].contains("pushrules|mentions|all"), rows[2])
        assertTrue(rows[4].contains("—"), "an empty value shows as a dash: ${rows[4]}")
        val unset = extConfigTable(fields, Json.parseToJsonElement("""{"_secrets":""}""").jsonObject)
        assertTrue(unset[1].contains("(not set)"), unset[1])
    }

    @Test
    fun `without a schema the table lists the keys the extension has`() {
        val rows = extConfigTable(emptyList(), Json.parseToJsonElement("""{"b":"2","a":"1","_secrets":""}""").jsonObject)
        assertEquals(2, rows.size)
        assertTrue(rows[0].trim().startsWith("a"), rows[0])
        assertEquals(emptyList(), extConfigTable(emptyList(), Json.parseToJsonElement("""{"_secrets":""}""").jsonObject))
    }

    @Test
    fun `values are checked against the field type`() {
        val byKey = fields.associateBy { it.key }
        assertEquals("true", extConfigValue(byKey["history"], "on").getOrThrow())
        assertEquals("false", extConfigValue(byKey["history"], "No").getOrThrow())
        assertTrue(extConfigValue(byKey["history"], "maybe").isFailure)
        assertEquals("25", extConfigValue(byKey["limit"], " 25 ").getOrThrow())
        assertTrue(extConfigValue(byKey["limit"], "lots").isFailure)
        assertEquals("mentions", extConfigValue(byKey["notify"], "MENTIONS").getOrThrow(), "an enum choice in its declared case")
        assertTrue(extConfigValue(byKey["notify"], "none").exceptionOrNull()!!.message!!.contains("pushrules, mentions, all"))
        assertEquals(" a b ", extConfigValue(byKey["homeserver"], " a b ").getOrThrow(), "text is taken as typed")
        assertEquals("x", extConfigValue(null, "x").getOrThrow(), "a key without a schema takes any text")
    }

    @Test
    fun `the payload sets a key, and unset removes a secret but blanks a plain key`() {
        assertEquals("p\"w", Json.parseToJsonElement(extConfigPayload("password", "p\"w", secret = true)).jsonObject["password"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, Json.parseToJsonElement(extConfigPayload("password", null, secret = true)).jsonObject["password"])
        assertEquals("", Json.parseToJsonElement(extConfigPayload("limit", null, secret = false)).jsonObject["limit"]!!.jsonPrimitive.content)
    }
}
