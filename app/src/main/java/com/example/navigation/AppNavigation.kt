package com.example.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.*

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = Route.Main.path) {
        composable(Route.Main.path) {
            MainScreen(
                onNavigateToRecord = { navController.navigate(Route.Record.path) },
                onNavigateToTrim = { navController.navigate(Route.Trim.path) },
                onNavigateToJoin = { navController.navigate(Route.Join.path) },
                onNavigateToMix = { navController.navigate(Route.Mix.path) },
                onNavigateToImg2Vid = { navController.navigate(Route.Img2Vid.path) },
                onNavigateToSub = { navController.navigate(Route.Sub.path) },
                onNavigateToStem = { navController.navigate(Route.Stem.path) },
                onNavigateToOther = { navController.navigate(Route.Other.path) },
                onNavigateToSettings = { navController.navigate(Route.Settings.path) }
            )
        }
        composable(Route.Record.path) { RecordScreen(navController = navController) }
        composable(Route.Trim.path) { TrimScreen(navController = navController) }
        composable(Route.Join.path) { JoinScreen(navController = navController) }
        composable(Route.Mix.path) { MixScreen(navController = navController) }
        composable(Route.Img2Vid.path) { Img2VidScreen(navController = navController) }
        composable(Route.Sub.path) { SubScreen(navController = navController) }
        composable(Route.Stem.path) { StemScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Route.Other.path) { OtherScreen(navController = navController) }
        composable(Route.Settings.path) { SettingsScreen(navController = navController) }
    }
}
