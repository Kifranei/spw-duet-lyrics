package io.github.kifranei.duetlyrics.align

import androidx.compose.ui.text.style.TextAlign
import io.github.kifranei.duetlyrics.core.Side
import io.github.kifranei.duetlyrics.core.SplLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotSame

class FakeLine(private val s: Long, private val e: Long, private val t: String, private val sub: String?) {
    fun a(): Long = s
    fun b(): Long = e
    fun c(): String = t
    fun d(): String? = sub
}

class FakeStyle private constructor(val big: Boolean, val size: Float, private val align: TextAlign?, val color: Long) {
    fun align(): TextAlign? = align
    companion object { fun of(a: TextAlign) = FakeStyle(true, 2f, a, 7L) }
}

class AlignUnitTest {
    private val map = AlignMap(
        listOf(
            SplLine(1000, "Left line", Side.LEFT, false),
            SplLine(2000, "Right line", Side.RIGHT, false),
            SplLine(2001, "(bg right)", Side.RIGHT, true),
            SplLine(3000, "Group", null, false),
        ),
    )

    @Test fun `matches by start and text`() {
        assertEquals(Side.LEFT, map.lookup(FakeLine(1000, 1500, "Left line", null)))
        assertEquals(Side.RIGHT, map.lookup(FakeLine(2000, 2500, "Right  Line", "翻译")))
        assertEquals(Side.RIGHT, map.lookup(FakeLine(2001, 2500, "(bg right)", null)))
        // end time equal to a written start must not be confused: text must match too
        assertNull(map.lookup(FakeLine(500, 1000, "something else", null)))
        assertNull(map.lookup(FakeLine(3000, 3500, "Group", null)), "group line keeps global setting")
        assertNull(map.lookup(FakeLine(1001, 1500, "Left line", null)))
        assertNull(map.lookup("not a line"))
    }

    @Test fun `style copy replaces only text align`() {
        val s = FakeStyle.of(TextAlign.Center)
        val r = StyleRewriter.withAlign(s, Side.RIGHT) as FakeStyle
        assertNotSame(s, r)
        assertEquals(TextAlign.Right, r.align())
        assertEquals(TextAlign.Center, s.align())
        assertEquals(s.size, r.size); assertEquals(s.color, r.color); assertEquals(s.big, r.big)
        assertEquals(TextAlign.Left, (StyleRewriter.withAlign(s, Side.LEFT) as FakeStyle).align())
        assertNull(StyleRewriter.withAlign("no align field", Side.LEFT))
    }

    @Test fun `rewrite keeps arguments when nothing matches or disabled`() {
        val style = FakeStyle.of(TextAlign.Center)
        LineAlignHook.lyricsLoaded(listOf(SplLine(2000, "Right line", Side.RIGHT, false)))
        try {
            val args = listOf<Any?>(FakeLine(2000, 2500, "Right line", null), style, false)
            assertEquals(TextAlign.Right, (LineAlignHook.rewrite(args, 1)!![1] as FakeStyle).align())
            assertNull(LineAlignHook.rewrite(listOf(FakeLine(9, 10, "x", null), style), 1))
            LineAlignHook.enabled = false
            assertNull(LineAlignHook.rewrite(args, 1))
        } finally {
            LineAlignHook.enabled = true
            LineAlignHook.lyricsLoaded(null)
        }
    }
}
