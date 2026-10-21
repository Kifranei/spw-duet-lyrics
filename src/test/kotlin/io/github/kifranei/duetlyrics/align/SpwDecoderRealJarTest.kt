package io.github.kifranei.duetlyrics.align

import io.github.kifranei.duetlyrics.core.LyricsConverter
import io.github.kifranei.duetlyrics.core.SplWriter
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feeds our SPL lines to SPW 1.19.0's own word-timestamp decoder
 * (`KaraokeTimestampDecoderImpl` = `androidx.compose.ui.з$Ԩ(start, end, text, translation)`)
 * from the real app jar. Skipped when the jars are absent.
 */
class SpwDecoderRealJarTest {
    private val jars: List<File> = File(System.getProperty("spw.jars") ?: "/nonexistent")
        .listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.sorted().orEmpty()

    private data class Cell(val start: Long, val end: Long, val text: String)
    private data class Decoded(val start: Long, val end: Long, val main: String, val cells: List<Cell>)

    private fun decode(cl: ClassLoader, start: Long, nextStart: Long, text: String): Decoded {
        val c = Class.forName("androidx.compose.ui.з\$Ԩ", true, cl)
        val k = c.getConstructor(java.lang.Long.TYPE, java.lang.Long.TYPE, String::class.java, String::class.java).apply { isAccessible = true }
        val m = c.getMethod("Ϳ").apply { isAccessible = true }
        val line = m.invoke(k.newInstance(start, nextStart, text, ""))
        fun g(o: Any, n: String) = o.javaClass.getMethod(n).invoke(o)
        val cells = (g(line, "ԩ") as List<*>).map { Cell(g(it!!, "Ϳ") as Long, g(it, "Ԩ") as Long, g(it, "ԩ") as String) }
        return Decoded(g(line, "Ϳ") as Long, g(line, "Ԩ") as Long, g(line, "ԫ") as String, cells)
    }

    /** Whole-document check with SPW's own SPL parser (`ݛ.Ϳ(String)`) and MultiLyrics' range rule. */
    @Test fun `SPW parser and playing range around 1_25 to 1_38`() {
        assumeTrue(jars.isNotEmpty(), "real SPW jars not available")
        val conv = LyricsConverter().convert(javaClass.getResource("/samples/see-you-again-excerpt.ttml")!!.readText())!!
        URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader).use { cl ->
            val parse = Class.forName("androidx.compose.ui.ݛ", true, cl).getMethod("Ϳ", String::class.java).apply { isAccessible = true }
            val doc = parse.invoke(null, conv.spl)
            fun g(o: Any, n: String) = o.javaClass.getMethod(n).apply { isAccessible = true }.invoke(o)
            val lines = (g(doc, "Ԩ") as List<*>).map { Triple(g(it!!, "Ϳ") as Long, g(it, "Ԩ") as Long, g(it, "ԫ") as String) }
            fun range(t: Long): List<String> {
                var first = -1; var last = -1
                for ((i, l) in lines.withIndex()) { if (l.first > t) break; if (t < l.second) { if (first == -1) first = i; last = i } }
                return if (first == -1) emptyList() else (first..last).map { lines[it].third }
            }
            for (t in 85_000L..99_000L step 50) {
                val r = range(t)
                if (t >= 91_816L && t < 98_366L) assertEquals(
                    listOf("Oh, I'll tell you all about it when I see you again", "(You know we started, let me tell you)"), r, "at $t")
            }
            assertEquals(listOf("We've come a long way from where we began", "(Yeah, we came a long way)"), range(89_000))
        }
    }

    @Test fun `SPW decodes gaps, clamped ends and closed parens as intended`() {
        assumeTrue(jars.isNotEmpty(), "real SPW jars not available")
        val text = javaClass.getResource("/samples/see-you-again-excerpt.ttml")!!.readText()
        val conv = LyricsConverter().convert(text)!!
        val lines = conv.spl.lines().filter { it.startsWith("[0") }
        val tag = Regex("^\\[(\\d+):(\\d+)\\.(\\d+)]")
        fun ms(m: MatchResult) = (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + m.groupValues[3].toLong()
        val parsed = lines.map { l -> val m = tag.find(l)!!; ms(m) to l.substring(m.range.last + 1) }
        val starts = parsed.map { it.first }.distinct().sorted()
        URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader).use { cl ->
            val decoded = parsed.map { (s, t) -> decode(cl, s, starts.firstOrNull { it > s } ?: (s + 5000), t) }
            val bg = decoded.first { it.main.startsWith("(You know") }
            assertEquals(92_775L, bg.cells.first { it.text.trim().startsWith("started") }.end, "word ends at its real end, not at the next word")
            assertEquals("(You know we started, let me tell you)", bg.main)
            val woo = decoded.first { it.main == "Woo" }
            assertEquals(209_243L, woo.end, "Woo no longer stays lit until 3:32.919")
            assertTrue(decoded.any { it.main == "(Yeah)" })
            assertEquals(91_816L, decoded.first { it.main.startsWith("We've come") }.end)
            // what SPW shows is exactly what the per-line alignment map recorded
            for (l in conv.layout) assertTrue(decoded.any { it.start == l.start && AlignMap.norm(it.main) == AlignMap.norm(l.text) }, "$l")
        }
    }
}
