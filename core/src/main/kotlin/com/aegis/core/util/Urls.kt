package com.aegis.core.util

/**
 * Tiny URL helpers.
 *
 * Hand-rolled rather than `java.net.URI` because the strings that reach this code are
 * frequently not valid URIs — a half-typed address bar, a `intent://` link from another
 * app, a DNS question name with no scheme at all — and throwing on those would mean a
 * filter that fails open exactly where it is being probed.
 */
object Urls {

    /** Lowercased hostname, without `www.`, port, credentials, path or query. */
    fun host(url: String): String {
        if (url.isBlank()) return ""
        val trimmed = url.trim()
        val withoutScheme = if (trimmed.contains("://")) trimmed.substringAfter("://") else trimmed
        val authority = withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val hostPart = authority.substringAfterLast('@').substringBefore(':')
        return hostPart.lowercase().removePrefix("www.").removeSuffix(".")
    }

    /** Hostname split into the pieces a lexicon can safely match against. */
    fun hostLabels(host: String): List<String> =
        host.split('.', '-').filter { it.isNotEmpty() }

    fun pathAndQuery(url: String): String {
        if (url.isBlank()) return ""
        val trimmed = url.trim()
        val withoutScheme = if (trimmed.contains("://")) trimmed.substringAfter("://") else trimmed
        val slash = withoutScheme.indexOf('/')
        return if (slash >= 0) withoutScheme.substring(slash) else ""
    }

    /** `news.bbc.co.uk` → `[news.bbc.co.uk, bbc.co.uk, co.uk, uk]`, most specific first. */
    fun hostSuffixes(host: String): List<String> {
        if (host.isEmpty()) return emptyList()
        val parts = host.split('.').filter { it.isNotEmpty() }
        return parts.indices.map { index -> parts.subList(index, parts.size).joinToString(".") }
    }

    fun isHttpUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /** Turn whatever the user typed into something loadable, or a search if it isn't an address. */
    fun normaliseUserInput(raw: String, searchTemplate: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (isHttpUrl(trimmed)) return trimmed
        val looksLikeHost = !trimmed.contains(' ') &&
            trimmed.contains('.') &&
            !trimmed.startsWith(".") &&
            !trimmed.endsWith(".")
        return if (looksLikeHost) "https://$trimmed" else searchTemplate.replace("%s", encode(trimmed))
    }

    private fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val char = byte.toInt().toChar()
            when {
                char.isLetterOrDigit() && char.code < 128 -> append(char)
                char == '-' || char == '_' || char == '.' || char == '~' -> append(char)
                char == ' ' -> append('+')
                else -> append('%').append(((byte.toInt() and 0xFF).toString(16)).padStart(2, '0').uppercase())
            }
        }
    }
}
