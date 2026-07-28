package com.aegis.app.block

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.aegis.app.engine.AegisEngine
import com.aegis.app.ui.components.Ledger
import com.aegis.app.ui.components.WaitRing
import com.aegis.app.ui.theme.AegisTheme
import com.aegis.app.ui.theme.Ash
import com.aegis.app.ui.theme.Brass
import com.aegis.app.ui.theme.Evidence
import com.aegis.app.ui.theme.Rust
import com.aegis.core.budget.GraceClaimResult
import com.aegis.core.budget.GraceRequestResult
import com.aegis.core.log.Correction
import com.aegis.core.rules.Decision
import com.aegis.core.util.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The screen a person actually meets when Aegis says no — and by a wide margin the one
 * they will see most often.
 *
 * It was plain centred text, which is the wrong answer for the most-seen surface in the
 * product. What it needs to do, in order:
 *
 * 1. **Be unmistakable in half a second.** The struck mark does that before any reading
 *    happens. It is the only place rust appears in the whole app, so it means one thing.
 * 2. **Say what happened, in a sentence.** Not "blocked" — the category, the confidence,
 *    and the route it came in by.
 * 3. **Show its working.** The matched terms, set in the evidence face, so a fair block
 *    and a misfire look different from each other.
 * 4. **Offer only honest exits.** A correction if the classifier was wrong, and a grace
 *    tap where one applies — priced in a minute of sitting here, drawn as the same closing
 *    arc used by the seal. There is no "continue anyway" on a walled category, and that
 *    absence is the product.
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

/** A circle struck through. Drawn rather than an icon, so it scales and carries weight. */
@Composable
private fun StruckMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(72.dp)) {
        val stroke = size.minDimension * 0.075f
        drawCircle(
            color = Rust,
            radius = size.minDimension / 2 - stroke,
            style = Stroke(width = stroke),
        )
        val inset = size.minDimension * 0.27f
        drawLine(
            color = Rust,
            start = Offset(inset, size.height - inset),
            end = Offset(size.width - inset, inset),
            strokeWidth = stroke,
        )
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
    var pauseSeconds by remember { mutableStateOf(60) }

    // The countdown is the feature. The point is to still be here, watching it, once the
    // impulse has passed.
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
            .padding(horizontal = 28.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        StruckMark()

        Spacer(Modifier.height(28.dp))

        Text(
            text = decision.cause?.headline ?: "Blocked",
            style = MaterialTheme.typography.displayMedium,
        )

        if (target.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = target,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = Evidence,
                color = Ash,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(text = decision.explanation, style = MaterialTheme.typography.bodyLarge)

        decision.liftsAtMillis?.let { liftsAt ->
            Spacer(Modifier.height(12.dp))
            val minute = LocalTime.minuteOfDay(
                liftsAt,
                java.util.TimeZone.getDefault().getOffset(liftsAt) / 60_000,
            )
            Text(
                text = "Lifts at ${LocalTime.formatMinuteOfDay(minute)}",
                style = MaterialTheme.typography.labelSmall,
                color = Brass,
            )
        }

        if (decision.evidence.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "WHAT IT MATCHED",
                style = MaterialTheme.typography.labelMedium,
                color = Ash,
            )
            Spacer(Modifier.height(8.dp))
            Ledger(decision.evidence)
        }

        Spacer(Modifier.height(32.dp))

        val graceKey = decision.graceKey
        if (decision.graceTapAvailable && graceKey != null) {
            when (val state = graceState) {
                GraceState.Idle -> {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                when (val result = engine.requestGraceTap(graceKey)) {
                                    is GraceRequestResult.Waiting -> {
                                        val seconds = result.pending.secondsRemaining(
                                            android.os.SystemClock.elapsedRealtime(),
                                        )
                                        pauseSeconds = seconds.coerceAtLeast(1)
                                        graceState = GraceState.Waiting(seconds)
                                    }

                                    is GraceRequestResult.Refused ->
                                        graceState = GraceState.Refused(result.reason)
                                }
                            }
                        },
                    ) {
                        Text("Give me five more minutes")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${engine.graceTapsRemaining()} left today, and a minute of waiting first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ash,
                    )
                }

                is GraceState.Waiting -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        WaitRing(
                            progress = 1f - (state.secondsRemaining.toFloat() / pauseSeconds),
                            label = "${state.secondsRemaining}",
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Still want it in a minute? Come back and take it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ash,
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
                                    is GraceClaimResult.Refused ->
                                        graceState = GraceState.Refused("No grace taps left today.")
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
                    color = Ash,
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        Button(modifier = Modifier.fillMaxWidth(), onClick = onDismiss) {
            Text("Close")
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
                Text(
                    text = if (corrected) "Noted — Aegis has adjusted" else "This was wrong",
                    color = Ash,
                )
            }
        }
    }
}

private sealed interface GraceState {
    data object Idle : GraceState
    data class Waiting(val secondsRemaining: Int) : GraceState
    data object Ready : GraceState
    data class Refused(val reason: String) : GraceState
}
