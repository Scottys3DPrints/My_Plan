package com.aegis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A deliberately quiet palette.
 *
 * This app spends most of its visible life telling someone they cannot have the thing
 * they just reached for. Loud reds and warning iconography would make every block feel
 * like an accusation, and an accusing tool gets uninstalled. Slate blue and warm paper;
 * the refusals carry their weight in the words instead.
 */
private val Ink = Color(0xFF1C1A17)
private val Paper = Color(0xFFF8F6F3)
private val Slate = Color(0xFF1F3A5F)
private val SlateLight = Color(0xFF9DB8D8)
private val Clay = Color(0xFF8C5A3C)
private val NightPaper = Color(0xFF14120F)
private val NightInk = Color(0xFFF3EFE9)

private val LightColors = lightColorScheme(
    primary = Slate,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3F3),
    onPrimaryContainer = Color(0xFF0C1B2E),
    secondary = Clay,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFFFDFA),
    onSurface = Ink,
    surfaceVariant = Color(0xFFEAE5DE),
    onSurfaceVariant = Color(0xFF544F48),
    outline = Color(0xFF8A837A),
)

private val DarkColors = darkColorScheme(
    primary = SlateLight,
    onPrimary = Color(0xFF0C1B2E),
    primaryContainer = Color(0xFF2B4363),
    onPrimaryContainer = Color(0xFFD6E3F3),
    secondary = Color(0xFFD9A98A),
    onSecondary = Color(0xFF3A2415),
    background = NightPaper,
    onBackground = NightInk,
    surface = Color(0xFF1D1A16),
    onSurface = NightInk,
    surfaceVariant = Color(0xFF33302B),
    onSurfaceVariant = Color(0xFFC9C2B8),
    outline = Color(0xFF938C82),
)

@Composable
fun AegisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
