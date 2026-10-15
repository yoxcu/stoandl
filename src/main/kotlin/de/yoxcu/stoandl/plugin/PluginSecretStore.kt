@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.plugin

import de.yoxcu.stoandl.secret.SecretStore
import io.rebble.libpebblecommon.storage.PluginSecrets
import io.rebble.libpebblecommon.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Plugins' `Pebble.secureStorage` on stoandl's secret store (the keyring, else the 0600 `secrets` file, both
 * out of backups): each value under `plugin/<uuid>/<key>`, plus an index of the plugin's keys so [destroy]
 * (the app was removed) can delete them all.
 */
class PluginSecretStore(private val store: SecretStore) : PluginSecrets {
    override fun forPlugin(pluginUuid: Uuid): SecureStorage = object : SecureStorage {
        private val prefix = "plugin/$pluginUuid/"
        private val indexRef = "plugin/$pluginUuid"

        private fun keys(): Set<String> = store.get(indexRef)?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        private fun writeKeys(k: Set<String>) = if (k.isEmpty()) store.remove(indexRef) else { store.put(indexRef, k.joinToString("\n")); Unit }

        override suspend fun get(key: String): String? = withContext(Dispatchers.IO) { store.get(prefix + key) }

        override suspend fun set(key: String, value: String) = withContext(Dispatchers.IO) {
            synchronized(this@PluginSecretStore) {
                store.put(prefix + key, value)
                writeKeys(keys() + key)
            }
        }

        override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
            synchronized(this@PluginSecretStore) {
                store.remove(prefix + key)
                writeKeys(keys() - key)
            }
        }

        override suspend fun destroy() = withContext(Dispatchers.IO) {
            synchronized(this@PluginSecretStore) {
                keys().forEach { store.remove(prefix + it) }
                store.remove(indexRef)
            }
        }
    }
}
