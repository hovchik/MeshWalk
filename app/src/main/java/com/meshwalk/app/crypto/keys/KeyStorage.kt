package com.meshwalk.app.crypto.keys

import android.content.Context
import android.provider.Settings
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meshwalk.app.domain.usecase.IdentityKeyPair
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.keyDataStore: DataStore<Preferences> by preferencesDataStore(name = "mesh_keys")

/**
 * Key storage using DataStore with Base64 encoding.
 *
 * DataStore Preferences only supports primitive types and String, so all ByteArray
 * values are stored as Base64-encoded strings. Decoding failures are logged and
 * return null rather than crashing.
 */
@Singleton
class KeyStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private fun signingPrivateKey(nodeId: String) =
            stringPreferencesKey("signing_priv_$nodeId")
        private fun signingPublicKey(nodeId: String) =
            stringPreferencesKey("signing_pub_$nodeId")
        private fun exchangePrivateKey(nodeId: String) =
            stringPreferencesKey("exchange_priv_$nodeId")
        private fun exchangePublicKey(nodeId: String) =
            stringPreferencesKey("exchange_pub_$nodeId")
        private fun preKeyPrivate(nodeId: String, preKeyId: Int) =
            stringPreferencesKey("prekey_priv_${nodeId}_$preKeyId")
        private fun preKeyPublic(nodeId: String, preKeyId: Int) =
            stringPreferencesKey("prekey_pub_${nodeId}_$preKeyId")
        private val ACTIVE_NODE_ID = stringPreferencesKey("active_node_id")

        private fun ByteArray.toBase64(): String =
            Base64.encodeToString(this, Base64.NO_WRAP)

        private fun String.fromBase64(): ByteArray? = try {
            Base64.decode(this, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Failed to decode Base64 key material")
            null
        }
    }

    suspend fun storeIdentityKeys(nodeId: String, keyPair: IdentityKeyPair) {
        context.keyDataStore.edit { prefs ->
            prefs[signingPrivateKey(nodeId)] = keyPair.signingPrivateKey.toBase64()
            prefs[signingPublicKey(nodeId)] = keyPair.signingPublicKey.toBase64()
            prefs[exchangePrivateKey(nodeId)] = keyPair.exchangePrivateKey.toBase64()
            prefs[exchangePublicKey(nodeId)] = keyPair.exchangePublicKey.toBase64()
        }
    }

    suspend fun getSigningPrivateKey(nodeId: String): ByteArray? {
        return context.keyDataStore.data
            .map { it[signingPrivateKey(nodeId)]?.fromBase64() }
            .first()
    }

    suspend fun getSigningPublicKey(nodeId: String): ByteArray? {
        return context.keyDataStore.data
            .map { it[signingPublicKey(nodeId)]?.fromBase64() }
            .first()
    }

    suspend fun getExchangePrivateKey(nodeId: String): ByteArray? {
        return context.keyDataStore.data
            .map { it[exchangePrivateKey(nodeId)]?.fromBase64() }
            .first()
    }

    suspend fun getExchangePublicKey(nodeId: String): ByteArray? {
        return context.keyDataStore.data
            .map { it[exchangePublicKey(nodeId)]?.fromBase64() }
            .first()
    }

    suspend fun storePreKey(nodeId: String, preKey: PreKey) {
        context.keyDataStore.edit { prefs ->
            prefs[preKeyPrivate(nodeId, preKey.preKeyId)] = preKey.privateKey.toBase64()
            prefs[preKeyPublic(nodeId, preKey.preKeyId)] = preKey.publicKey.toBase64()
        }
    }

    suspend fun getPreKeyPrivate(nodeId: String, preKeyId: Int): ByteArray? {
        return context.keyDataStore.data
            .map { it[preKeyPrivate(nodeId, preKeyId)]?.fromBase64() }
            .first()
    }

    suspend fun setActiveNodeId(nodeId: String) {
        context.keyDataStore.edit { it[ACTIVE_NODE_ID] = nodeId }
    }

    suspend fun getActiveNodeId(): String? {
        return context.keyDataStore.data.map { it[ACTIVE_NODE_ID] }.first()
    }

    suspend fun deleteKeys(nodeId: String) {
        context.keyDataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.contains(nodeId) }
                .forEach { prefs.remove(it) }
        }
    }

    /**
     * Returns a stable, device-based node ID that persists across app reinstalls.
     *
     * The ID is derived from Android's ANDROID_ID (unique per device+user combination,
     * survives app uninstall/reinstall, changes only on factory reset).
     * This ensures the same device always gets the same node ID on the mesh network.
     */
    fun getDeviceNodeId(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        return UUID.nameUUIDFromBytes("meshwalk:$androidId".toByteArray(Charsets.UTF_8)).toString()
    }
}
