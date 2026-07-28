package com.aegis.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegis.app.engine.AegisEngine
import com.aegis.app.ui.components.AegisCard
import com.aegis.app.ui.components.Explanation
import com.aegis.app.ui.components.LabelledRow
import com.aegis.app.ui.components.SectionHeader
import com.aegis.core.rules.AccountabilityPartner
import com.aegis.core.util.LocalTime
import kotlinx.coroutines.launch

/**
 * The self-binding controls (§3.6, §3.7), plus what the classifier has learned.
 *
 * Every control here is subject to the same rule as everything else: making Aegis
 * stronger is instant, making it weaker waits. Including the control that sets how long
 * the wait is.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val scope = rememberCoroutineScope()
    val rules by engine.rules.collectAsState()
    val pending by engine.pending.collectAsState()

    var partnerName by remember { mutableStateOf(rules.partner?.name.orEmpty()) }
    var partnerContact by remember { mutableStateOf(rules.partner?.contact.orEmpty()) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SectionHeader(
                title = "Cooling-off",
                subtitle = "How long a change that weakens your rules has to wait.",
            )
        }

        item {
            AegisCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (hours in listOf(1, 12, 24, 72)) {
                        FilterChip(
                            selected = rules.coolingOffHours == hours,
                            onClick = {
                                scope.launch {
                                    engine.submitRuleChange(
                                        rules.copy(coolingOffHours = hours),
                                        note = "Cooling-off → ${hours}h",
                                    )
                                }
                            },
                            label = { Text("${hours}h") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (!rules.armed) {
                        "Currently ${rules.coolingOffHours}h, but nothing is locked yet — every " +
                            "change still applies immediately. This starts biting when you lock " +
                            "your rules in from the Home screen."
                    } else {
                        "Currently ${rules.coolingOffHours}h. Making this longer applies now; " +
                            "making it shorter has to sit through the current ${rules.coolingOffHours}h first."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (pending.isNotEmpty()) {
            item { SectionHeader(title = "Queued changes") }
            items(pending, key = { it.id }) { change ->
                AegisCard(highlighted = true) {
                    Text(change.summary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Takes effect in ${LocalTime.formatDuration(engine.minutesRemaining(change))}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { scope.launch { engine.cancelPending(change.id) } }) {
                        Text("Cancel")
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "Grace taps",
                subtitle = "The escape hatch, priced in waiting rather than in willpower.",
            )
        }

        item {
            AegisCard {
                LabelledRow("Pause before it is granted", "${rules.graceTapPauseSeconds}s")
                Spacer(Modifier.height(6.dp))
                LabelledRow("Time granted", LocalTime.formatDuration(rules.graceTapMinutes))
                Spacer(Modifier.height(6.dp))
                LabelledRow("Taps per day", rules.maxGraceTapsPerDay.toString())
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (taps in listOf(0, 1, 3, 5)) {
                        FilterChip(
                            selected = rules.maxGraceTapsPerDay == taps,
                            onClick = {
                                scope.launch {
                                    engine.submitRuleChange(rules.copy(maxGraceTapsPerDay = taps))
                                }
                            },
                            label = { Text(if (taps == 0) "None" else "$taps") },
                        )
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "Accountability partner",
                subtitle = "Someone who is told when you try to weaken your own rules.",
            )
        }

        item {
            AegisCard {
                OutlinedTextField(
                    value = partnerName,
                    onValueChange = { partnerName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = partnerContact,
                    onValueChange = { partnerContact = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Email or phone") },
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = partnerName.isNotBlank() && partnerContact.isNotBlank(),
                        onClick = {
                            scope.launch {
                                engine.submitRuleChange(
                                    rules.copy(
                                        partner = AccountabilityPartner(
                                            name = partnerName.trim(),
                                            contact = partnerContact.trim(),
                                        ),
                                    ),
                                    note = "Add accountability partner",
                                )
                            }
                        },
                    ) {
                        Text(if (rules.partner == null) "Add" else "Update")
                    }
                    if (rules.partner != null) {
                        TextButton(onClick = {
                            scope.launch { engine.submitRuleChange(rules.copy(partner = null)) }
                        }) {
                            Text("Remove")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Aegis prepares the message and hands it to your own mail or messaging " +
                        "app to send. It has no server of its own, and nothing about what you " +
                        "browse is ever included — only the fact that a rule was weakened.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionHeader(
                title = "Filtering",
                subtitle = "How the classifier behaves on pages Aegis renders itself.",
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
                        Text("Blur flagged images", fontWeight = FontWeight.Medium)
                        Text(
                            text = "On an allowed page, blur individual images that look explicit. " +
                                "Tap one to reveal it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = rules.blurFlaggedImages,
                        onCheckedChange = { blur ->
                            scope.launch { engine.submitRuleChange(rules.copy(blurFlaggedImages = blur)) }
                        },
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "What Aegis has learned from you",
                subtitle = "Corrections you made, applied on this device only.",
            )
        }

        item {
            val learned = engine.learnedAdjustments()
            AegisCard {
                if (learned.isEmpty()) {
                    Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    for (adjustment in learned) {
                        Text("• ${adjustment.label}", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (category in learned.map { it.category }.distinct()) {
                            TextButton(onClick = {
                                scope.launch { engine.resetLearning(category) }
                            }) {
                                Text("Reset ${category.label.substringBefore(' ')}")
                            }
                        }
                    }
                }
            }
        }

        item {
            Explanation(
                "Aegis has no account and no server. Rules, usage, corrections and the log " +
                    "live in this app's private storage and are excluded from cloud backup — " +
                    "restoring an old backup must not be a way around a cooling-off period.",
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
