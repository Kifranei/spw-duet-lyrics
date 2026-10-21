package io.github.kifranei.duetlyrics.core

/**
 * Decides whether raw lyric text needs this plugin and converts it.
 *
 *  - TTML (any `<tt>` root, with or without `<?xml …?>`) is always converted, because the
 *    host would otherwise show the raw XML.
 *  - LRC/ELRC is converted only when it contains voice / background markers (or when
 *    [normalizeAllElrc] is set); plain or word-timed LRC without markers is left to the
 *    host's own SPL parser (return null).
 */
class LyricsConverter(
    private val spl: SplOptions = SplOptions(),
    private val normalizeAllElrc: Boolean = false,
) {
    data class Conversion(
        val document: LyricsDocument,
        /** Text to hand to Salt Player (SPL). */
        val spl: String,
        /** Raw / generated TTML for an external duet renderer, when useful. */
        val ttml: String?,
        val rawWasTtml: Boolean,
    )

    fun convert(raw: String?): Conversion? {
        if (raw.isNullOrBlank()) return null
        if (TtmlParser.looksLikeTtml(raw)) {
            val doc = TtmlParser.parse(raw) ?: return null
            return Conversion(doc, SplWriter.write(doc, spl), raw.trimStart('\uFEFF'), true)
        }
        val r = ElrcParser.parse(raw)
        if (r.timedLineCount == 0) return null
        val needed = r.markerCount > 0 || (normalizeAllElrc && r.document.hasWordTiming)
        if (!needed) return null
        val doc = r.document
        val ttml = if (doc.isDuet || doc.hasBackground) TtmlWriter.write(doc) else null
        return Conversion(doc, SplWriter.write(doc, spl), ttml, false)
    }
}
