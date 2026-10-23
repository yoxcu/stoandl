package de.yoxcu.stoandl.calendar

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Calindori's calendar list (`calindorirc`): its own calendars and the external files it shows, under its names. */
class CalindoriRcTest {
    private val own = File("/home/u/.local/share/calindori")

    @Test
    fun `external calendars come from their group's file, own ones from Calindori's folder`() {
        // Shaped like the op6's: vdirsyncer singlefile copies, plus read-only shared groups Calindori doesn't list.
        val rc = """
            [general]
            activeCalendar=work
            calendars=personal
            externalCalendars=wichtig;work;gone

            [events]
            duration=60

            [work]
            external=true
            file=/home/u/.local/share/op6-dav/calendars/mick/3F-5FE31880.ics

            [work (auer@example.org)]
            external=true
            file=/home/u/.local/share/op6-dav/calendars/mick/auer_work.ics

            [wichtig]
            color=#FF0000
            file = /home/u/.local/share/op6-dav/calendars/mick/4A-5FE31C80.ics
        """.trimIndent()
        assertEquals(
            listOf(
                NamedIcs("personal", File(own, "calindori_personal.ics")),
                NamedIcs("wichtig", File("/home/u/.local/share/op6-dav/calendars/mick/4A-5FE31C80.ics")),
                NamedIcs("work", File("/home/u/.local/share/op6-dav/calendars/mick/3F-5FE31880.ics")),
            ),
            parseCalindoriRc(rc, own),
        )
    }

    @Test
    fun `no general group, or the same file twice`() {
        assertEquals(emptyList(), parseCalindoriRc("[work]\nfile=/x.ics\n", own))
        val twice = "[general]\nexternalCalendars=a;b\n[a]\nfile=/c/x.ics\n[b]\nfile=/c/./x.ics\n"
        assertEquals(listOf(NamedIcs("a", File("/c/x.ics"))), parseCalindoriRc(twice, own))
    }

    @Test
    fun `a group's lockFile is under the runtime dir unless absolute`() {
        // As the op6's op6-dav-register writes it, and Calindori r103 resolves it.
        val rc = """
            [general]
            calendars=own
            externalCalendars=work;shared;elsewhere;blank

            [own]
            lockFile=own.lock

            [work]
            file=/d/work.ics
            lockFile=dav-sync.lock

            [shared]
            file=/d/shared.ics

            [elsewhere]
            file=/d/elsewhere.ics
            lockFile=/var/lock/sync.lock

            [blank]
            file=/d/blank.ics
            lockFile=
        """.trimIndent()
        val run = File("/run/user/1000")
        assertEquals(
            listOf(
                NamedIcs("own", File(own, "calindori_own.ics"), File(run, "own.lock")),
                NamedIcs("work", File("/d/work.ics"), File(run, "dav-sync.lock")),
                NamedIcs("shared", File("/d/shared.ics")),
                NamedIcs("elsewhere", File("/d/elsewhere.ics"), File("/var/lock/sync.lock")),
                NamedIcs("blank", File("/d/blank.ics")),
            ),
            parseCalindoriRc(rc, own, run),
        )
        // No $XDG_RUNTIME_DIR: a relative lock can't be found, an absolute one still is.
        assertEquals(
            listOf(null, null, null, File("/var/lock/sync.lock"), null),
            parseCalindoriRc(rc, own, runtimeDir = null).map { it.lockFile },
        )
    }

    @Test
    fun `the sync locks are by calendar file, for the calendars that name one`() {
        val sync = File("/run/user/1000/dav-sync.lock")
        val calendars = listOf(
            NamedIcs("work", File("/d/./work.ics"), sync),
            NamedIcs("home", File("/d/home.ics"), sync),
            NamedIcs("local", File("/d/local.ics")),
            NamedIcs("other", File("/e/other.ics"), File("/var/lock/other.lock")),
        )
        assertEquals(
            mapOf(File("/d/work.ics") to sync, File("/d/home.ics") to sync, File("/e/other.ics") to File("/var/lock/other.lock")),
            syncLocksOf(calendars),
        )
    }
}
