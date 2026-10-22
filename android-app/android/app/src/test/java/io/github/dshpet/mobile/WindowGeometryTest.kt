package io.github.dshpet.mobile

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fixed stage must not move the pet: wherever the body anchor is, every crop
 * lands on screen exactly where its own one-off window would have put it - but
 * without the window ever changing size or position between clips.
 */
class WindowGeometryTest {
    private val body = SourceBox(212.0, 60.0, 428.0, 330.0)
    private val idle = SourceBox(194.0, 58.0, 446.0, 332.0)
    private val wideClick = SourceBox(114.0, 62.0, 526.0, 332.0)
    private val tallClick = SourceBox(181.0, 17.0, 459.0, 333.0)
    private val move = SourceBox(119.0, 56.0, 521.0, 337.0)
    private val stage = SourceBox(114.0, 17.0, 526.0, 337.0)
    private val scale = 1.2444

    @Test
    fun unionCoversEveryClipCrop() {
        val clips = listOf(idle, wideClick, tallClick, move).map {
            ClipSpec("clip", "idle", 24.0, 241, crop = it)
        }
        val computed = WindowGeometry.union(clips)
        assertEquals(stage, computed)
        for (crop in listOf(idle, wideClick, tallClick, move)) {
            assertTrue(computed.left <= crop.left && computed.top <= crop.top)
            assertTrue(computed.right >= crop.right && computed.bottom >= crop.bottom)
        }
    }

    /**
     * The heart of the fixed stage: whatever is playing, the window and the
     * crop's position inside it combine to the same on-screen rect - so a clip
     * switch can never look like the pet jumped.
     */
    @Test
    fun everyCropLandsExactlyWhereItsOwnWindowPutIt() {
        val bodyX = 300.0
        val bodyY = 400.0
        val safeLeft = 0
        val safeTop = 100
        val placement = WindowGeometry.placement(bodyX, bodyY, body, stage, scale, safeLeft, safeTop)
        for (crop in listOf(idle, wideClick, tallClick, move)) {
            val offset = WindowGeometry.contentOffset(crop, stage, scale)
            val legacyLeft = (safeLeft + (bodyX + crop.left - body.left) * scale).toInt()
            val legacyTop = (safeTop + (bodyY + crop.top - body.top) * scale).toInt()
            assertTrue("clip ${crop.left} moved horizontally",
                abs((placement.x + offset.x) - legacyLeft) <= 1.5)
            assertTrue("clip ${crop.top} moved vertically",
                abs((placement.y + offset.y) - legacyTop) <= 1.5)
        }
    }

    @Test
    fun theStageKeepsTheWindowConstantWhateverPlays() {
        val first = WindowGeometry.placement(300.0, 400.0, body, stage, scale, 0, 100)
        val second = WindowGeometry.placement(300.0, 400.0, body, stage, scale, 0, 100)
        assertEquals(first, second)
    }

    @Test
    fun theWidestPulseFitsWithoutChangingTheBodyAnchor() {
        val offset = WindowGeometry.contentOffset(stage, stage, scale)
        val placement = WindowGeometry.placement(300.0, 400.0, body, stage, scale, 0, 0)
        val pulse = Squash.geometry(offset.x, offset.y,
            (stage.width * scale).toFloat(), (stage.height * scale).toFloat(), .5)
        assertTrue("left=${pulse[0]}", pulse[0] >= 0)
        assertTrue("right=${pulse[0] + pulse[2]} window=${placement.width}",
            pulse[0] + pulse[2] <= placement.width)
        assertEquals(0f, offset.y, 0f)
    }

    @Test
    fun windowNeverCollapsesToAnEmptySurface() {
        val tiny = SourceBox(0.0, 0.0, 0.4, 0.4)
        val placement = WindowGeometry.placement(0.0, 0.0, body, tiny, .1, 0, 0)
        assertTrue(placement.width >= 1 && placement.height >= 1)
    }

    @Test
    fun effectsClipsGetTheirOwnStageNotEveryoneElses() {
        fun clip(id: String, fx: Boolean, crop: SourceBox) =
            ClipSpec(id, "idle", 24.0, 24, crop = crop, fx = fx)
        val plain = clip("plain", false, SourceBox(150.0, 20.0, 500.0, 340.0))
        val effect = clip("effect", true, SourceBox(6.0, 0.0, 634.0, 360.0))
        val catalog = AssetCatalog(640, 360, body, WindowGeometry.union(listOf(plain, effect)),
            listOf(plain, effect), emptyList())
        assertTrue("stage ${catalog.stageBox} must not cover the effect",
            catalog.stageBox.right < effect.crop.right)
        assertEquals(catalog.unionCrop, catalog.effectBox)
    }
}
