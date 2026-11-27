package de.yoxcu.stoandl.testutil

import io.rebble.libpebblecommon.connection.ActiveDevice
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckState
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.RealConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.endpointmanager.LanguagePackInstallState
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import java.lang.reflect.Proxy
import kotlin.time.Instant

/**
 * A connected watch as one libpebble3 `watches` emission sees it: a new object per call, as libpebble3 builds
 * one. [ConnectedPebbleDevice] is sealed upstream (no Proxy can implement it), so this is libpebble3's own
 * [RealConnectedPebbleDevice] (the daemon's tests are friends of the fork module) over strict stubs: the
 * watch's identity, board, firmware, capabilities and language-pack state are the arguments, [language]
 * answers the language-pack calls, and anything else throws.
 */
internal fun fakeConnectedWatch(
    platform: WatchHardwarePlatform = WatchHardwarePlatform.CORE_ASTERIX,
    firmware: String = "v4.41.1",
    capabilities: Set<ProtocolCapsFlag> = emptySet(),
    installState: LanguagePackInstallState = LanguagePackInstallState.Idle(),
    language: ConnectedPebble.LanguageInstall = strictStub(),
    identifier: String = "AA:BB:CC:DD:EE:FF",
): ConnectedPebbleDevice {
    val id = object : PebbleIdentifier {
        override val asString: String = identifier
        override fun equals(other: Any?) = other is PebbleIdentifier && other.asString == asString
        override fun hashCode() = asString.hashCode()
    }
    return RealConnectedPebbleDevice(
        watchInfo = watchInfo(platform, firmware, capabilities, identifier),
        knownDevice = strictStub<KnownPebbleDevice> { method, _ ->
            if (method == "getIdentifier") id else unexpected("KnownPebbleDevice", method)
        },
        activeDevice = strictStub<ActiveDevice>(),
        services = ConnectedPebble.Services(
            debug = strictStub(), appRunState = strictStub(), firmware = strictStub<FirmwareUpdater>(),
            messages = strictStub(), time = strictStub(), appMessages = strictStub(), logs = strictStub(),
            coreDump = strictStub(), music = strictStub(), pkjs = strictStub(), companionAppControl = strictStub(),
            devConnection = strictStub(), screenshot = strictStub(), language = language, health = strictStub(),
        ),
        firmwareUpdateState = FirmwareUpdater.FirmwareUpdateStatus.NotInProgress.Idle(),
        firmwareUpdateAvailable = FirmwareUpdateCheckState(false, null),
        batteryLevel = null,
        languagePackInstallState = installState,
        installedLanguagePack = null,
    )
}

/** A [T] that answers through [answer] (by method name; without one every call throws), with identity
 *  `equals`/`hashCode`. A suspend function's stub answers with its plain return value. */
internal inline fun <reified T : Any> strictStub(noinline answer: ((String, Array<Any?>?) -> Any?)? = null): T {
    val type = T::class.simpleName
    return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "toString" -> "stub $type"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> if (answer != null) answer(method.name, args) else unexpected(type, method.name)
        }
    } as T
}

internal fun unexpected(type: String?, method: String): Nothing = throw UnsupportedOperationException("$type.$method")

private fun version(tag: String, recovery: Boolean) = FirmwareVersion.from(
    tag, isRecovery = recovery, gitHash = "", timestamp = Instant.DISTANT_PAST, isDualSlot = false, isSlot0 = false,
)!!

private fun watchInfo(platform: WatchHardwarePlatform, firmware: String, capabilities: Set<ProtocolCapsFlag>, address: String) =
    WatchInfo(
        runningFwVersion = version(firmware, recovery = false),
        recoveryFwVersion = version(firmware, recovery = true),
        platform = platform,
        bootloaderTimestamp = Instant.DISTANT_PAST,
        board = platform.revision,
        serial = "XXXXXXXXXXXX",
        btAddress = address,
        resourceCrc = 0,
        resourceTimestamp = Instant.DISTANT_PAST,
        language = "en_US",
        languageVersion = 1,
        capabilities = capabilities,
        isUnfaithful = false,
        healthInsightsVersion = null,
        javascriptVersion = null,
        color = WatchColor.Pebble2DuoBlack,
    )
