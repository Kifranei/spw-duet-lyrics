package io.github.kifranei.duetlyrics.core

/**
 * Parser for LRC / enhanced LRC ("ELRC", A2 extension) with duet voice markers.
 *
 * Supported:
 *  - line timestamps `[mm:ss.xx]`, `[mm:ss:xx]`, `[mm:ss]`, repeated `[t1][t2]text`
 *  - word timestamps `<mm:ss.xx>` and SPL-style inline `[mm:ss.xx]`
 *  - voice markers right after the timestamp(s) (or after the first word tag):
 *    `v1:` `v2:` `V1 :` `v1：` (full-width colon) `[v1]` `(v1)` `【v1】`, any `v<number>`
 *  - background vocals: `bg:` / `[bg]` marked lines, and Lyricify style `[bg: <t>word<t>…]`
 *  - translations: same-timestamp lines without word timing, or untimestamped lines
 *    directly following a lyric line (SPL rule)
 *  - explicit line end `[t]` (trailing tag or empty timestamp line), `[offset:±ms]`
 *
 * Unmarked lines inherit the voice of the previous marked main line
 * (files that only mark voice changes and files that mark every line both work).
 */
object ElrcParser {

    data class Result(
        val document: LyricsDocument,
        /** Number of voice / bg markers that were recognised (and stripped). */
        val markerCount: Int,
        val timedLineCount: Int,
    )

    private val TS = "(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,6}))?"
    private val LEADING_TS = Regex("^((?:\\[$TS\\]\\s*)+)")
    private val ONE_TS = Regex("\\[$TS\\]")
    private val INLINE_TAG = Regex("[<\\[]$TS[>\\]]")
    private val LEADING_INLINE_TAGS = Regex("^((?:\\s*[<\\[]$TS[>\\]])+)")
    private val BG_BRACKET = Regex("^\\[\\s*bg\\s*[:：](.*)\\]\\s*$", RegexOption.IGNORE_CASE)
    private val META = Regex("^\\[([A-Za-z#][\\w#-]*)\\s*:(.*)\\]\\s*$")

    /** Voice marker at the start of the lyric text. Group 1/2 = marker. */
    internal val MARKER = Regex(
        "^\\s*(?:[\\[(（【]\\s*(v\\d{1,4}|bg)\\s*[\\])）】]\\s*[:：]?|(v\\d{1,4}|bg)\\s*[:：])\\s*",
        RegexOption.IGNORE_CASE,
    )

    private fun tsToMs(m: MatchResult, base: Int = 1): Long {
        val min = m.groupValues[base].toLong()
        val sec = m.groupValues[base + 1].toLong()
        return min * 60_000 + sec * 1_000 + TimeFormat.lrcFraction(m.groupValues[base + 2])
    }

    /** Quick check used to decide whether the plugin should take over from the host parser. */
    fun hasMarkers(text: String): Boolean = parse(text).markerCount > 0

    private class Body(
        val text: String,
        val words: List<MutableWord>,
        val marker: String?,
        val end: Long?,
        val firstTag: Long?,
    )

    private class MutableWord(var start: Long, var end: Long?, var text: String)

    private class Building(
        var start: Long,
        var end: Long?,
        val text: String,
        val words: List<MutableWord>,
        val voice: String?,
        val explicit: Boolean,
        val translations: MutableList<String> = mutableListOf(),
        val backgrounds: MutableList<Building> = mutableListOf(),
    )

    private fun stripMarker(s: String): Pair<String?, String> {
        val m = MARKER.find(s) ?: return null to s
        val marker = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase()
        return marker to s.substring(m.range.last + 1)
    }

    private fun parseBody(rawBody: String, lineStart: Long?): Body {
        var marker: String?
        var body: String
        stripMarker(rawBody).let { marker = it.first; body = it.second }
        if (marker == null) {
            // marker may follow the leading word tag(s): `<00:01.00>v1: word`
            LEADING_INLINE_TAGS.find(body)?.let { lead ->
                val (mk, rest) = stripMarker(body.substring(lead.range.last + 1))
                if (mk != null) {
                    marker = mk
                    body = lead.value + rest
                }
            }
        }

        val tags = INLINE_TAG.findAll(body).toList()
        if (tags.isEmpty()) {
            return Body(clean(body).trim(), emptyList(), marker, null, null)
        }
        val words = mutableListOf<MutableWord>()
        val prefix = body.substring(0, tags.first().range.first)
        val firstTagTime = tsToMs(tags.first())
        if (prefix.isNotBlank()) {
            words += MutableWord(lineStart ?: firstTagTime, firstTagTime, clean(prefix))
        }
        var end: Long? = null
        for ((i, tag) in tags.withIndex()) {
            val t = tsToMs(tag)
            val textEnd = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            val seg = clean(body.substring(tag.range.last + 1, textEnd))
            val next = if (i + 1 < tags.size) tsToMs(tags[i + 1]) else null
            // a tag closes the previous word
            words.lastOrNull()?.let { if (it.end == null || it.end!! > t) it.end = t }
            when {
                seg.isEmpty() -> if (i == tags.lastIndex) end = t
                seg.isBlank() -> {
                    // A2 style `word <t> <t>next`: whitespace belongs to previous word
                    words.lastOrNull()?.let { it.text += " " }
                    if (i == tags.lastIndex) end = t
                }
                else -> words += MutableWord(t, next, seg)
            }
        }
        // Only a trailing end tag, e.g. `[00:01]text[00:02]` => line-level with explicit end.
        if (words.size == 1 && prefix.isNotBlank()) {
            return Body(clean(prefix).trim(), emptyList(), marker, end ?: tags.lastOrNull()?.let(::tsToMs), null)
        }
        words.firstOrNull()?.let { it.text = it.text.trimStart() }
        words.lastOrNull()?.let { it.text = it.text.trimEnd() }
        val filtered = words.filter { it.text.isNotEmpty() }
        return Body(filtered.joinToString("") { it.text }, filtered, marker, end, firstTagTime)
    }

    private fun clean(s: String): String = s.replace('\t', ' ').replace(Regex(" {2,}"), " ")

    fun parse(input: String): Result {
        val text = input.removePrefix("\uFEFF")
        val metadata = linkedMapOf<String, String>()
        var offset = 0L
        val mains = mutableListOf<Building>()
        var lastMain: Building? = null
        var lastWasLyric = false
        var currentVoice: String? = null
        var markers = 0
        var timedLines = 0

        for (rawLine in text.split(Regex("\\r\\n|\\r|\\n"))) {
            val line = rawLine.trim()
            if (line.isEmpty()) { lastWasLyric = false; continue }

            val bgMatch = BG_BRACKET.matchEntire(line)
            if (bgMatch != null) {
                markers++
                val parent = lastMain
                val b = parseBody(bgMatch.groupValues[1], null)
                val start = b.words.firstOrNull()?.start ?: b.firstTag ?: parent?.start ?: 0L
                val bg = Building(start, b.end, b.text, b.words, parent?.voice, parent?.explicit ?: false)
                if (b.text.isNotEmpty()) {
                    if (parent != null) parent.backgrounds += bg else mains += bg
                }
                lastWasLyric = false
                continue
            }

            val lead = LEADING_TS.find(line)
            if (lead == null) {
                val meta = META.matchEntire(line)
                if (meta != null) {
                    val key = meta.groupValues[1].lowercase()
                    val value = meta.groupValues[2].trim()
                    if (key == "offset") offset = value.replace("+", "").toLongOrNull() ?: 0L
                    else metadata[key] = value
                    lastWasLyric = false
                    continue
                }
                // untimestamped text right after a lyric line => translation (SPL rule)
                val target = lastMain
                if (lastWasLyric && target != null) {
                    val (_, rest) = stripMarker(line)
                    val t = clean(INLINE_TAG.replace(rest, "")).trim()
                    if (t.isNotEmpty()) target.translations += t
                }
                continue
            }

            val times = ONE_TS.findAll(lead.value).map { tsToMs(it) }.toList()
            val body = parseBody(line.substring(lead.range.last + 1), times.first())
            if (body.marker != null) markers++

            if (body.text.isEmpty()) {
                // pure end marker `[t]`
                for (t in times) {
                    val target = mains.lastOrNull { it.start < t && it.end == null }
                    if (target != null && target === lastMain) target.end = t
                }
                lastWasLyric = false
                continue
            }
            timedLines++

            for ((index, t) in times.withIndex()) {
                val shift = t - times.first()
                val words = if (index == 0) body.words else body.words.map {
                    MutableWord(it.start + shift, it.end?.plus(shift), it.text)
                }
                val end = body.end?.plus(shift)

                if (body.marker == "bg") {
                    val parent = lastMain
                    val bg = Building(t, end, body.text, words, parent?.voice, parent?.explicit ?: false)
                    if (parent != null) parent.backgrounds += bg else mains += bg
                    continue
                }

                val explicit = body.marker != null
                val voice = body.marker ?: currentVoice

                // same-timestamp, untimed line => translation of the earlier line
                val sameStart = mains.lastOrNull { it.start == t }
                if (sameStart != null && words.isEmpty() &&
                    (!explicit || body.marker == sameStart.voice)
                ) {
                    sameStart.translations += body.text
                    continue
                }

                if (explicit) currentVoice = body.marker
                val b = Building(t, end, body.text, words, voice, explicit)
                mains += b
                lastMain = b
            }
            lastWasLyric = true
        }

        // Finalise: offset, sort, fill unknown word ends.
        fun shift(v: Long) = (v - offset).coerceAtLeast(0)
        val sorted = mains.sortedBy { it.start }
        val starts = sorted.map { it.start }
        fun nextStartAfter(t: Long): Long? = starts.firstOrNull { it > t }

        fun finish(b: Building, fallbackEnd: Long?): Line {
            val lineEnd = b.end ?: b.words.lastOrNull()?.end ?: fallbackEnd
            val words = b.words.mapIndexed { i, w ->
                val e = w.end ?: b.words.getOrNull(i + 1)?.start ?: lineEnd ?: (w.start + 1_000)
                Word(shift(w.start), shift(maxOf(e, w.start)), w.text)
            }
            return Line(
                start = shift(b.start),
                end = (b.end ?: b.words.lastOrNull()?.end)?.let(::shift),
                text = b.text,
                words = words,
                voice = b.voice,
                explicitVoice = b.explicit,
                translations = b.translations.toList(),
                backgrounds = b.backgrounds.map { finish(it, b.end ?: nextStartAfter(b.start)) },
            )
        }

        val lines = sorted.map { finish(it, nextStartAfter(it.start)) }
        val doc = LyricsDocument(
            format = SourceFormat.ELRC,
            lines = lines,
            agents = lines.mapNotNull { it.voice }.distinct().map { Agent(it) },
            metadata = metadata,
        )
        return Result(doc, markers, timedLines)
    }
}
