package com.aegis.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aegis.app.browser.BrowserScreen
import com.aegis.app.engine.AegisEngine
import com.aegis.app.ui.screens.AppsScreen
import com.aegis.app.ui.screens.CategoriesScreen
import com.aegis.app.ui.screens.DestinationsScreen
import com.aegis.app.ui.screens.HomeScreen
import com.aegis.app.ui.screens.LogScreen
import com.aegis.app.ui.screens.SettingsScreen
import com.aegis.app.ui.theme.AegisTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AegisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AegisApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A cooling-off change that came due while the app was closed should be in force
        // by the time the user is looking at the screen that shows it.
        lifecycleScope.launch { AegisEngine.get(this@MainActivity).applyDueChanges() }
    }
}

private data class Section(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val SECTIONS = listOf(
    Section("home", "Home", Icons.Filled.Home),
    Section("browser", "Browse", Icons.Filled.Public),
    Section("categories", "Categories", Icons.Filled.Category),
    Section("apps", "Apps", Icons.Filled.Apps),
    Section("log", "Log", Icons.Filled.Receipt),
)

@Composable
private fun AegisApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                for (section in SECTIONS) {
                    val selected = currentDestination?.hierarchy?.any { it.route == section.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(section.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(section.icon, contentDescription = section.label) },
                        label = { Text(section.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("home") {
                HomeScreen(
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenDestinations = { navController.navigate("destinations") },
                )
            }
            composable("browser") { BrowserScreen() }
            composable("categories") { CategoriesScreen() }
            composable("apps") { AppsScreen() }
            composable("log") { LogScreen() }
            composable("destinations") { DestinationsScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
