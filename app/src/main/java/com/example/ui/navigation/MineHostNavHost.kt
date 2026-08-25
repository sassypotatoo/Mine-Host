package com.example.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.ui.components.MineHostBottomBar
import com.example.ui.components.MineHostScreen
import com.example.ui.theme.*
import com.example.ui.legacy.LegacyServerScreen
import com.example.ui.screens.backups.BackupManagerScreen
import com.example.ui.screens.dashboard.DashboardScreen
import com.example.ui.screens.files.FileManagerScreen
import com.example.ui.screens.network.ServerNetworkAccessScreen
import com.example.ui.screens.performance.PerformanceMonitorScreen
import com.example.ui.screens.plugins.MarketplaceScreen
import com.example.ui.screens.plugins.PluginManagerScreen
import com.example.ui.screens.profile.*
import com.example.ui.screens.servers.*
import com.example.ui.servercreation.CreateServerWizardScreen
import com.example.ui.screens.tools.*
import com.example.ui.screens.versions.VersionManagerScreen
import com.example.ui.screens.worlds.WorldManagerScreen

@Composable
fun MineHostNavHost(appState: MineHostAppState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val route = appState.currentDestination?.route
    val rootRoutes = setOf(
        MineHostDestination.Dashboard.route,
        MineHostDestination.Console.route,
        MineHostDestination.Marketplace.route,
        MineHostDestination.AiAssistantTab.route
    )
    val snackbar = remember { SnackbarHostState() }
    val startDestination = MineHostDestination.Dashboard.route
    val message by viewModel.operationMessage.collectAsState()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.consumeOperationMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (route in rootRoutes) MineHostBottomBar(currentRoute = route, onNavigate = { appState.navigateToRootDestination(it) }) }
    ) { padding ->
        val safePadding = PaddingValues(
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding(),
            start = padding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
            end = padding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
        )
        NavHost(appState.navController, startDestination, modifier.padding(safePadding)) {
            composable(MineHostDestination.Dashboard.route) { DashboardScreen(viewModel) { appState.navController.navigate(it) } }
            
            composable(MineHostDestination.Console.route) { 
                ConsoleTabScreen(viewModel) { appState.navController.navigate(it) }
            }

            composable(MineHostDestination.Plugins.route) { PluginManagerScreen(viewModel, { appState.navController.navigate(MineHostDestination.Marketplace.route) }) }
            composable(
                MineHostDestination.ServerPlugins.route,
                arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
            ) { entry ->
                val serverId = entry.arguments?.getString("serverId")
                PluginManagerScreen(viewModel, { appState.navController.navigate(MineHostDestination.Marketplace.route) }, serverId)
            }
            composable(MineHostDestination.AiAssistantTab.route) { 
                AiAssistantTabScreen(viewModel) { appState.navController.popBackStack() }
            }
            composable(MineHostDestination.Files.route) { FileManagerScreen(viewModel) }
            composable(
                MineHostDestination.ServerFiles.route,
                arguments = listOf(navArgument("serverId") { type = NavType.StringType })
            ) { entry -> FileManagerScreen(viewModel, entry.arguments?.getString("serverId")) }
            composable(MineHostDestination.Profile.route) {
                ProfileScreen(viewModel, { appState.navController.navigate(MineHostDestination.AppSettings.route) }, { appState.navController.navigate(MineHostDestination.Account.route) }, { appState.navController.navigate(MineHostDestination.Notifications.route) })
            }

            composable(MineHostDestination.ServerCreation.route) { 
                CreateServerWizardScreen(viewModel, onBack = { appState.navController.popBackStack() }, onDone = { appState.navController.popBackStack() }) 
            }
            composable(
                MineHostDestination.ServerEdit.route,
                arguments = listOf(navArgument("serverId") { type = NavType.StringType })
            ) { entry ->
                val serverId = entry.arguments?.getString("serverId")
                ServerCreationScreen(viewModel, serverId = serverId, onBack = { appState.navController.popBackStack() }, onDone = { appState.navController.popBackStack() })
            }
            composable(MineHostDestination.Marketplace.route) { MarketplaceScreen(viewModel) { appState.navController.popBackStack() } }
            composable(MineHostDestination.PerformanceMonitor.route) { PerformanceMonitorScreen(viewModel, { appState.navController.popBackStack() }) }
            composable(MineHostDestination.ServerPerformanceMonitor.route, arguments = listOf(navArgument("serverId") { type = NavType.StringType })) { entry ->
                PerformanceMonitorScreen(viewModel, { appState.navController.popBackStack() }, entry.arguments?.getString("serverId"))
            }
            composable(MineHostDestination.VersionManager.route) { VersionManagerScreen(viewModel, { appState.navController.popBackStack() }) }
            composable(MineHostDestination.ServerVersionManager.route, arguments = listOf(navArgument("serverId") { type = NavType.StringType })) { entry ->
                VersionManagerScreen(viewModel, { appState.navController.popBackStack() }, entry.arguments?.getString("serverId"))
            }
            composable(MineHostDestination.BackupManager.route) { BackupManagerScreen(viewModel, { appState.navController.popBackStack() }) }
            composable(MineHostDestination.ServerBackupManager.route, arguments = listOf(navArgument("serverId") { type = NavType.StringType })) { entry ->
                BackupManagerScreen(viewModel, { appState.navController.popBackStack() }, entry.arguments?.getString("serverId"))
            }
            composable(MineHostDestination.WorldManager.route) { WorldManagerScreen(viewModel, { appState.navController.popBackStack() }) }
            composable(MineHostDestination.ServerWorldManager.route, arguments = listOf(navArgument("serverId") { type = NavType.StringType })) { entry ->
                WorldManagerScreen(viewModel, { appState.navController.popBackStack() }, entry.arguments?.getString("serverId"))
            }
            composable(MineHostDestination.ServerNetworkAccess.route, arguments = listOf(navArgument("serverId") { type = NavType.StringType })) { entry ->
                val serverId = entry.arguments?.getString("serverId").orEmpty()
                ServerNetworkAccessScreen(viewModel, serverId) { appState.navController.popBackStack() }
            }

            composable(MineHostDestination.AppSettings.route) { AppSettingsScreen(viewModel, { appState.navController.navigate(it) }) { appState.navController.popBackStack() } }
            composable(MineHostDestination.BatchDownload.route) { BatchDownloadScreen { appState.navController.popBackStack() } }
            composable(MineHostDestination.Account.route) { AccountScreen(viewModel) { appState.navController.popBackStack() } }
            composable(MineHostDestination.Notifications.route) { NotificationsScreen(viewModel) { appState.navController.popBackStack() } }
            composable(MineHostDestination.LegacyServer.route) { LegacyServerScreen(viewModel) }

            composable(
                MineHostDestination.ServerOverview.route,
                arguments = listOf(navArgument("serverId") { type = NavType.StringType })
            ) { entry ->
                val serverId = entry.arguments?.getString("serverId").orEmpty()
                ServerDetailScreen(
                    viewModel, serverId, { appState.navController.popBackStack() },
                    { appState.navController.navigate(MineHostDestination.LegacyServer.route) },
                    { appState.navController.navigate(MineHostDestination.Activity.createRoute(serverId)) },
                    { appState.navController.navigate(MineHostDestination.AiAssistant.createRoute(serverId)) },
                    { appState.navController.navigate(MineHostDestination.PerformanceRecommendations.createRoute(serverId)) },
                    { appState.navController.navigate(MineHostDestination.CrashAnalysis.createRoute(serverId)) },
                    { appState.navController.navigate(MineHostDestination.ServerHealth.createRoute(serverId)) },
                    { appState.navController.navigate(MineHostDestination.AutoOptimization.createRoute(serverId)) },
                    { appState.navController.navigate(MineHostDestination.ServerBackupManager.createRoute(serverId)) },
                    { appState.navController.navigate(MineHostDestination.ServerWorldManager.createRoute(serverId)) },
                    { appState.navController.navigate(MineHostDestination.ServerNetworkAccess.createRoute(serverId)) },
                    { appState.navController.navigate(MineHostDestination.ServerEdit.createRoute(it)) }
                )
            }

            toolRoute(MineHostDestination.Activity.route) { serverId -> ActivityScreen(viewModel, { appState.navController.popBackStack() }, serverId) }
            toolRoute(MineHostDestination.AiAssistant.route) { serverId -> AiAssistantScreen(viewModel, { appState.navController.popBackStack() }, serverId) }
            toolRoute(MineHostDestination.PerformanceRecommendations.route) { serverId -> PerformanceRecommendationsScreen(viewModel, { appState.navController.popBackStack() }, serverId) }
            toolRoute(MineHostDestination.CrashAnalysis.route) { serverId -> CrashAnalysisScreen(viewModel, { appState.navController.popBackStack() }, serverId) }
            toolRoute(MineHostDestination.ServerHealth.route) { serverId -> ServerHealthScreen(viewModel, { appState.navController.popBackStack() }, serverId) }
            toolRoute(MineHostDestination.AutoOptimization.route) { serverId -> AutoOptimizationScreen(viewModel, { appState.navController.popBackStack() }, serverId) }
        }
    }
}

private fun androidx.navigation.NavGraphBuilder.toolRoute(route: String, content: @Composable (String) -> Unit) {
    composable(route, arguments = listOf(navArgument("serverId") { type = NavType.StringType })) { entry ->
        content(entry.arguments?.getString("serverId").orEmpty())
    }
}
