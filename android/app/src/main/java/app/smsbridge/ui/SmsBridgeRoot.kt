package app.smsbridge.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.smsbridge.feature.dashboard.DashboardScreen
import app.smsbridge.feature.messages.MessagesScreen
import app.smsbridge.feature.settings.SettingsScreen

/**
 * Top-level destinations. Backup, Transfer, Sync and Devices tabs are added in the phases that
 * implement them, so the app never shows a tab that does nothing.
 */
enum class TopDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    MESSAGES("messages", "Messages", Icons.Filled.Email),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

@Composable
fun SmsBridgeRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { navController.navigateTopLevel(destination.route) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopDestination.HOME.route) {
                DashboardScreen(onOpenMessages = { navController.navigateTopLevel(TopDestination.MESSAGES.route) })
            }
            composable(TopDestination.MESSAGES.route) { MessagesScreen() }
            composable(TopDestination.SETTINGS.route) { SettingsScreen() }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
