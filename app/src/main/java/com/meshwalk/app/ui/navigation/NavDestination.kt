package com.meshwalk.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Chats("chats", "Chats", Icons.Default.Chat),
    Peers("peers", "Peers", Icons.Default.People),
    Network("network", "Network", Icons.Default.Hub),
    Settings("settings", "Settings", Icons.Default.Settings)
}
