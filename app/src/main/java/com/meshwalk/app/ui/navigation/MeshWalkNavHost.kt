package com.meshwalk.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.meshwalk.app.ui.screens.ChatsScreen
import com.meshwalk.app.ui.screens.NetworkScreen
import com.meshwalk.app.ui.screens.PeersScreen
import com.meshwalk.app.ui.screens.SettingsScreen

@Composable
fun MeshWalkNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = NavDestination.Chats.route,
        modifier = modifier
    ) {
        composable(NavDestination.Chats.route) { ChatsScreen() }
        composable(NavDestination.Peers.route) { PeersScreen() }
        composable(NavDestination.Network.route) { NetworkScreen() }
        composable(NavDestination.Settings.route) { SettingsScreen() }
    }
}
