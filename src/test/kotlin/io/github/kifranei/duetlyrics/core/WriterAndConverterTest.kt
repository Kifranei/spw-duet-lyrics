package io.github.kifranei.duetlyrics.core

import kotlin.test.*

class WriterAndConverterTest {
    private val ttmlDoc = assertNotNull(TtmlParser.parse(sample("duet.ttml")))
    private val elrcDoc = ElrcParser.parse(sample("duet.elrc")).document

    private fun lines(s: String) = s.lines().filter { it.isNotBlank() }

    @Test fun splFromTtmlKeepsWordTimingAndNoXml() {
        val spl = SplWriter.write(ttmlDoc, SplOptions(background = BackgroundStyle.SUBLINE))
        assertFalse(spl.contains("<?xml")); assertFalse(spl.contains("<tt")); assertFalse(spl.contains("span"))
        val l = lines(spl)
        assertEquals("[00:01.000]Hello <00:01.600>world[00:03.500]", l[0])
        assertEquals("[00:01.000]你好世界", l[1])
        assertEquals("[00:04.000]I'm <00:04.500>here[00:07.000]", l[2])
        assertEquals("[00:04.000]我在这里", l[3])
        assertEquals("[00:04.000](right here)", l[4])
    }

    @Test fun simultaneousLinesNeverShareATimestamp() {
        val spl = SplWriter.write(ttmlDoc, SplOptions())
        val l = lines(spl)
        assertTrue(l.contains("[00:07.000]Same <00:08.000>time[00:09.000]"))
        assertTrue(l.contains("[00:07.001]<00:07.001>Together[00:09.000]") || l.contains("[00:07.001]Together[00:09.000]"))
        assertTrue(l.contains("[00:07.001]一起"))
    }

    @Test fun defaultsAreNoMarkerAndSeparateWordTimedBackground() {
        assertEquals(BackgroundStyle.SEPARATE, SplOptions().background)
        val ttml = lines(SplWriter.write(ttmlDoc))
        assertTrue(ttml.contains("[00:04.000]I'm <00:04.500>here[00:07.000]"), ttml.joinToString("\n"))
        assertTrue(ttml.contains("[00:05.300](right <00:06.000>here)[00:07.000]"), ttml.joinToString("\n"))
        assertTrue(ttml.contains("[00:05.300]（就在这里）"), ttml.joinToString("\n")) // bg translation follows its own line
        assertFalse(ttml.contains("[00:04.000](right here)"))
        val spl = SplWriter.write(elrcDoc)
        val l = lines(spl)
        assertTrue(l.contains("[00:04.000]I'm <00:04.500>here[00:05.200]"), spl)
        assertTrue(l.contains("[00:05.300](right <00:06.000>here)[00:07.000]"), spl)
        assertTrue(l.contains("[00:10.000](ooh <00:10.500>ah)[00:11.000]"), spl)
        assertFalse(spl.contains('\u3000') || spl.contains("◀") || spl.contains("▶"))
        assertFalse(spl.contains("v1:") || spl.contains("v2：") || spl.contains("[v2]") || spl.contains("bg:"))
    }

    @Test fun separateBackgroundNeverCollidesWithMainLineTimestamp() {
        val d = ElrcParser.parse("[00:01.00]v1: <00:01.00>main<00:02.00>\n[bg: <00:01.00>(echo)<00:02.00>]").document
        val l = lines(SplWriter.write(d))
        assertEquals(listOf("[00:01.000]main[00:02.000]", "[00:01.001](echo)[00:02.000]"), l)
        // re-parsing must yield two lyric lines, not a line + translation
        assertEquals(2, ElrcParser.parse(l.joinToString("\n")).document.lines.size)
    }

    @Test fun backgroundSeparateAndDrop() {
        val sep = lines(SplWriter.write(elrcDoc, SplOptions(background = BackgroundStyle.SEPARATE)))
        assertTrue(sep.contains("[00:05.300](right <00:06.000>here)[00:07.000]"), sep.joinToString("\n"))
        val drop = SplWriter.write(elrcDoc, SplOptions(background = BackgroundStyle.DROP))
        assertFalse(drop.contains("right"))
        assertFalse(drop.contains("ooh"))
    }

    @Test fun duetLinesAreNeverDecorated() {
        val l = lines(SplWriter.write(elrcDoc))
        assertTrue(l.contains("[00:01.000]Hello <00:01.600>world[00:03.500]"), l.joinToString("\n"))
        assertTrue(l.contains("[00:04.000]I'm <00:04.500>here[00:05.200]"), l.joinToString("\n"))
        assertTrue(l.none { "◀" in it || "▶" in it })
    }

    @Test fun nonDuetGetsNoDecoration() {
        val d = ElrcParser.parse("[00:01.00]bg: <00:01.00>la<00:02.00>\n[00:01.50]v1: only one voice").document
        val spl = SplWriter.write(d)
        assertFalse(spl.contains('\u3000')); assertFalse(spl.contains("◀"))
    }

    @Test fun splOutputRoundTripsThroughElrcParser() {
        val spl = SplWriter.write(ttmlDoc, SplOptions(background = BackgroundStyle.DROP))
        val back = ElrcParser.parse(spl).document
        assertEquals(ttmlDoc.lines.map { it.text }, back.lines.map { it.text })
        assertEquals(ttmlDoc.lines[0].words, back.lines[0].words)
    }

    @Test fun textThatLooksLikeTimestampsIsDefused() {
        val d = LyricsDocument(SourceFormat.ELRC, listOf(Line(0, null, "at [01:02.03] o'clock\nnext")))
        val spl = SplWriter.write(d)
        assertEquals(1, ElrcParser.parse(spl).document.lines.size)
        assertFalse(spl.contains("[01:02.03]"))
    }

    @Test fun generatedTtmlFromElrcIsReadableAndKeepsVoices() {
        val ttml = TtmlWriter.write(elrcDoc)
        assertTrue(TtmlParser.looksLikeTtml(ttml))
        val back = assertNotNull(TtmlParser.parse(ttml))
        assertEquals(elrcDoc.lines.map { it.text }, back.lines.map { it.text })
        assertEquals(elrcDoc.lines.map { it.voice }, back.lines.map { it.voice })
        assertEquals(elrcDoc.lines[0].words, back.lines[0].words)
        assertEquals("(right here)", back.lines[1].backgrounds.single().text)
        assertEquals(listOf("你好世界"), back.lines[0].translations)
    }

    @Test fun converterDecisions() {
        val c = LyricsConverter()
        val t = assertNotNull(c.convert(sample("duet.ttml")))
        assertTrue(t.rawWasTtml); assertNotNull(t.ttml)
        assertFalse(t.spl.contains("<"+"tt"))
        val e = assertNotNull(c.convert(sample("duet.elrc")))
        assertFalse(e.rawWasTtml); assertNotNull(e.ttml)
        assertNull(c.convert(sample("plain.lrc")), "plain LRC must be left to the host")
        assertNotNull(LyricsConverter(normalizeAllElrc = true).convert(sample("plain.lrc")))
        assertNull(c.convert("just text"))
        assertNull(c.convert(""))
        assertNull(c.convert("<tt><body><p>broken"))
    }
}
