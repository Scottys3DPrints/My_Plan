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
import androidx.compose.ui.unit.dp
import com.aegis.app.engine.AegisEngine
import com.aegis.app.ui.components.Ledger
import com.aegis.app.ui.components.Note
import com.aegis.app.ui.components.Panel
import com.aegis.app.ui.components.SectionHeader
import com.aegis.app.ui.theme.Ash
import com.aegis.app.ui.theme.Brass
import com.aegis.app.ui.theme.Evidence
import com.aegis.app.ui.theme.Rust
import com.aegis.core.log.Correction
import com.aegis.core.log.LogEntry
import com.aegis.core.log.TransparencyLog
import com.aegis.core.rules.Outcome
import com.aegis.core.util.LocalTime
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * "Why was this blocked?" — with a one-tap answer to "because it was wrong".
 *
 * Laid out as a ledger rather than a feed, because that is what it is: a dated list of
 * judgments with the evidence attached. The outcome is a coloured rule down the left edge,
 * so blocks, warnings and allowances are separable by eye while scrolling, without a badge
 * on every row.
 *
 * A correction here is not a complaint box. It changes the weights the on-device
 * classifier uses, immediately and locally, and is visible and reversible in Settings.
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
                eyebrow = "Record",
                title = "What Aegis did",
                subtitle = "Kept on this phone, most recent ${TransparencyLog.LIMIT}, never uploaded.",
            )
        }

        if (log.entries.isEmpty()) {
            item { Note("Nothing yet. Blocks and warnings will appear here as they happen.") }
        }

        items(log.entries, key = { it.id }) { entry ->
            Panel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.host.ifBlank { "(no address)" },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = Evidence,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = timeOf(entry.atMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ash,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = outcomeLabel(entry).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (entry.outcome) {
                        Outcome.BLOCK -> Rust
                        Outcome.WARN -> Brass
                        Outcome.ALLOW -> Ash
                    },
                )

                if (entry.explanation.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(entry.explanation, style = MaterialTheme.typography.bodyMedium)
                }

                if (entry.evidence.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Ledger(entry.evidence)
                }

                if (entry.category != null) {
                    Spacer(Modifier.height(8.dp))
                    if (entry.correction != null) {
                        Text(
                            text = "You marked this: ${entry.correction!!.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Brass,
                        )
                    } else {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    engine.correct(
                                        entry,
                                        if (entry.wasBlocked) Correction.FALSE_POSITIVE else Correction.MISSED,
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (entry.wasBlocked) {
                                    "This was wrong"
                                } else {
                                    "This should have been blocked"
                                },
                                color = Ash,
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
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
