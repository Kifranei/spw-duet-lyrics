package io.github.kifranei.duetlyrics.align

import io.github.kifranei.duetlyrics.core.Side
import io.github.kifranei.duetlyrics.core.SplLine
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JModifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps SPW's parsed lyric lines back to the voice side of the SPL lines this plugin wrote.
 * A line matches only when BOTH its start time and its main text equal a written line, so
 * lyrics from other sources (or a previous song) are never re-aligned.
 */
class AlignMap(lines: List<SplLine>) {
    private val byStart: Map<Long, List<SplLine>> = lines.filter { it.align != null }.groupBy { it.start }

    val isEmpty: Boolean get() = byStart.isEmpty()

    fun lookup(start: Long, texts: Collection<String>): Side? {
        val cands = byStart[start] ?: return null
        val norms = texts.map(::norm).filter { it.isNotEmpty() }
        return cands.firstOrNull { c -> norm(c.text).let { t -> t.isNotEmpty() && t in norms } }?.align
    }

    /** Looks the host line object up by reflection (any `long` getter as start, `String` getters as text). */
    fun lookup(hostLine: Any?): Side? {
        if (hostLine == null || isEmpty) return null
        val r = HostLineReader.of(hostLine.javaClass) ?: return null
        val texts = r.texts(hostLine)
        if (texts.isEmpty()) return null
        for (v in r.longs(hostLine)) lookup(v, texts)?.let { return it }
        return null
    }

    companion object {
        internal fun norm(s: String) = s.replace(Regex("\\s+"), "").lowercase()
    }
}

/** Cached reflective accessors for SPW's (obfuscated) lyric line class. */
internal class HostLineReader private constructor(private val longs: List<Method>, private val strings: List<Method>) {
    fun longs(o: Any): List<Long> = longs.mapNotNull { runCatching { it.invoke(o) as Long }.getOrNull() }
    fun texts(o: Any): List<String> = strings.mapNotNull { runCatching { it.invoke(o) as String? }.getOrNull() }

    companion object {
        private val cache = ConcurrentHashMap<Class<*>, Any>()
        private val NONE = Any()

        fun of(c: Class<*>): HostLineReader? {
            val v = cache.getOrPut(c) { build(c) ?: NONE }
            return v as? HostLineReader
        }

        private fun build(c: Class<*>): HostLineReader? {
            if (c.isPrimitive || c.isEnum || c.name.startsWith("java.") || c.name.startsWith("kotlin.")) return null
            val ms = c.methods.filter { !JModifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.declaringClass == c }
            val longs = ms.filter { it.returnType == java.lang.Long.TYPE }.sortedBy { it.name }
            val strings = ms.filter { it.returnType == String::class.java && it.name != "toString" }.sortedBy { it.name }
            if (longs.isEmpty() || strings.isEmpty()) return null
            return HostLineReader(longs, strings)
        }
    }
}
