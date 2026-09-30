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
 * allowing the user to scroll or swipe away from the prohibited content.
 */
class RedShieldOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: FrameLayout? = null
    private var reasonTextView: TextView? = null
    private var isVisible = false

    fun show(reason: String, opacity: Float = 0.93f) {
        mainHandler.post {
            ensureOverlayCreated()

            val alpha = (opacity.coerceIn(0.5f, 0.98f) * 255).toInt()
            // Deep crimson red (R: 195, G: 15, B: 15)
            overlayView?.setBackgroundColor(Color.argb(alpha, 195, 15, 15))
            reasonTextView?.text = reason

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
            reasonTextView = null
            isVisible = false
        }
    }

    private fun ensureOverlayCreated() {
        if (overlayView != null) return

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.FILL
        }

        val root = FrameLayout(context).apply {
            // Initial state is hidden until show() is invoked
            visibility = View.GONE
        }

        // Center card with notification banner
        val contentCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)

            // Semi-translucent dark background for readability
            val cardBg = Color.argb(160, 20, 20, 20)
            setBackgroundColor(cardBg)
        }

        val cardLayoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            marginStart = 64
            marginEnd = 64
        }

        // Shield Icon / Title
        val titleView = TextView(context).apply {
            text = "🛡️ Fitna Shield Active"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }

        // Trigger reason text
        val reasonView = TextView(context).apply {
            text = "Prohibited visual content or music detected"
            textSize = 15f
            setTextColor(Color.argb(240, 255, 235, 235))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        // Instruction to swipe away
        val instructionView = TextView(context).apply {
            text = "Swipe away, scroll, or exit to unblock screen"
            textSize = 13f
            setTextColor(Color.argb(200, 220, 220, 220))
            setTypeface(typeface, Typeface.ITALIC)
            gravity = Gravity.CENTER
        }

        contentCard.addView(titleView)
        contentCard.addView(reasonView)
        contentCard.addView(instructionView)

        root.addView(contentCard, cardLayoutParams)

        try {
            windowManager.addView(root, layoutParams)
            overlayView = root
            reasonTextView = reasonView
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
