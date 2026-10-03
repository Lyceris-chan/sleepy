package dev.sleepy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.navigation.compose.rememberNavController
import dev.sleepy.app.ui.navigation.SleepyNavGraph
import dev.sleepy.app.ui.theme.SleepyTheme
import dev.sleepy.app.viewmodel.HomeViewModel
import dev.sleepy.app.viewmodel.PatchViewModel

/**
 * The app's single activity: it hosts the navigation graph and the two view models the screens
 * share.
 */
class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val patchViewModel: PatchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge display is required on API 36 and later.
        enableEdgeToEdge()

        setContent {
            SleepyTheme {
                val navController = rememberNavController()
                SleepyNavGraph(
                    navController = navController,
                    homeViewModel = homeViewModel,
                    patchViewModel = patchViewModel
                )
            }
        }
    }
}
