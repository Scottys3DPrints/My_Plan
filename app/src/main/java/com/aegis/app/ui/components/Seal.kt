package com.aegis.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aegis.app.ui.theme.Ash
import com.aegis.app.ui.theme.Brass
import com.aegis.app.ui.theme.BrassDim
import com.aegis.app.ui.theme.LocalAnimationsEnabled

/**
 * The signature of the app.
 *
 * Aegis has exactly one fact that matters more than all the others — are your rules
 * holding you, or are you still free to change them? In the first version that fact was a
 * card halfway down a scrolling list, indistinguishable from a budget row, and people
 * could not tell which state they were in. Here it is a single object you read from
 * across the room.
 *
 * Two states, drawn rather than written:
 *
 * - **Open.** A broken ring with a visible gap. Nothing is holding you.
 * - **Sealed.** A closed ring with a struck centre. The gap is gone.
 *
 * And when a change is queued, the wait is drawn as an arc closing over the gap —
 * [pendingProgress] of the way round. The countdown is the same object, in the same
 * language, rather than a number in grey text somewhere else. You can see how much of the
 * wait is left without reading anything, which is the point: the mechanism of this app is
 * time, so time is what the seal is made of.
 */
@Composable
fun Seal(
    sealed: Boolean,
    modifier: Modifier = Modifier,
    /** 0..1 through the cooling-off period of the soonest queued change, or null. */
    pendingProgress: Float? = null,
    /** Struck into the centre when sealed — e.g. "24h". */
    centreLabel: String = "",
    size: androidx.compose.ui.unit.Dp = 168.dp,
) {
    val animationsEnabled = LocalAnimationsEnabled.current

    // One deliberate moment, on the screen that matters, and only on first appearance.
    var appeared by remember { mutableStateOf(!animationsEnabled) }
    LaunchedEffect(Unit) { appeared = true }

    val sweep by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = if (animationsEnabled) 900 else 0),
        label = "sealSweep",
    )

    val closed by animateFloatAsState(
        targetValue = if (sealed) 1f else 0f,
        animationSpec = tween(durationMillis = if (animationsEnabled) 500 else 0),
        label = "sealClosed",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.055f
            val inset = stroke * 1.6f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)

            // The gap sits at the top and closes as the seal is armed. An open seal is
            // legible as open even in a thumbnail.
            val gapDegrees = 74f * (1f - closed)
            val start = -90f + gapDegrees / 2f
            val total = (360f - gapDegrees) * sweep

            // The ring itself.
            drawArc(
                color = if (sealed) Brass else Ash,
                startAngle = start,
                sweepAngle = total,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )

            // The waiting, drawn over the ring in the same geometry.
            if (pendingProgress != null) {
                drawArc(
                    color = BrassDim,
                    startAngle = start,
                    sweepAngle = (360f - gapDegrees) * pendingProgress.coerceIn(0f, 1f) * sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke * 2.1f),
                )
            }

            // The struck centre: present only when sealed, and only as a hairline circle,
            // so the ring stays the thing you read.
            if (closed > 0.01f) {
                drawCircle(
                    color = Brass.copy(alpha = 0.16f * closed),
                    radius = this.size.minDimension * 0.30f,
                )
                drawCircle(
                    color = Brass.copy(alpha = 0.55f * closed),
                    radius = this.size.minDimension * 0.30f,
                    style = Stroke(width = stroke * 0.35f),
                )
            }
        }

        if (sealed && centreLabel.isNotBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = centreLabel,
                    style = MaterialTheme.typography.displayMedium,
                    color = Brass,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "TO LOOSEN",
                    style = MaterialTheme.typography.labelMedium,
                    color = BrassDim,
                )
            }
        }
    }
}

/**
 * The same arc language at small size, used for the grace-tap pause.
 *
 * Reusing the seal's geometry is deliberate: waiting looks like one thing throughout the
 * app, whether the wait is sixty seconds or a day.
 */
@Composable
fun WaitRing(
    progress: Float,
    label: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 108.dp,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.07f
            val inset = stroke
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = BrassDim.copy(alpha = 0.4f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = Brass,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.headlineMedium,
            color = Brass,
        )
    }
}
