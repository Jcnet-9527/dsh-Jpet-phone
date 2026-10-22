package io.github.dshpet.mobile

/**
 * Cuts a finished answer into bubble-sized pieces.
 *
 * A bubble is a speech bubble, not a document. One long paragraph either runs
 * off the screen or has to be cut, and cutting it with an ellipsis silently
 * loses the end of the answer - which is exactly what "it says it will look it
 * up and then just stops" looked like. So: split at sentence ends, glue short
 * sentences back together while they still fit, break a single over-long
 * sentence at a comma, and never drop a character.
 *
 * The joined pieces are the original text, minus the whitespace at the seams.
 */
object SpeechLines {
    /** Longer than this and the bubble starts covering the pet. */
    const val MAX_CHARS = 52

    /** Shorter than this is too terse for its own bubble; glue it to a neighbour. */
    private const val MIN_CHARS = 16

    private const val ENDERS = "。！？!?；;…"
    private const val SOFT = "，,、：: "

    fun split(text: String): List<String> {
        val cleaned = text.replace("\r\n", "\n").trim()
        if (cleaned.isEmpty()) return emptyList()
        val pieces = ArrayList<String>()
        for (sentence in sentences(cleaned)) {
            val trimmed = sentence.trim()
            if (trimmed.isNotEmpty()) pieces += cut(trimmed)
        }
        return glue(pieces)
    }

    /** Sentences keep their own full stop; a line break ends one too. */
    private fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (c in text) {
            if (c == '\n') {
                if (current.isNotBlank()) out += current.toString()
                current.setLength(0)
                continue
            }
            current.append(c)
            if (ENDERS.indexOf(c) >= 0) {
                out += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }

    /** Breaks one over-long sentence at a comma when it can, mid-sentence when it must. */
    private fun cut(sentence: String): List<String> {
        if (sentence.length <= MAX_CHARS) return listOf(sentence)
        val out = ArrayList<String>()
        var rest = sentence
        while (rest.length > MAX_CHARS) {
            val window = rest.substring(0, MAX_CHARS)
            val comma = window.indexOfLast { it in SOFT }
            val split = if (comma >= MIN_CHARS) comma + 1 else MAX_CHARS
            out += rest.substring(0, split).trim()
            rest = rest.substring(split).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** Merges neighbours while the result still fits one bubble. */
    private fun glue(pieces: List<String>): List<String> {
        val out = ArrayList<String>()
        var current = ""
        for (piece in pieces) {
            if (piece.isEmpty()) continue
            current = when {
                current.isEmpty() -> piece
                current.length + piece.length <= MAX_CHARS -> current + piece
                else -> {
                    out += current
                    piece
                }
            }
        }
        if (current.isNotEmpty()) out += current
        return out
    }
}
