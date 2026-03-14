package com.meshwalk.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meshwalk.app.domain.model.IdentityType
import com.meshwalk.app.domain.model.NodeIdentity
import com.meshwalk.app.domain.model.PeerNode

enum class MeshStatus(val label: String) {
    ONLINE("Online"),
    SCANNING("Scanning"),
    OFFLINE("Offline")
}

@Composable
fun TopMenuBar(
    identity: NodeIdentity?,
    meshStatus: MeshStatus,
    nearestPeer: PeerNode?,
    modifier: Modifier = Modifier
) {
    val statusColor by animateColorAsState(
        targetValue = when (meshStatus) {
            MeshStatus.ONLINE -> MaterialTheme.colorScheme.primary
            MeshStatus.SCANNING -> MaterialTheme.colorScheme.tertiary
            MeshStatus.OFFLINE -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(400),
        label = "statusColor"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                val icon = when (identity?.identityType) {
                    IdentityType.ANONYMOUS -> Icons.Filled.VisibilityOff
                    IdentityType.TEMPORARY -> Icons.Filled.Timer
                    else -> Icons.Filled.Person
                }
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Username + status
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = identity?.displayName ?: "Anonymous",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = meshStatus.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Nearest peer
            if (nearestPeer != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (nearestPeer.isConnected) Icons.Filled.WifiTethering
                            else Icons.Filled.Sensors,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = nearestPeer.displayName
                                ?: nearestPeer.nodeId.take(8),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 100.dp)
                        )
                    }
                }
            }
        }
    }
}
