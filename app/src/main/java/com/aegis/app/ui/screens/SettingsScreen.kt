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
import com.aegis.app.BuildConfig
import com.aegis.app.engine.AegisEngine
import com.aegis.app.service.AegisAccessibilityService
import com.aegis.app.update.UpdateCheck
import com.aegis.app.update.Updater
import com.aegis.app.ui.components.Eyebrow
import com.aegis.app.ui.components.Focus
import com.aegis.app.ui.components.Note
import com.aegis.app.ui.components.Panel
import com.aegis.app.ui.components.SectionHeader
import com.aegis.app.ui.components.StatRow
import com.aegis.app.ui.theme.Ash
import com.aegis.core.rules.AccountabilityPartner
import com.aegis.core.util.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                eyebrow = "Settings",
                title = "Cooling-off",
                subtitle = "How long a change that weakens your rules has to wait.",
            )
        }

        item {
            Panel {
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
                    color = Ash,
                )
            }
        }

        if (pending.isNotEmpty()) {
            item { Eyebrow("Queued changes") }
            items(pending, key = { it.id }) { change ->
                Focus {
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
            Panel {
                StatRow("Pause before it is granted", "${rules.graceTapPauseSeconds}s")
                Spacer(Modifier.height(6.dp))
                StatRow("Time granted", LocalTime.formatDuration(rules.graceTapMinutes))
                Spacer(Modifier.height(6.dp))
                StatRow("Taps per day", rules.maxGraceTapsPerDay.toString())
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
            Panel {
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
                    color = Ash,
                )
            }
        }

        item {
            SectionHeader(
                title = "Updates",
                subtitle = "Aegis is not in a store, so it looks for its own updates.",
            )
        }

        item {
            val update by engine.updateAvailable.collectAsState()
            val checksOn by engine.updateChecksEnabled.collectAsState()
            val signing = remember { Updater.signingIdentity(context) }
            var status by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }

            Panel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Check daily", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "One request to github.com. Nothing about you is sent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ash,
                        )
                    }
                    Switch(
                        checked = checksOn,
                        onCheckedChange = { on -> scope.launch { engine.setUpdateChecksEnabled(on) } },
                    )
                }

                Spacer(Modifier.height(14.dp))
                StatRow("Installed", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                if (signing.fingerprint.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    StatRow("Signed with", signing.fingerprint)
                }

                val available = update
                if (available != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Version ${available.versionName} is available" +
                            if (available.readableSize.isNotBlank()) " (${available.readableSize})." else ".",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                status = "Downloading…"
                                val file = withContext(Dispatchers.IO) {
                                    Updater.download(context, available) { percent ->
                                        status = "Downloading… $percent%"
                                    }
                                }
                                busy = false
                                if (file == null) {
                                    status = "Download failed. Try again later."
                                } else {
                                    status = ""
                                    Updater.install(context, file)
                                }
                            }
                        },
                    ) {
                        Text(if (busy) status.ifBlank { "Working…" } else "Download and install")
                    }
                } else {
                    Spacer(Modifier.height(14.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                status = "Checking…"
                                // Each outcome says something different. Reporting them all
                                // as "you are up to date" is how this screen used to lie.
                                status = when (val result = engine.checkForUpdate(force = true)) {
                                    is UpdateCheck.Available -> ""
                                    UpdateCheck.UpToDate -> "You are on the latest release."
                                    UpdateCheck.NoReleases ->
                                        "No releases have been published yet, so there is " +
                                            "nothing to update to. Builds sit in GitHub Actions " +
                                            "until a version tag is pushed."
                                    is UpdateCheck.NoInstaller ->
                                        "Release ${result.tag} exists but has no APK attached yet."
                                    is UpdateCheck.Failed -> result.reason
                                }
                                busy = false
                            }
                        },
                    ) {
                        Text("Check now")
                    }
                }

                if (status.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ash,
                    )
                }
            }
        }

        // Surfaced as its own panel because it is not advice — it decides whether the
        // button above can ever succeed.
        if (Updater.signingIdentity(context).isDebugKey) {
            item {
                Focus {
                    Text("Updates cannot install", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "This build is signed with Android's debug key, which is " +
                            "generated fresh on every build machine. Android only accepts an " +
                            "update signed with the same key as the installed app, so any " +
                            "update found here would fail at the final dialog.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ash,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Fix it once by running tools/make-keystore.sh and adding the " +
                            "four secrets to the repository. Every build after that shares " +
                            "your key and updates land in place.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ash,
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "Filtering",
                subtitle = "How the classifier behaves on pages Aegis renders itself.",
            )
        }

        item {
            Panel {
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
                            color = Ash,
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
                eyebrow = "Diagnostics",
                title = "What the guard is seeing",
                subtitle = "If a site is not being blocked in another browser, this says why.",
            )
        }

        item {
            val observation by engine.diagnostics.state.collectAsState()
            val guardOn = remember { AegisAccessibilityService.isConnected }

            Panel {
                Text(observation.summary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
                StatRow("Guard running", if (guardOn) "yes" else "no")
                Spacer(Modifier.height(6.dp))
                StatRow("Last app", observation.lastPackage.ifBlank { "—" })
                Spacer(Modifier.height(6.dp))
                StatRow(
                    "Last address",
                    observation.lastUrl.ifBlank { "—" }.take(40),
                )
                Spacer(Modifier.height(6.dp))
                StatRow("Page text read", "${observation.lastHarvestChars} chars")
                Spacer(Modifier.height(6.dp))
                StatRow(
                    "Closest match",
                    if (observation.lastTopCategory.isBlank()) "none"
                    else "${observation.lastTopCategory} ${(observation.lastConfidence * 100).toInt()}%",
                )
                Spacer(Modifier.height(6.dp))
                StatRow("Last block attempt", observation.lastEnforcement.ifBlank { "—" })
                Spacer(Modifier.height(6.dp))
                StatRow(
                    "Page credited to",
                    if (observation.mismatches == 0L) "the app that reported it"
                    else "the window on screen (${observation.mismatches} corrected)",
                )
                Spacer(Modifier.height(16.dp))
                var testResult by remember { mutableStateOf("") }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val service = AegisAccessibilityService.running
                        testResult = if (service == null) {
                            "The guard is not running. Turn it on from the Home screen."
                        } else {
                            val error = service.showTestOverlay()
                            if (error.isBlank()) "" else "Could not draw it — $error"
                        }
                    },
                ) {
                    Text("Test the block screen")
                }
                if (testResult.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = testResult, style = MaterialTheme.typography.bodySmall, color = Ash)
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Open the page in the other browser, then come back here. " +
                        "Zero characters means the page exposed nothing readable. A match " +
                        "below your threshold means the category needs Cautious sensitivity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ash,
                )
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
            Panel {
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
            Note(
                "Aegis has no account and no server. Rules, usage, corrections and the log " +
                    "live in this app's private storage and are excluded from cloud backup — " +
                    "restoring an old backup must not be a way around a cooling-off period.",
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
