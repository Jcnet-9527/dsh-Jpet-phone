package io.github.dshpet.mobile

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import kotlin.math.hypot

/**
 * One pet's transparent surface. The window tracks the clip that is playing -
 * see [WindowGeometry] - so this view is exactly as big as the pixels it draws
 * plus the Q 弹 pulse's slack, and a tap only counts where the frame is opaque.
 */
@SuppressLint("ViewConstructor") // Constructed by the service, never inflated from XML.
class PetView(
    context: Context,
    val petId: Int,
    private val engine: PetEngine,
    private val listener: Listener,
) : View(context) {
    interface Listener {
        fun onChanged(petId: Int)

        fun onPressed(petId: Int)

        fun onReleased(petId: Int)

        fun onClicked(petId: Int)

        fun onMenu(petId: Int)
    }

    var renderScale = 1.0
    var lastGesture = "none"
        private set

    /** The crop the service must size the window for: the one being drawn now. */
    val displayedCrop: SourceBox get() = display.frame?.crop ?: union

    private var union = SourceBox(0.0, 0.0, 640.0, 360.0)
    private val display = DisplayState<Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sourceRect = Rect()
    private val destinationRect = RectF()
    private var squashProgress = INACTIVE
    private var squashAnimator: ValueAnimator? = null
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var originX = 0.0
    private var originY = 0.0
    private var moved = false
    private var menuOpened = false
    private var activePointer = -1
    /** False until a touch really landed on the character, not on empty pixels. */
    private var consuming = false
    private var releaseVelocityX = 0f
    private var releaseVelocityY = 0f
    private var lastMoveTime = 0L
    private val longPress = Runnable {
        if (!moved && activePointer >= 0) {
            menuOpened = true
            engine.cancelDrag()
            listener.onMenu(petId)
        }
    }

    private companion object {
        /** 0..1 while the Q 弹 pulse plays; anything below zero means "not squashing". */
        const val INACTIVE = -1.0
    }

    init {
        contentDescription = context.getString(R.string.app_name)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        isLongClickable = true
    }

    /** Fixed geometry for this character and size. */
    fun configure(union: SourceBox, scale: Double) {
        val changed = this.union != union || renderScale != scale
        this.union = union
        renderScale = scale
        if (changed && display.frame != null) invalidate()
    }

    /**
     * Shows a decoded frame. A null frame means the decoder is still behind: the
     * previous frame *and its crop* stay on screen together, so a clip switch can
     * never paint the old image with the new geometry - nor resize the window
     * for a frame it is not drawing yet.
     */
    fun present(frame: FrameLoader.DecodedFrame?, crop: SourceBox, mirrored: Boolean) {
        if (display.present(frame?.bitmap, frame?.revision ?: -1L, crop, mirrored)) {
            union = crop
            invalidate()
        }
    }

    /** Starts the desktop's 220 ms Q 弹 pulse: wider and shorter, bottom anchored. */
    fun squash() {
        cancelSquash()
        squashProgress = 0.0
        squashAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (Squash.DURATION_SECONDS * 1000).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                squashProgress = (it.animatedValue as Float).toDouble()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    squashProgress = INACTIVE
                    squashAnimator = null
                    invalidate()
                }
            })
            start()
        }
    }

    private fun cancelSquash() {
        squashAnimator?.removeAllListeners()
        squashAnimator?.removeAllUpdateListeners()
        squashAnimator?.cancel()
        squashAnimator = null
        squashProgress = INACTIVE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = display.frame ?: return
        val offset = WindowGeometry.contentOffset(current.crop, renderScale)
        val width = (current.crop.width * renderScale).toFloat()
        val height = (current.crop.height * renderScale).toFloat()
        if (squashProgress >= 0.0) {
            val squashed = Squash.geometry(offset.x, offset.y, width, height, squashProgress)
            destinationRect.set(squashed[0], squashed[1], squashed[0] + squashed[2],
                squashed[1] + squashed[3])
        } else {
            destinationRect.set(offset.x, offset.y, offset.x + width, offset.y + height)
        }
        sourceRect.set(current.crop.left.toInt(), current.crop.top.toInt(),
            current.crop.right.toInt(), current.crop.bottom.toInt())
        canvas.save()
        if (current.mirrored) {
            canvas.scale(-1f, 1f, destinationRect.centerX(), destinationRect.centerY())
        }
        canvas.drawBitmap(current.bitmap, sourceRect, destinationRect, paint)
        canvas.restore()
        PetRuntime.draws.incrementAndGet()
    }

    override fun performClick(): Boolean {
        super.performClick()
        engine.click()
        squash()
        listener.onClicked(petId)
        listener.onChanged(petId)
        return true
    }

    override fun performLongClick(): Boolean {
        listener.onMenu(petId)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Transparent pixels are not the pet: let the app below have them.
                if (!hitsPet(event.x, event.y)) return false
                consuming = true
                activePointer = event.getPointerId(0)
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                trackScreenMovement(event)
                downX = event.rawX; downY = event.rawY
                originX = engine.x; originY = engine.y
                moved = false; menuOpened = false
                releaseVelocityX = 0f; releaseVelocityY = 0f; lastMoveTime = event.eventTime
                engine.beginDrag()
                lastGesture = "down"
                listener.onPressed(petId)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                listener.onChanged(petId)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!consuming) return false
                if (activePointer < 0 || menuOpened) return true
                trackScreenMovement(event)
                tracker?.computeCurrentVelocity(1000)
                releaseVelocityX = tracker?.getXVelocity(activePointer) ?: 0f
                releaseVelocityY = tracker?.getYVelocity(activePointer) ?: 0f
                lastMoveTime = event.eventTime
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (hypot(dx, dy) > ViewConfiguration.get(context).scaledTouchSlop) {
                    moved = true
                    removeCallbacks(longPress)
                }
                if (moved) {
                    engine.dragTo(originX + dx / renderScale, originY + dy / renderScale)
                    listener.onChanged(petId)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!consuming) return false
                if (activePointer < 0) return true
                removeCallbacks(longPress)
                if (!menuOpened) {
                    if (moved) {
                        // ACTION_UP can clear VelocityTracker; use the last MOVE,
                        // but discard stale velocity after the finger has rested.
                        val age = event.eventTime - lastMoveTime
                        val fresh = age in 0L..150L
                        val vx = if (fresh) releaseVelocityX / renderScale else 0.0
                        val vy = if (fresh) releaseVelocityY / renderScale else 0.0
                        lastGesture = "release vx=$vx vy=$vy age_ms=$age"
                        engine.release(vx, vy)
                        listener.onReleased(petId)
                    } else performClick()
                }
                finishGesture()
                listener.onChanged(petId)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!consuming) return false
                lastGesture = "cancel action=${event.actionMasked}"
                engine.cancelDrag()
                finishGesture()
                listener.onReleased(petId)
                listener.onChanged(petId)
            }
        }
        return true
    }

    /**
     * True when the finger landed on an opaque pixel of the frame being drawn.
     * The window hugs the clip's alpha box, so most taps inside it are still
     * empty space; counting those made the pet react to taps nowhere near it and
     * swallowed touches meant for the app underneath.
     */
    private fun hitsPet(x: Float, y: Float): Boolean {
        val current = display.frame ?: return false
        val bitmap = current.bitmap
        val crop = current.crop
        val offset = WindowGeometry.contentOffset(crop, renderScale)
        val width = (crop.width * renderScale).toFloat()
        val height = (crop.height * renderScale).toFloat()
        val point = TapTarget.sourcePoint(x, y, offset, width, height, current.mirrored)
            ?: return false
        val pixelX = (crop.left + point.first * crop.width).toInt().coerceIn(0, bitmap.width - 1)
        val pixelY = (crop.top + point.second * crop.height).toInt().coerceIn(0, bitmap.height - 1)
        return Color.alpha(bitmap.getPixel(pixelX, pixelY)) >= TapTarget.MIN_ALPHA
    }

    private fun trackScreenMovement(event: MotionEvent) {
        // The overlay moves with the finger, so local coordinates hide its speed.
        val screenEvent = MotionEvent.obtain(event)
        screenEvent.offsetLocation(event.rawX - event.x, event.rawY - event.y)
        tracker?.addMovement(screenEvent)
        screenEvent.recycle()
    }

    private fun finishGesture() {
        removeCallbacks(longPress)
        tracker?.recycle(); tracker = null
        activePointer = -1
        consuming = false
    }

    override fun onDetachedFromWindow() {
        cancelSquash()
        finishGesture()
        display.clear()
        super.onDetachedFromWindow()
    }
}
