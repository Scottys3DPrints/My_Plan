package com.aegis.app.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aegis.app.engine.AegisEngine
import com.aegis.app.service.CriticalPackages
import com.aegis.app.service.SocialPackages
import com.aegis.core.feed.ShortFormRule
import com.aegis.core.feed.ShortFormSurface
import com.aegis.app.ui.components.Eyebrow
import com.aegis.app.ui.components.Focus
import com.aegis.app.ui.components.Note
import com.aegis.app.ui.components.Panel
import com.aegis.app.ui.components.SectionHeader
import com.aegis.app.ui.components.StatRow
import com.aegis.app.ui.onboarding.ModePicker
import com.aegis.app.ui.theme.Ash
import com.aegis.app.ui.theme.Brass
import com.aegis.core.model.Category
import com.aegis.core.rules.AppRule
import com.aegis.core.rules.RuleMode
import com.aegis.core.util.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything you have told Aegis to hold, in one place.
 *
 * Content and apps used to be separate tabs of equal standing in a five-tab bar, which
 * implied they were unrelated. They are the same decision seen twice — what am I choosing
 * not to reach for — so they live under one heading with a switch between them.
 */
@Composable
fun RulesScreen() {
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        SectionHeader(
            eyebrow = "Rules",
            title = when (tab) {
                0 -> "What Aegis judges"
                1 -> "Apps on this phone"
                else -> "Reels, Shorts, and everything after them"
            },
            subtitle = when (tab) {
                0 -> "Categories, not addresses. A site it has never seen is still caught."
                1 -> "Block an app outright, or give it a daily budget."
                else -> "Switch a screen off outright, or be told when a scroll has run long."
            },
        )

        Switcher(
            options = listOf("Content", "Apps", "Scrolling"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(8.dp))

        when (tab) {
            0 -> ContentRules()
            1 -> AppRules()
            else -> ScrollRules()
        }
    }
}

/**
 * The endless-feed rule.
 *
 * Separate from the Apps tab on purpose, even though it is also per-app. A daily budget
 * answers "how much of this do I get?"; this answers "am I still choosing?" — and putting
 * them in one list would have made this look like another way of spelling a time limit,
 * which is the one thing it is not.
 */
@Composable
private fun ScrollRules() {
    val context = LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val scope = rememberCoroutineScope()
    val rules by engine.rules.collectAsState()
    val pending by engine.pending.collectAsState()
    val rule = rules.feedRule

    val installed by produceState(initialValue = emptyList<InstalledApp>()) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    // Known feeds first, then everything else. The list is long and the four apps anyone
    // actually means should not need scrolling to reach — which would be its own joke.
    val ordered = remember(installed) {
        installed.sortedWith(
            compareByDescending<InstalledApp> { it.packageName in SocialPackages.KNOWN }
                .thenBy { it.label.lowercase() },
        )
    }

    val installedPackages = remember(installed) { installed.map { it.packageName }.toSet() }
    val waiting = pending.firstOrNull { "feed" in it.targetKeys }

    fun submit(updated: com.aegis.core.rules.FeedRule) {
        scope.launch { engine.submitRuleChange(rules.copy(feedRule = updated)) }
    }

    val shortForm = rules.shortFormRule
    val shortFormWaiting = pending.firstOrNull { "shortform" in it.targetKeys }

    fun submitShortForm(updated: ShortFormRule) {
        scope.launch { engine.submitRuleChange(rules.copy(shortFormRule = updated)) }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SectionHeader(
                title = "Switch a screen off entirely",
                subtitle = "No timer, no threshold, no warning first. The screen simply " +
                    "does not open.",
            )
        }

        items(ShortFormSurface.entries.toList(), key = { "surface:${it.id}" }) { surface ->
            val isInstalled = surface.packageName in installedPackages
            Panel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(surface.label, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (isInstalled) "Stopped at the tap, before it opens."
                            else "Not installed on this phone.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ash,
                        )
                    }
                    Switch(
                        checked = surface in shortForm.surfaces && shortForm.enabled,
                        onCheckedChange = { on ->
                            val surfaces = if (on) shortForm.surfaces + surface
                            else shortForm.surfaces - surface
                            submitShortForm(
                                shortForm.copy(enabled = surfaces.isNotEmpty(), surfaces = surfaces),
                            )
                        },
                    )
                }
            }
        }

        if (shortFormWaiting != null) {
            item {
                Panel {
                    WaitingNotice(
                        summary = shortFormWaiting.summary,
                        minutes = engine.minutesRemaining(shortFormWaiting),
                        onCancel = { scope.launch { engine.cancelPending(shortFormWaiting.id) } },
                    )
                }
            }
        }

        item {
            Note(
                "The rest of the app keeps working — only that screen is refused. It is " +
                    "recognised by its layout, never by anything anyone posted, so no " +
                    "captions or comments are read to do this.",
            )
        }

        item {
            SectionHeader(
                title = "Interrupt long scrolling",
                subtitle = "For the feeds you keep. Not a block — a number, and a way out.",
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
                        Text("Interrupt endless scrolling", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Says how long you have been at it, then puts the phone down.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ash,
                        )
                    }
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { on ->
                            submit(
                                if (on && rule.packageNames.isEmpty()) {
                                    // Turning it on with nothing selected would be a switch
                                    // that does nothing, so it starts with the feeds that
                                    // are actually installed. Every one is removable below.
                                    rule.copy(
                                        enabled = true,
                                        packageNames = installed
                                            .map { it.packageName }
                                            .filter { it in SocialPackages.KNOWN }
                                            .toSet(),
                                    )
                                } else {
                                    rule.copy(enabled = on)
                                },
                            )
                        },
                    )
                }

                if (waiting != null) {
                    Spacer(Modifier.height(16.dp))
                    WaitingNotice(
                        summary = waiting.summary,
                        minutes = engine.minutesRemaining(waiting),
                        onCancel = { scope.launch { engine.cancelPending(waiting.id) } },
                    )
                }
            }
        }

        if (rule.enabled) {
            item {
                Panel {
                    Eyebrow("After")
                    Spacer(Modifier.height(8.dp))
                    Text("Say something after", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    MinutePicker(
                        options = listOf(5, 10, 20, 30),
                        selected = rule.afterMinutes,
                        onSelect = { submit(rule.copy(afterMinutes = it)) },
                    )
                    Spacer(Modifier.height(18.dp))
                    Text("Then again every", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    MinutePicker(
                        options = listOf(2, 5, 10, 15),
                        selected = rule.remindEveryMinutes,
                        onSelect = { submit(rule.copy(remindEveryMinutes = it)) },
                    )
                }
            }

            item {
                Note(
                    "A pause of more than a minute and a half ends the run and the count " +
                        "starts again — so glancing at a message does not cost you, and " +
                        "putting the phone down genuinely resets it.",
                )
            }

            item {
                SectionHeader(
                    title = "Which apps",
                    subtitle = "Scrolling is also how you read an article or go back " +
                        "through a chat, so this only watches what you name.",
                )
            }

            items(ordered, key = { it.packageName }) { app ->
                val watched = app.packageName in rule.packageNames
                Panel {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            if (app.packageName in SocialPackages.KNOWN) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Known feed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Brass,
                                )
                            }
                        }
                        Switch(
                            checked = watched,
                            onCheckedChange = { on ->
                                submit(
                                    rule.copy(
                                        packageNames = if (on) rule.packageNames + app.packageName
                                        else rule.packageNames - app.packageName,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        } else {
            item {
                Note(
                    "Off. Nothing counts your scrolling, and no scroll events are looked " +
                        "at — the rule names the apps it watches, and right now it names none.",
                )
            }
        }

        item {
            Note(
                "This needs the app & route guard switched on, the same as app limits. " +
                    "Nothing about what you scrolled past is recorded — only how long, " +
                    "in which app, and that is what appears in the Record.",
            )
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

/** Two-way switch, styled as one object rather than two competing buttons. */
@Composable
fun Switcher(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        for ((index, option) in options.withIndex()) {
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .then(if (isSelected) Modifier.background(Brass.copy(alpha = 0.16f)) else Modifier)
                    .clickable { onSelect(index) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Brass else Ash,
                )
            }
        }
    }
}

@Composable
private fun ContentRules() {
    val context = LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val scope = rememberCoroutineScope()
    val rules by engine.rules.collectAsState()
    val pending by engine.pending.collectAsState()

    LazyColumn(Modifier.fillMaxSize()) {
        items(Category.entries.toList(), key = { it.id }) { category ->
            val rule = rules.ruleFor(category)
            val effective = engine.effectiveMode(category)
            val waiting = pending.firstOrNull { "category:${category.id}" in it.targetKeys }

            Panel {
                Text(category.label, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = category.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ash,
                )

                Spacer(Modifier.height(16.dp))
                ModePicker(
                    selected = rule.mode,
                    onSelect = { mode ->
                        scope.launch {
                            engine.submitRuleChange(rules.withCategoryRule(rule.copy(mode = mode)))
                        }
                    },
                )

                if (effective != rule.mode) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "A profile is holding this at ${effective.label}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brass,
                    )
                }

                if (rule.mode != RuleMode.OFF) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "How sure must it be?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    SensitivityPicker(
                        threshold = rule.blockThreshold,
                        onSelect = { threshold ->
                            scope.launch {
                                engine.submitRuleChange(
                                    rules.withCategoryRule(rule.copy(blockThreshold = threshold)),
                                )
                            }
                        },
                    )
                }

                if (rule.mode == RuleMode.TIMED) {
                    Spacer(Modifier.height(18.dp))
                    Text("Daily budget", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    MinutePicker(
                        options = listOf(15, 30, 60, 120),
                        selected = rule.dailyBudgetMinutes,
                        onSelect = { minutes ->
                            scope.launch {
                                engine.submitRuleChange(
                                    rules.withCategoryRule(rule.copy(dailyBudgetMinutes = minutes)),
                                )
                            }
                        },
                    )
                }

                if (waiting != null) {
                    Spacer(Modifier.height(16.dp))
                    WaitingNotice(
                        summary = waiting.summary,
                        minutes = engine.minutesRemaining(waiting),
                        onCancel = { scope.launch { engine.cancelPending(waiting.id) } },
                    )
                }
            }
        }

        item {
            Note(
                if (rules.armed) {
                    "Tightening applies at once. Loosening — a gentler mode, a lower " +
                        "sensitivity, a bigger budget — waits ${rules.coolingOffHours} hours."
                } else {
                    "Nothing is sealed, so every change here applies immediately."
                },
            )
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun AppRules() {
    val context = LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val scope = rememberCoroutineScope()
    val rules by engine.rules.collectAsState()
    val usage by engine.usage.collectAsState()
    val pending by engine.pending.collectAsState()

    var query by remember { mutableStateOf("") }

    val installed by produceState(initialValue = emptyList<InstalledApp>()) {
        val critical = withContext(Dispatchers.IO) { CriticalPackages.resolve(context) }
        value = withContext(Dispatchers.IO) {
            loadLaunchableApps(context).filterNot { it.packageName in critical }
        }
    }

    val visible = remember(installed, query) {
        if (query.isBlank()) installed
        else installed.filter { it.label.contains(query, ignoreCase = true) }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Find an app") },
            )
        }

        if (installed.isEmpty()) {
            item { Note("Reading the list of installed apps…") }
        }

        items(visible, key = { it.packageName }) { app ->
            val rule = rules.ruleFor(app.packageName) ?: AppRule(app.packageName, app.label)
            val spent = usage.packageMinutes(app.packageName)
            val waiting = pending.firstOrNull { "app:${app.packageName}" in it.targetKeys }

            Panel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (spent > 0) "${LocalTime.formatDuration(spent)} today" else "Unused today",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ash,
                        )
                    }
                    Switch(
                        checked = rule.blocked,
                        onCheckedChange = { blocked ->
                            scope.launch {
                                engine.submitRuleChange(
                                    rules.withAppRule(rule.copy(label = app.label, blocked = blocked)),
                                )
                            }
                        },
                    )
                }

                if (!rule.blocked) {
                    Spacer(Modifier.height(14.dp))
                    Text("Daily limit", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    MinutePicker(
                        options = listOf(0, 15, 30, 60),
                        selected = rule.dailyBudgetMinutes,
                        onSelect = { minutes ->
                            scope.launch {
                                engine.submitRuleChange(
                                    rules.withAppRule(
                                        rule.copy(label = app.label, dailyBudgetMinutes = minutes),
                                    ),
                                )
                            }
                        },
                    )
                }

                if (waiting != null) {
                    Spacer(Modifier.height(16.dp))
                    WaitingNotice(
                        summary = waiting.summary,
                        minutes = engine.minutesRemaining(waiting),
                        onCancel = { scope.launch { engine.cancelPending(waiting.id) } },
                    )
                }
            }
        }

        item {
            Note(
                "App limits need the app & route guard switched on. Your launcher, Settings " +
                    "and the dialer are never blockable and are left off this list.",
            )
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

/**
 * Sensitivity as three named positions.
 *
 * A raw 0–1 slider invites fiddling and means nothing to read. These say what the choice
 * costs you in each direction, which is the only thing worth knowing about a threshold.
 */
@Composable
private fun SensitivityPicker(threshold: Float, onSelect: (Float) -> Unit) {
    val options = listOf(
        Triple("Cautious", 0.45f, "Catches more. Expect the odd wrong call."),
        Triple("Balanced", 0.60f, "The default."),
        Triple("Certain", 0.80f, "Only when sure. Some things get through."),
    )
    val currentIndex = options.indices.minByOrNull { kotlin.math.abs(options[it].second - threshold) } ?: 1

    Column {
        Switcher(
            options = options.map { it.first },
            selected = currentIndex,
            onSelect = { onSelect(options[it].second) },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = options[currentIndex].third,
            style = MaterialTheme.typography.bodySmall,
            color = Ash,
        )
    }
}

@Composable
private fun MinutePicker(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Switcher(
        options = options.map { if (it == 0) "None" else LocalTime.formatDuration(it) },
        selected = options.indexOf(selected).takeIf { it >= 0 } ?: 0,
        onSelect = { onSelect(options[it]) },
    )
}

/** Shown inline against the control the change is waiting on. */
@Composable
private fun WaitingNotice(summary: String, minutes: Int, onCancel: () -> Unit) {
    Column {
        StatRow(summary, LocalTime.formatDuration(minutes))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel that change")
        }
    }
}

data class InstalledApp(val packageName: String, val label: String)

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
                // Keep updated system apps — a preinstalled browser is exactly what someone
                // wants to limit — but drop the rest of the platform.
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
