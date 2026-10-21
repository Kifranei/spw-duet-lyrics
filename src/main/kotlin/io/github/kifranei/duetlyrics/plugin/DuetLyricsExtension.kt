package io.github.kifranei.duetlyrics.plugin

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import io.github.kifranei.duetlyrics.core.LyricsConverter
import org.pf4j.Extension

/**
 * Hooks the documented lyric extension point `onBeforeLoadLyrics` (runs on the IO thread
 * before SPW's own lyric loading). Returning SPL text replaces the host's lyrics for this
 * song; returning null keeps SPW's default behaviour.
 */
@Extension
class DuetLyricsExtension : PlaybackExtensionPoint {
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        if (!DuetLyricsPlugin.active) return null
        val s = DuetLyricsPlugin.settings
        if (!s.enabled) return null
        return try {
            process(mediaItem.path, mediaItem.id, s)
        } catch (t: Throwable) {
            System.err.println("[DuetLyrics] failed for ${mediaItem.path}: $t")
            null
        }
    }

    internal companion object {
        fun process(path: String, id: String, s: Settings): String? {
            val bridge = DuetLyricsPlugin.bridge
            val source = LyricsSourceReader(s.embeddedFirst).read(path)
            val conversion = LyricsConverter(s.splOptions, s.normalizeAllElrc).convert(source?.text)
            if (conversion == null) {
                bridge?.cancelPending()
                return null
            }
            val ttml = conversion.ttml
            if (s.amllBridge && ttml != null && bridge != null) bridge.submit(id, path, ttml) else bridge?.cancelPending()
            return conversion.spl.ifBlank { null }
        }
    }
}
