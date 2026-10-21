package io.github.dshpet.mobile

import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a pet window sits, and where the current frame is drawn inside it. */
data class WindowPlacement(val x: Int, val y: Int, val width: Int, val height: Int)

data class ContentOffset(val x: Float, val y: Float)

/**
 * Window size follows the clip that is currently on screen.
 *
 * Every clip carries its own alpha box, and those boxes differ wildly: the
 * "butterflies" idle spans almost the whole 640px canvas while a breathing idle
 * barely covers the character. Sizing the window after the union of all of them
 * left the pet inside a box three body widths wide, and that box - not the
 * character - is what the system keeps inside the screen and what answers every
 * tap. So the window is now resized to the playing clip's own crop: the same
 * pixels are drawn in exactly the same place, because the offset below is still
 * derived from the crop against the body anchor.
 */
object WindowGeometry {
    /**
     * The box covering every crop. Kept for asset bookkeeping; the window no
     * longer uses it, because it made every animation pay for the widest one.
     */
    fun union(clips: Collection<ClipSpec>): SourceBox {
        require(clips.isNotEmpty()) { "no clips" }
        var left = Double.MAX_VALUE
        var top = Double.MAX_VALUE
        var right = -Double.MAX_VALUE
        var bottom = -Double.MAX_VALUE
        for (clip in clips) {
            left = min(left, clip.crop.left)
            top = min(top, clip.crop.top)
            right = max(right, clip.crop.right)
            bottom = max(bottom, clip.crop.bottom)
        }
        return SourceBox(left, top, right, bottom)
    }

    /** Where the window for [crop] goes, given the body anchor in screen units. */
    fun placement(bodyX: Double, bodyY: Double, body: SourceBox, crop: SourceBox,
                  scale: Double, safeLeft: Int, safeTop: Int): WindowPlacement {
        val pad = pulsePadding(crop, scale)
        val width = max(1, ceil(crop.width * scale).toInt()) + 2 * pad
        val height = max(1, (crop.height * scale).roundToInt())
        val x = (safeLeft + (bodyX + crop.left - body.left) * scale).roundToInt() - pad
        val y = (safeTop + (bodyY + crop.top - body.top) * scale).roundToInt()
        return WindowPlacement(x, y, width, height)
    }

    /** The Q 弹 pulse widens the drawing, so every frame starts one pad inside. */
    fun contentOffset(crop: SourceBox, scale: Double): ContentOffset =
        ContentOffset(pulsePadding(crop, scale).toFloat(), 0f)

    private fun pulsePadding(crop: SourceBox, scale: Double) =
        ceil(crop.width * scale * Squash.WIDEN / 2).toInt().coerceAtLeast(0)
}

/**
 * Maps a tap inside the window onto the frame's own pixels, so a touch on a
 * transparent pixel can be ignored and passed through to whatever is below.
 *
 * Pure maths: no view, no bitmap, so this is unit-testable on the JVM.
 */
object TapTarget {
    /** Minimum alpha (0..255) that still counts as "the user hit the pet". */
    const val MIN_ALPHA = 24

    /** Normalised 0..1 source coordinates, or null when the tap is outside. */
    fun sourcePoint(touchX: Float, touchY: Float, offset: ContentOffset,
                    width: Float, height: Float, mirrored: Boolean): Pair<Float, Float>? {
        if (width <= 0f || height <= 0f) return null
        val rawX = (touchX - offset.x) / width
        val rawY = (touchY - offset.y) / height
        if (rawX !in 0f..1f || rawY !in 0f..1f) return null
        val u = if (mirrored) 1f - rawX else rawX
        return u to rawY
    }
}
