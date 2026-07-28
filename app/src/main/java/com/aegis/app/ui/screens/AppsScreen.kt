package com.aegis.app.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegis.app.engine.AegisEngine
import com.aegis.app.service.CriticalPackages
import com.aegis.app.ui.components.AegisCard
import com.aegis.app.ui.components.Explanation
import com.aegis.app.ui.components.SectionHeader
import com.aegis.core.rules.AppRule
import com.aegis.core.util.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class InstalledApp(val packageName: String, val label: String)

/**
 * Whole-app blocking and per-app budgets (§3.3, §3.4).
 *
 * Apps the user has actually launched are listed; system components are not, because a
 * list of four hundred packages is a list nobody reads.
 */
@Composable
fun AppsScreen() {
    val context = LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val scope = rememberCoroutineScope()
    val rules by engine.rules.collectAsState()
    val usage by engine.usage.collectAsState()
    val pending by engine.pending.collectAsState()

    var query by remember { mutableStateOf("") }

    val installed by produceState(initialValue = emptyList<InstalledApp>()) {
        val critical = withContext(Dispatchers.IO) { CriticalPackages.resolve(context) }
        // Hidden rather than shown-and-ignored: offering a Block switch that silently
        // does nothing is worse than not offering it.
        value = withContext(Dispatchers.IO) {
            loadLaunchableApps(context).filterNot { it.packageName in critical }
        }
    }

    val visible = remember(installed, query) {
        if (query.isBlank()) installed
        else installed.filter { it.label.contains(query, ignoreCase = true) }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SectionHeader(
                title = "Apps",
                subtitle = "Block an app outright, or give it a daily budget.",
            )
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text("Find an app") },
            )
        }

        if (installed.isEmpty()) {
            item {
                Explanation("Reading the list of installed apps…")
            }
        }

        items(visible, key = { it.packageName }) { app ->
            val rule = rules.ruleFor(app.packageName) ?: AppRule(app.packageName, app.label)
            val spent = usage.packageMinutes(app.packageName)
            val waiting = pending.firstOrNull { "app:${app.packageName}" in it.targetKeys }

            AegisCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, fontWeight = FontWeight.Medium)
                        Text(
                            text = if (spent > 0) "${LocalTime.formatDuration(spent)} today" else "Not used today",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = rule.blocked,
                        onCheckedChange = { blocked ->
                            scope.launch {
                                engine.submitRuleChange(
                                    rules.withAppRule(rule.copy(label = app.label, blocked = blocked)),
                                    note = if (blocked) "Block ${app.label}" else "Unblock ${app.label}",
                                )
                            }
                        },
                    )
                }

                if (waiting != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Waiting: ${waiting.summary} — in " +
                            LocalTime.formatDuration(engine.minutesRemaining(waiting)) + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = { scope.launch { engine.cancelPending(waiting.id) } }) {
                        Text("Cancel that change")
                    }
                }

                if (!rule.blocked) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (rule.dailyBudgetMinutes > 0) {
                            "Daily limit: ${LocalTime.formatDuration(rule.dailyBudgetMinutes)}"
                        } else {
                            "No daily limit"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (minutes in listOf(0, 15, 30, 60)) {
                            FilterChip(
                                selected = rule.dailyBudgetMinutes == minutes,
                                onClick = {
                                    scope.launch {
                                        engine.submitRuleChange(
                                            rules.withAppRule(
                                                rule.copy(label = app.label, dailyBudgetMinutes = minutes),
                                            ),
                                        )
                                    }
                                },
                                label = { Text(if (minutes == 0) "None" else LocalTime.formatDuration(minutes)) },
                            )
                        }
                    }
                }
            }
        }

        item {
            Explanation(
                "App limits need the app & route guard switched on, on the Home screen.",
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * Apps with a launcher entry, minus system packages and Aegis itself.
 *
 * Excluding ourselves is not tidiness — an app that lets you block its own guard is a
 * one-tap bypass of everything else on this screen.
 */
private fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val manager = context.packageManager
    return try {
        manager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { info -> manager.getLaunchIntentForPackage(info.packageName) != null }
            .filter { info -> info.packageName != context.packageName }
            .filter { info ->
                // Keep updated system apps (a preinstalled browser is exactly what someone
                // wants to limit) but drop the rest of the platform.
                (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = manager.getApplicationLabel(info).toString(),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    } catch (error: Exception) {
        emptyList()
    }
}
