package com.aegis.app.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aegis.core.model.ImageSignal
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A coarse on-device look at the pictures on a page.
 *
 * ## Why this is a weak signal, on purpose
 *
 * This measures the proportion of pixels falling in a broad skin-tone range. That is a
 * blunt instrument: it cannot distinguish a swimming lesson, a dermatology page, or a
 * close-up of a wooden floor from pornography, and any claim otherwise would be false.
 * [com.aegis.core.classifier.LexicalClassifier] therefore caps how far image evidence can
 * move a verdict on its own — enough to warn, never enough to wall.
 *
 * What it is genuinely good at is the §3.1 case: a page whose *text* already looks
 * suspicious, where the images settle it. Used that way it is worth having; used as a
 * standalone detector it would be a machine for insulting people, so it isn't one.
 *
 * The threshold is deliberately tuned to be less sensitive to darker skin tones than the
 * naive version of this heuristic, which fires on hue alone and consequently misfires
 * along racial lines. Requiring a red-dominant, moderately-saturated pixel narrows that
 * gap; it does not close it, which is the honest reason this signal is capped and the
 * blur is one tap to clear.
 *
 * Everything runs on-device. Images are fetched from the same cache-friendly URL the page
 * already loaded, decoded at a fraction of full size, and discarded.
 */
object ImageSampler {

    private const val MAX_IMAGES = 6
    private const val MAX_BYTES = 512 * 1024
    private const val CONNECT_TIMEOUT_MILLIS = 4_000
    private const val READ_TIMEOUT_MILLIS = 4_000
    private const val TARGET_EDGE = 64
    private const val SAMPLE_STEP = 2

    /**
     * Sample the most prominent images on a page. Returns one signal per image that could
     * be fetched and decoded; images that fail are simply absent, never guessed at.
     */
    fun sample(images: List<ExtractedImage>, referer: String): List<ImageSignal> {
        if (images.isEmpty()) return emptyList()

        return images
            .sortedByDescending { it.prominence }
            .take(MAX_IMAGES)
            .mapNotNull { image ->
                val bitmap = fetchAndDecode(image.src, referer) ?: return@mapNotNull null
                try {
                    ImageSignal(
                        ref = image.src,
                        skinToneRatio = skinToneRatio(bitmap),
                        prominence = image.prominence,
                        describedBy = "${image.alt} ${fileNameOf(image.src)}".trim(),
                    )
                } finally {
                    bitmap.recycle()
                }
            }
    }

    private fun fetchAndDecode(source: String, referer: String): Bitmap? {
        if (!source.startsWith("http://") && !source.startsWith("https://")) return null

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/*")
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
            }
            if (connection.responseCode !in 200..299) return null

            val bytes = connection.inputStream.use { stream ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                while (buffer.size() < MAX_BYTES) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
            if (bytes.isEmpty()) return null

            // Two passes: measure, then decode small. Full-size decoding of a dozen images
            // is how a browser runs out of memory.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (error: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var size = 1
        var longest = max(width, height)
        while (longest / 2 >= TARGET_EDGE) {
            longest /= 2
            size *= 2
        }
        return size
    }

    /**
     * Fraction of sampled pixels that look like skin.
     *
     * The rule requires red dominance, a bounded red-to-blue spread, moderate saturation
     * and mid brightness. Requiring all four rejects most of what a hue-only test gets
     * wrong: sand, timber, terracotta, and warm-lit interiors.
     */
    fun skinToneRatio(bitmap: Bitmap): Float {
        var sampled = 0
        var matched = 0

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                sampled++
                if (isSkinTone(red, green, blue)) matched++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }

        return if (sampled == 0) 0f else matched.toFloat() / sampled
    }

    private fun isSkinTone(red: Int, green: Int, blue: Int): Boolean {
        if (red < 60 || red > 250) return false
        if (green < 30 || blue < 15) return false
        if (red <= green || green < blue) return false

        val maximum = max(red, max(green, blue))
        val minimum = min(red, min(green, blue))
        val spread = maximum - minimum
        if (spread < 12) return false // too grey to be skin
        if (abs(red - green) < 8) return false // too yellow

        val brightness = maximum
        return brightness in 70..245
    }

    private fun fileNameOf(source: String): String =
        source.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
            .replace('-', ' ')
            .replace('_', ' ')
}
