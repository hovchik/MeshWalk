package com.meshwalk.app.ui.peers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.ConnectionType
import com.meshwalk.app.domain.model.IdentityType
import com.meshwalk.app.domain.model.PeerNode
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.PeerRepository
import com.meshwalk.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PeersViewModel @Inject constructor(
    private val peerRepo: PeerRepository,
    private val conversationRepo: ConversationRepository
) : ViewModel() {

    val peers: StateFlow<List<PeerNode>> = peerRepo.observeNearbyPeers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun startChat(peerNodeId: String, peerDisplayName: String?, onNavigate: (conversationId: String, peerNodeId: String) -> Unit) {
        viewModelScope.launch {
            val conversation = conversationRepo.getOrCreateDirectConversation(peerNodeId, peerDisplayName)
            onNavigate(conversation.conversationId, peerNodeId)
        }
    }
}

@Composable
fun PeersScreen(
    onStartChat: (conversationId: String, peerNodeId: String) -> Unit,
    viewModel: PeersViewModel = hiltViewModel()
) {
    val peers by viewModel.peers.collectAsState()

    if (peers.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Scanning for nearby nodes...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Make sure Bluetooth and Wi-Fi are enabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    } else {
        Column {
            Text(
                text = "${peers.size} found",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                val directPeers = peers.filter { it.isDirect }
                val relayPeers = peers.filter { !it.isDirect }

                if (directPeers.isNotEmpty()) {
                    item {
                        Text(
                            "Direct (${directPeers.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(directPeers, key = { it.nodeId }) { peer ->
                        PeerItem(peer = peer, onChat = {
                            viewModel.startChat(peer.nodeId, peer.displayName, onStartChat)
                        })
                    }
                }

                if (relayPeers.isNotEmpty()) {
                    item {
                        Text(
                            "Via Relay (${relayPeers.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(relayPeers, key = { it.nodeId }) { peer ->
                        PeerItem(peer = peer, onChat = {
                            viewModel.startChat(peer.nodeId, peer.displayName, onStartChat)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerItem(peer: PeerNode, onChat: () -> Unit) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(peer.displayName ?: peer.nodeId.take(8))
                if (peer.identityType == IdentityType.ANONYMOUS) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = "Anonymous",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val typeIcon = when (peer.connectionType) {
                    ConnectionType.BLE -> Icons.Filled.Bluetooth
                    ConnectionType.WIFI_DIRECT -> Icons.Filled.Wifi
                    ConnectionType.NEARBY_CONNECTIONS -> Icons.Filled.WifiTethering
                    else -> Icons.Filled.DeviceHub
                }
                Icon(typeIcon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (peer.isDirect) "Direct" else "${peer.hopCount} hops",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = TimeUtils.formatTimestamp(peer.lastSeen),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        leadingContent = {
            Icon(
                if (peer.isConnected) Icons.Filled.WifiTethering else Icons.Filled.Sensors,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (peer.isConnected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline
            )
        },
        trailingContent = {
            FilledTonalIconButton(onClick = onChat) {
                Icon(Icons.Filled.Chat, "Start chat")
            }
        }
    )
}
