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
/**
 * One lyric line as written to SPL: its (possibly nudged) start time, the main text exactly as
 * written (without timestamps) and the duet alignment it should get (null = leave SPW's own).
 */
data class SplLine(val start: Long, val text: String, val align: Side?, val background: Boolean)

/** SPL text plus the per-line layout used for per-line alignment in SPW's own lyric view. */
data class SplLayout(val spl: String, val lines: List<SplLine>)

object SplWriter {
    private val TAG_LIKE = Regex("[<\\[](\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,6}))?[>\\]]")

    /** [group] = index of the source line; a main line and its background vocals share a group. */
    private data class Block(val line: Line, val background: Boolean, val voice: String?, val group: Int, val groupStart: Long)

    /** Gaps between words shorter than this are not written (SPW fills a word until the next tag). */
    private const val MIN_GAP_MS = 30L

    fun write(doc: LyricsDocument, options: SplOptions = SplOptions()): String = layout(doc, options).spl

    fun layout(doc: LyricsDocument, options: SplOptions = SplOptions()): SplLayout {
        val out = mutableListOf<String>()
        val written = mutableListOf<SplLine>()
        if (options.metadata) {
            for (k in listOf("ti", "ar", "al", "au", "lr", "by")) {
                doc.metadata[k]?.takeIf { it.isNotBlank() }?.let { out += "[$k:${safe(it)}]" }
            }
        }

        val blocks = mutableListOf<Block>()
        doc.lines.forEachIndexed { g, l ->
            blocks += Block(l, false, l.voice, g, l.start)
            if (options.background == BackgroundStyle.SEPARATE) {
                l.backgrounds.forEach { blocks += Block(it, true, it.voice ?: l.voice, g, l.start) }
            }
        }
        blocks.sortWith(compareBy<Block> { it.line.start }.thenBy { it.background })

        val occupied = HashSet<Long>()
        val starts = blocks.map { b ->
            var start = b.line.start
            val limit = b.line.knownEnd
            while (start in occupied && (limit == null || start + 1 < limit)) start++
            occupied += start
            start
        }
        // SPW 1.19.0 lights the CONTIGUOUS range of lines from the first to the last line whose
        // [start, end) contains the playback time (MultiLyrics), and its highlight does not
        // reliably let go of an earlier line that overlapped the next one even briefly. Sources
        // (e.g. Apple TTML) routinely end a line after the next line starts (by a few ms up to
        // seconds), so every line, including its last word, is ended at the first line of a LATER
        // source line. A main line and its own background vocals may still overlap, as sung, and
        // lines starting together (simultaneous duet lines) are not clamped to each other.
        val clampTo = blocks.mapIndexed { i, b ->
            blocks.indices.filter { j -> blocks[j].groupStart > b.groupStart && starts[j] > starts[i] }
                .minOfOrNull { starts[it] }
        }

        for ((i, b) in blocks.withIndex()) {
            val start = starts[i]
            val encoded = encode(b.line, start, parenthesize = b.background, clamp = clampTo[i])
            out += encoded
            written += SplLine(start, plainText(encoded), doc.alignOf(b.voice), b.background)

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
        return SplLayout(out.joinToString("\n"), written)
    }

    private val ANY_TAG = Regex("[<\\[]\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,6})?[>\\]]")

    /** Main text of an encoded SPL line, i.e. what SPW shows for it. */
    internal fun plainText(encoded: String): String = encoded.replace(ANY_TAG, "")

    private fun isOpen(c: Char) = c == '(' || c == '（'
    private fun isClose(c: Char) = c == ')' || c == '）'

    /** Parentheses to add around a background text: (prefix, suffix); never doubles, always closes. */
    internal fun parenFix(text: String): Pair<String, String> {
        val t = text.trim()
        if (t.isEmpty()) return "" to ""
        val open = isOpen(t.first())
        val close = isClose(t.last())
        return when {
            open && close -> "" to ""
            open -> "" to (if (t.first() == '（') "）" else ")")   // source lost its ")" e.g. "(Yeah"
            close -> (if (t.last() == '）') "（" else "(") to ""
            else -> "(" to ")"
        }
    }

    private fun paren(s: String): String {
        val t = s.trim()
        val (a, b) = parenFix(t)
        return a + t + b
    }

    private fun encode(line: Line, start: Long, parenthesize: Boolean, clamp: Long? = null): String {
        val sb = StringBuilder(TimeFormat.lrc(start))
        val end = line.knownEnd
        if (!line.hasWords) {
            var t = safe(line.text)
            if (parenthesize) t = paren(t)
            sb.append(t)
            val e = if (clamp != null && clamp > start) minOf(end ?: clamp, clamp) else end
            if (e != null && e > start) sb.append(TimeFormat.lrc(e))
            return sb.toString()
        }
        val words = line.words.map { it.copy(text = safe(it.text)) }.toMutableList()
        if (parenthesize && words.isNotEmpty()) {
            val (a, b) = parenFix(words.joinToString("") { it.text })
            if (a.isNotEmpty()) {
                val w = words[0]
                val lead = w.text.takeWhile { it.isWhitespace() }
                words[0] = w.copy(text = lead + a + w.text.drop(lead.length))
            }
            if (b.isNotEmpty()) {
                val w = words.last()
                val trail = w.text.takeLastWhile { it.isWhitespace() }
                words[words.lastIndex] = w.copy(text = w.text.dropLast(trail.length) + b + trail)
            }
        }
        // SPW fills a word until the NEXT timestamp, so a pause inside the line (e.g. a background
        // vocal split around the main line) is written as `<end of word><start of next word>`;
        // SPW 1.19.0 drops the empty segment and ends the word cell at its real end.
        // Only clamp when no word starts at/after the clamp (otherwise the overlap is real singing).
        val cap = clamp?.takeIf { c -> words.all { maxOf(it.start, start) < c } }
        var prev = start
        var prevEnd = start
        words.forEachIndexed { i, w ->
            val t = maxOf(w.start, prev)
            if (i > 0 && prevEnd > prev && t - prevEnd >= MIN_GAP_MS) sb.append(TimeFormat.lrc(prevEnd, '<', '>'))
            if (i > 0 || t > start) sb.append(TimeFormat.lrc(t, '<', '>'))
            sb.append(w.text)
            prev = t
            prevEnd = maxOf(w.end, t)
        }
        val lastWordEnd = maxOf(words.last().end, prev)
        var lineEnd = maxOf(end ?: lastWordEnd, lastWordEnd)
        if (cap != null) lineEnd = minOf(lineEnd, cap)
        else if (clamp != null) lineEnd = maxOf(lastWordEnd, minOf(lineEnd, clamp))
        if (lineEnd > prev) sb.append(TimeFormat.lrc(lineEnd))
        return sb.toString()
    }

    /** Never let lyric text be re-read as a timestamp or a new line. */
    private fun safe(s: String): String =
        TAG_LIKE.replace(s.replace('\r', ' ').replace('\n', ' ')) { m -> m.value.replace(':', '∶') }
}
