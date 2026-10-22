package io.github.dshpet.mobile

import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a pet window sits, and where the current frame is drawn inside it. */
data class WindowPlacement(val x: Int, val y: Int, val width: Int, val height: Int)

data class ContentOffset(val x: Float, val y: Float)

/**
 * Every pet window owns a *stage*: one fixed source box, chosen before anything
 * plays, that decides the window's size and where its top-left corner sits.
 *
 * It used to be the box of the clip being played. That made the window dance
 * with the animation: a switch resized and moved the window in the same call, so
 * for one frame the window manager had already applied the new geometry while
 * the view was still painting the previous frame - the character jumped by as
 * much as the two crops differ (up to 85 dp) and a drag started with a visible
 * hop. With a constant stage the numbers never change, so the window and its
 * contents can never disagree, whatever is playing.
 *
 * The stage still has to be big enough for what it shows: the stage box covers
 * every clip in the random pools, and the few extra-wide "effects" animations
 * get their own larger window - see [AssetCatalog.effectBox] - instead of
 * stretching the stage for everyone.
 */
object WindowGeometry {
    /**
     * The box covering every crop in [clips]. Used for the effects stage, and
     * for asset bookkeeping; the main stage is built from the trimmed pool.
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

    /**
     * Where the window goes, given the body anchor in screen units. The result
     * depends only on [stage], so it is constant while the stage is.
     */
    fun placement(bodyX: Double, bodyY: Double, body: SourceBox, stage: SourceBox,
                  scale: Double, safeLeft: Int, safeTop: Int): WindowPlacement {
        val pad = pulsePadding(stage, scale)
        val width = max(1, ceil(stage.width * scale).toInt()) + 2 * pad
        val height = max(1, (stage.height * scale).roundToInt())
        val x = (safeLeft + (bodyX + stage.left - body.left) * scale).roundToInt() - pad
        val y = (safeTop + (bodyY + stage.top - body.top) * scale).roundToInt()
        return WindowPlacement(x, y, width, height)
    }

    /** Where [crop] is painted inside a [stage] window. */
    fun contentOffset(crop: SourceBox, stage: SourceBox, scale: Double): ContentOffset {
        val pad = pulsePadding(stage, scale)
        return ContentOffset(((crop.left - stage.left) * scale + pad).toFloat(),
            ((crop.top - stage.top) * scale).toFloat())
    }

    /** The Q 弹 pulse widens the drawing, so every frame starts one pad inside. */
    private fun pulsePadding(stage: SourceBox, scale: Double) =
        ceil(stage.width * scale * Squash.WIDEN / 2).toInt().coerceAtLeast(0)
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

    /** Radius (dp) of the ring also probed, so a fingertip brushing the outline still grabs it. */
    const val SLOP_DP = 10f

    /** The tap itself plus eight points around it: a fingertip, not a laser. */
    fun probes(touchX: Float, touchY: Float, slop: Float): List<Pair<Float, Float>> {
        val points = ArrayList<Pair<Float, Float>>(9)
        points += touchX to touchY
        for (dx in -1..1) for (dy in -1..1) {
            if (dx != 0 || dy != 0) points += (touchX + dx * slop) to (touchY + dy * slop)
        }
        return points
    }

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
