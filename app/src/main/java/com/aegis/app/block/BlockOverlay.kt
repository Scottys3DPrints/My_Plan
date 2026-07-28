package com.aegis.app.block

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.core.rules.Decision

/**
 * The block screen, drawn by the accessibility service itself.
 *
 * ## Why this is not an Activity
 *
 * It was, and it did not work. The service detected the page correctly, logged the block,
 * and then called `startActivity` — which Android 10 and later silently refuse, because an
 * app with no visible window is not allowed to launch an activity into the foreground. The
 * user saw the page load normally while the transparency log filled up with blocks that
 * had never been shown to them. Nothing in the API reports this; the launch simply does
 * not happen.
 *
 * `TYPE_ACCESSIBILITY_OVERLAY` is the mechanism intended for exactly this. An accessibility
 * service may add a window of that type through [WindowManager] with **no permission at
 * all** — not even "display over other apps" — and it is not subject to the background
 * activity restrictions, because it is not an activity.
 *
 * ## Why plain Views rather than Compose
 *
 * A Compose surface in a `WindowManager` overlay needs lifecycle, saved-state and
 * view-tree owners wired by hand, and gets them wrong in ways that crash on some devices.
 * This is the one screen that absolutely must appear, over an app that is actively trying
 * to show something else, so it is built from the simplest components that can possibly
 * work and styled to match the rest of the app by hand.
 */
class BlockOverlay(private val service: AccessibilityService) {

    /**
     * The context the overlay window is added through.
     *
     * Not the service itself. From Android 11 a window may only be added through a context
     * that has a window token, and a plain service context has none — the request is
     * rejected with "token null is not valid". That is a `BadTokenException` thrown inside
     * `addView`, which the previous version caught and reported only as "overlay refused",
     * so the block screen silently failed to appear on every modern phone and said nothing
     * about why.
     */
    private val overlayContext: Context = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.createWindowContext(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                null,
            )
        } else {
            service
        }
    } catch (error: Exception) {
        service
    }

    private val windowManager =
        overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** Why the last attempt failed, for the diagnostics panel. Empty when it succeeded. */
    var lastError: String = ""
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var countdown: Runnable? = null

    val isShowing: Boolean get() = root != null

    /**
     * @return true if the overlay was actually attached. False means the caller should
     *         fall back, rather than assume the user was told anything.
     */
    fun show(
        decision: Decision,
        target: String,
        onClose: () -> Unit,
        grace: GraceActions?,
    ): Boolean {
        hide()

        return try {
            val view = buildView(decision, target, onClose, grace)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Focusable, so the buttons work and the app underneath cannot be typed
                // into; opaque, so nothing shows through.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE,
            ).apply { gravity = Gravity.TOP or Gravity.START }

            windowManager.addView(view, params)
            root = view
            lastError = ""
            true
        } catch (error: Throwable) {
            // Throwable, not Exception: a bad window token arrives as a RuntimeException
            // subclass but a missing class or method on an unusual build does not, and
            // failing to show the block screen must never take the guard down with it.
            root = null
            lastError = error.javaClass.simpleName + ": " + (error.message ?: "no detail")
            false
        }
    }

    fun hide() {
        countdown?.let { handler.removeCallbacks(it) }
        countdown = null
        val view = root ?: return
        root = null
        try {
            windowManager.removeView(view)
        } catch (error: Exception) {
            // Already gone. Nothing to undo.
        }
    }

    // ------------------------------------------------------------------ construction

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        service.resources.displayMetrics,
    ).toInt()

    private fun buildView(
        decision: Decision,
        target: String,
        onClose: () -> Unit,
        grace: GraceActions?,
    ): View {
        val scroll = ScrollView(overlayContext).apply {
            setBackgroundColor(OBSIDIAN)
            isFillViewport = true
        }

        val column = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(48), dp(28), dp(48))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        column.addView(
            StruckMark(overlayContext),
            LinearLayout.LayoutParams(dp(64), dp(64)),
        )

        column.addView(spacer(24))
        column.addView(
            text(
                decision.cause?.headline ?: "Blocked",
                sizeSp = 28f,
                color = CHALK,
                typeface = Typeface.SERIF,
            ),
        )

        if (target.isNotBlank()) {
            column.addView(spacer(10))
            column.addView(text(target, sizeSp = 13f, color = ASH, typeface = Typeface.MONOSPACE))
        }

        if (decision.explanation.isNotBlank()) {
            column.addView(spacer(18))
            column.addView(text(decision.explanation, sizeSp = 16f, color = CHALK))
        }

        if (decision.evidence.isNotEmpty()) {
            column.addView(spacer(22))
            column.addView(text("WHAT IT MATCHED", sizeSp = 11f, color = ASH, typeface = Typeface.MONOSPACE))
            column.addView(spacer(8))
            val ledger = text(
                decision.evidence.joinToString("\n"),
                sizeSp = 13f,
                color = ASH,
                typeface = Typeface.MONOSPACE,
            ).apply {
                setBackgroundColor(SLATE_HIGH)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            column.addView(ledger)
        }

        column.addView(spacer(32))

        if (grace != null) {
            val graceButton = button("Give me five more minutes", outlined = true)
            val note = text(
                "${grace.tapsRemaining} left today, and a minute of waiting first.",
                sizeSp = 13f,
                color = ASH,
            )
            graceButton.setOnClickListener {
                graceButton.isEnabled = false
                grace.onRequest()
                startCountdown(graceButton, note, grace)
            }
            column.addView(graceButton)
            column.addView(spacer(8))
            column.addView(note)
            column.addView(spacer(16))
        }

        val close = button("Close", outlined = false)
        close.setOnClickListener {
            hide()
            onClose()
        }
        column.addView(close)

        // No "this was wrong" here, deliberately. Retraining the classifier is a change to
        // the rules, and the moment you are staring at a block is the worst moment to make
        // one — that is the whole premise of the cooling-off period, and a button that
        // weakens the filter in one tap, right at the point of wanting, undoes it. The
        // block is already in the Record; correct it there, later, calmly.
        column.addView(spacer(10))
        column.addView(
            text(
                "Think this was wrong? It's in the Record — correct it in Aegis.",
                sizeSp = 12f,
                color = ASH,
            ),
        )

        scroll.addView(column)
        return scroll
    }

    /**
     * The mandatory pause, ticking in place.
     *
     * Deliberately not automatic at the end: the user has to come back and press again,
     * which is the moment most re-opens die.
     */
    private fun startCountdown(target: Button, note: TextView, grace: GraceActions) {
        var remaining = grace.pauseSeconds
        val tick = object : Runnable {
            override fun run() {
                remaining -= 1
                if (remaining > 0) {
                    target.text = "$remaining"
                    note.text = "Still want it in a minute? Come back and take it."
                    handler.postDelayed(this, 1_000)
                } else {
                    target.isEnabled = true
                    target.text = "Take five minutes"
                    note.text = ""
                    target.setOnClickListener {
                        hide()
                        grace.onClaim()
                    }
                }
            }
        }
        countdown = tick
        target.text = "$remaining"
        handler.postDelayed(tick, 1_000)
    }

    private fun spacer(height: Int): View = View(overlayContext).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(height),
        )
    }

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        typeface: Typeface = Typeface.SANS_SERIF,
    ): TextView = TextView(overlayContext).apply {
        this.text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTypeface(typeface)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun button(label: String, outlined: Boolean): Button = Button(overlayContext).apply {
        text = label
        isAllCaps = false
        setTextColor(if (outlined) ASH else OBSIDIAN)
        setBackgroundColor(if (outlined) SLATE_HIGH else BRASS)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /** A circle struck through — the same mark the Compose block screen draws. */
    private class StruckMark(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RUST
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            val size = minOf(width, height).toFloat()
            if (size <= 0f) return
            val stroke = size * 0.075f
            paint.strokeWidth = stroke
            canvas.drawCircle(width / 2f, height / 2f, size / 2f - stroke, paint)
            val inset = size * 0.27f
            canvas.drawLine(inset, height - inset, width - inset, inset, paint)
        }
    }

    /** What the overlay may do about a grace tap, supplied by the service. */
    data class GraceActions(
        val tapsRemaining: Int,
        val pauseSeconds: Int,
        val onRequest: () -> Unit,
        val onClaim: () -> Unit,
    )

    private companion object {
        val OBSIDIAN = Color.parseColor("#0E0E12")
        val SLATE_HIGH = Color.parseColor("#24242E")
        val CHALK = Color.parseColor("#EFEBE3")
        val ASH = Color.parseColor("#8B8880")
        val BRASS = Color.parseColor("#C89B4A")
        val RUST = Color.parseColor("#C2604A")
    }
}
