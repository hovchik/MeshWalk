package com.meshwalk.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.meshwalk.app.mesh.service.MeshForegroundService
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
import com.meshwalk.app.ui.theme.MeshWalkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Start the service regardless of which permissions were granted.
        // Each transport checks its own permissions before calling platform APIs.
        startMeshService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()

        setContent {
            MeshWalkTheme {
                MeshWalkApp()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startMeshService()
        }
    }

    private fun startMeshService() {
        startForegroundService(MeshForegroundService.startIntent(this))
    }
}

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
