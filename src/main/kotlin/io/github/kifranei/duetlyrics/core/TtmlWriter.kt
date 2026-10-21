package io.github.kifranei.duetlyrics.core

/**
 * Writes Apple-Music-style TTML (ttm:agent per line, word spans, x-bg, x-translation).
 * Used to hand ELRC duet lyrics, converted from their real word timings, to renderers
 * that can lay out duets left/right (AMLL into SPW).
 */
object TtmlWriter {
    fun write(doc: LyricsDocument): String {
        val sb = StringBuilder()
        val word = doc.hasWordTiming
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" ")
        sb.append("xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" ")
        sb.append("itunes:timing=\"").append(if (word) "Word" else "Line").append("\" xml:lang=\"und\">")
        sb.append("<head><metadata>")
        val voices = doc.voices
        for (v in voices) {
            val agent = doc.agents.firstOrNull { it.id == v }
            sb.append("<ttm:agent type=\"").append(esc(agent?.type ?: "person")).append("\" xml:id=\"").append(esc(v)).append("\"")
            if (agent?.name != null) sb.append("><ttm:name type=\"full\">").append(esc(agent.name)).append("</ttm:name></ttm:agent>")
            else sb.append("/>")
        }
        sb.append("</metadata></head>")
        val lines = doc.lines
        val ends = lines.mapIndexed { i, l -> l.knownEnd ?: lines.getOrNull(i + 1)?.start ?: (l.start + 5_000) }
        val bodyEnd = (ends.maxOrNull() ?: 0L)
        sb.append("<body dur=\"").append(TimeFormat.ttml(bodyEnd)).append("\">")
        sb.append("<div begin=\"").append(TimeFormat.ttml(lines.firstOrNull()?.start ?: 0)).append("\" end=\"")
            .append(TimeFormat.ttml(bodyEnd)).append("\">")
        lines.forEachIndexed { i, l ->
            val end = maxOf(ends[i], l.start + 1)
            sb.append("<p begin=\"").append(TimeFormat.ttml(l.start)).append("\" end=\"").append(TimeFormat.ttml(end)).append("\"")
            if (l.voice != null) sb.append(" ttm:agent=\"").append(esc(l.voice)).append("\"")
            sb.append(" itunes:key=\"L").append(i + 1).append("\">")
            content(sb, l, end)
            for (bg in l.backgrounds) {
                val bgEnd = bg.knownEnd ?: end
                sb.append("<span ttm:role=\"x-bg\" begin=\"").append(TimeFormat.ttml(bg.start)).append("\" end=\"")
                    .append(TimeFormat.ttml(maxOf(bgEnd, bg.start + 1))).append("\">")
                content(sb, bg, bgEnd)
                sb.append("</span>")
            }
            sb.append("</p>")
        }
        sb.append("</div></body></tt>")
        return sb.toString()
    }

    private fun content(sb: StringBuilder, l: Line, end: Long) {
        if (l.hasWords) {
            l.words.forEach { w ->
                val core = w.text.trim()
                val lead = w.text.length - w.text.trimStart().length
                if (lead > 0) sb.append(' ')
                if (core.isNotEmpty()) {
                    sb.append("<span begin=\"").append(TimeFormat.ttml(w.start)).append("\" end=\"")
                        .append(TimeFormat.ttml(maxOf(w.end, w.start + 1))).append("\">").append(esc(core)).append("</span>")
                }
                if (w.text.endsWith(" ") && core.isNotEmpty()) sb.append(' ')
            }
        } else {
            sb.append(esc(l.text))
        }
        l.translations.forEach {
            sb.append("<span ttm:role=\"x-translation\" xml:lang=\"und\">").append(esc(it)).append("</span>")
        }
        l.romanizations.forEach { sb.append("<span ttm:role=\"x-roman\">").append(esc(it)).append("</span>") }
    }

    private fun esc(s: String) = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;")
            else -> if (c.code < 0x20 && c != '\t') append(' ') else append(c)
        }
    }
}
