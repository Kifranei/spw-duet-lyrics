package io.github.kifranei.duetlyrics.align

import androidx.compose.runtime.Composable
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import io.github.kifranei.duetlyrics.core.SplLine
import java.io.File

/**
 * Per-line alignment inside SPW's OWN lyric view (CLASS_TRANSFORM).
 *
 * Replaces the per-line `LyricsLineItem(...)` call inside SPW's lyric list with a call to the
 * very same function, only swapping the style argument for a copy whose `TextAlign` is Left
 * (primary voice) or Right (other voices) when the line is one this plugin wrote. Everything
 * else (karaoke, translation, animation, blur, fonts) stays SPW's. Lines that don't match keep
 * the arguments untouched, i.e. SPW's global "歌词对齐" setting.
 */
@OptIn(UnstableSpwWorkshopApi::class)
object LineAlignHook {
    @Volatile var enabled: Boolean = true
    @Volatile var status: String = "not installed"
        private set
    @Volatile var log: (String) -> Unit = { System.err.println("[DuetLyrics] $it") }

    /** Alignment map of the lyrics most recently handed to SPW by this plugin (null: none). */
    @Volatile var current: AlignMap? = null
        private set

    private var handle: HookHandle? = null
    private var target: LineItemTarget? = null
    private var original: OriginalCall? = null

    fun lyricsLoaded(lines: List<SplLine>?) {
        current = lines?.let(::AlignMap)?.takeIf { !it.isEmpty }
    }

    val installed: Boolean get() = handle != null

    fun install(): Boolean {
        if (handle != null) return true
        return try {
            if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
                status = "class-transform permission not granted"
                log("per-line align: $status")
                return false
            }
            val host = hostFiles()
            log("per-line align: scanning ${host.map { "${it.name} (${it.length()} B)" }}")
            val t = LineItemTarget.discover(host)
            val loader = WorkshopApi::class.java.classLoader
            val owner = Class.forName(t.owner, false, loader)
            val orig = OriginalCall.resolve(owner, t.method, t.descriptor)
            target = t
            original = orig
            handle = WorkshopApi.hookRegistrar.replaceComposable(t.owner, t.method, t.caller, t.parameters, 50) { call ->
                Content(call)
            }
            status = "installed: ${t.caller} -> ${t.owner}.${t.method} style#${t.styleIndex}=${t.styleClass}"
            log("per-line align: $status")
            true
        } catch (e: Throwable) {
            status = "failed: $e"
            log("per-line align: $status")
            runCatching { handle?.close() }
            handle = null; target = null; original = null
            false
        }
    }

    fun uninstall() {
        runCatching { handle?.close() }
        handle = null; target = null; original = null
        status = "not installed"
    }

    @Composable
    private fun Content(call: UiHookCall) {
        val orig = original
        val t = target
        val args = call.arguments
        if (orig == null || t == null) return
        orig.Invoke(rewrite(args, t.styleIndex) ?: args)
    }

    /** New argument list with an aligned style, or null to keep the original arguments. */
    internal fun rewrite(args: List<Any?>, styleIndex: Int): List<Any?>? = runCatching {
        if (!enabled) return null
        val map = current ?: return null
        val style = args.getOrNull(styleIndex) ?: return null
        val side = args.asSequence().withIndex()
            .filter { it.index != styleIndex && candidate(it.value) }
            .firstNotNullOfOrNull { map.lookup(it.value) } ?: return null
        val aligned = StyleRewriter.withAlign(style, side) ?: return null
        if (aligned === style) return null
        args.toMutableList().also { it[styleIndex] = aligned }
    }.getOrNull()

    private fun candidate(v: Any?): Boolean = v != null && v !is Function<*> && v !is Boolean &&
        v !is Number && v !is Enum<*> && v !is androidx.compose.ui.Modifier && v !is String

    /** SPW's code source: in 1.19.0 the app jar is `app/ffmpeg-x64.dll` (a zip despite the name). */
    private fun hostFiles(): List<File> {
        val out = LinkedHashSet<File>()
        runCatching { File(WorkshopApi::class.java.protectionDomain.codeSource.location.toURI()) }
            .getOrNull()?.takeIf { it.isFile }?.let { out += it }
        System.getProperty("java.class.path").orEmpty().split(File.pathSeparatorChar)
            .map(::File).filter { it.isFile }.forEach { out += it }
        return out.toList()
    }
}
