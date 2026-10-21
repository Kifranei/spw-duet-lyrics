package io.github.kifranei.duetlyrics.align

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.use
import io.github.kifranei.duetlyrics.core.Side
import io.github.kifranei.duetlyrics.core.SplLine
import org.objectweb.asm.Type
import kotlin.test.Test
import kotlin.test.assertEquals

internal val seen = mutableListOf<TextAlign?>()

@Composable
fun FakeLineItem(line: FakeLine, onClick: () -> Unit, modifier: Modifier = Modifier, style: FakeStyle, flag: Boolean = false) {
    seen += style.align()
}

/** Calls a real Kotlin-compiled composable through [OriginalCall] inside a Compose scene, as the hook does. */
class OriginalCallTest {
    @Test fun `invokes the original composable with the rewritten style`() {
        val owner = Class.forName("io.github.kifranei.duetlyrics.align.OriginalCallTestKt")
        val m = owner.declaredMethods.first { it.name == "FakeLineItem" }
        val call = OriginalCall.resolve(owner, m.name, Type.getMethodDescriptor(m))
        assertEquals(5, call.businessCount)
        LineAlignHook.lyricsLoaded(listOf(SplLine(2000, "Right line", Side.RIGHT, false)))
        seen.clear()
        try {
            ImageComposeScene(200, 100).use { scene ->
                scene.setContent {
                    val style = FakeStyle.of(TextAlign.Center)
                    val a = listOf<Any?>(FakeLine(2000, 2500, "Right line", null), {}, Modifier, style, false)
                    call.Invoke(LineAlignHook.rewrite(a, 3) ?: a)
                    val b = listOf<Any?>(FakeLine(1, 2, "other", null), {}, Modifier, style, false)
                    call.Invoke(LineAlignHook.rewrite(b, 3) ?: b)
                }
                scene.render()
            }
        } finally {
            LineAlignHook.lyricsLoaded(null)
        }
        assertEquals(listOf<TextAlign?>(TextAlign.Right, TextAlign.Center), seen.take(2))
    }
}
