package io.github.dshpet.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bubble walk-through must show the whole answer: no 90-character cut, no
 * dropped sentence, and no piece so long that it covers the pet.
 */
class SpeechLinesTest {
    @Test
    fun aShortLineStaysOneBubble() {
        assertEquals(listOf("今天也在努力呢。"), SpeechLines.split("今天也在努力呢。"))
    }

    @Test
    fun anEmptyAnswerHasNoBubbles() {
        assertTrue(SpeechLines.split("").isEmpty())
        assertTrue(SpeechLines.split("   \n ").isEmpty())
    }

    @Test
    fun shortSentencesAreGluedWhileTheyStillFit() {
        val text = "你好呀。今天过得开心吗？我很想你。"
        val pieces = SpeechLines.split(text)
        assertEquals(listOf(text), pieces)
    }

    @Test
    fun everyPieceFitsTheBubble() {
        val text = "第一句比较长一点，用来凑够字数看看会不会分组。" +
            "第二句也不短，里面有逗号、顿号，还有一个问号？" +
            "第三句是最后一句，说完就结束了。"
        for (piece in SpeechLines.split(text)) {
            assertTrue("piece too long: ${piece.length} / $piece",
                piece.length <= SpeechLines.MAX_CHARS)
        }
    }

    @Test
    fun nothingIsLost() {
        val text = "深深今天看到窗外的云很像棉花糖，就多看了两眼。" +
            "你说，如果云真的能吃，会是什么味道的呢？" +
            "我猜是甜的，但是吃多了大概会牙疼。"
        val pieces = SpeechLines.split(text)
        assertTrue("expected several bubbles, got $pieces", pieces.size >= 2)
        assertEquals(text.filterNot { it.isWhitespace() },
            pieces.joinToString("").filterNot { it.isWhitespace() })
    }

    @Test
    fun anOverlongSentenceBreaksAtAComma() {
        val clause = "今天下午三点要开会记得带上电脑和资料，"
        val text = clause.repeat(5) + "别忘了。"
        val pieces = SpeechLines.split(text)
        assertTrue("expected several bubbles, got ${pieces.size}", pieces.size >= 3)
        for (piece in pieces) {
            assertTrue("piece too long: ${piece.length}", piece.length <= SpeechLines.MAX_CHARS)
        }
    }

    @Test
    fun aSentenceWithoutAnyCommaIsCutRatherThanDropped() {
        val text = "嗯".repeat(120)
        val pieces = SpeechLines.split(text)
        assertTrue("expected several bubbles, got ${pieces.size}", pieces.size >= 3)
        assertEquals(120, pieces.sumOf { it.length })
        assertTrue(pieces.all { it.length <= SpeechLines.MAX_CHARS })
    }
}
