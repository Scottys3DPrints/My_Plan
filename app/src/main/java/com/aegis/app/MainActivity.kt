package com.aegis.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aegis.app.browser.BrowserScreen
import com.aegis.app.engine.AegisEngine
import com.aegis.app.engine.ChangeNotice
import com.aegis.app.ui.components.Brand
import com.aegis.app.ui.onboarding.OnboardingScreen
import com.aegis.app.ui.screens.DestinationsScreen
import com.aegis.app.ui.screens.HomeScreen
import com.aegis.app.ui.screens.LogScreen
import com.aegis.app.ui.screens.RulesScreen
import com.aegis.app.ui.screens.SettingsScreen
import com.aegis.app.ui.theme.AegisTheme
import com.aegis.app.ui.theme.Ash
import com.aegis.app.ui.theme.Brass
import com.aegis.core.util.LocalTime
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

/**
 * Four destinations, not five, and they are not equals.
 *
 * The old bar gave Home, Browse, Categories, Apps and Log identical weight, which is a
 * claim that they matter equally — they do not. Content rules and app rules are the same
 * decision seen twice and now share one screen; Settings is not a place you go often and
 * has moved to the bar at the top where it belongs.
 */
private data class Section(val route: String, val label: String, val icon: ImageVector)

private val SECTIONS = listOf(
    Section("home", "Shield", Icons.Filled.Shield),
    Section("rules", "Rules", Icons.Filled.Tune),
    Section("browser", "Browse", Icons.Filled.Public),
    Section("record", "Record", Icons.Filled.Receipt),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AegisApp() {
    val context = LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }

    val onboardingComplete by engine.onboardingComplete.collectAsState()
    var onboardingDismissed by remember { mutableStateOf(false) }

    // Every deferred edit surfaces here. Without it, a tap on a sealed control looks
    // exactly like a bug — which is what it was mistaken for.
    LaunchedEffect(Unit) {
        engine.notices.collect { notice ->
            val message = when (notice) {
                is ChangeNotice.Deferred ->
                    "Waiting ${LocalTime.formatDuration(notice.minutesRemaining)} — ${notice.summary}"

                is ChangeNotice.Applied -> "Now in effect: ${notice.summary}"
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    if (!onboardingComplete && !onboardingDismissed) {
        OnboardingScreen(onFinished = { onboardingDismissed = true })
        return
    }

    val currentRoute = currentDestination?.route
    val isBrowser = currentRoute == "browser"

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // The browser is full-bleed: a chrome bar above a web page is two address
            // bars, and the wordmark is not worth the vertical space there.
            if (!isBrowser) {
                TopAppBar(
                    title = { Brand() },
                    actions = {
                        IconButton(onClick = { navController.navigate("settings") }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = Ash,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
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
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Brass,
                            selectedTextColor = Brass,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                            unselectedIconColor = Ash,
                            unselectedTextColor = Ash,
                        ),
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
                    onOpenRecord = { navController.navigate("record") },
                    onOpenDestinations = { navController.navigate("destinations") },
                )
            }
            composable("rules") { RulesScreen() }
            composable("browser") { BrowserScreen() }
            composable("record") { LogScreen() }
            composable("destinations") { DestinationsScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
