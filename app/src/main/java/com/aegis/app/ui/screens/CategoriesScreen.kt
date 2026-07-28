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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegis.app.engine.AegisEngine
import com.aegis.app.ui.components.AegisCard
import com.aegis.app.ui.components.Explanation
import com.aegis.app.ui.components.SectionHeader
import com.aegis.core.model.Category
import com.aegis.core.rules.CategoryRule
import com.aegis.core.rules.RuleMode
import com.aegis.core.util.LocalTime
import kotlinx.coroutines.launch

/**
 * Categories, not addresses (§3.1).
 *
 * The threshold slider is here rather than buried in an advanced screen because it is the
 * honest control for a probabilistic system. "Block adult content" is not a switch; it is
 * a line drawn on a confidence scale, and the person living with the consequences should
 * be able to see where the line is and move it.
 */
@Composable
fun CategoriesScreen() {
    val context = LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val scope = rememberCoroutineScope()
    val rules by engine.rules.collectAsState()
    val pending by engine.pending.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SectionHeader(
                title = "Categories",
                subtitle = if (rules.armed) {
                    "Stricter applies at once. Looser waits ${rules.coolingOffHours}h."
                } else {
                    "Aegis judges what a page contains, so a site it has never seen is still caught."
                },
            )
        }

        items(Category.entries.toList(), key = { it.id }) { category ->
            val rule = rules.ruleFor(category)
            val effective = engine.effectiveMode(category)
            val waiting = pending.firstOrNull { "category:${category.id}" in it.targetKeys }

            AegisCard {
                Text(category.label, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = category.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in RuleMode.entries) {
                        FilterChip(
                            selected = rule.mode == mode,
                            onClick = {
                                scope.launch {
                                    engine.submitRuleChange(
                                        rules.withCategoryRule(rule.copy(mode = mode)),
                                        note = "${category.label} → ${mode.label}",
                                    )
                                }
                            },
                            label = { Text(mode.label) },
                        )
                    }
                }

                if (effective != rule.mode) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "A profile is currently holding this at ${effective.label}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // Without this the chip simply springs back and the screen looks broken.
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

                if (rule.mode != RuleMode.OFF) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Blocks at ${(rule.blockThreshold * 100).toInt()}% confidence",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    ThresholdControls(
                        rule = rule,
                        onChange = { updated ->
                            scope.launch {
                                engine.submitRuleChange(rules.withCategoryRule(updated))
                            }
                        },
                    )
                }

                if (rule.mode == RuleMode.TIMED) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Daily budget: ${LocalTime.formatDuration(rule.dailyBudgetMinutes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (minutes in listOf(15, 30, 60, 120)) {
                            FilterChip(
                                selected = rule.dailyBudgetMinutes == minutes,
                                onClick = {
                                    scope.launch {
                                        engine.submitRuleChange(
                                            rules.withCategoryRule(rule.copy(dailyBudgetMinutes = minutes)),
                                        )
                                    }
                                },
                                label = { Text(LocalTime.formatDuration(minutes)) },
                            )
                        }
                    }
                }
            }
        }

        item {
            Explanation(
                if (rules.armed) {
                    "Tightening a rule applies at once. Loosening one waits out the cooling-off " +
                        "period — including lowering sensitivity or raising a budget."
                } else {
                    "Nothing is locked yet, so every change here applies immediately. Lock your " +
                        "rules in from the Home screen when they look right."
                },
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * Sensitivity as three named steps rather than a raw number.
 *
 * A continuous slider invites fiddling and gives no sense of what a value means. Three
 * choices with plain descriptions is the same control with the guesswork removed.
 */
@Composable
private fun ThresholdControls(
    rule: CategoryRule,
    onChange: (CategoryRule) -> Unit,
) {
    val options = listOf(
        Triple("Cautious", 0.45f, "Catches more. Expect the occasional wrong call."),
        Triple("Balanced", 0.60f, "The default."),
        Triple("Certain", 0.80f, "Only blocks when it is sure. Some things get through."),
    )

    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, threshold, _) in options) {
                FilterChip(
                    selected = kotlin.math.abs(rule.blockThreshold - threshold) < 0.01f,
                    onClick = { onChange(rule.copy(blockThreshold = threshold)) },
                    label = { Text(label) },
                )
            }
        }
        val current = options.minByOrNull { kotlin.math.abs(it.second - rule.blockThreshold) }
        if (current != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = current.third,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
