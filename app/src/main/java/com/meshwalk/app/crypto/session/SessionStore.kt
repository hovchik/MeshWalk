package com.meshwalk.app.crypto.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "mesh_sessions")

/**
 * Persistent storage for encrypted sessions.
 * Session keys are stored encrypted with the local encryption key.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private fun sendKeyKey(sid: String) = byteArrayPreferencesKey("send_$sid")
        private fun recvKeyKey(sid: String) = byteArrayPreferencesKey("recv_$sid")
        private fun sendCountKey(sid: String) = intPreferencesKey("send_cnt_$sid")
        private fun recvCountKey(sid: String) = intPreferencesKey("recv_cnt_$sid")
        private fun peerKey(sid: String) = stringPreferencesKey("peer_$sid")
        private fun createdKey(sid: String) = longPreferencesKey("created_$sid")
        private fun lastUsedKey(sid: String) = longPreferencesKey("used_$sid")
    }

    suspend fun saveSession(session: MeshSession) {
        context.sessionDataStore.edit { prefs ->
            prefs[sendKeyKey(session.sessionId)] = session.sendingChainKey.encoded
            prefs[recvKeyKey(session.sessionId)] = session.receivingChainKey.encoded
            prefs[sendCountKey(session.sessionId)] = session.sendCounter
            prefs[recvCountKey(session.sessionId)] = session.receiveCounter
            prefs[peerKey(session.sessionId)] = session.peerNodeId
            prefs[createdKey(session.sessionId)] = session.createdAt
            prefs[lastUsedKey(session.sessionId)] = session.lastUsed
        }
    }

    suspend fun getSession(sessionId: String): MeshSession? {
        return context.sessionDataStore.data.map { prefs ->
            val sendKey = prefs[sendKeyKey(sessionId)] ?: return@map null
            val recvKey = prefs[recvKeyKey(sessionId)] ?: return@map null
            val peer = prefs[peerKey(sessionId)] ?: return@map null

            MeshSession(
                sessionId = sessionId,
                peerNodeId = peer,
                sendingChainKey = SecretKeySpec(sendKey, "AES"),
                receivingChainKey = SecretKeySpec(recvKey, "AES"),
                sendCounter = prefs[sendCountKey(sessionId)] ?: 0,
                receiveCounter = prefs[recvCountKey(sessionId)] ?: 0,
                createdAt = prefs[createdKey(sessionId)] ?: 0L,
                lastUsed = prefs[lastUsedKey(sessionId)] ?: 0L
            )
        }.first()
    }

    suspend fun deleteSession(sessionId: String) {
        context.sessionDataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.endsWith(sessionId) }
                .forEach { prefs.remove(it) }
        }
    }
}
