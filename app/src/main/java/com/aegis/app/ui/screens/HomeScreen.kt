package com.aegis.app.ui.screens

import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegis.app.engine.AegisEngine
import com.aegis.app.service.AegisAccessibilityService
import com.aegis.app.service.AegisVpnService
import com.aegis.app.ui.components.AegisCard
import com.aegis.app.ui.components.Explanation
import com.aegis.app.ui.components.LabelledRow
import com.aegis.app.ui.components.SectionHeader
import com.aegis.core.rules.FocusSession
import com.aegis.core.util.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The screen that answers "is this actually protecting me right now?"
 *
 * Protection here depends on two permissions the user has to grant in Android's own
 * settings, and either of them can be revoked without Aegis being told. So the state is
 * re-read on every resume and shown plainly — a blocker that claims to be on when it is
 * not is worse than no blocker, because it is trusted.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenDestinations: () -> Unit,
) {
    val context = LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val scope = rememberCoroutineScope()

    val rules by engine.rules.collectAsState()
    val usage by engine.usage.collectAsState()
    val pending by engine.pending.collectAsState()

    var filterRunning by remember { mutableStateOf(AegisVpnService.isRunning) }
    var guardEnabled by remember { mutableStateOf(AegisAccessibilityService.isEnabled(context)) }

    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            AegisVpnService.start(context)
        }
    }

    // Both permissions live outside the app, so poll while this screen is visible rather
    // than trusting a value captured once. Assigning an unchanged value is a no-op in
    // Compose, so this does not cause a recomposition every 1.5 seconds.
    LaunchedEffect(Unit) {
        while (true) {
            filterRunning = AegisVpnService.isRunning
            guardEnabled = AegisAccessibilityService.isEnabled(context)
            delay(1_500)
        }
    }

    // A countdown that does not count down reads as a frozen screen. Half-minute ticks
    // are enough for a display measured in minutes and hours.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SectionHeader(
                title = "Protection",
                subtitle = "Everything below runs on this device. Nothing is uploaded.",
            )
        }

        item {
            AegisCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Network filter", fontWeight = FontWeight.Medium)
                        Text(
                            text = if (filterRunning) {
                                "Running. Blocked destinations are refused for every app."
                            } else {
                                "Off. Only the Aegis browser is filtered."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = filterRunning,
                        onCheckedChange = { wanted ->
                            if (wanted) {
                                val consent = VpnService.prepare(context)
                                if (consent == null) {
                                    AegisVpnService.start(context)
                                } else {
                                    vpnConsent.launch(consent)
                                }
                            } else {
                                AegisVpnService.stop(context)
                            }
                        },
                    )
                }
            }
        }

        item {
            AegisCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("App & route guard", fontWeight = FontWeight.Medium)
                        Text(
                            text = if (guardEnabled) {
                                "On. App blocks, time budgets and in-app browsers are enforced."
                            } else {
                                "Off. App limits and in-app browser blocking will not work."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!guardEnabled) {
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) {
                            Text("Turn on")
                        }
                    }
                }
                if (!guardEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Android calls this an accessibility service. Because Aegis was " +
                            "installed directly rather than from a store, you may first need to " +
                            "open App info → ⋮ → Allow restricted settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = if (rules.armed) "Locked in" else "Setup",
                subtitle = if (rules.armed) {
                    "Weakening a rule now waits ${rules.coolingOffHours}h. Strengthening one is instant."
                } else {
                    "Changes apply immediately while you set things up."
                },
            )
        }

        item {
            AegisCard(highlighted = !rules.armed) {
                if (rules.armed) {
                    Text("Your rules are locked.", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Making anything stricter still happens at once. Anything that " +
                            "loosens a rule — including unlocking — joins the queue below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        scope.launch { engine.submitRuleChange(rules.copy(armed = false)) }
                    }) {
                        Text("Unlock (waits ${rules.coolingOffHours}h)")
                    }
                } else {
                    Text("Set your rules first.", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Nothing is holding you to anything yet — change whatever you like " +
                            "and it takes effect straight away. When the rules look right, lock " +
                            "them in. After that, loosening one costs you a ${rules.coolingOffHours}-hour wait, " +
                            "which is the entire point.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch { engine.submitRuleChange(rules.copy(armed = true)) }
                        },
                    ) {
                        Text("Lock in my rules")
                    }
                }
            }
        }

        if (pending.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Waiting",
                    subtitle = "Changes that weaken your rules take effect after the cooling-off period.",
                )
            }
            items(pending, key = { it.id }) { change ->
                AegisCard(highlighted = true) {
                    Text(change.summary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    val remaining = remember(tick, change.id) { engine.minutesRemaining(change) }
                    Text(
                        text = "In ${LocalTime.formatDuration(remaining)}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { scope.launch { engine.cancelPending(change.id) } }) {
                        Text("Cancel this change")
                    }
                }
            }
        }

        item { SectionHeader(title = "Today") }

        item {
            AegisCard {
                if (rules.budgets.isEmpty()) {
                    Text("No budgets set.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    for (budget in rules.budgets) {
                        val spent = usage.budgetMinutes(budget.id)
                        LabelledRow(
                            label = budget.label,
                            value = "${LocalTime.formatDuration(spent)} of " +
                                LocalTime.formatDuration(budget.dailyMinutes),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                LabelledRow(
                    label = "Grace taps left",
                    value = engine.graceTapsRemaining().toString(),
                )
            }
        }

        item {
            SectionHeader(
                title = "Focus session",
                subtitle = "A total lockdown you cannot end early.",
            )
        }

        item {
            val active = rules.focusSession
            AegisCard {
                if (active != null && active.isActiveAt(System.currentTimeMillis())) {
                    Text("\"${active.label}\" is running.", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Ends at " + LocalTime.formatMinuteOfDay(
                            LocalTime.minuteOfDay(
                                active.endsAtMillis,
                                java.util.TimeZone.getDefault().getOffset(active.endsAtMillis) / 60_000,
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Nothing running.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (minutes in listOf(25, 60, 120)) {
                            Button(onClick = {
                                scope.launch {
                                    val now = System.currentTimeMillis()
                                    engine.submitRuleChange(
                                        rules.copy(
                                            focusSession = FocusSession(
                                                startedAtMillis = now,
                                                endsAtMillis = now + minutes * 60_000L,
                                                label = "${minutes}-minute focus",
                                            ),
                                        ),
                                    )
                                }
                            }) {
                                Text("${minutes}m")
                            }
                        }
                    }
                }
            }
        }

        item {
            Explanation(
                "Starting a session takes effect immediately. Ending one early does not — " +
                    "that is the point of it.",
            )
        }

        item { SectionHeader(title = "More") }

        item {
            AegisCard {
                TextButton(onClick = onOpenDestinations, modifier = Modifier.fillMaxWidth()) {
                    Text("Destinations you never want to reach")
                }
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Settings, cooling-off and accountability")
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
