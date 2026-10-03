package dev.sleepy.app.ui.navigation

/**
 * A destination in the navigation graph.
 *
 * @property route The route pattern registered for this destination, with `{}` placeholders for
 *   arguments.
 */
sealed class Screen(val route: String) {
    /** The home screen, where the user chooses the application to patch. */
    data object Home : Screen("home")
    /** The patch-selection screen, which takes the id of the chosen source as an argument. */
    data object PatchSelect : Screen("patch_select/{sourceId}") {
        /** Returns the route for the source with the given [sourceId]. */
        fun createRoute(sourceId: String) = "patch_select/$sourceId"
    }
    /** The progress screen, which reports a running patch. */
    data object Progress : Screen("progress")
    /** The result screen, which reports the finished run. */
    data object Result : Screen("result")
    /** The settings screen, which lists provenance and build information. */
    data object Settings : Screen("settings")
}
