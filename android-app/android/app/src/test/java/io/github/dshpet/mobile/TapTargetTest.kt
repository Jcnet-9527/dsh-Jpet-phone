package io.github.dshpet.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Tap maths: transparent pixels must be identifiable from the touch point. */
class TapTargetTest {
    private val offset = ContentOffset(6f, 0f)
    private val width = 100f
    private val height = 80f

    @Test
    fun theCentreMapsToTheMiddleOfTheFrame() {
        val point = TapTarget.sourcePoint(56f, 40f, offset, width, height, false)
        assertNotNull(point)
        assertEquals(.5f, point!!.first, .01f)
        assertEquals(.5f, point.second, .01f)
    }

    @Test
    fun aMirroredFrameFlipsHorizontallyOnly() {
        val plain = TapTarget.sourcePoint(26f, 60f, offset, width, height, false)!!
        val mirrored = TapTarget.sourcePoint(26f, 60f, offset, width, height, true)!!
        assertEquals(1f - plain.first, mirrored.first, .01f)
        assertEquals(plain.second, mirrored.second, .01f)
    }

    @Test
    fun tapsOutsideTheFrameAreIgnored() {
        assertNull(TapTarget.sourcePoint(0f, 40f, offset, width, height, false))
        assertNull(TapTarget.sourcePoint(56f, 90f, offset, width, height, false))
        assertNull(TapTarget.sourcePoint(-30f, 40f, offset, width, height, false))
    }

    @Test
    fun anEmptyFrameNeverAnswers() {
        assertNull(TapTarget.sourcePoint(6f, 0f, offset, 0f, 0f, false))
    }
}
