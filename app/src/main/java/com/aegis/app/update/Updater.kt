package com.aegis.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an update and hands it to Android's installer.
 *
 * **This cannot install silently, and that is correct.** Only system apps and enterprise
 * device owners may install packages without confirmation; everything else must show
 * Android's own install dialog. So "automatic" here means the app notices, fetches, and
 * gets you to a single Update button — not that software changes underneath you without
 * your knowledge. For a tool whose entire value rests on doing exactly what the user
 * asked, silent self-replacement would be the wrong thing to want even if it were
 * possible.
 *
 * The file is written to the app's own cache directory and shared through a
 * [FileProvider], so it never touches shared storage where another app could swap it
 * between download and install.
 */
object Updater {

    private const val TAG = "AegisUpdate"
    private const val TIMEOUT_MILLIS = 30_000
    private const val MAX_BYTES = 200L * 1024 * 1024

    /** Where downloads land. Cleared before each attempt so a part-file cannot linger. */
    private fun downloadDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    fun download(context: Context, update: AvailableUpdate, onProgress: (Int) -> Unit = {}): File? {
        val directory = downloadDir(context)
        directory.listFiles()?.forEach { it.delete() }
        val target = File(directory, "aegis-${update.versionName}.apk")

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Aegis")
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "download refused: ${connection.responseCode}")
                return null
            }

            val expected = connection.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
            var written = 0L

            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > MAX_BYTES) {
                            Log.w(TAG, "download exceeded the size ceiling; abandoning")
                            target.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                        if (expected > 0) onProgress(((written * 100) / expected).toInt().coerceIn(0, 100))
                    }
                }
            }

            // A truncated APK produces a baffling "problem parsing the package" dialog
            // rather than an obvious failure, so catch it here instead.
            if (expected > 0 && written != expected) {
                Log.w(TAG, "download truncated: $written of $expected")
                target.delete()
                return null
            }

            target
        } catch (error: Exception) {
            Log.w(TAG, "download failed", error)
            target.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Show Android's install dialog for [apk].
     *
     * If the user has not granted Aegis permission to install packages, Android sends them
     * to the settings screen for it instead; there is no way to install without that.
     */
    fun install(context: Context, apk: File) {
        if (!canRequestInstall(context)) {
            openInstallPermissionSettings(context)
            return
        }

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (error: Exception) {
            Log.w(TAG, "no installer available", error)
        }
    }

    fun canRequestInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun openInstallPermissionSettings(context: Context) {
        try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (error: Exception) {
            Log.w(TAG, "could not open install-permission settings", error)
        }
    }
}
