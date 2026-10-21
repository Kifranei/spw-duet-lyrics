package io.github.kifranei.duetlyrics.core

import kotlin.test.*

class TtmlParserTest {
    private val doc = assertNotNull(TtmlParser.parse(sample("duet.ttml")))

    @Test fun detectsTtmlWithAndWithoutXmlDeclaration() {
        assertTrue(TtmlParser.looksLikeTtml(sample("duet.ttml")))
        assertTrue(TtmlParser.looksLikeTtml("\uFEFF  <!-- c --><tt xmlns=\"http://www.w3.org/ns/ttml\"><body/></tt>"))
        assertTrue(TtmlParser.looksLikeTtml("<tts:tt xmlns:tts=\"http://www.w3.org/ns/ttml\"/>"))
        assertFalse(TtmlParser.looksLikeTtml("[00:01.00]<tt> is not xml root"))
        assertFalse(TtmlParser.looksLikeTtml("<ttx>"))
    }

    @Test fun agentsFromHead() {
        assertEquals(listOf("v1", "v2", "v1000"), doc.agents.map { it.id })
        assertEquals("Alice", doc.agents[0].name)
        assertEquals("group", doc.agents[2].type)
    }

    @Test fun linesVoicesAndWordTiming() {
        assertEquals(5, doc.lines.size)
        val l1 = doc.lines[0]
        assertEquals("Hello world", l1.text)
        assertEquals("v1", l1.voice)
        assertEquals(listOf(Word(1000, 1600, "Hello "), Word(1600, 3500, "world")), l1.words)
        assertEquals(1000, l1.start); assertEquals(3500, l1.end)
        assertEquals("v2", doc.lines[1].voice)
        assertTrue(doc.isDuet)
    }

    @Test fun sidesFollowAppleConvention() {
        assertEquals(Side.LEFT, doc.sideOf("v1"))
        assertEquals(Side.RIGHT, doc.sideOf("v2"))
        assertEquals(Side.LEFT, doc.sideOf("v1000")) // group
    }

    @Test fun backgroundVocalsAreSeparatedFromMainText() {
        val l2 = doc.lines[1]
        assertEquals("I'm here", l2.text)
        assertEquals(1, l2.backgrounds.size)
        val bg = l2.backgrounds[0]
        assertEquals("(right here)", bg.text)
        assertEquals(5300, bg.start)
        assertEquals(2, bg.words.size)
        assertEquals("v2", bg.voice)
        assertEquals(listOf("（就在这里）"), bg.translations)
    }

    @Test fun translationsFromHeadAndInline() {
        assertEquals(listOf("你好世界"), doc.lines[0].translations)
        assertEquals(listOf("我在这里"), doc.lines[1].translations)
        val together = doc.lines.single { it.text == "Together" }
        assertEquals(listOf("一起"), together.translations)
    }

    @Test fun offsetTimeExpressions() {
        val both = doc.lines.last()
        assertEquals(listOf(10_000L, 11_500L), both.words.map { it.start })
        assertEquals(12_000, both.words.last().end)
    }

    @Test fun lineTimedTtml() {
        val d = assertNotNull(TtmlParser.parse(sample("line.ttml")))
        assertEquals(listOf("First line & more", "Second line"), d.lines.map { it.text })
        assertEquals(2500, d.lines[0].start)
        assertEquals(4000, d.lines[0].end)
        assertFalse(d.hasWordTiming)
        assertFalse(d.isDuet)
    }

    @Test fun rejectsBrokenOrUntimedXml() {
        assertNull(TtmlParser.parse("<tt><body><p>unterminated"))
        assertNull(TtmlParser.parse("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p>no timing</p></div></body></tt>"))
    }

    @Test fun externalEntitiesAreNeverResolved() {
        val evil = """<?xml version="1.0"?><!DOCTYPE tt [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="2s">safe</p></div></body></tt>"""
        val d = assertNotNull(TtmlParser.parse(evil))
        assertEquals("safe", d.lines.single().text)
    }
}
