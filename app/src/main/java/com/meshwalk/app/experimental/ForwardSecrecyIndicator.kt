package com.meshwalk.app.experimental

import com.meshwalk.app.domain.model.ForwardSecrecyMode
import com.meshwalk.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 6 — Partial Double Ratchet mode indicator.
 *
 * The existing SessionManager already uses a chain-ratchet for partial
 * forward secrecy. When the user opts in to [ForwardSecrecyMode.DOUBLE_RATCHET],
 * new outgoing sessions will advance the ratchet more aggressively — and the
 * UI shows a distinct indicator on the message bubble so the user can tell
 * which forward-secrecy guarantee applied to each message.
 *
 * This module is a pure indicator/adapter: it exposes the current mode and
 * provides the label/tooltip copy shown next to each outgoing message.
 */
@Singleton
class ForwardSecrecyIndicator @Inject constructor(
    private val settingsRepo: SettingsRepository
) {
    fun observeMode(): Flow<ForwardSecrecyMode> = settingsRepo.observeSettings().map {
        if (it.experimental.partialDoubleRatchetEnabled) ForwardSecrecyMode.DOUBLE_RATCHET
        else ForwardSecrecyMode.CHAIN_RATCHET
    }

    fun label(mode: ForwardSecrecyMode): String = when (mode) {
        ForwardSecrecyMode.CHAIN_RATCHET -> "Chain"
        ForwardSecrecyMode.DOUBLE_RATCHET -> "Double"
    }

    fun tooltip(mode: ForwardSecrecyMode): String = when (mode) {
        ForwardSecrecyMode.CHAIN_RATCHET ->
            "Chain ratchet — forward secrecy degraded in store-and-forward."
        ForwardSecrecyMode.DOUBLE_RATCHET ->
            "Double ratchet — stronger forward secrecy. Store-and-forward delivery may fail if keys rotate before delivery."
    }
}
