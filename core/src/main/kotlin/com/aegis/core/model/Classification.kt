package com.aegis.core.model

import kotlinx.serialization.Serializable

/**
 * Everything the classifier was given to look at.
 *
 * All of this is assembled on-device and never leaves it. [text] is page text as
 * extracted by the renderer we control (our browser), truncated by the caller.
 */
@Serializable
data class ContentInput(
    val url: String = "",
    val title: String = "",
    val text: String = "",
    /** `<meta name="keywords">`, OpenGraph tags, and similar declared hints. */
    val metaKeywords: List<String> = emptyList(),
    /**
     * Coarse per-image signals produced on-device by [com.aegis.core.classifier.ImageSignal].
     * A weak input on purpose — see the class docs for why.
     */
    val imageSignals: List<ImageSignal> = emptyList(),
    /** How this content was reached. Route matters: see [RouteContext]. */
    val route: RouteContext = RouteContext.DIRECT,
)

/**
 * A coarse, on-device signal about one image. Never a verdict on its own —
 * it only ever nudges a score that page text has already moved.
 */
@Serializable
data class ImageSignal(
    /** Stable identifier, usually the image URL, used to blur the right element later. */
    val ref: String,
    /** 0..1 fraction of sampled pixels falling in a broad skin-tone range. */
    val skinToneRatio: Float,
    /** 0..1 fraction of the viewport this image occupies. Big images matter more. */
    val prominence: Float,
    /** Alt text and filename, which are often more informative than the pixels. */
    val describedBy: String = "",
)

/**
 * How a request arrived. "No side doors" (§3.5) is enforced by treating the same
 * destination reached by a different route as its own blockable event.
 */
@Serializable
enum class RouteContext(val id: String, val label: String) {
    DIRECT("direct", "Aegis browser"),
    IN_APP_WEBVIEW("in_app_webview", "In-app browser"),
    LINK_PREVIEW("link_preview", "Link preview"),
    REDIRECT("redirect", "Redirect"),
    DNS("dns", "Network request"),
    APP("app", "Installed app"),
    ;

    companion object {
        fun fromId(id: String): RouteContext = entries.firstOrNull { it.id == id } ?: DIRECT
    }
}

/**
 * What the classifier concluded, with the evidence that got it there.
 *
 * The evidence is not decoration. §3.10 requires that every block can answer
 * "why was this blocked?", and that the answer is specific enough for the user to
 * tell a fair call from a misfire.
 */
@Serializable
data class Classification(
    val scores: Map<Category, Float> = emptyMap(),
    val evidence: Map<Category, List<String>> = emptyMap(),
    /** Milliseconds spent classifying. Surfaced in the log so slowness is visible. */
    val elapsedMillis: Long = 0L,
) {
    fun score(category: Category): Float = scores[category] ?: 0f

    fun evidenceFor(category: Category): List<String> = evidence[category].orEmpty()

    /** The category that scored highest, or null if nothing scored above zero. */
    val topCategory: Category?
        get() = scores.entries.filter { it.value > 0f }.maxByOrNull { it.value }?.key

    companion object {
        val EMPTY = Classification()
    }
}
