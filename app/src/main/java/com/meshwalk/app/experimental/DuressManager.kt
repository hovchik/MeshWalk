package com.meshwalk.app.experimental

import com.meshwalk.app.domain.model.ExperimentalFeatures
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.MessageRepository
import com.meshwalk.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.firstOrNull
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 13 — Self-destruct on duress PIN.
 *
 * The user may configure a duress PIN separate from their normal unlock
 * biometric. When the duress PIN is entered, the app silently wipes all
 * local messages and conversations, then completes authentication as if
 * nothing happened. Intended for high-risk users who may be forced to unlock
 * their device under coercion.
 */
enum class DuressUnlockResult {
    /** Normal unlock — PIN is blank/no duress PIN set. */
    NOT_APPLICABLE,
    /** Duress PIN matched — wipe succeeded. Proceed with normal unlock UI. */
    TRIGGERED,
    /** PIN did not match duress PIN — caller should not wipe. */
    NOT_A_DURESS_PIN
}

@Singleton
class DuressManager @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository
) {
    suspend fun setDuressPin(pin: String?) {
        val settings = settingsRepo.getSettings()
        val hash = pin?.takeIf { it.isNotBlank() }?.let { hashPin(it) }
        settingsRepo.updateSettings(
            settings.copy(
                experimental = settings.experimental.copy(
                    duressPinEnabled = hash != null,
                    duressPinHash = hash
                )
            )
        )
    }

    suspend fun checkAndTrigger(enteredPin: String): DuressUnlockResult {
        val settings = settingsRepo.getSettings()
        val cfg: ExperimentalFeatures = settings.experimental
        if (!cfg.duressPinEnabled || cfg.duressPinHash == null) return DuressUnlockResult.NOT_APPLICABLE
        val entered = hashPin(enteredPin)
        if (!constantTimeEquals(entered, cfg.duressPinHash)) return DuressUnlockResult.NOT_A_DURESS_PIN
        wipeLocalData()
        return DuressUnlockResult.TRIGGERED
    }

    private suspend fun wipeLocalData() {
        val conversations = conversationRepo.observeConversations().firstOrNull().orEmpty()
        for (convo in conversations) {
            messageRepo.deleteMessagesByConversation(convo.conversationId)
            conversationRepo.deleteConversation(convo.conversationId)
        }
    }

    private fun hashPin(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("meshwalk-duress-v1".toByteArray(Charsets.UTF_8))
        md.update(pin.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
