package io.github.dshpet.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The floating hold-to-talk button, parked next to the pet like the chat
 * prompt. Pressing it starts the microphone, releasing sends whatever was
 * said; an outside touch dismisses the whole thing.
 */
class VoiceButton(private val context: Context, private val windows: WindowManager) {
    private var button: TextView? = null

    val isOpen get() = button != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(x: Int, y: Int, safe: android.graphics.Rect,
             onPress: () -> Unit, onRelease: () -> Unit, onDismiss: () -> Unit) {
        close()
        val density = context.resources.displayMetrics.density
        val size = (56 * density).roundToInt()
        val idle = Color.rgb(0, 107, 92)
        val held = Color.rgb(0x99, 0x3C, 0x1D)
        val view = TextView(context).apply {
            text = context.getString(R.string.voice_talk)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(held)
                    setStroke((2 * density).roundToInt(), Color.argb(0x66, 0xFF, 0xFF, 0xFF))
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(idle)
                    setStroke((2 * density).roundToInt(), Color.argb(0x33, 0xFF, 0xFF, 0xFF))
                })
            }
            elevation = 8 * density
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { isPressed = true; onPress(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val heldDown = isPressed
                        isPressed = false
                        if (heldDown) onRelease()
                        true
                    }
                    MotionEvent.ACTION_OUTSIDE -> {
                        if (isPressed) { isPressed = false; onRelease() }
                        close(); onDismiss(); true
                    }
                    else -> false
                }
            }
        }
        val params = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            this.x = x.coerceIn(safe.left, max(safe.left, safe.right - size))
            this.y = y.coerceIn(safe.top, max(safe.top, safe.bottom - size))
        }
        try {
            windows.addView(view, params)
        } catch (e: RuntimeException) {
            onDismiss()
            return
        }
        button = view
        PetRuntime.bubbles.incrementAndGet()
    }

    fun close() {
        val view = button ?: return
        button = null
        runCatching { windows.removeViewImmediate(view) }
    }
}
