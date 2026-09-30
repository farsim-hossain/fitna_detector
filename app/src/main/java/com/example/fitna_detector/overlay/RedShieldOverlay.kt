package com.example.fitna_detector.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Manages the high-opacity red screen overlay when prohibited content or music is detected.
 *
 * Uses WindowManager with FLAG_NOT_TOUCHABLE so that touch and swipe events
 * pass directly to the underlying application (e.g. YouTube, TikTok, Browser),
 * allowing the user to change the content or scroll away from the prohibited content.
 *
 * Stays visible for at least 30 seconds once triggered.
 */
class RedShieldOverlay(
    private val context: Context,
    private val isAccessibilityMode: Boolean = false
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: FrameLayout? = null
    private var titleTextView: TextView? = null
    private var messageTextView: TextView? = null
    private var instructionTextView: TextView? = null
    private var reasonTextView: TextView? = null
    private var isVisible = false

    fun show(reason: String, opacity: Float = 0.93f) {
        mainHandler.post {
            ensureOverlayCreated()

            val alpha = (opacity.coerceIn(0.5f, 0.98f) * 255).toInt()
            // Deep crimson red (R: 195, G: 15, B: 15)
            overlayView?.setBackgroundColor(Color.argb(alpha, 195, 15, 15))

            reasonTextView?.text = if (reason.isNotEmpty()) "Detected: $reason" else ""

            if (!isVisible) {
                overlayView?.visibility = View.VISIBLE
                isVisible = true
            }
        }
    }

    fun hide() {
        mainHandler.post {
            if (isVisible) {
                overlayView?.visibility = View.GONE
                isVisible = false
            }
        }
    }

    fun isShowing(): Boolean = isVisible

    fun destroy() {
        mainHandler.post {
            overlayView?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (ignored: Exception) {}
            }
            overlayView = null
            titleTextView = null
            messageTextView = null
            instructionTextView = null
            reasonTextView = null
            isVisible = false
        }
    }

    private fun ensureOverlayCreated() {
        if (overlayView != null) return

        val windowType = if (isAccessibilityMode) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.FILL
        }

        val root = FrameLayout(context).apply {
            visibility = View.GONE
        }

        // Center card with notification banner
        val contentCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 56, 56, 56)

            // Semi-translucent dark background card for readability
            val cardBg = Color.argb(200, 15, 15, 15)
            setBackgroundColor(cardBg)
        }

        val cardLayoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            marginStart = 48
            marginEnd = 48
        }

        // Exact requested Title: "Fitna !"
        val titleView = TextView(context).apply {
            text = "Fitna !"
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(10f, 0f, 2f, Color.RED)
        }

        // Exact requested Message: "change the content. Watch something that Islam approves"
        val messageView = TextView(context).apply {
            text = "change the content. Watch something that Islam approves"
            textSize = 17f
            setTextColor(Color.argb(255, 255, 235, 235))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 16)
            setLineSpacing(4f, 1.2f)
        }

        // Guidance notice: immediately unblocks when content is stopped or swiped away
        val instructionView = TextView(context).apply {
            text = "Stop playback or swipe away to unblock screen"
            textSize = 14f
            setTextColor(Color.argb(240, 255, 204, 128)) // Warm amber tone
            setTypeface(typeface, Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 12)
        }

        // Detected trigger reason
        val reasonView = TextView(context).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.argb(180, 220, 220, 220))
            setTypeface(typeface, Typeface.ITALIC)
            gravity = Gravity.CENTER
        }

        contentCard.addView(titleView)
        contentCard.addView(messageView)
        contentCard.addView(instructionView)
        contentCard.addView(reasonView)

        root.addView(contentCard, cardLayoutParams)

        try {
            windowManager.addView(root, layoutParams)
            overlayView = root
            titleTextView = titleView
            messageTextView = messageView
            instructionTextView = instructionView
            reasonTextView = reasonView
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
