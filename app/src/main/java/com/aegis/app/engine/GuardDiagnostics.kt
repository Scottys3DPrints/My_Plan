package com.aegis.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the guard last saw, made visible.
 *
 * This exists because "Chrome isn't blocking" is unfalsifiable from the outside. The guard
 * could be switched off, or seeing no address, or seeing an address but no page text, or
 * seeing plenty of text and scoring it below the threshold. Those are four completely
 * different problems with four different fixes, and from the user's side of the screen
 * they are indistinguishable — which meant diagnosing it turned into guesswork about
 * Chrome's internals, twice.
 *
 * So the app now shows its working. Nothing here is stored or sent; it is the last
 * observation only, held in memory, replaced by the next one.
 */
class GuardDiagnostics {

    private val _state = MutableStateFlow(GuardObservation())
    val state: StateFlow<GuardObservation> = _state.asStateFlow()

    fun recordAddress(packageName: String, url: String) {
        _state.value = _state.value.copy(
            lastPackage = packageName,
            lastUrl = url,
            observations = _state.value.observations + 1,
        )
    }

    fun recordHarvest(characters: Int, topCategory: String, confidence: Float, blocked: Boolean) {
        _state.value = _state.value.copy(
            lastHarvestChars = characters,
            lastTopCategory = topCategory,
            lastConfidence = confidence,
            lastBlocked = blocked,
            harvests = _state.value.harvests + 1,
        )
    }

    fun recordNoAddress(packageName: String) {
        _state.value = _state.value.copy(
            lastPackage = packageName,
            lastUrl = "",
            observations = _state.value.observations + 1,
        )
    }
}

data class GuardObservation(
    val lastPackage: String = "",
    val lastUrl: String = "",
    /** How much page text the last walk produced. Zero here is the whole answer. */
    val lastHarvestChars: Int = 0,
    val lastTopCategory: String = "",
    val lastConfidence: Float = 0f,
    val lastBlocked: Boolean = false,
    val observations: Long = 0,
    val harvests: Long = 0,
) {
    /**
     * The one-line reading of the numbers above, in the terms someone debugging would use.
     */
    val summary: String
        get() = when {
            observations == 0L -> "The guard has not seen anything yet. Is it switched on?"
            lastUrl.isBlank() -> "Seeing $lastPackage, but no web address on screen."
            harvests == 0L -> "Reading the address, but no page text has come back yet."
            lastHarvestChars == 0 -> "Address seen, but the page exposed no readable text."
            lastBlocked -> "Last page was blocked."
            lastTopCategory.isBlank() -> "Read $lastHarvestChars characters. Nothing matched."
            else -> "Read $lastHarvestChars characters. Closest: $lastTopCategory " +
                "at ${(lastConfidence * 100).toInt()}%."
        }
}
