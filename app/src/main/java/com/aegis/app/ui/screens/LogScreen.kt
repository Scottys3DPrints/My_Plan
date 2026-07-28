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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegis.app.engine.AegisEngine
import com.aegis.app.ui.components.AegisCard
import com.aegis.app.ui.components.Explanation
import com.aegis.app.ui.components.SectionHeader
import com.aegis.core.log.Correction
import com.aegis.core.log.LogEntry
import com.aegis.core.rules.Outcome
import com.aegis.core.util.LocalTime
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * "Why was this blocked?" — with a one-tap answer to "because it was wrong" (§3.10).
 *
 * A correction here is not a complaint box. It changes the weights the on-device
 * classifier uses, immediately and locally, and the change is visible and reversible in
 * Settings. That is the difference between a filter that feels arbitrary and one that
 * feels like it is yours.
 */
@Composable
fun LogScreen() {
    val context = LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val scope = rememberCoroutineScope()
    val log by engine.log.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SectionHeader(
                title = "What Aegis did",
                subtitle = "Kept on this device, capped at the most recent ${com.aegis.core.log.TransparencyLog.LIMIT} events, never uploaded.",
            )
        }

        if (log.entries.isEmpty()) {
            item { Explanation("Nothing yet.") }
        }

        items(log.entries, key = { it.id }) { entry ->
            AegisCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.host.ifBlank { "(no address)" }, fontWeight = FontWeight.Medium)
                        Text(
                            text = "${outcomeLabel(entry)} · ${entry.route.label} · ${timeOf(entry.atMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (entry.explanation.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(entry.explanation, style = MaterialTheme.typography.bodyMedium)
                }

                if (entry.evidence.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = entry.evidence.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (entry.category != null) {
                    Spacer(Modifier.height(8.dp))
                    if (entry.correction != null) {
                        Text(
                            text = "You marked this: ${entry.correction!!.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (entry.wasBlocked) {
                                TextButton(onClick = {
                                    scope.launch { engine.correct(entry, Correction.FALSE_POSITIVE) }
                                }) {
                                    Text("This was wrong")
                                }
                            } else {
                                TextButton(onClick = {
                                    scope.launch { engine.correct(entry, Correction.MISSED) }
                                }) {
                                    Text("Should have been blocked")
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun outcomeLabel(entry: LogEntry): String = when (entry.outcome) {
    Outcome.BLOCK -> "Blocked"
    Outcome.WARN -> if (entry.proceededAnyway) "Warned, continued" else "Warned"
    Outcome.ALLOW -> "Allowed"
}

private fun timeOf(millis: Long): String {
    val offset = TimeZone.getDefault().getOffset(millis) / 60_000
    return LocalTime.formatMinuteOfDay(LocalTime.minuteOfDay(millis, offset))
}
