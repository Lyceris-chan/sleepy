package dev.sleepy.app.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object PatchSelect : Screen("patch_select/{sourceId}") {
        fun createRoute(sourceId: String) = "patch_select/$sourceId"
    }
    data object Progress : Screen("progress")
    data object Result : Screen("result")
    data object Settings : Screen("settings")
}
