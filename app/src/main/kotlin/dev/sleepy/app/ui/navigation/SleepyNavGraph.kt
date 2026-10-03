package dev.sleepy.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.sleepy.app.ui.screens.HomeScreen
import dev.sleepy.app.ui.screens.PatchSelectScreen
import dev.sleepy.app.ui.screens.ProgressScreen
import dev.sleepy.app.ui.screens.ResultScreen
import dev.sleepy.app.ui.screens.SettingsScreen
import dev.sleepy.app.viewmodel.HomeViewModel
import dev.sleepy.app.viewmodel.PatchViewModel

/**
 * The app's navigation graph: one destination per screen, with the arguments each route takes.
 *
 * @param navController The controller that holds the navigation state and performs the
 *   navigation actions.
 * @param homeViewModel The view model for the home and settings screens.
 * @param patchViewModel The view model for the patch-selection, progress, and result screens.
 */
@Composable
fun SleepyNavGraph(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    patchViewModel: PatchViewModel
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = homeViewModel,
                onSourceSelected = { sourceId ->
                    navController.navigate(Screen.PatchSelect.createRoute(sourceId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.PatchSelect.route,
            arguments = listOf(navArgument("sourceId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sourceId = backStackEntry.arguments?.getString("sourceId") ?: return@composable
            PatchSelectScreen(
                sourceId = sourceId,
                viewModel = patchViewModel,
                onStartPatch = {
                    navController.navigate(Screen.Progress.route)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Progress.route) {
            ProgressScreen(
                viewModel = patchViewModel,
                onFinished = {
                    navController.navigate(Screen.Result.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                onExit = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Result.route) {
            ResultScreen(
                viewModel = patchViewModel,
                onStartOver = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onRetry = {
                    // The run starts before the navigation, so the progress screen does not open
                    // on the failed run it is replacing.
                    patchViewModel.startPatch()
                    navController.navigate(Screen.Progress.route) {
                        popUpTo(Screen.Result.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            val sources by homeViewModel.sources.collectAsState()
            val selectedSource by patchViewModel.selectedSource.collectAsState()
            SettingsScreen(
                sources = sources,
                selectedSourceId = selectedSource?.id,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
