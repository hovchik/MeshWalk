package com.meshwalk.app.ui.peers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.ConnectionType
import com.meshwalk.app.domain.model.IdentityType
import com.meshwalk.app.domain.model.PeerNode
import com.meshwalk.app.domain.repository.BlockedPeer
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.PeerRepository
import com.meshwalk.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class PeersViewModel @Inject constructor(
    private val peerRepo: PeerRepository,
    private val conversationRepo: ConversationRepository,
    private val transportManager: com.meshwalk.app.transport.manager.TransportManager
) : ViewModel() {

    val peers: StateFlow<List<PeerNode>> = peerRepo.observeNearbyPeers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blockedPeers: StateFlow<List<BlockedPeer>> = peerRepo.observeBlockedPeers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** nodeId -> nickname, kept in-memory and synced to ConversationRepository */
    private val _nicknames = MutableStateFlow<Map<String, String>>(emptyMap())
    val nicknames: StateFlow<Map<String, String>> = _nicknames.asStateFlow()

    /** nodeId set of favorites */
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    fun startChat(
        peerNodeId: String,
        peerDisplayName: String?,
        onNavigate: (conversationId: String, peerNodeId: String) -> Unit
    ) {
        viewModelScope.launch {
            val conversation = conversationRepo.getOrCreateDirectConversation(peerNodeId, peerDisplayName)
            onNavigate(conversation.conversationId, peerNodeId)
        }
    }

    fun rescanPeers() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                transportManager.rescanPeers()
            } finally {
                // Keep indicator visible briefly so user sees feedback
                kotlinx.coroutines.delay(1500)
                _isScanning.value = false
            }
        }
    }

    fun removePeer(nodeId: String) {
        viewModelScope.launch {
            peerRepo.removePeer(nodeId)
        }
    }

    fun blockPeer(nodeId: String, displayName: String?) {
        viewModelScope.launch {
            peerRepo.blockPeer(nodeId, displayName)
        }
    }

    fun unblockPeer(nodeId: String) {
        viewModelScope.launch {
            peerRepo.unblockPeer(nodeId)
        }
    }

    /**
     * Sets a contact nickname for a peer. Persists through the direct conversation
     * record in [ConversationRepository].
     */
    fun setNickname(nodeId: String, displayName: String?, nickname: String) {
        viewModelScope.launch {
            val trimmed = nickname.trim()
            if (trimmed.isNotEmpty()) {
                _nicknames.update { it + (nodeId to trimmed) }
            } else {
                _nicknames.update { it - nodeId }
            }
            // Persist via conversation repo
            val conversation = conversationRepo.getOrCreateDirectConversation(nodeId, displayName)
            conversationRepo.updateNickname(
                conversation.conversationId,
                trimmed.ifEmpty { null }
            )
        }
    }

    fun toggleFavorite(nodeId: String) {
        _favorites.update { current ->
            if (nodeId in current) current - nodeId else current + nodeId
        }
    }

    /** Mark a peer's safety number as verified (or revoke verification). */
    fun setVerified(nodeId: String, verified: Boolean) {
        viewModelScope.launch {
            peerRepo.setVerified(nodeId, verified)
        }
    }
}

// ---------------------------------------------------------------------------
// PeersScreen
// ---------------------------------------------------------------------------

@Composable
fun PeersScreen(
    onStartChat: (conversationId: String, peerNodeId: String) -> Unit,
    viewModel: PeersViewModel = hiltViewModel()
) {
    val peers by viewModel.peers.collectAsState()
    val blockedPeers by viewModel.blockedPeers.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val nicknames by viewModel.nicknames.collectAsState()
    val favorites by viewModel.favorites.collectAsState()

    // Confirmation dialog state
    var confirmAction by remember { mutableStateOf<PeerAction?>(null) }

    // Nickname dialog state
    var nicknameTarget by remember { mutableStateOf<PeerNode?>(null) }

    // Safety-number verification dialog state
    var verifyTarget by remember { mutableStateOf<PeerNode?>(null) }

    // ---------- Safety number dialog ----------
    verifyTarget?.let { peer ->
        AlertDialog(
            onDismissRequest = { verifyTarget = null },
            title = { Text("Verify ${peer.displayName ?: peer.nodeId.take(8)}") },
            text = {
                Column {
                    Text(
                        "Compare this safety number with your contact in person or over a " +
                            "trusted channel. If it matches on both devices, your connection " +
                            "is not being intercepted.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = peer.safetyNumber ?: "Unavailable (no key yet)",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                if (peer.isVerified) {
                    TextButton(onClick = {
                        viewModel.setVerified(peer.nodeId, false)
                        verifyTarget = null
                    }) { Text("Unverify") }
                } else {
                    TextButton(
                        enabled = peer.safetyNumber != null,
                        onClick = {
                            viewModel.setVerified(peer.nodeId, true)
                            verifyTarget = null
                        }
                    ) { Text("Mark verified") }
                }
            },
            dismissButton = {
                TextButton(onClick = { verifyTarget = null }) { Text("Close") }
            }
        )
    }

    // ---------- Confirmation dialog ----------
    if (confirmAction != null) {
        val action = confirmAction!!
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = {
                Text(if (action is PeerAction.Block) "Block Peer" else "Remove Peer")
            },
            text = {
                val name = action.displayName ?: action.nodeId.take(8)
                Text(
                    if (action is PeerAction.Block)
                        "Block $name? You won't receive messages from them and they won't appear in your peer list."
                    else
                        "Remove $name from your peer list? They may reappear on the next scan."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (action) {
                            is PeerAction.Block -> viewModel.blockPeer(action.nodeId, action.displayName)
                            is PeerAction.Remove -> viewModel.removePeer(action.nodeId)
                        }
                        confirmAction = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(if (action is PeerAction.Block) "Block" else "Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ---------- Nickname dialog ----------
    if (nicknameTarget != null) {
        val peer = nicknameTarget!!
        var nicknameText by remember(peer.nodeId) {
            mutableStateOf(nicknames[peer.nodeId] ?: "")
        }
        AlertDialog(
            onDismissRequest = { nicknameTarget = null },
            title = { Text("Set Nickname") },
            text = {
                Column {
                    Text(
                        "Set a nickname for ${peer.displayName ?: peer.nodeId.take(8)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nicknameText,
                        onValueChange = { nicknameText = it },
                        label = { Text("Nickname") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setNickname(peer.nodeId, peer.displayName, nicknameText)
                    nicknameTarget = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { nicknameTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ---------- Content ----------
    if (peers.isEmpty() && blockedPeers.isEmpty()) {
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
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { viewModel.rescanPeers() },
                    enabled = !isScanning
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isScanning) "Rescanning..." else "Rescan")
                }
            }
        }
    } else {
        // Partition peers
        val favoritePeers = peers.filter { it.nodeId in favorites }
        val nonFavoritePeers = peers.filter { it.nodeId !in favorites }
        val directPeers = nonFavoritePeers.filter { it.isDirect }
        val relayPeers = nonFavoritePeers.filter { !it.isDirect }

        Box(modifier = Modifier.fillMaxSize()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${peers.size} found",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { viewModel.rescanPeers() },
                        enabled = !isScanning
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(if (isScanning) "Rescanning..." else "Rescan")
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    // ---- Favorites section ----
                    if (favoritePeers.isNotEmpty()) {
                        item {
                            Text(
                                "Favorites (${favoritePeers.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(favoritePeers, key = { "fav_${it.nodeId}" }) { peer ->
                            PeerItem(
                                peer = peer,
                                nickname = nicknames[peer.nodeId],
                                isFavorite = true,
                                onChat = { viewModel.startChat(peer.nodeId, peer.displayName, onStartChat) },
                                onRemove = { confirmAction = PeerAction.Remove(peer.nodeId, peer.displayName) },
                                onBlock = { confirmAction = PeerAction.Block(peer.nodeId, peer.displayName) },
                                onSetNickname = { nicknameTarget = peer },
                                onToggleFavorite = { viewModel.toggleFavorite(peer.nodeId) },
                                onVerify = { verifyTarget = peer }
                            )
                        }
                    }

                    // ---- Direct peers section ----
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
                            PeerItem(
                                peer = peer,
                                nickname = nicknames[peer.nodeId],
                                isFavorite = false,
                                onChat = { viewModel.startChat(peer.nodeId, peer.displayName, onStartChat) },
                                onRemove = { confirmAction = PeerAction.Remove(peer.nodeId, peer.displayName) },
                                onBlock = { confirmAction = PeerAction.Block(peer.nodeId, peer.displayName) },
                                onSetNickname = { nicknameTarget = peer },
                                onToggleFavorite = { viewModel.toggleFavorite(peer.nodeId) },
                                onVerify = { verifyTarget = peer }
                            )
                        }
                    }

                    // ---- Relay peers section ----
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
                            PeerItem(
                                peer = peer,
                                nickname = nicknames[peer.nodeId],
                                isFavorite = false,
                                onChat = { viewModel.startChat(peer.nodeId, peer.displayName, onStartChat) },
                                onRemove = { confirmAction = PeerAction.Remove(peer.nodeId, peer.displayName) },
                                onBlock = { confirmAction = PeerAction.Block(peer.nodeId, peer.displayName) },
                                onSetNickname = { nicknameTarget = peer },
                                onToggleFavorite = { viewModel.toggleFavorite(peer.nodeId) },
                                onVerify = { verifyTarget = peer }
                            )
                        }
                    }

                    // ---- Blocked section ----
                    if (blockedPeers.isNotEmpty()) {
                        item {
                            Text(
                                "Blocked (${blockedPeers.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(blockedPeers, key = { it.nodeId }) { blocked ->
                            BlockedPeerItem(
                                blockedPeer = blocked,
                                onUnblock = { viewModel.unblockPeer(blocked.nodeId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Signal strength helpers
// ---------------------------------------------------------------------------

/**
 * Maps an RSSI value to a 0-4 bar count.
 *  > -50  -> 4 bars (excellent)
 *  > -65  -> 3 bars (good)
 *  > -75  -> 2 bars (fair)
 *  > -85  -> 1 bar  (weak)
 *  else   -> 0 bars (no signal / unknown)
 */
private fun rssiToBars(rssi: Int?): Int {
    if (rssi == null) return -1 // unknown
    return when {
        rssi > -50 -> 4
        rssi > -65 -> 3
        rssi > -75 -> 2
        rssi > -85 -> 1
        else -> 0
    }
}

/**
 * Draws a small 4-bar signal-strength indicator.
 * Each bar is a small rounded rectangle; filled bars use [activeColor],
 * unfilled bars use [inactiveColor].
 */
@Composable
private fun SignalStrengthIndicator(
    bars: Int,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.outlineVariant
) {
    val barCount = 4
    Box(
        modifier = modifier
            .width(20.dp)
            .height(16.dp)
            .drawBehind {
                val totalBars = barCount
                val gap = 1.dp.toPx()
                val barWidth = (size.width - gap * (totalBars - 1)) / totalBars
                for (i in 0 until totalBars) {
                    val fraction = (i + 1).toFloat() / totalBars
                    val barHeight = size.height * fraction
                    val x = i * (barWidth + gap)
                    val y = size.height - barHeight
                    val color = if (i < bars) activeColor else inactiveColor
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(1.dp.toPx())
                    )
                }
            }
    )
}

/**
 * Returns a tint color for signal bars.
 */
@Composable
private fun signalBarColor(bars: Int): Color = when {
    bars >= 4 -> MaterialTheme.colorScheme.primary
    bars == 3 -> MaterialTheme.colorScheme.primary
    bars == 2 -> MaterialTheme.colorScheme.tertiary
    bars == 1 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

// ---------------------------------------------------------------------------
// Battery level helper
// ---------------------------------------------------------------------------

/**
 * Picks a battery icon based on an approximate level derived from signal
 * strength (used as a hint only when explicit battery data is unavailable).
 * The PeerNode model does not carry a dedicated battery field, so we show
 * a battery hint icon when `signalStrength` is available, giving the user
 * a rough visual cue (stronger signal often correlates with proximity /
 * better device state).
 */
@Composable
private fun BatteryHintIcon(
    signalStrength: Int?,
    modifier: Modifier = Modifier
) {
    if (signalStrength == null) return
    // Use signal as a rough proxy for battery hint
    val icon = when {
        signalStrength > -50 -> Icons.Filled.BatteryFull
        signalStrength > -65 -> Icons.Filled.BatteryFull
        signalStrength > -75 -> Icons.Filled.BatteryStd
        signalStrength > -85 -> Icons.Filled.BatteryAlert
        else -> Icons.Filled.BatteryAlert
    }
    val tint = when {
        signalStrength > -65 -> MaterialTheme.colorScheme.primary
        signalStrength > -85 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Icon(
        icon,
        contentDescription = "Battery hint",
        modifier = modifier.size(14.dp),
        tint = tint.copy(alpha = 0.7f)
    )
}

// ---------------------------------------------------------------------------
// PeerItem
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeerItem(
    peer: PeerNode,
    nickname: String?,
    isFavorite: Boolean,
    onChat: () -> Unit,
    onRemove: () -> Unit,
    onBlock: () -> Unit,
    onSetNickname: () -> Unit,
    onToggleFavorite: () -> Unit,
    onVerify: () -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val bars = rssiToBars(peer.signalStrength)

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = { /* normal tap does nothing extra */ },
            onLongClick = { menuExpanded = true }
        ),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Show nickname if set, with original name in parentheses
                if (nickname != null) {
                    Text(
                        text = nickname,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "(${peer.displayName ?: peer.nodeId.take(8)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = peer.displayName ?: peer.nodeId.take(8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (peer.identityType == IdentityType.ANONYMOUS) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = "Anonymous",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
                if (peer.isVerified) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.VerifiedUser,
                        contentDescription = "Verified",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (isFavorite) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Favorite",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        },
        supportingContent = {
            Column {
                // Row 1: connection type, signal, hop count, last seen
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val typeIcon = when (peer.connectionType) {
                        ConnectionType.BLE -> Icons.Filled.Bluetooth
                        ConnectionType.WIFI_DIRECT -> Icons.Filled.Wifi
                        ConnectionType.NEARBY_CONNECTIONS -> Icons.Filled.WifiTethering
                        else -> Icons.Filled.DeviceHub
                    }
                    Icon(
                        typeIcon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (peer.isDirect) "Direct" else "${peer.hopCount} hops",
                        style = MaterialTheme.typography.bodySmall
                    )

                    // Signal strength bars
                    if (bars >= 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        SignalStrengthIndicator(
                            bars = bars,
                            activeColor = signalBarColor(bars)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "${peer.signalStrength} dBm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    // Battery hint
                    Spacer(modifier = Modifier.width(8.dp))
                    BatteryHintIcon(signalStrength = peer.signalStrength)

                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = TimeUtils.formatTimestamp(peer.lastSeen),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                // Row 2: extra info (connection type label, relay capable)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.connectionType.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (peer.relayCapable) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Hub,
                            contentDescription = "Relay capable",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Relay",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (peer.fingerprint != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Fingerprint,
                            contentDescription = "Verified",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    }
                }
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
            Row {
                FilledTonalIconButton(onClick = onChat) {
                    Icon(Icons.Filled.Chat, "Start chat")
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Set Nickname") },
                            onClick = {
                                menuExpanded = false
                                onSetNickname()
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Edit, contentDescription = null)
                            }
                        )
                        if (peer.publicExchangeKey != null) {
                            DropdownMenuItem(
                                text = { Text(if (peer.isVerified) "Verified ✓" else "Verify safety number") },
                                onClick = {
                                    menuExpanded = false
                                    onVerify()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.VerifiedUser,
                                        contentDescription = null,
                                        tint = if (peer.isVerified) MaterialTheme.colorScheme.primary
                                        else LocalContentColor.current
                                    )
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites")
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleFavorite()
                            },
                            leadingIcon = {
                                Icon(
                                    if (isFavorite) Icons.Filled.StarBorder else Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            onClick = {
                                menuExpanded = false
                                onRemove()
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.PersonRemove, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Block") },
                            onClick = {
                                menuExpanded = false
                                onBlock()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Block,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }
    )
}

// ---------------------------------------------------------------------------
// BlockedPeerItem
// ---------------------------------------------------------------------------

@Composable
private fun BlockedPeerItem(
    blockedPeer: BlockedPeer,
    onUnblock: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                blockedPeer.displayName ?: blockedPeer.nodeId.take(8),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        supportingContent = {
            Text(
                text = "Blocked ${TimeUtils.formatTimestamp(blockedPeer.blockedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        },
        leadingContent = {
            Icon(
                Icons.Filled.Block,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
            )
        },
        trailingContent = {
            OutlinedButton(onClick = onUnblock) {
                Text("Unblock")
            }
        }
    )
}

// ---------------------------------------------------------------------------
// PeerAction
// ---------------------------------------------------------------------------

private sealed interface PeerAction {
    val nodeId: String
    val displayName: String?
    data class Remove(override val nodeId: String, override val displayName: String?) : PeerAction
    data class Block(override val nodeId: String, override val displayName: String?) : PeerAction
}
