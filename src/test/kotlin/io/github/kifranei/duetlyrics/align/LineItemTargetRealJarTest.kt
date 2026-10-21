package io.github.kifranei.duetlyrics.align

import androidx.compose.ui.text.style.TextAlign
import io.github.kifranei.duetlyrics.core.Side
import io.github.kifranei.duetlyrics.core.SplLine
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the hook target against the REAL Salt Player for Windows 1.19.0 app jar
 * (extracts of `app/ffmpeg-x64.dll`; proprietary, so not in the repo). Skipped when absent.
 * Point `-PspwJars=<dir>` at a folder holding the jar(s).
 */
class LineItemTargetRealJarTest {
    private val jars: List<File> = File(System.getProperty("spw.jars") ?: "/nonexistent")
        .listFiles { f -> f.isFile && (f.name.endsWith(".jar") || f.name.endsWith(".dll")) }?.sorted().orEmpty()

    private fun requireJars() = assumeTrue(jars.isNotEmpty(), "real SPW jars not available")

    @Test
    fun `discovers LyricsLineItem call site in SPW 1_19_0`() {
        requireJars()
        val t = LineItemTarget.discover(jars)
        println("target: $t")
        assertEquals("androidx.compose.ui.є", t.owner)
        assertEquals("Ϳ", t.method)
        assertEquals("androidx.compose.ui.ھ\$ׯ", t.caller)
        assertEquals("androidx.compose.ui.ń", t.styleClass)
        assertEquals(
            listOf(
                "com.xuncorp.spc.lyrics.ԭ", "kotlin.jvm.functions.Function0", "com.xuncorp.spc.lyrics.ԫ",
                "kotlin.jvm.functions.Function0", "androidx.compose.ui.Modifier", "kotlin.jvm.functions.Function0",
                "boolean", "androidx.compose.ui.ń", "androidx.compose.ui.મ",
            ),
            t.parameters,
        )
        assertEquals(7, t.styleIndex)
    }

    private fun hostLoader() = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader)

    @Test
    fun `original method resolves and real style and line classes work by reflection`() {
        requireJars()
        val t = LineItemTarget.discover(jars)
        hostLoader().use { cl ->
            val owner = Class.forName(t.owner, false, cl)
            val call = OriginalCall.resolve(owner, t.method, t.descriptor)
            assertEquals(t.parameters.size, call.businessCount)

            // Real lyric line: com.xuncorp.spc.lyrics.ԫ(long start, long end, List words, String text)
            val lineClass = Class.forName(t.parameters[2], true, cl)
            val ctor = lineClass.getConstructor(java.lang.Long.TYPE, java.lang.Long.TYPE, List::class.java, String::class.java)
            val line = ctor.newInstance(12_340L, 15_000L, emptyList<Any>(), "Hello  world")
            val map = AlignMap(listOf(SplLine(12_340L, "Hello world", Side.RIGHT, false), SplLine(9_000L, "x", Side.LEFT, false)))
            assertEquals(Side.RIGHT, map.lookup(line))
            val other = ctor.newInstance(12_340L, 15_000L, emptyList<Any>(), "Different text")
            assertEquals(null, map.lookup(other))

            // Real style class ń: build one through its private constructor, then re-align it.
            val styleClass = Class.forName(t.styleClass, true, cl)
            val fields = styleClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            val sctor = styleClass.declaredConstructors.first { it.parameterTypes.toList() == fields.map { f -> f.type } }
            sctor.isAccessible = true
            val values = fields.map { f ->
                when (f.type) {
                    java.lang.Boolean.TYPE -> true
                    java.lang.Float.TYPE -> 1.5f
                    java.lang.Long.TYPE -> 42L
                    TextAlign::class.java -> TextAlign.Center as Any
                    androidx.compose.ui.text.font.FontWeight::class.java -> androidx.compose.ui.text.font.FontWeight.Bold
                    androidx.compose.ui.text.TextStyle::class.java -> androidx.compose.ui.text.TextStyle.Default
                    else -> error("unexpected field type ${f.type}")
                }
            }
            val style = sctor.newInstance(*values.toTypedArray())
            val right = StyleRewriter.withAlign(style, Side.RIGHT)
            assertNotNull(right)
            fields.forEach { it.isAccessible = true }
            val alignField = fields.single { it.type == TextAlign::class.java }
            assertEquals(TextAlign.Right as Any, alignField.get(right))
            assertEquals(TextAlign.Center as Any, alignField.get(style), "original untouched")
            fields.filter { it != alignField }.forEach { assertEquals(it.get(style), it.get(right), it.name) }
            // SPW's own getter (used by LyricsLineItem) sees the new alignment.
            val getter = styleClass.declaredMethods.single { it.parameterCount == 0 && it.returnType == TextAlign::class.java }
            getter.isAccessible = true
            assertEquals(TextAlign.Right as Any, getter.invoke(right))
            assertSame(right, StyleRewriter.withAlign(right!!, Side.RIGHT))

            // Hook argument rewrite on real objects.
            val args = MutableList<Any?>(t.parameters.size) { null }
            args[2] = line; args[t.styleIndex] = style; args[6] = false
            LineAlignHook.lyricsLoaded(map.let { listOf(SplLine(12_340L, "Hello world", Side.RIGHT, false)) })
            val out = LineAlignHook.rewrite(args, t.styleIndex)
            assertNotNull(out)
            assertEquals(TextAlign.Right as Any, getter.invoke(out[t.styleIndex]))
            assertTrue(out.indices.filter { it != t.styleIndex }.all { out[it] === args[it] })
            LineAlignHook.lyricsLoaded(null)
            assertEquals(null, LineAlignHook.rewrite(args, t.styleIndex))
        }
    }
}
