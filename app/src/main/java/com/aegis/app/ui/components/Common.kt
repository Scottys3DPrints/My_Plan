package com.aegis.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aegis.app.ui.theme.Ash
import com.aegis.app.ui.theme.Brass
import com.aegis.app.ui.theme.Evidence

/**
 * The vocabulary the screens are built from.
 *
 * Deliberately small. The first version had one card style used for everything, which is
 * why "your protection is off" and "here is a budget" carried identical weight and nothing
 * looked more important than anything else. These pieces differ by *rank*: [Eyebrow] names
 * a region, [Panel] holds an ordinary group, [Focus] is reserved for the one thing on a
 * screen that must be noticed, and [Ledger] is for facts rather than prose.
 */

/**
 * The wordmark.
 *
 * Set in the evidence face and widely letterspaced, so the app's own name reads as a
 * stamp rather than a title — the same register as the seal it sits above.
 */
@Composable
fun Brand(modifier: Modifier = Modifier) {
    Text(
        text = "A E G I S",
        style = MaterialTheme.typography.labelMedium,
        color = Brass,
        modifier = modifier,
    )
}

/**
 * The small monospaced label that names a region.
 *
 * Monospaced and letterspaced so it reads as a tab on a drawer rather than a heading —
 * structure, not content.
 */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Ash,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

/** A region title in the display serif, with optional supporting prose beneath it. */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    eyebrow: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
        if (eyebrow != null) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Ash,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Ash,
            )
        }
    }
}

/** An ordinary group of controls. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

/**
 * The one thing on a screen that must be noticed.
 *
 * Brass hairline rather than a filled block: it draws the eye without shouting, and keeps
 * the accent colour meaning "your authority" rather than "warning".
 */
@Composable
fun Focus(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, Brass.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

/**
 * Facts, set in the evidence face.
 *
 * Used for matched terms, hostnames and confidences. The distinct face is doing real work:
 * it tells the reader at a glance that they are looking at what the classifier actually
 * saw, not at a description of it.
 */
@Composable
fun Ledger(lines: List<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (line in lines) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = Evidence,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A label on the left, a value on the right in the evidence face. */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Spacer(Modifier.width(12.dp))
        Text(text = value, style = MaterialTheme.typography.labelSmall, color = Brass)
    }
}

/** Quiet supporting prose between sections. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Ash,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

/**
 * A horizontal meter for a budget.
 *
 * Shows spent against allowance as a filled bar, so "most of the day is gone" is legible
 * without arithmetic. Turns brass when the remaining time is short.
 */
@Composable
fun BudgetBar(
    spentMinutes: Int,
    allowanceMinutes: Int,
    modifier: Modifier = Modifier,
) {
    val fraction = if (allowanceMinutes <= 0) 0f
    else (spentMinutes.toFloat() / allowanceMinutes).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (fraction > 0.8f) Brass else Ash),
        )
    }
}
