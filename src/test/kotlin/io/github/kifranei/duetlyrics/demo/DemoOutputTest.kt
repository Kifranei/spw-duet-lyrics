package io.github.kifranei.duetlyrics.demo

import io.github.kifranei.duetlyrics.core.*
import java.io.File
import kotlin.test.Test

/** Writes human-readable conversion output to build/demo for review (not an assertion test). */
class DemoOutputTest {
    @Test fun writeDemo() {
        val out = File("build/demo").apply { mkdirs() }
        for (name in listOf("duet.ttml", "line.ttml", "duet.elrc")) {
            val raw = requireNotNull(javaClass.getResource("/samples/$name")).readText()
            val c = LyricsConverter(SplOptions()).convert(raw) ?: continue
            File(out, "$name.spl.txt").writeText(c.spl)
            c.ttml?.takeIf { !c.rawWasTtml }?.let { File(out, "$name.generated.ttml").writeText(it) }
        }
    }
}
