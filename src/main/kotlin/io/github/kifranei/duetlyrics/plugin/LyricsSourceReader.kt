package io.github.kifranei.duetlyrics.plugin

import java.io.File
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Finds the lyric text for a song.
 *
 * Default order: same-name `.ttml` → same-name `.elrc` (dedicated rich-format files the host
 * does not read itself) → embedded tag lyrics → same-name `.lrc` (the last two can be swapped
 * in settings). The first existing, non-blank source in that order is returned.
 */
internal class LyricsSourceReader(
    private val embeddedFirst: Boolean = true,
    private val embeddedReader: (File) -> String? = ::readEmbedded,
) {
    data class Source(val text: String, val origin: String)

    fun read(audioPath: String): Source? {
        if (audioPath.isBlank()) return null
        val audio = File(audioPath)
        val sidecarOnly = listOf("ttml", "elrc")
        for (ext in sidecarOnly) sidecar(audio, ext)?.let { return it }
        val lrc = { sidecar(audio, "lrc") }
        val embedded = {
            runCatching { embeddedReader(audio) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { Source(it, "embedded") }
        }
        return if (embeddedFirst) embedded() ?: lrc() else lrc() ?: embedded()
    }

    private fun sidecar(audio: File, ext: String): Source? {
        val dir = audio.absoluteFile.parentFile ?: return null
        val base = audio.name.substringBeforeLast('.', audio.name)
        val exact = File(dir, "$base.$ext")
        val file = if (exact.isFile) exact else dir.listFiles()?.firstOrNull { f ->
            f.isFile && f.name.length == base.length + ext.length + 1 &&
                f.name.startsWith(base) && f.name.lowercase(Locale.ROOT).endsWith(".$ext")
        } ?: return null
        if (file.length() > MAX_BYTES) return null
        val text = runCatching { TextDecoding.decode(file.readBytes()) }.getOrNull() ?: return null
        return if (text.isBlank()) null else Source(text, file.name)
    }

    companion object {
        private const val MAX_BYTES = 8L * 1024 * 1024
        private val TXXX_KEYS = listOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "UNSYNCED LYRICS", "LYRICS-TTML", "TTML", "USLT")

        /** Lyrics stored in the audio file (ID3 USLT, Vorbis/FLAC LYRICS/UNSYNCEDLYRICS, MP4 ©lyr…). */
        fun readEmbedded(audio: File): String? {
            if (!audio.isFile) return null
            Logger.getLogger("org.jaudiotagger").level = Level.OFF
            val f = org.jaudiotagger.audio.AudioFileIO.read(audio)
            val tag = f.tag ?: return null
            val candidates = buildList {
                runCatching { add(tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS)) }
                if (tag is org.jaudiotagger.tag.id3.AbstractID3v2Tag) {
                    // ID3 user text frames such as TXXX:LYRICS (written by ffmpeg and some taggers)
                    runCatching {
                        for (field in tag.getFields("TXXX")) {
                            val body = (field as? org.jaudiotagger.tag.id3.AbstractID3v2Frame)?.body
                                as? org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX ?: continue
                            if (body.description.uppercase(Locale.ROOT) in TXXX_KEYS) add(body.text)
                        }
                    }
                } else {
                    for (k in TXXX_KEYS) runCatching { add(tag.getFirst(k)) }
                }
            }
            return candidates.firstOrNull { !it.isNullOrBlank() }
        }
    }
}
