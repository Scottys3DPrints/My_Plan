package com.aegis.app.update

import android.content.Context
import android.util.Log
import com.aegis.app.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for a newer build on the repository's Releases page.
 *
 * ## Why this exists
 *
 * An app distributed outside a store has no update mechanism unless it grows one. Without
 * this, staying current means noticing there was a change, finding the run on GitHub,
 * downloading a zip, unzipping it and tapping the APK — which nobody does, so the version
 * on the phone quietly rots.
 *
 * ## What it does and does not send
 *
 * One unauthenticated GET to `api.github.com` for the latest release. It sends no
 * identifiers, no usage data, and nothing about what the user browses. It is the only
 * network request Aegis makes that the user did not directly ask for, which is why it is
 * stated plainly here, in the README, and beside a switch in Settings that turns it off.
 *
 * ## Why it is safe to install what this finds
 *
 * Android refuses to install an update signed by a different key than the installed app.
 * So even if this URL were hijacked, the replacement could not install over Aegis unless
 * it was signed with the same private key — which lives on the user's machine, not here.
 * That guarantee is also the reason this whole feature is useless without release signing
 * set up: builds signed with a throwaway debug key cannot update each other at all.
 */
object UpdateChecker {

    private const val TAG = "AegisUpdate"
    private const val TIMEOUT_MILLIS = 10_000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return the newer release, or null if the check failed or we are already current.
     */
    fun check(context: Context): AvailableUpdate? {
        val release = fetchLatestRelease() ?: return null
        val asset = release.apkAsset ?: return null

        val installedCode = installedVersionCode(context)
        if (asset.versionCode != null && asset.versionCode <= installedCode) return null
        if (asset.versionCode == null && release.tag.trimStart('v') == BuildConfig.VERSION_NAME) return null

        return AvailableUpdate(
            versionName = release.tag.trimStart('v'),
            versionCode = asset.versionCode,
            downloadUrl = asset.url,
            sizeBytes = asset.size,
            notes = release.notes,
        )
    }

    private fun installedVersionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    } catch (error: Exception) {
        0L
    }

    private fun fetchLatestRelease(): Release? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Aegis")
            }
            // 404 is the normal answer when no release has been published yet.
            if (connection.responseCode !in 200..299) return null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseRelease(json.parseToJsonElement(body).jsonObject)
        } catch (error: Exception) {
            Log.d(TAG, "update check failed", error)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseRelease(root: JsonObject): Release? {
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val notes = root["body"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        val assets = root["assets"] as? JsonArray ?: return null

        val apk = assets.asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
                if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
                // The debug variant is a fallback build, never the thing to update to.
                if (name.contains("-debug")) return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: return@mapNotNull null
                val size = asset["size"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull() ?: 0L
                ApkAsset(url = url, size = size, versionCode = versionCodeFrom(name))
            }
            .firstOrNull()

        return Release(tag = tag, notes = notes, apkAsset = apk)
    }

    /**
     * CI names release assets `aegis-<versionName>-<versionCode>.apk`, so the build number
     * travels with the file and the app can compare without a second request.
     */
    private fun versionCodeFrom(assetName: String): Long? =
        assetName.removeSuffix(".apk").substringAfterLast('-').toLongOrNull()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

    private data class Release(val tag: String, val notes: String, val apkAsset: ApkAsset?)
    private data class ApkAsset(val url: String, val size: Long, val versionCode: Long?)
}

data class AvailableUpdate(
    val versionName: String,
    val versionCode: Long?,
    val downloadUrl: String,
    val sizeBytes: Long,
    val notes: String,
) {
    val readableSize: String
        get() = if (sizeBytes <= 0) "" else "${(sizeBytes / (1024.0 * 1024.0)).let { "%.1f".format(it) }} MB"
}
