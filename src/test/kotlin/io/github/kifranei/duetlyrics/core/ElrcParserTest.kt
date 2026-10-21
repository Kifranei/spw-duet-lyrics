package io.github.kifranei.duetlyrics.core

import kotlin.test.*

class ElrcParserTest {
    private val result = ElrcParser.parse(sample("duet.elrc"))
    private val doc = result.document

    @Test fun metadataAndMarkers() {
        assertEquals("Duet Song", doc.metadata["ti"])
        assertTrue(result.markerCount >= 5)
    }

    @Test fun voicePrefixesStrippedInAllVariants() {
        val texts = doc.lines.map { it.text }
        assertEquals(listOf("Hello world", "I'm here", "Same time", "Together", "continues as v1 by inheritance"), texts)
        assertTrue(texts.none { it.contains("v1", true) && it.contains(":") })
        assertEquals(listOf("v1", "v2", "v2", "v1", "v1"), doc.lines.map { it.voice })
        assertFalse(doc.lines.last().explicitVoice) // inherited
    }

    @Test fun wordTiming() {
        val l = doc.lines[0]
        assertEquals(listOf(Word(1000, 1600, "Hello "), Word(1600, 3500, "world")), l.words)
        assertEquals(3500, l.end)
    }

    @Test fun sameTimestampTranslationVsSimultaneousDuetLine() {
        assertEquals(listOf("你好世界"), doc.lines[0].translations)
        // 00:07.00 v2 and v1 lines both survive as main lines (both have word timing)
        assertEquals(2, doc.lines.count { it.start == 7000L })
    }

    @Test fun backgroundBracketAndPrefixForms() {
        val bg1 = doc.lines[1].backgrounds.single()
        assertEquals("(right here)", bg1.text)
        assertEquals(5300, bg1.start)
        assertEquals("v2", bg1.voice)
        val bg2 = doc.lines.last().backgrounds.single()
        assertEquals("ooh ah", bg2.text)
        assertEquals(11000, bg2.words.last().end)
    }

    @Test fun emptyTimestampEndsPreviousLine() {
        assertEquals(12_000, doc.lines.last().end)
    }

    @Test fun plainLrcHasNoMarkers() {
        val r = ElrcParser.parse(sample("plain.lrc"))
        assertEquals(0, r.markerCount)
        assertEquals(2, r.timedLineCount)
        assertEquals(listOf(3000L, 3500L, 4000L), r.document.lines[1].words.map { it.start })
        assertEquals(5000, r.document.lines[1].end)
    }

    @Test fun variantsParenBracketFullWidthAndCase() {
        val r = ElrcParser.parse(
            """
            [00:01.00](v1) one
            [00:02.00]【V2】 two
            [00:03.00]v2 ： three
            [00:04.00]<00:04.00>v1:four<00:05.00>
            [00:06.00]v12: twelve
            """.trimIndent()
        )
        assertEquals(listOf("one", "two", "three", "four", "twelve"), r.document.lines.map { it.text })
        assertEquals(listOf("v1", "v2", "v2", "v1", "v12"), r.document.lines.map { it.voice })
        assertEquals(listOf(Word(4000, 5000, "four")), r.document.lines[3].words)
    }

    @Test fun timestampFormsAndOffset() {
        val r = ElrcParser.parse("[offset:+500]\n[1:2.5]a\n[00:03:20]v1: b\n[103:03.405]c")
        assertEquals(listOf(62_000L, 2_700L, 103 * 60_000L + 2_905L).sorted(), r.document.lines.map { it.start })
    }

    @Test fun repeatedTimestampsDuplicateLines() {
        val r = ElrcParser.parse("[00:01.00][00:10.00]v1: chorus")
        assertEquals(listOf(1000L, 10_000L), r.document.lines.map { it.start })
        assertTrue(r.document.lines.all { it.text == "chorus" && it.voice == "v1" })
    }

    @Test fun untimestampedTranslationLine() {
        val r = ElrcParser.parse("[00:01.00]v2: hola\nhello\n\nstray text")
        assertEquals(listOf("hello"), r.document.lines.single().translations)
    }

    @Test fun lyricWordsThatLookLikeMarkersButAreNot() {
        val r = ElrcParser.parse("[00:01.00]v1 is not a marker\n[00:02.00]bgm: music")
        assertEquals(0, r.markerCount)
        assertEquals(listOf("v1 is not a marker", "bgm: music"), r.document.lines.map { it.text })
    }

    @Test fun garbageInput() {
        assertEquals(0, ElrcParser.parse("").timedLineCount)
        assertEquals(0, ElrcParser.parse("hello\nworld").timedLineCount)
        assertEquals(0, ElrcParser.parse("[00:01.00]").timedLineCount)
    }
}
