package io.github.kifranei.duetlyrics.plugin

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension
import java.util.jar.Manifest
import kotlin.test.*

/** Checks what the host's PF4J loader relies on: manifest attributes and the extension index. */
class Pf4jPackagingTest {
    @Test fun extensionIsAnnotatedAndImplementsExtensionPoint() {
        assertNotNull(DuetLyricsExtension::class.java.getAnnotation(Extension::class.java))
        assertTrue(PlaybackExtensionPoint::class.java.isAssignableFrom(DuetLyricsExtension::class.java))
        // PF4J instantiates extensions via a public no-arg constructor
        DuetLyricsExtension::class.java.getConstructor().newInstance()
    }

    @Test fun extensionIndexListsOurExtension() {
        val idx = javaClass.classLoader.getResources("META-INF/extensions.idx").toList().map { it.readText() }
        assertTrue(idx.any { it.contains("io.github.kifranei.duetlyrics.plugin.DuetLyricsExtension") }, idx.toString())
    }

    @Test fun inactivePluginNeverTouchesLyrics() {
        DuetLyricsPlugin.active = false
        assertNull(DuetLyricsExtension().onBeforeLoadLyrics(PlaybackExtensionPoint.MediaItem("t", "a", "al", "aa", "/nonexistent.flac")))
    }

    @Test fun bridgeIsNoOpWithoutAmll() {
        AmllBridge().use { it.submit("", "/x.flac", "<tt/>"); it.cancelPending() }
    }
}
