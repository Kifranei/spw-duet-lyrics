package io.github.kifranei.duetlyrics.align

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LineItemTargetSyntheticTest {
    private val itemDesc = "(Lp/Line;Landroidx/compose/ui/Modifier;Lp/Style;Landroidx/compose/runtime/Composer;II)V"

    private fun cls(name: String, body: ClassWriter.() -> Unit): Pair<String, ByteArray> {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null)
        cw.body(); cw.visitEnd()
        return name to cw.toByteArray()
    }

    private fun ClassWriter.marker(s: String) { visitField(ACC_STATIC, "m", "Ljava/lang/String;", null, s).visitEnd() }

    private fun ClassWriter.calls(owner: String, desc: String = itemDesc) {
        val mv = visitMethod(ACC_PUBLIC or ACC_STATIC, "go", "()V", null, null)
        mv.visitCode(); mv.visitMethodInsn(INVOKESTATIC, owner, "x", desc, false); mv.visitInsn(RETURN); mv.visitMaxs(0, 0); mv.visitEnd()
    }

    private fun base() = mapOf(
        cls("p/Item") { marker(LineItemTarget.ITEM_MARKER + "LyricsLineItem.kt:72)") },
        cls("p/Style") { visitField(ACC_PRIVATE or ACC_FINAL, "a", "Landroidx/compose/ui/text/style/TextAlign;", null, null).visitEnd() },
        cls("p/Line") {},
        cls("p/Measure") { marker(LineItemTarget.MEASURE_MARKER + "x)"); calls("p/Item") },
    )

    @Test fun `finds unique caller and skips measure pass`() {
        val t = LineItemTarget.discover(base() + cls("p/List\$1") { calls("p/Item") })
        assertEquals("p.Item", t.owner); assertEquals("p.List\$1", t.caller); assertEquals(2, t.styleIndex)
        assertEquals(listOf("p.Line", "androidx.compose.ui.Modifier", "p.Style"), t.parameters)
    }

    @Test fun `prefers the MultiLyrics lambda over the settings preview`() {
        val t = LineItemTarget.discover(base() + cls("p/Multi") { marker(LineItemTarget.LIST_MARKER + "MultiLyrics.kt:95)") } +
            cls("p/Multi\$1") { calls("p/Item") } + cls("p/Preview") { calls("p/Item") })
        assertEquals("p.Multi\$1", t.caller)
    }

    @Test fun `ambiguous or missing targets fail`() {
        assertFailsWith<IllegalArgumentException> {
            LineItemTarget.discover(base() + cls("p/A") { calls("p/Item") } + cls("p/B") { calls("p/Item") })
        }
        assertFailsWith<IllegalArgumentException> { LineItemTarget.discover(base()) }
        assertFailsWith<IllegalArgumentException> { LineItemTarget.discover(mapOf(cls("q/X") {})) }
    }
}
