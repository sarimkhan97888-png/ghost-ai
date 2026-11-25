package com.ghost.ai

/**
 * Finds the wake words "Ghost" / "Jarvis" in a speech transcript.
 * Speech engines mishear these often, so common variants (Latin and Devanagari) are accepted.
 */
object WakeWord {

    private val LATIN = Regex(
        "\\b(ghost|ghosts|gohst|gost|goast|jarvis|jarvish|jarviz|jarvice|jervis|garvis|jaarvis|jar vis|jar viz)\\b",
        RegexOption.IGNORE_CASE
    )

    private val DEVANAGARI = listOf(
        "घोस्ट", "गोस्ट", "घोश्ट", "गॉस्ट",
        "जार्विस", "जारविस", "जार्विश", "जार्वीस", "जार्विज", "जारवीस"
    )

    private val TRIM_CHARS = charArrayOf(' ', ',', '.', '!', '?', '-', ':', ';', '।', ' ')

    /**
     * @return null when no wake word is present, otherwise the text spoken AFTER the wake word
     * (possibly empty), for example "jarvis open whatsapp" gives "open whatsapp".
     */
    fun match(transcript: String?): String? {
        if (transcript.isNullOrBlank()) return null
        val text = transcript.trim()

        var end = -1
        LATIN.find(text)?.let { end = it.range.last + 1 }
        for (word in DEVANAGARI) {
            val i = text.indexOf(word)
            if (i >= 0) {
                val candidateEnd = i + word.length
                if (end < 0 || candidateEnd < end) end = candidateEnd
            }
        }
        if (end < 0) return null

        return text.substring(end).trim(*TRIM_CHARS)
    }
}
