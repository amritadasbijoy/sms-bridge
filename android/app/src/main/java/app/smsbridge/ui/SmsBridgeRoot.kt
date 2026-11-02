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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import app.smsbridge.R
import app.smsbridge.feature.backup.BackupScreen
import app.smsbridge.feature.sync.SyncScreen
import app.smsbridge.feature.transfer.TransferScreen
import app.smsbridge.feature.dashboard.DashboardScreen
import app.smsbridge.feature.messages.ComposeScreen
import app.smsbridge.feature.messages.MessagesScreen
import app.smsbridge.feature.messages.ThreadScreen
import app.smsbridge.feature.settings.SettingsScreen

/**
 * Top-level destinations. Backup, Transfer, Sync and Devices tabs are added in the phases that
 * implement them, so the app never shows a tab that does nothing.
 */
enum class TopDestination(val route: String, val label: String, val icon: ImageVector?, @DrawableRes val iconRes: Int? = null) {
    HOME(Routes.HOME, "Home", Icons.Filled.Home),
    MESSAGES(Routes.MESSAGES, "Messages", Icons.Filled.Email),
    BACKUP(Routes.BACKUP, "Backup", null, R.drawable.ic_backup),
    TRANSFER(Routes.TRANSFER, "Transfer", null, R.drawable.ic_transfer),
    SYNC(Routes.SYNC, "Sync", null, R.drawable.ic_sync),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
}

@Composable
fun SmsBridgeRoot(navigationRequests: NavigationRequests) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val pending by navigationRequests.pending.collectAsStateWithLifecycle()

    LaunchedEffect(pending) {
        when (val request = pending) {
            is NavRequest.OpenThread -> navController.navigate(Routes.thread(request.addressKey)) { launchSingleTop = true }
            is NavRequest.Compose -> navController.navigate(Routes.compose(request.recipient, request.body))
            NavRequest.OpenSync -> navController.navigateTopLevel(Routes.SYNC)
            NavRequest.OpenRemote -> navController.navigate(Routes.REMOTE) { launchSingleTop = true }
            null -> return@LaunchedEffect
        }
        navigationRequests.consume()
    }

    Scaffold(
        bottomBar = {
            if (TopDestination.entries.any { it.route == currentRoute }) {
                NavigationBar {
                    TopDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateTopLevel(destination.route) },
                            icon = {
                                val res = destination.iconRes
                                if (res != null) Icon(painterResource(res), contentDescription = null)
                                else Icon(destination.icon!!, contentDescription = null)
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                DashboardScreen(onOpenMessages = { navController.navigateTopLevel(Routes.MESSAGES) })
            }
            composable(Routes.MESSAGES) {
                MessagesScreen(
                    onOpenThread = { navController.navigate(Routes.thread(it)) },
                    onCompose = { navController.navigate(Routes.compose()) },
                )
            }
            composable(Routes.BACKUP) { BackupScreen() }
            composable(Routes.TRANSFER) { TransferScreen() }
            composable(Routes.SYNC) { SyncScreen(onOpenRemote = { navController.navigate(Routes.REMOTE) { launchSingleTop = true } }) }
            composable(Routes.REMOTE) { app.smsbridge.feature.remote.RemoteMessagesScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(
                Routes.THREAD,
                arguments = listOf(navArgument("key") { type = NavType.StringType; defaultValue = "" }),
            ) {
                ThreadScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.COMPOSE,
                arguments = listOf(
                    navArgument("to") { type = NavType.StringType; defaultValue = "" },
                    navArgument("body") { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                ComposeScreen(
                    onBack = { navController.popBackStack() },
                    onSent = { key ->
                        navController.navigate(Routes.thread(key)) { popUpTo(Routes.COMPOSE) { inclusive = true } }
                    },
                )
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
