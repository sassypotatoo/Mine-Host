package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class MineHostAppState(
    val navController: NavHostController
) {
    val currentDestination: androidx.navigation.NavDestination?
        @Composable get() = navController.currentBackStackEntryAsState().value?.destination

    fun navigateToRootDestination(destination: MineHostDestination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
}

@Composable
fun rememberMineHostAppState(
    navController: NavHostController = rememberNavController()
): MineHostAppState {
    return remember(navController) {
        MineHostAppState(navController)
    }
}
