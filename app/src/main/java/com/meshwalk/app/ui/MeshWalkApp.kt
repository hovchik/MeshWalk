package com.meshwalk.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.meshwalk.app.ui.chat.ChatListScreen
import com.meshwalk.app.ui.chat.ChatScreen
import com.meshwalk.app.ui.diagnostics.DiagnosticsScreen
import com.meshwalk.app.ui.groups.GroupListScreen
import com.meshwalk.app.ui.identity.IdentitySetupScreen
import com.meshwalk.app.ui.network.NetworkGraphScreen
import com.meshwalk.app.ui.onboarding.OnboardingScreen
import com.meshwalk.app.ui.peers.PeersScreen
import com.meshwalk.app.ui.settings.SettingsScreen
import com.meshwalk.app.ui.components.TopMenuBar

// -- Navigation Routes --
object Routes {
    const val ONBOARDING = "onboarding"
    const val IDENTITY_SETUP = "identity_setup"
    const val CHATS = "chats"
    const val CHAT_DETAIL = "chat/{conversationId}/{peerNodeId}"
    const val GROUPS = "groups"
    const val PEERS = "peers"
    const val NETWORK = "network"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"

    fun chatDetail(conversationId: String, peerNodeId: String) =
        "chat/$conversationId/$peerNodeId"
}

enum class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Chats(Routes.CHATS, "Chats", Icons.Filled.Chat, Icons.Outlined.Chat),
    Groups(Routes.GROUPS, "Groups", Icons.Filled.Group, Icons.Outlined.Group),
    Peers(Routes.PEERS, "Peers", Icons.Filled.Sensors, Icons.Outlined.Sensors),
    Network(Routes.NETWORK, "Network", Icons.Filled.Hub, Icons.Outlined.Hub),
    Settings(Routes.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshWalkApp(mainViewModel: MainViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val setupRoutes = setOf(Routes.ONBOARDING, Routes.IDENTITY_SETUP)
    val currentRoute = currentDestination?.route
    val showBottomBar = currentRoute != null && currentRoute !in setupRoutes
    val bottomNavRoutes = BottomNavItem.entries.map { it.route }
    val showTopMenu = currentRoute in bottomNavRoutes

    val identity by mainViewModel.identity.collectAsState()
    val meshStatus by mainViewModel.meshStatus.collectAsState()
    val nearestPeer by mainViewModel.nearestPeer.collectAsState()

    Scaffold(
        topBar = {
            if (showTopMenu) {
                TopMenuBar(
                    identity = identity,
                    meshStatus = meshStatus,
                    nearestPeer = nearestPeer
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BottomNavItem.entries.forEach { item ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == item.route } == true

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHATS, // Will redirect to onboarding if needed
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onComplete = {
                        navController.navigate(Routes.IDENTITY_SETUP) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.IDENTITY_SETUP) {
                IdentitySetupScreen(
                    onComplete = {
                        navController.navigate(Routes.CHATS) {
                            popUpTo(Routes.IDENTITY_SETUP) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.CHATS) {
                ChatListScreen(
                    onChatClick = { convId, peerNodeId ->
                        navController.navigate(Routes.chatDetail(convId, peerNodeId))
                    },
                    onNeedSetup = {
                        navController.navigate(Routes.ONBOARDING) {
                            popUpTo(Routes.CHATS) { inclusive = true }
                        }
                    }
                )
            }

            composable(
                Routes.CHAT_DETAIL,
                arguments = listOf(
                    navArgument("conversationId") { type = NavType.StringType },
                    navArgument("peerNodeId") { type = NavType.StringType }
                )
            ) { backStack ->
                ChatScreen(
                    conversationId = backStack.arguments?.getString("conversationId") ?: "",
                    peerNodeId = backStack.arguments?.getString("peerNodeId") ?: "",
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.GROUPS) {
                GroupListScreen(
                    onGroupClick = { groupId ->
                        navController.navigate(Routes.chatDetail(groupId, groupId))
                    }
                )
            }

            composable(Routes.PEERS) {
                PeersScreen(
                    onStartChat = { conversationId, peerNodeId ->
                        navController.navigate(
                            Routes.chatDetail(conversationId, peerNodeId)
                        )
                    }
                )
            }

            composable(Routes.NETWORK) {
                NetworkGraphScreen()
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onDiagnostics = {
                        navController.navigate(Routes.DIAGNOSTICS)
                    }
                )
            }

            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
