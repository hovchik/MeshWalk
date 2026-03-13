package com.meshwalk.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.NodeIdentity
import com.meshwalk.app.domain.model.PeerNode
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.domain.repository.PeerRepository
import com.meshwalk.app.mesh.service.MeshForegroundService
import com.meshwalk.app.ui.components.MeshStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    identityRepo: IdentityRepository,
    peerRepo: PeerRepository
) : ViewModel() {

    val identity: StateFlow<NodeIdentity?> = identityRepo.observeActiveIdentity()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val peers: StateFlow<List<PeerNode>> = peerRepo.observeNearbyPeers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val meshStatus: StateFlow<MeshStatus> = combine(
        peers,
        MeshForegroundService.isRunning
    ) { peerList, serviceRunning ->
        when {
            peerList.any { it.isConnected } -> MeshStatus.ONLINE
            peerList.isNotEmpty() -> MeshStatus.SCANNING
            serviceRunning -> MeshStatus.SCANNING
            else -> MeshStatus.OFFLINE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MeshStatus.OFFLINE)

    val nearestPeer: StateFlow<PeerNode?> = peers.map { peerList ->
        peerList
            .filter { it.isDirect }
            .minByOrNull { it.hopCount * 1000 - (it.signalStrength ?: -100) }
            ?: peerList.minByOrNull { it.hopCount }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
