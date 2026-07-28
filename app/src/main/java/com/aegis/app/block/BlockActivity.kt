package com.aegis.app.block

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegis.app.engine.AegisEngine
import com.aegis.app.ui.theme.AegisTheme
import com.aegis.core.budget.GraceClaimResult
import com.aegis.core.budget.GraceRequestResult
import com.aegis.core.log.Correction
import com.aegis.core.rules.Decision
import com.aegis.core.util.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The screen a person actually meets when Aegis says no.
 *
 * This is where §2.5 is either honoured or not. It states the cause, shows the evidence,
 * says when the block lifts, and offers two honest exits: correct a misfire, or spend a
 * grace tap — which costs a full minute of sitting here first.
 *
 * There is no "continue anyway" on a walled category, and that omission is the product.
 */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val decision = runCatching {
            Json.decodeFromString(Decision.serializer(), intent.getStringExtra(EXTRA_DECISION).orEmpty())
        }.getOrNull()

        if (decision == null) {
            finish()
            return
        }

        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        val logEntryId = intent.getStringExtra(EXTRA_LOG_ENTRY_ID)

        setContent {
            AegisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BlockScreen(
                        decision = decision,
                        target = target,
                        logEntryId = logEntryId,
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_DECISION = "decision"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_LOG_ENTRY_ID = "log_entry_id"

        fun intentFor(
            context: Context,
            decision: Decision,
            target: String,
            blockedPackage: String? = null,
            logEntryId: String? = null,
        ): Intent = Intent(context, BlockActivity::class.java)
            .putExtra(EXTRA_DECISION, Json.encodeToString(Decision.serializer(), decision))
            .putExtra(EXTRA_TARGET, target)
            .putExtra(EXTRA_PACKAGE, blockedPackage)
            .putExtra(EXTRA_LOG_ENTRY_ID, logEntryId)
    }
}

@Composable
private fun BlockScreen(
    decision: Decision,
    target: String,
    logEntryId: String?,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val scope = rememberCoroutineScope()

    var graceState by remember { mutableStateOf<GraceState>(GraceState.Idle) }
    var corrected by remember { mutableStateOf(false) }

    // Tick the mandatory pause down. The countdown is the feature: the point is to still
    // be here, watching it, when the impulse has passed.
    LaunchedEffect(graceState) {
        val waiting = graceState as? GraceState.Waiting ?: return@LaunchedEffect
        var remaining = waiting.secondsRemaining
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
            graceState = GraceState.Waiting(remaining)
        }
        graceState = GraceState.Ready
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = decision.cause?.headline ?: "Blocked",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(8.dp))

        if (target.isNotBlank()) {
            Text(
                text = target,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }

        Text(text = decision.explanation, style = MaterialTheme.typography.bodyLarge)

        decision.liftsAtMillis?.let { liftsAt ->
            Spacer(Modifier.height(12.dp))
            val minute = LocalTime.minuteOfDay(liftsAt, java.util.TimeZone.getDefault().getOffset(liftsAt) / 60_000)
            Text(
                text = "Lifts at ${LocalTime.formatMinuteOfDay(minute)}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (decision.evidence.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Why",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (item in decision.evidence) {
                        Text("• $item", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        val graceKey = decision.graceKey
        if (decision.graceTapAvailable && graceKey != null) {
            when (val state = graceState) {
                GraceState.Idle -> {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                when (val result = engine.requestGraceTap(graceKey)) {
                                    is GraceRequestResult.Waiting ->
                                        graceState = GraceState.Waiting(
                                            result.pending.secondsRemaining(android.os.SystemClock.elapsedRealtime()),
                                        )

                                    is GraceRequestResult.Refused ->
                                        graceState = GraceState.Refused(result.reason)
                                }
                            }
                        },
                    ) {
                        Text("Give me five more minutes")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${engine.graceTapsRemaining()} left today. There is a one-minute wait.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is GraceState.Waiting -> {
                    Text(
                        text = "${state.secondsRemaining}",
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Still want it in a minute? Come back and take it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                GraceState.Ready -> {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                when (engine.claimGraceTap(graceKey)) {
                                    is GraceClaimResult.Granted -> onDismiss()
                                    is GraceClaimResult.NotReady -> Unit
                                    is GraceClaimResult.Refused -> graceState =
                                        GraceState.Refused("No grace taps left today.")
                                }
                            }
                        },
                    ) {
                        Text("Take five minutes")
                    }
                }

                is GraceState.Refused -> Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (logEntryId != null && decision.category != null) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !corrected,
                onClick = {
                    scope.launch {
                        val entry = engine.log.value.entries.firstOrNull { it.id == logEntryId }
                        if (entry != null) {
                            engine.correct(entry, Correction.FALSE_POSITIVE)
                            corrected = true
                        }
                    }
                },
            ) {
                Text(if (corrected) "Noted — Aegis has adjusted" else "This was wrong")
            }
        }

        Button(modifier = Modifier.fillMaxWidth(), onClick = onDismiss) {
            Text("Close")
        }
    }
}

private sealed interface GraceState {
    data object Idle : GraceState
    data class Waiting(val secondsRemaining: Int) : GraceState
    data object Ready : GraceState
    data class Refused(val reason: String) : GraceState
}
