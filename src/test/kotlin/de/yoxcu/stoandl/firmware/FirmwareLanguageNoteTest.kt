package de.yoxcu.stoandl.firmware

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PebbleOS removed its built-in translations in 4.38.0, and on asterix (Pebble 2 Duo) in 4.37.0. The
 *  update flow warns only when an update crosses that release. */
class FirmwareLanguageNoteTest {
    private fun drops(board: String, current: String, latest: String) =
        FirmwareControl.dropsBuiltInLanguages(board, current, latest)

    @Test
    fun `crossing 4_38 warns`() {
        assertTrue(drops("obelix_pvt", "v4.37.0", "v4.38.2"))
        assertTrue(drops("getafix_dvt2", "v4.36.2", "v4.38.0"))
    }

    @Test
    fun `asterix lost them in 4_37 already`() {
        assertTrue(drops("asterix", "v4.36.2", "v4.37.0"))
        assertFalse(drops("asterix", "v4.37.0", "v4.38.2"))
        assertFalse(drops("obelix_pvt", "v4.36.2", "v4.37.0"))
    }

    @Test
    fun `no warning when already past it, for older targets or unversioned tags`() {
        assertFalse(drops("obelix_pvt", "v4.38.1", "v4.38.2"))
        assertFalse(drops("obelix_pvt", "v4.38.2", "v4.30.3"))
        assertFalse(drops("snowy_dvt", "v4.3", "v4.4.3-rbl"))
        assertFalse(drops("obelix_pvt", "v4.37.0", "nightly"))
    }
}
