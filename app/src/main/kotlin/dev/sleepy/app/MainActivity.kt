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

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val patchViewModel: PatchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // API 36+ and 37 mandatory edge-to-edge support
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
