package io.github.kifranei.duetlyrics.core

enum class BackgroundStyle {
    /**
     * Background vocals become an extra sub-line (same slot as translations) in parentheses.
     * SPW does not advance karaoke progress on sub-lines.
     */
    SUBLINE,

    /** Background vocals become their own word-timed lyric line (default). */
    SEPARATE,

    /** Background vocals are dropped. */
    DROP,
}

/**
 * SPL has no voice/alignment field and SPW renders lyric text as-is, so duet voice markers
 * (`v1:`, `v2:`, …) are simply stripped; real left/right layout needs AMLL into SPW,
 * which is fed the TTML separately.
 */
data class SplOptions(
    val background: BackgroundStyle = BackgroundStyle.SEPARATE,
    val translations: Boolean = true,
    val romanization: Boolean = false,
    val metadata: Boolean = true,
)

/**
 * Writes Salt Player Lyrics (SPL, https://moriafly.com/standards/spl):
 * `[start]first<t2>second<t3>third[end]`, translations as following lines with the
 * same timestamp. Two different lyric lines may never share a timestamp in SPL (the
 * second would become a translation), so colliding starts are nudged by 1 ms.
 */
object SplWriter {
    private val TAG_LIKE = Regex("[<\\[](\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,6}))?[>\\]]")

    private data class Block(val line: Line, val background: Boolean)

    fun write(doc: LyricsDocument, options: SplOptions = SplOptions()): String {
        val out = mutableListOf<String>()
        if (options.metadata) {
            for (k in listOf("ti", "ar", "al", "au", "lr", "by")) {
                doc.metadata[k]?.takeIf { it.isNotBlank() }?.let { out += "[$k:${safe(it)}]" }
            }
        }

        val blocks = mutableListOf<Block>()
        for (l in doc.lines) {
            blocks += Block(l, false)
            if (options.background == BackgroundStyle.SEPARATE) {
                l.backgrounds.forEach { blocks += Block(it, true) }
            }
        }
        blocks.sortWith(compareBy<Block> { it.line.start }.thenBy { it.background })

        val occupied = HashSet<Long>()
        for (b in blocks) {
            var start = b.line.start
            val limit = b.line.knownEnd
            while (start in occupied && (limit == null || start + 1 < limit)) start++
            occupied += start

            out += encode(b.line, start, parenthesize = b.background)

            val subs = mutableListOf<String>()
            if (options.translations) subs += b.line.translations
            if (options.romanization) subs += b.line.romanizations
            if (!b.background && options.background == BackgroundStyle.SUBLINE) {
                b.line.backgrounds.forEach { bg ->
                    subs += paren(bg.text)
                    if (options.translations) bg.translations.forEach { subs += paren(it) }
                }
            }
            for (s in subs) {
                val t = safe(s)
                if (t.isNotBlank()) out += TimeFormat.lrc(start) + t
            }
        }
        return out.joinToString("\n")
    }

    private fun paren(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return t
        return if ((t.startsWith("(") || t.startsWith("（")) && (t.endsWith(")") || t.endsWith("）"))) t else "($t)"
    }

    private fun encode(line: Line, start: Long, parenthesize: Boolean): String {
        val sb = StringBuilder(TimeFormat.lrc(start))
        val end = line.knownEnd
        if (!line.hasWords) {
            var t = safe(line.text)
            if (parenthesize) t = paren(t)
            sb.append(t)
            if (end != null && end > start) sb.append(TimeFormat.lrc(end))
            return sb.toString()
        }
        val words = line.words.map { it.copy(text = safe(it.text)) }.toMutableList()
        if (parenthesize && words.isNotEmpty()) {
            val joined = words.joinToString("") { it.text }.trim()
            if (!(joined.startsWith("(") || joined.startsWith("（"))) {
                words[0] = words[0].copy(text = "(" + words[0].text)
                words[words.lastIndex] = words.last().copy(text = words.last().text + ")")
            }
        }
        var prev = start
        words.forEachIndexed { i, w ->
            val t = maxOf(w.start, prev)
            if (i > 0 || t > start) sb.append(TimeFormat.lrc(t, '<', '>'))
            sb.append(w.text)
            prev = t
        }
        val lineEnd = maxOf(end ?: words.last().end, words.last().end)
        if (lineEnd > prev) sb.append(TimeFormat.lrc(lineEnd))
        return sb.toString()
    }

    /** Never let lyric text be re-read as a timestamp or a new line. */
    private fun safe(s: String): String =
        TAG_LIKE.replace(s.replace('\r', ' ').replace('\n', ' ')) { m -> m.value.replace(':', '∶') }
}
