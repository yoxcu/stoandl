package de.yoxcu.stoandl.config

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** QOL-D8: a hand edit of stoandl.conf is seen and applied key by key, like a SetConfig. */
class ConfigReloadTest {
    @Test
    fun `only the keys whose value changed are reported, in schema order`() {
        val before = StoandlConfig.defaults()
        val after = before.copy(datalog = !before.datalog, weatherEnabled = !before.weatherEnabled)
        assertEquals(listOf("weather.enabled", "datalog.enabled"), changedConfigFields(before, after).map { it.key })
        assertEquals(emptyList(), changedConfigFields(before, before.copy()))
    }

    @Test
    fun `restart keys are told apart from live ones`() {
        val before = StoandlConfig.defaults()
        val changed = changedConfigFields(before, before.copy(classicDiscover = !before.classicDiscover))
        assertEquals(listOf(ConfigApply.RESTART), changed.map { it.apply })
    }

    @Test
    fun `an edit that replaces the file is seen once it settles`() = runBlocking {
        val dir = Files.createTempDirectory("confwatch").toFile()
        try {
            val conf = File(dir, "stoandl.conf").apply { writeText("weather.enabled = false\n") }
            val seen = async { fileEdits(conf, settle = 100.milliseconds).first() }
            delay(300) // let the watch register
            File(dir, "other.txt").writeText("not the conf")
            val tmp = File(dir, "stoandl.conf.tmp").apply { writeText("weather.enabled = true\n") }
            Files.move(tmp.toPath(), conf.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            withTimeout(10_000) { seen.await() }
        } finally {
            dir.deleteRecursively()
        }
    }
}
