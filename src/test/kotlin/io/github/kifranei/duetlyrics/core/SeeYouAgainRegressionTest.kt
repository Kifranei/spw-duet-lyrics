package io.github.kifranei.duetlyrics.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for bugs seen in a 1.1.0 test build in SPW 1.19.0 with "See You Again" (Apple Music TTML, embedded),
 * excerpt L19–L21 and L41–L44 of the real lyrics.
 */
class SeeYouAgainRegressionTest {
    private val conv = LyricsConverter().convert(sample("see-you-again-excerpt.ttml"))!!
    private val spl = conv.spl.lines()
    private fun lineFor(text: String) = spl.first { SplWriter.plainText(it).contains(text) }
    private val tag = Regex("[<\\[](\\d+):(\\d+)\\.(\\d+)[>\\]]")
    private fun times(l: String) = tag.findAll(l).map { (it.groupValues[1].toLong() * 60 + it.groupValues[2].toLong()) * 1000 + it.groupValues[3].toLong() }.toList()

    @Test fun `bg pause around the main line is written as a gap`() {
        // "(You know we started," 1:31.816–1:32.775, then " let me tell you)" from 1:37.623.
        val bg = lineFor("You know we started")
        assertTrue("<01:32.775><01:37.623>" in bg, bg)
        assertEquals("(You know we started, let me tell you)", SplWriter.plainText(bg).let { it.substring(it.indexOf('(')) })
    }

    @Test fun `line timed line does not stay lit past the next line`() {
        // TTML says "Woo" lasts 3:26.287–3:32.919 but "When I see you again" starts at 3:29.243.
        val woo = lineFor("Woo")
        assertEquals(listOf(206_287L, 209_243L), times(woo), woo)
        // every line (main or bg) ends no later than the next SOURCE line starts (incl. its last word)
        val doc = conv.document
        for ((g, src) in doc.lines.withIndex()) {
            val next = doc.lines.getOrNull(g + 1)?.start ?: continue
            for (part in listOf(src) + src.backgrounds) {
                val l = spl.first { SplWriter.plainText(it).replace("(", "").replace(")", "").trim() == part.text.replace("(", "").replace(")", "").trim() && times(it).first() in part.start..part.start + 2 }
                val t = times(l)
                assertTrue(t.last() <= next, "$l ends ${t.last()} after next source line $next")
                assertTrue(t.all { it <= next }, "$l has a word tag after next source line $next")
            }
        }
    }

    /**
     * SPW 1.19.0 (MultiLyrics) lights the contiguous index range first..last of lines with
     * start <= t < end. At 1:35 the user saw "We've come a long way…" + "(Yeah, we came a long
     * way)" still lit next to the current pair, because "began" ended at 1:31.855, 39 ms after
     * the next line started at 1:31.816.
     */
    @Test fun `SPW playing range never mixes a finished line pair with the next pair`() {
        val parsed = spl.filter { it.startsWith("[0") }.map { times(it).first() to it }
            .groupBy { it.first }.toSortedMap().values.map { it.first() } // SPW merges same-start translations
        data class L(val start: Long, val end: Long, val text: String)
        val ls = parsed.map { (s, l) -> val t = times(l); L(s, if (t.size > 1) t.last() else Long.MAX_VALUE, SplWriter.plainText(l)) }
        fun range(t: Long): IntRange? {
            var first = -1; var last = -1
            for ((i, l) in ls.withIndex()) { if (l.start > t) break; if (t < l.end) { if (first == -1) first = i; last = i } }
            return if (first == -1) null else first..last
        }
        val l19 = ls.indexOfFirst { it.text.startsWith("We've come") }
        val l20 = ls.indexOfFirst { it.text.startsWith("Oh, I'll tell") }
        for (t in 91_816L..98_366L) {
            val r = range(t) ?: continue
            assertTrue(l19 !in r && (l19 + 1) !in r, "at $t SPW would light ${r.map { ls[it].text }}")
        }
        assertEquals(l20..l20 + 1, range(95_000))
        assertEquals(91_816L, ls[l19].end, "\"began\" is cut at the next line instead of 1:31.855")
    }

    @Test fun `background that lost its closing paren is closed once`() {
        val yeah = spl.first { SplWriter.plainText(it).startsWith("(Yeah") && !SplWriter.plainText(it).contains(",") }
        assertEquals("(Yeah)", SplWriter.plainText(yeah))
        val uh = lineFor("(Uh, yeah, yeah)")
        assertFalse(SplWriter.plainText(uh).contains("(("))
        assertFalse(SplWriter.plainText(uh).contains("))"))
    }

    @Test fun `paren fix`() {
        assertEquals("" to ")", SplWriter.parenFix("(Yeah"))
        assertEquals("" to "", SplWriter.parenFix("(Yeah)"))
        assertEquals("(" to "", SplWriter.parenFix("yeah)"))
        assertEquals("(" to ")", SplWriter.parenFix("ooh"))
        assertEquals("" to "）", SplWriter.parenFix("（啊"))
    }

    @Test fun `main and its own background may still overlap and keep alignment layout`() {
        val main = lineFor("Oh, I'll tell you all about it")
        assertEquals(98_366L, times(main).last())
        assertTrue(conv.layout.any { it.background && it.text.contains("(Yeah)") })
    }
}
