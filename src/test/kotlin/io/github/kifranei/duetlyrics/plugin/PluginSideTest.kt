package io.github.kifranei.duetlyrics.plugin

import io.github.kifranei.duetlyrics.core.BackgroundStyle
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class PluginSideTest {
    private val dir: File = Files.createTempDirectory("duet").toFile()

    private fun res(name: String) = requireNotNull(javaClass.getResource("/samples/$name")).readText()

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    @Test fun sidecarPriorityTtmlThenElrcThenLrc() {
        val audio = File(dir, "Song.flac").apply { writeBytes(ByteArray(0)) }
        File(dir, "Song.lrc").writeText("[00:01.00]lrc")
        val reader = LyricsSourceReader(embeddedReader = { "[00:01.00]embedded" })
        assertEquals("embedded", reader.read(audio.path)?.origin) // default: embedded before .lrc
        assertEquals("Song.lrc", LyricsSourceReader(false, { "[00:01.00]embedded" }).read(audio.path)?.origin)
        File(dir, "Song.ELRC").writeText(res("duet.elrc"))
        assertEquals("Song.ELRC", reader.read(audio.path)?.origin)
        File(dir, "Song.ttml").writeText(res("duet.ttml"))
        assertEquals("Song.ttml", reader.read(audio.path)?.origin)
    }

    @Test fun embeddedOrder() {
        val audio = File(dir, "a.mp3").apply { writeBytes(ByteArray(0)) }
        File(dir, "a.lrc").writeText("[00:01.00]lrc")
        val emb = { _: File -> res("duet.ttml") }
        assertEquals("a.lrc", LyricsSourceReader(false, emb).read(audio.path)?.origin)
        assertEquals("embedded", LyricsSourceReader(true, emb).read(audio.path)?.origin)
        File(dir, "a.lrc").delete()
        assertEquals("embedded", LyricsSourceReader(false, emb).read(audio.path)?.origin)
    }

    @Test fun decodesGb18030AndBoms() {
        val s = "[00:01.00]v1: 你好"
        assertEquals(s, TextDecoding.decode(s.toByteArray(charset("GB18030"))))
        assertEquals(s, TextDecoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.toByteArray()))
        assertEquals(s, TextDecoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun settingsParsing() {
        val map = mapOf<String, Any>("duet.style" to "ARROWS", "bg.style" to "DROP", "enabled" to false)
        val s = Settings.read { k, d -> map[k] ?: d }
        assertEquals(BackgroundStyle.DROP, s.background)
        assertFalse(s.enabled)
        assertEquals(Settings(), Settings.read { _, d -> d })
        // removed duet.style option: any old saved value is ignored
        for (old in listOf("ARROWS", "PAD", "NONE", "bogus")) {
            assertEquals(Settings(), Settings.read { k, d -> if (k == "duet.style") old else d })
        }
        assertEquals(Settings.read { k, d -> map[k] ?: d }.splOptions.background, BackgroundStyle.DROP)
        val def = Settings()
        assertTrue(def.embeddedFirst)
        assertEquals(BackgroundStyle.SEPARATE, def.background)
        assertFalse(Settings.read { k, d -> if (k == "source.order") "SIDECAR_FIRST" else d }.embeddedFirst)
    }

    @Test fun endToEndProcessWithoutHost() {
        val audio = File(dir, "x.m4a").apply { writeBytes(ByteArray(0)) }
        File(dir, "x.lrc").writeText(res("duet.elrc"))
        val spl = assertNotNull(DuetLyricsExtension.process(audio.path, "", Settings()))
        assertFalse(spl.contains("v1:"))
        assertFalse(spl.contains('\u3000'))
        assertTrue(spl.contains("[00:05.300](right <00:06.000>here)[00:07.000]"), spl)
        File(dir, "x.lrc").writeText(res("plain.lrc"))
        assertNull(DuetLyricsExtension.process(audio.path, "", Settings()))
    }

    @Test fun embeddedReaderToleratesNonAudio() {
        val junk = File(dir, "junk.mp3").apply { writeText("not audio") }
        assertNull(runCatching { LyricsSourceReader.readEmbedded(junk) }.getOrNull())
    }
}

class EmbeddedTagTest {
    private fun copy(name: String): File {
        val dir = Files.createTempDirectory("emb").toFile()
        val f = File(dir, name)
        requireNotNull(javaClass.getResourceAsStream("/samples/$name")).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    @Test fun flacVorbisLyricsWithTtml() {
        val f = copy("embedded-ttml.flac")
        val text = assertNotNull(LyricsSourceReader.readEmbedded(f))
        assertTrue(text.contains("<tt"))
        val spl = assertNotNull(DuetLyricsExtension.process(f.path, "", Settings()))
        assertFalse(spl.contains("<?xml"))
        assertTrue(spl.startsWith("[00:01.000]Hello <00:01.600>world"), spl)
    }

    @Test fun mp3Id3UsltWithElrc() {
        val f = copy("embedded-elrc.mp3")
        val spl = assertNotNull(DuetLyricsExtension.process(f.path, "", Settings()))
        assertFalse(spl.contains("v1:")); assertFalse(spl.contains("v2："))
        assertTrue(spl.contains("I'm <00:04.500>here"), spl)
    }

    @Test fun mp3Id3TxxxLyrics() {
        val f = copy("embedded-elrc-txxx.mp3")
        val spl = assertNotNull(DuetLyricsExtension.process(f.path, "", Settings()))
        assertTrue(spl.contains("Hello <00:01.600>world"), spl)
    }

    @Test fun embeddedTtmlBeatsSidecarLrcByDefault() {
        val f = copy("embedded-ttml.flac")
        File(f.parentFile, "embedded-ttml.lrc").writeText("[00:01.00]v1: sidecar")
        val spl = assertNotNull(DuetLyricsExtension.process(f.path, "", Settings()))
        assertTrue(spl.startsWith("[00:01.000]Hello"), spl)
        val sidecar = assertNotNull(DuetLyricsExtension.process(f.path, "", Settings(embeddedFirst = false)))
        assertEquals("[00:01.000]sidecar", sidecar)
    }
}
