package com.aistudio.mediatool.navigation

import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.Composable
import com.aistudio.mediatool.feature.studio.ui.StudioLabScreen
import com.aistudio.mediatool.feature.studio.ui.StudioProjectScreen
import com.aistudio.mediatool.feature.studio.ui.StudioProjectsScreen
import com.aistudio.mediatool.ui.screens.*

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Route.Main.path) {
        composable(Route.Main.path) {
            MainScreen(
                onNavigateToStudio = { navController.navigate(Route.StudioProjects.path) },
                onNavigateToRecord = { navController.navigate(Route.Record.path) },
                onNavigateToTrim = { navController.navigate(Route.Trim.path) },
                onNavigateToJoin = { navController.navigate(Route.Join.path) },
                onNavigateToMix = { navController.navigate(Route.Mix.path) },
                onNavigateToImg2Vid = { navController.navigate(Route.Img2Vid.path) },
                onNavigateToSub = { navController.navigate(Route.Sub.path) },
                onNavigateToStem = { navController.navigate(Route.Stem.path) },
                onNavigateToVoiceCleanup = { navController.navigate(Route.VoiceCleanup.path) },
                onNavigateToOther = { navController.navigate(Route.Other.path) },
                onNavigateToSettings = { navController.navigate(Route.Settings.path) },
            )
        }
        composable(Route.StudioProjects.path) {
            StudioProjectsScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenProject = { projectId -> navController.navigate(Route.StudioProject.create(projectId)) },
                onOpenLab = { projectId -> navController.navigate(Route.StudioLab.create(projectId)) },
            )
        }
        composable(Route.StudioProject.path) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            StudioProjectScreen(
                projectId = projectId,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Route.StudioLab.path) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            StudioLabScreen(
                projectId = projectId,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Route.Record.path) { RecordScreen(navController = navController) }
        composable(Route.Trim.path) { TrimScreen(navController = navController) }
        composable(Route.Join.path) { JoinScreen(navController = navController) }
        composable(Route.Mix.path) { MixScreen(navController = navController) }
        composable(Route.Img2Vid.path) { Img2VidScreen(navController = navController) }
        composable(Route.Sub.path) { SubScreen(navController = navController) }
        composable(Route.Stem.path) { StemScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Route.VoiceCleanup.path) {
            VoiceCleanupScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Route.Other.path) { OtherScreen(navController = navController) }
        composable(Route.Settings.path) { SettingsScreen(navController = navController) }
    }
}
