package io.github.kifranei.duetlyrics.plugin

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Tiny diagnostic log, because SPW (MS Store build) keeps no log files the user can find.
 * Written next to the installed plugin: `…\Salt Player for Windows\workshop\plugins\spw-duet-lyrics.log`.
 */
internal object PluginLog {
    @Volatile private var file: File? = null
    private const val MAX = 256 * 1024L
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun init(pluginPath: String?) {
        file = runCatching { File(pluginPath ?: return).absoluteFile.parentFile?.resolve("spw-duet-lyrics.log") }.getOrNull()
    }

    @Synchronized
    fun write(msg: String) {
        System.err.println("[DuetLyrics] $msg")
        val f = file ?: return
        runCatching {
            if (f.length() > MAX) f.writeText("")
            f.appendText("${LocalDateTime.now().format(fmt)} $msg\n")
        }
    }
}
