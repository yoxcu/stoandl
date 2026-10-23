package de.yoxcu.stoandl.calendar

import com.sun.net.httpserver.HttpServer
import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.secret.FileSecretStore
import de.yoxcu.stoandl.util.Flock
import de.yoxcu.stoandl.util.stoandlHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import org.w3c.dom.Element

/** To-dos: the VTODO text, the due forms the assistant sends, which CalDAV account a list belongs to, and
 *  the writer's local targets and offline queue (against a local HttpServer standing in for CalDAV). */
class TodosTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    // 2026-10-21 12:00:00 UTC
    private val now = Instant.fromEpochSeconds(1_792_584_000)

    // --- the iCalendar text --------------------------------------------------------------------------

    @Test
    fun `a timed to-do is due in UTC with an alarm at that time`() {
        val due = TodoDue.parse("2026-10-22T17:00", berlin)
        val ics = TodoIcs.vtodo(NewTodo("Call the garage", due, "ask about tyres"), "u1@stoandl", now, berlin)
        val lines = ics.split("\r\n")
        assertTrue("SUMMARY:Call the garage" in lines)
        assertTrue("DESCRIPTION:ask about tyres" in lines)
        assertTrue("DUE:20261022T150000Z" in lines, ics)
        assertTrue("TRIGGER;VALUE=DATE-TIME:20261022T150000Z" in lines, ics)
        assertTrue("DTSTAMP:20261021T120000Z" in lines, ics)
        assertTrue(ics.startsWith("BEGIN:VTODO\r\n") && ics.endsWith("END:VTODO\r\n"))
    }

    @Test
    fun `a due day is a date with a morning alarm, and no due means no alarm`() {
        val day = TodoIcs.vtodo(NewTodo("Bins", TodoDue.parse("2026-10-23", berlin)), "u", now, berlin)
        assertTrue("DUE;VALUE=DATE:20261023" in day.lines().map { it.trimEnd('\r') })
        assertTrue("TRIGGER;VALUE=DATE-TIME:20261023T070000Z" in day, day) // 09:00 in Berlin (CEST)
        val none = TodoIcs.vtodo(NewTodo("Milk"), "u", now, berlin)
        assertFalse("DUE" in none)
        assertFalse("VALARM" in none)
        assertFalse("DESCRIPTION" in none)
    }

    @Test
    fun `the due forms the assistant sends are read`() {
        assertEquals(TodoDue.Day(LocalDate.of(2026, 10, 22)), TodoDue.parse(" 2026-10-22 ", berlin))
        assertEquals(TodoDue.At(Instant.fromEpochSeconds(1_792_681_200)), TodoDue.parse("2026-10-22T17:00", berlin))
        assertEquals(TodoDue.At(Instant.fromEpochSeconds(1_792_681_200)), TodoDue.parse("2026-10-22T15:00Z", berlin))
        assertEquals(TodoDue.At(Instant.fromEpochSeconds(1_792_681_200)), TodoDue.parse("2026-10-22T17:00+02:00", berlin))
        assertNull(TodoDue.parse("tomorrow", berlin))
    }

    @Test
    fun `the log shows the due as read, in the local zone`() {
        assertEquals("2026-10-22T20:00+02:00", TodoDue.parse("2026-10-22T18:00Z", berlin)!!.describe(berlin))
        assertEquals("2026-10-22, all day", TodoDue.parse("2026-10-22", berlin)!!.describe(berlin))
    }

    @Test
    fun `text is escaped and long lines fold without splitting a character`() {
        assertEquals("a\\, b\\; c\\\\d\\ne", TodoIcs.escape("a, b; c\\d\ne"))
        val line = "SUMMARY:" + "ä".repeat(60)
        val folded = TodoIcs.fold(line)
        val parts = folded.split("\r\n")
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(it.toByteArray().size <= 75, "'$it' is longer than 75 octets") }
        assertEquals(line, parts.first() + parts.drop(1).joinToString("") { it.removePrefix(" ") })
    }

    @Test
    fun `the VTODO comes back out of its calendar`() {
        val vtodo = TodoIcs.vtodo(NewTodo("Milk"), "u", now, berlin)
        assertEquals(vtodo, TodoIcs.vtodoOf(TodoIcs.calendar(vtodo)))
    }

    @Test
    fun `a to-do goes into an existing calendar in its own line endings`() {
        val vtodo = TodoIcs.vtodo(NewTodo("Milk"), "u", now, berlin)
        val lf = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:e\nEND:VEVENT\nEND:VCALENDAR\n"
        val out = TodoIcs.insert(lf, vtodo)
        assertFalse('\r' in out)
        assertTrue("END:VEVENT\nBEGIN:VTODO\n" in out, out)
        assertTrue(out.endsWith("END:VTODO\nEND:VCALENDAR\n"), out)
        assertTrue("END:VEVENT\r\nBEGIN:VTODO\r\n" in TodoIcs.insert(lf.replace("\n", "\r\n"), vtodo))
        // No newline before END:VCALENDAR, and no calendar at all.
        assertTrue("UID:e\nBEGIN:VTODO\n" in TodoIcs.insert("BEGIN:VCALENDAR\nUID:e\nEND:VCALENDAR", vtodo))
        assertTrue(TodoIcs.insert("", vtodo).startsWith("BEGIN:VCALENDAR\r\n"))
    }

    // --- which account a list belongs to -------------------------------------------------------------

    private val home = StoandlConfig.CalDavAccount("a1", "https://dav.example.org/remote.php/dav", "alice")
    private val work = StoandlConfig.CalDavAccount("w2", "https://work.example.com/caldav/", "bob")

    @Test
    fun `a list is matched to the account it was found under, then by URL, then by host`() {
        val lists = listOf(home, work)
        assertEquals(work, caldavAccountFor("https://dav.example.org/x/", lists) { "caldav:w2" })
        assertEquals(home, caldavAccountFor("https://dav.example.org/remote.php/dav/calendars/alice/tasks/", lists) { null })
        assertEquals(work, caldavAccountFor("https://WORK.example.com/elsewhere/tasks/", lists) { null })
        assertNull(caldavAccountFor("https://other.example.net/tasks/", lists) { null })
        // Two accounts on one host and a URL under neither: no guess.
        val twin = StoandlConfig.CalDavAccount("t3", "https://work.example.com/other/", "carol")
        assertNull(caldavAccountFor("https://work.example.com/third/", listOf(work, twin)) { null })
    }

    @Test
    fun `a collection takes to-dos unless its component set leaves VTODO out`() {
        fun response(prop: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(
                """<d:response xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:propstat><d:prop>$prop</d:prop></d:propstat></d:response>""".toByteArray()))
            .documentElement
        assertTrue(response("").takesTodos())
        assertTrue(response("<c:supported-calendar-component-set/>").takesTodos())
        assertTrue(response("""<c:supported-calendar-component-set><c:comp name="VEVENT"/><c:comp name="VTODO"/></c:supported-calendar-component-set>""").takesTodos())
        assertFalse(response("""<c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>""").takesTodos())
    }

    // --- the writer ----------------------------------------------------------------------------------

    private data class Put(val path: String, val auth: String?, val ifNoneMatch: String?, val body: String)

    private val puts = CopyOnWriteArrayList<Put>()
    @Volatile private var status = 201
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            puts += Put(ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"),
                ex.requestHeaders.getFirst("If-None-Match"), ex.requestBody.readBytes().decodeToString())
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val tmp = Files.createTempDirectory("todos").toFile()
    private val queue = File(tmp, "queue")
    private val secrets = FileSecretStore(File(tmp, "secrets")).apply { put("caldav:a1", "pw") }
    private val client = stoandlHttpClient()
    private var target = ""
    private var account = StoandlConfig.CalDavAccount("a1", "$base/dav/", "alice")

    /** Calindori's `lockFile` per calendar file. */
    private val syncLocks = mutableMapOf<File, File>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val writer = TodoWriter(
        target = { target }, accounts = { listOf(account) }, accountIdFor = { null },
        secretStore = secrets, client = client, queueDir = queue, scope = scope, zone = { berlin }, now = { now },
        lockFileFor = { syncLocks[it] }, lockWait = 300.milliseconds,
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.stop(0)
        client.close()
        tmp.deleteRecursively()
    }

    @Test
    fun `no list chosen is off`() = runBlocking {
        assertEquals(TodoWriter.Result.Off, writer.add(NewTodo("Milk")))
    }

    @Test
    fun `a to-do is PUT into the list with the account's sign-in`() = runBlocking {
        target = "$base/dav/calendars/alice/tasks/"
        assertEquals(TodoWriter.Result.Saved, writer.add(NewTodo("Milk")))
        val put = puts.single()
        assertTrue(put.path.startsWith("/dav/calendars/alice/tasks/") && put.path.endsWith(".ics"), put.path)
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("alice:pw".toByteArray()), put.auth)
        assertEquals("*", put.ifNoneMatch)
        assertTrue(put.body.startsWith("BEGIN:VCALENDAR\r\n") && "SUMMARY:Milk" in put.body)
    }

    @Test
    fun `an unreachable server queues the to-do and a later flush sends it`() = runBlocking {
        target = "http://127.0.0.1:1/dav/tasks/"
        account = StoandlConfig.CalDavAccount("a1", "http://127.0.0.1:1/dav/", "alice")
        assertEquals(TodoWriter.Result.Queued(""), writer.add(NewTodo("Milk")))
        assertEquals(listOf("Milk"), writer.pending().map { it.title })
        assertTrue(writer.pending().single().lastError.startsWith("Unreachable"))

        // The server is back (same list moved nowhere: the queued target is retried as is).
        val queued = writer.pending().single()
        val file = queue.listFiles()!!.single()
        file.writeText(file.readText().replace("http://127.0.0.1:1", base))
        writer.flush()
        assertEquals(emptyList(), writer.pending())
        assertEquals("/dav/tasks/${queued.uid.substringBefore('@')}.ics", puts.single().path)
    }

    @Test
    fun `a refusing server queues with its reason, a missing list fails`() = runBlocking {
        target = "$base/dav/tasks/"
        status = 401
        assertEquals(TodoWriter.Result.Queued("Sign-in failed (401)"), writer.add(NewTodo("Milk")))
        status = 404
        assertIs<TodoWriter.Result.Failed>(writer.add(NewTodo("Eggs")))
        // The flush before the second write met the 404 too: a list that is gone drops what waits for it.
        assertEquals(emptyList(), writer.pending())
    }

    @Test
    fun `a list on a host without an account fails`() = runBlocking {
        target = "https://elsewhere.example/tasks/"
        val r = writer.add(NewTodo("Milk"))
        assertIs<TodoWriter.Result.Failed>(r)
        assertTrue("elsewhere.example" in r.message)
    }

    @Test
    fun `a local file gets the to-do added, a folder gets a file per to-do`() = runBlocking {
        val ics = File(tmp, "personal.ics").apply { writeText("BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR\n") }
        target = ics.path
        assertEquals(TodoWriter.Result.Saved, writer.add(NewTodo("Milk")))
        assertEquals(TodoWriter.Result.Saved, writer.add(NewTodo("Eggs")))
        val text = ics.readText()
        assertEquals(2, Regex("BEGIN:VTODO").findAll(text).count())
        assertTrue(text.trimEnd().endsWith("END:VCALENDAR"))

        val vdir = File(tmp, "vdir").apply { mkdirs() }
        target = vdir.path
        assertEquals(TodoWriter.Result.Saved, writer.add(NewTodo("Bread")))
        val written = vdir.listFiles()!!.single()
        assertTrue(written.name.endsWith(".ics") && "SUMMARY:Bread" in written.readText())

        target = File(tmp, "missing/x.ics").path
        assertIs<TodoWriter.Result.Failed>(writer.add(NewTodo("Jam")))
        assertTrue(puts.isEmpty())
    }

    @Test
    fun `a file a sync holds waits for the sync, then a flush writes the to-do`() = runBlocking {
        val ics = File(tmp, "work.ics").apply { writeText("BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR\n") }
        val lock = File(tmp, "dav-sync.lock")
        syncLocks[ics] = lock
        target = ics.path

        // Free: written under the lock, which is let go again.
        assertEquals(TodoWriter.Result.Saved, writer.add(NewTodo("Milk")))
        assertFalse(Flock.isHeld(lock))

        // Held by the sync for longer than the writer waits: queued, the file untouched.
        val sync = Flock.acquire(lock, Duration.ZERO)!!
        val before = ics.readText()
        assertEquals(TodoWriter.Result.Queued(SYNC_RUNNING), writer.add(NewTodo("Eggs")))
        assertEquals(listOf("Eggs" to SYNC_RUNNING), writer.pending().map { it.title to it.lastError })
        writer.flush()
        assertEquals(before, ics.readText())
        assertEquals(1, writer.pending().size)

        // The sync is over: the flush after it writes the to-do into the file the sync left.
        ics.writeText(before.replace("VERSION:2.0", "VERSION:2.0\nX-SYNCED:yes"))
        sync.close()
        writer.flush()
        assertEquals(emptyList(), writer.pending())
        val text = ics.readText()
        assertTrue("X-SYNCED:yes" in text && "SUMMARY:Milk" in text && "SUMMARY:Eggs" in text, text)
        assertTrue(text.trimEnd().endsWith("END:VCALENDAR"))
    }

    @Test
    fun `a to-do a sync kept back is written once the sync lets go, without a calendar sync`() = runBlocking {
        val ics = File(tmp, "work.ics").apply { writeText("BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR\n") }
        val lock = File(tmp, "dav-sync.lock")
        syncLocks[ics] = lock
        target = ics.path

        // A sync that writes nothing the file watch would see (vdirsyncer offline, say): no flush comes.
        val sync = Flock.acquire(lock, Duration.ZERO)!!
        assertEquals(TodoWriter.Result.Queued(SYNC_RUNNING), writer.add(NewTodo("Eggs")))
        assertEquals(TodoWriter.Result.Queued(SYNC_RUNNING), writer.add(NewTodo("Milk")))
        delay(1_500)
        assertEquals(2, writer.pending().size, "nothing while the sync holds the lock")
        sync.close()
        withTimeout(5_000) { while (writer.pending().isNotEmpty()) delay(50) }
        val text = ics.readText()
        assertTrue("SUMMARY:Eggs" in text && "SUMMARY:Milk" in text, text)
        assertFalse(Flock.isHeld(lock))
    }
}
