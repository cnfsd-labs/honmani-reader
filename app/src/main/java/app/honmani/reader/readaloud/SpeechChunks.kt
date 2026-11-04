package app.honmani.reader.readaloud

/**
 * Cuts [text] into pieces of at most [max] characters for a speech engine that refuses longer
 * input (`TextToSpeech.getMaxSpeechInputLength()`). The pieces joined are exactly [text]. A piece
 * ends after a sentence end, comma or space where one is in the second half of the room, else at
 * the limit; never between the halves of a surrogate pair.
 */
fun splitForSpeech(text: String, max: Int): List<String> {
    require(max >= 2) { "max must be at least 2" }
    if (text.length <= max) return if (text.isEmpty()) emptyList() else listOf(text)
    val pieces = ArrayList<String>()
    var start = 0
    while (text.length - start > max) {
        var end = start + max
        val half = start + max / 2
        var cut = -1
        for (i in end - 1 downTo half) {
            if (text[i] in BREAKS) {
                cut = i + 1
                break
            }
        }
        if (cut > 0) end = cut
        if (end < text.length && end > start && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        pieces.add(text.substring(start, end))
        start = end
    }
    if (start < text.length) pieces.add(text.substring(start))
    return pieces
}

private const val BREAKS = "。！？!?、，,；;：:　 \n"
