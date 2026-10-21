package io.github.dshpet.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The floating chat prompt: a small input bubble parked next to the pet, exactly
 * like the desktop plugin's chat dialog. Whatever is typed goes to the chat
 * module, and the answer comes back as the pet's own speech bubble - the pet
 * answers in place instead of a full screen taking over.
 */
class ChatPrompt(private val context: Context, private val windows: WindowManager) {
    private var panel: View? = null
    private var field: EditText? = null

    val isOpen get() = panel != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(x: Int, y: Int, safe: Rect, onSubmit: (String) -> Unit, onClose: () -> Unit) {
        close()
        val density = context.resources.displayMetrics.density
        val width = (WIDTH_DP * density).roundToInt()
        val root = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    close(); onClose(); return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (8 * density).roundToInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 18 * density
                setStroke(1, Color.rgb(213, 224, 226))
            }
            elevation = 8 * density
        }
        val edit = EditText(context).apply {
            hint = context.getString(R.string.chat_placeholder)
            setHintTextColor(Color.rgb(140, 150, 150))
            setTextColor(Color.rgb(29, 42, 47))
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, event ->
                val send = actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                if (send) { submit(root, onSubmit, onClose); true } else false
            }
        }
        val button = Button(context).apply {
            text = context.getString(R.string.chat_send)
            setOnClickListener { submit(root, onSubmit, onClose) }
        }
        root.addView(edit)
        root.addView(button)
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) { close(); onClose() }
            false
        }

        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            // A focused overlay window can host the soft keyboard.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            this.x = x.coerceIn(safe.left, max(safe.left, safe.right - width))
            this.y = y.coerceIn(safe.top, max(safe.top, safe.bottom - (56 * density).roundToInt()))
        }
        try {
            windows.addView(root, params)
        } catch (e: RuntimeException) {
            Toast.makeText(context, R.string.permission_required, Toast.LENGTH_LONG).show()
            onClose()
            return
        }
        panel = root
        field = edit
        edit.requestFocus()
        PetRuntime.bubbles.incrementAndGet()
    }

    private fun submit(panel: View, onSubmit: (String) -> Unit, onClose: () -> Unit) {
        val text = field?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(context, R.string.chat_empty, Toast.LENGTH_SHORT).show()
            return
        }
        field?.setText("")
        close()
        onClose()
        onSubmit(text)
    }

    fun close() {
        val view = panel ?: return
        panel = null
        field = null
        runCatching { windows.removeViewImmediate(view) }
    }

    private companion object {
        const val WIDTH_DP = 260
    }
}
