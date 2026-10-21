package io.github.kifranei.duetlyrics.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplLayoutTest {
    @Test fun `layout gives per line side with bg following main`() {
        val conv = LyricsConverter().convert(sample("duet.elrc"))!!
        val layout = conv.layout
        val spl = conv.spl.lines()
        assertTrue(layout.isNotEmpty())
        // every layout line is a written SPL line with exactly that start and main text
        for (l in layout) assertTrue(spl.any { it.startsWith(TimeFormat.lrc(l.start)) && SplWriter.plainText(it) == l.text }, "$l")
        assertEquals(layout.size, layout.map { it.start }.distinct().size, "starts are unique keys")
        val mains = layout.filter { !it.background }
        assertEquals(listOf(Side.LEFT, Side.RIGHT, Side.RIGHT, Side.LEFT, Side.LEFT), mains.map { it.align })
        val bgs = layout.filter { it.background }
        assertEquals(2, bgs.size)
        assertEquals(Side.RIGHT, bgs.first { it.text.contains("right here") }.align, "bg follows its v2 main line")
        assertEquals(Side.LEFT, bgs.first { it.text.contains("ooh") }.align, "bg follows its v1 main line")
        assertEquals(conv.spl, SplWriter.write(conv.document))
    }

    @Test fun `non duet has no alignment and group keeps global`() {
        val solo = LyricsConverter().convert("[00:01.000]v1: a\n[00:02.000][bg: b]")!!
        assertTrue(solo.layout.all { it.align == null })
        val doc = LyricsDocument(SourceFormat.TTML, listOf(Line(0, 1, "a", voice = "v1"), Line(1, 2, "b", voice = "v2"), Line(2, 3, "c", voice = "v1000")),
            agents = listOf(Agent("v1"), Agent("v2"), Agent("v1000", "group")))
        assertEquals(listOf(Side.LEFT, Side.RIGHT, null), SplWriter.layout(doc).lines.map { it.align })
    }
}
