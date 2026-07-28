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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aegis.app.engine.AegisEngine
import com.aegis.app.service.AegisAccessibilityService
import com.aegis.app.service.AegisVpnService
import com.aegis.app.ui.components.BudgetBar
import com.aegis.app.ui.components.Eyebrow
import com.aegis.app.ui.components.Focus
import com.aegis.app.ui.components.Note
import com.aegis.app.ui.components.Panel
import com.aegis.app.ui.components.Seal
import com.aegis.app.ui.components.StatRow
import com.aegis.app.ui.theme.Ash
import com.aegis.app.ui.theme.Brass
import com.aegis.core.rules.FocusSession
import com.aegis.core.util.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The screen that answers, in one glance from across the room: is this holding me?
 *
 * The old version buried that in a scrolling list of identical cards, so people could not
 * tell whether their rules were in force. Here the seal is the first and largest thing,
 * everything else is subordinate to it, and only one element on the screen is ever allowed
 * to compete for attention — whatever is currently wrong.
 */
@Composable
fun HomeScreen(
    onOpenRecord: () -> Unit,
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
    var tick by remember { mutableStateOf(0) }

    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) AegisVpnService.start(context)
    }

    // Both permissions live outside the app and can be revoked without telling us.
    LaunchedEffect(Unit) {
        while (true) {
            filterRunning = AegisVpnService.isRunning
            guardEnabled = AegisAccessibilityService.isEnabled(context)
            delay(1_500)
        }
    }

    // Drives the seal's arc and the countdowns. Half a minute is plenty for a display
    // measured in hours, and keeps the screen from recomposing constantly.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }

    val soonest = remember(pending, tick) { pending.minByOrNull { engine.minutesRemaining(it) } }
    val sealProgress = remember(soonest, tick, rules.coolingOffHours) {
        soonest?.let { change ->
            val total = (rules.coolingOffHours * 60).coerceAtLeast(1)
            1f - (engine.minutesRemaining(change).toFloat() / total).coerceIn(0f, 1f)
        }
    }

    val protectionIncomplete = !filterRunning || !guardEnabled

    LazyColumn(modifier = Modifier.fillMaxSize()) {

        // ------------------------------------------------------------- the seal
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Seal(
                    sealed = rules.armed,
                    pendingProgress = sealProgress,
                    centreLabel = "${rules.coolingOffHours}h",
                )

                Spacer(Modifier.height(22.dp))

                Text(
                    text = if (rules.armed) "Sealed" else "Open",
                    style = MaterialTheme.typography.displayMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (rules.armed) {
                        "Tighten anything now. Loosening waits ${rules.coolingOffHours} hours."
                    } else {
                        "Nothing is holding you yet. Every change takes effect at once."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ash,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )

                Spacer(Modifier.height(20.dp))

                if (!rules.armed) {
                    Button(
                        onClick = { scope.launch { engine.submitRuleChange(rules.copy(armed = true)) } },
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        Text("Seal my rules")
                    }
                } else {
                    TextButton(
                        onClick = { scope.launch { engine.submitRuleChange(rules.copy(armed = false)) } },
                    ) {
                        Text("Unseal (waits ${rules.coolingOffHours}h)", color = Ash)
                    }
                }
            }
        }

        // ------------------------------------------- the one thing that is wrong
        //
        // Only ever one Focus panel on the screen. If protection is incomplete that
        // outranks everything, because every other setting is theatre without it.
        if (protectionIncomplete) {
            item {
                Focus {
                    Text("Not fully protecting you", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (!filterRunning && !guardEnabled) {
                            "Only the Aegis browser is filtered. Other apps are untouched."
                        } else if (!filterRunning) {
                            "App limits work, but other apps' web traffic is unfiltered."
                        } else {
                            "Sites are filtered, but app blocks and time budgets are not enforced."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ash,
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Network filter", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = filterRunning,
                            onCheckedChange = { wanted ->
                                if (wanted) {
                                    val consent = VpnService.prepare(context)
                                    if (consent == null) AegisVpnService.start(context)
                                    else vpnConsent.launch(consent)
                                } else {
                                    AegisVpnService.stop(context)
                                }
                            },
                        )
                    }

                    if (!guardEnabled) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Turn on the app & route guard")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Greyed out? Settings › Apps › Aegis › ⋮ › Allow restricted " +
                                "settings, then come back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ash,
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------- the queue
        if (pending.isNotEmpty()) {
            item { Eyebrow("Waiting") }
            items(pending, key = { it.id }) { change ->
                val remaining = remember(tick, change.id) { engine.minutesRemaining(change) }
                Panel {
                    Text(change.summary, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    StatRow("Takes effect in", LocalTime.formatDuration(remaining))
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = { scope.launch { engine.cancelPending(change.id) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel — keep the stricter rule")
                    }
                }
            }
        }

        // -------------------------------------------------------------- today
        item { Eyebrow("Today") }

        item {
            Panel {
                if (rules.budgets.isEmpty()) {
                    Text(
                        "No time budgets set.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ash,
                    )
                } else {
                    for ((index, budget) in rules.budgets.withIndex()) {
                        if (index > 0) Spacer(Modifier.height(18.dp))
                        val spent = usage.budgetMinutes(budget.id)
                        StatRow(
                            budget.label,
                            "${LocalTime.formatDuration(spent)} / ${LocalTime.formatDuration(budget.dailyMinutes)}",
                        )
                        Spacer(Modifier.height(8.dp))
                        BudgetBar(spentMinutes = spent, allowanceMinutes = budget.dailyMinutes)
                    }
                }
                Spacer(Modifier.height(18.dp))
                StatRow("Grace taps left", engine.graceTapsRemaining().toString())
            }
        }

        // ------------------------------------------------------- focus session
        item { Eyebrow("Focus session") }

        item {
            val active = rules.focusSession
            val isRunning = active != null && active.isActiveAt(System.currentTimeMillis())
            Panel {
                if (isRunning && active != null) {
                    Text("\"${active.label}\"", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    StatRow(
                        "Ends at",
                        LocalTime.formatMinuteOfDay(
                            LocalTime.minuteOfDay(
                                active.endsAtMillis,
                                java.util.TimeZone.getDefault().getOffset(active.endsAtMillis) / 60_000,
                            ),
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Everything except your allow list is closed until it ends. " +
                            "Your launcher, Settings and the dialer always stay reachable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ash,
                    )
                } else {
                    Text(
                        "Close everything for a set stretch. It cannot be ended early.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ash,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (minutes in listOf(25, 60, 120)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val now = System.currentTimeMillis()
                                        engine.submitRuleChange(
                                            rules.copy(
                                                focusSession = FocusSession(
                                                    startedAtMillis = now,
                                                    endsAtMillis = now + minutes * 60_000L,
                                                    label = LocalTime.formatDuration(minutes) + " focus",
                                                ),
                                            ),
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(LocalTime.formatDuration(minutes))
                            }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------------- more
        item { Eyebrow("Elsewhere") }

        item {
            Panel(onClick = onOpenDestinations) {
                Text("Never reach", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (rules.destinationRules.isEmpty()) {
                        "Name a site you never want to open, by any route."
                    } else {
                        "${rules.destinationRules.size} blocked by every route."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ash,
                )
            }
        }

        item {
            Panel(onClick = onOpenRecord) {
                Text("Record", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Every block, with the words that caused it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ash,
                )
            }
        }

        item {
            Note(
                "Rules, usage and the record stay on this phone. Nothing is uploaded, and " +
                    "there is no account.",
            )
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}
