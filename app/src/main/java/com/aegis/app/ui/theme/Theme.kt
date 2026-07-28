package com.aegis.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ## The look, and why it is this one
 *
 * Aegis spends most of its visible life refusing someone. That single fact drives every
 * choice here.
 *
 * **Dark, always.** Not a preference — a fit. This app is tested at one in the morning,
 * when a person reaches for their phone without deciding to, and a page of white is a
 * slap. It also has to be unmistakable at a glance: the built-in browser is the only
 * place content filtering works properly, so it must never be confused with Chrome. There
 * is no light theme, and the launch theme matches, so there is no white flash either.
 *
 * **Brass for your own authority.** Every element that represents a decision *you* made —
 * the seal, a locked rule, the time you are made to wait — is brass. Not red. A blocker
 * that shouts in warning colours reads as an accusation, and an accusing tool gets
 * uninstalled; the refusals here carry their weight in words and in typography instead.
 * Rust appears in exactly two places: the mark on the block screen, and the line that
 * says the guard was silently switched off by an update. The second one is not a warning
 * about the user's behaviour — it is the app admitting it has not been doing its job, and
 * that is the one message here that must not be politely coloured. It is also the only
 * failure the app has that is completely invisible from the outside: everything looks
 * right, and the Record is showing last week.
 *
 * **Three type roles, each doing one job.** A serif for judgments and headings, because
 * almost nothing on Android uses one and it stops the app reading as a settings page. A
 * sans for prose. A monospace for evidence — hostnames, matched terms, durations,
 * confidence. That last one is not styling: in a tool that blocks things, being able to
 * see at a glance that you are looking at a literal address rather than a description is
 * the difference between reading a claim and reading a fact.
 */

// ---------------------------------------------------------------------------- palette

/** App ground. Blue-black rather than neutral, so brass reads warm against it. */
val Obsidian = Color(0xFF0E0E12)

/** Raised surfaces: cards, sheets. */
val Slate = Color(0xFF191921)

/** The surface above that — inputs, selected states, the ledger blocks. */
val SlateHigh = Color(0xFF24242E)

/** Primary text. Warm, so it does not glare. */
val Chalk = Color(0xFFEFEBE3)

/** Secondary text and hairlines. */
val Ash = Color(0xFF8B8880)

/** Your authority: the seal, locked rules, the waiting. */
val Brass = Color(0xFFC89B4A)

/** Brass at rest — inactive rings, dividers that still belong to the seal. */
val BrassDim = Color(0xFF6E5629)

/** Used once, on the mark that means "refused". Never as a fill. */
val Rust = Color(0xFFC2604A)

private val AegisColors = darkColorScheme(
    primary = Brass,
    onPrimary = Obsidian,
    primaryContainer = SlateHigh,
    onPrimaryContainer = Chalk,
    secondary = Ash,
    onSecondary = Obsidian,
    secondaryContainer = SlateHigh,
    onSecondaryContainer = Chalk,
    error = Rust,
    onError = Obsidian,
    background = Obsidian,
    onBackground = Chalk,
    surface = Slate,
    onSurface = Chalk,
    surfaceVariant = SlateHigh,
    onSurfaceVariant = Ash,
    outline = Color(0xFF3A3A44),
    outlineVariant = Color(0xFF26262E),
    scrim = Color(0xCC000000),
)

// ------------------------------------------------------------------------- typography

private val Display = FontFamily.Serif
private val Body = FontFamily.SansSerif

/** Evidence, addresses, durations, counts. Anything the user reads as a fact. */
val Evidence = FontFamily.Monospace

private val AegisTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.6).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    // The eyebrow: small, spaced, monospaced. Used to name a section, never to decorate.
    labelMedium = TextStyle(
        fontFamily = Evidence,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Evidence,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
)

private val AegisShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Whether the device has animations switched off. Honoured by the seal and the arcs. */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

@Composable
fun AegisTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val animationsEnabled = androidx.compose.runtime.remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }.getOrDefault(true)
    }

    CompositionLocalProvider(LocalAnimationsEnabled provides animationsEnabled) {
        MaterialTheme(
            colorScheme = AegisColors,
            typography = AegisTypography,
            shapes = AegisShapes,
            content = content,
        )
    }
}
