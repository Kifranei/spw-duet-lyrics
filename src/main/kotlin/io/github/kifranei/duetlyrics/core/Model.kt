package io.github.kifranei.duetlyrics.core

/** One timed unit (syllable / word / phrase). Times are absolute milliseconds. */
data class Word(val start: Long, val end: Long, val text: String)

/** Which side of a duet a line belongs to. */
enum class Side { LEFT, RIGHT }

/**
 * A lyric line.
 *
 * @property voice voice / agent id such as `v1`, `v2`, `v1000`; null when the source has no voices
 * @property explicitVoice true when the source line carried its own marker / agent attribute
 * @property words word timing; empty for line-synced lyrics
 * @property backgrounds background vocal lines (TTML `x-bg`, ELRC `bg:` / `[bg: …]`)
 */
data class Line(
    val start: Long,
    val end: Long?,
    val text: String,
    val words: List<Word> = emptyList(),
    val voice: String? = null,
    val explicitVoice: Boolean = false,
    val translations: List<String> = emptyList(),
    val romanizations: List<String> = emptyList(),
    val backgrounds: List<Line> = emptyList(),
) {
    val hasWords: Boolean get() = words.isNotEmpty()

    /** End time from explicit end, else the last word end. */
    val knownEnd: Long? get() = end ?: words.lastOrNull()?.end
}

data class Agent(val id: String, val type: String = "person", val name: String? = null)

enum class SourceFormat { TTML, ELRC }

data class LyricsDocument(
    val format: SourceFormat,
    val lines: List<Line>,
    val agents: List<Agent> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    val hasWordTiming: Boolean get() = lines.any { it.hasWords || it.backgrounds.any(Line::hasWords) }
    val hasBackground: Boolean get() = lines.any { it.backgrounds.isNotEmpty() }

    /** Distinct voices in order of first use. */
    val voices: List<String> get() = lines.mapNotNull { it.voice }.distinct()

    /** True when at least two different voices sing (a duet). */
    val isDuet: Boolean get() = voices.size >= 2

    /**
     * Side mapping, Apple Music convention: the primary voice (v1, or the first
     * declared/used agent) sings on the left, every other person on the right.
     * Group agents (e.g. `v1000` "both") stay on the left because SPL has no centre.
     */
    fun sideOf(voice: String?): Side {
        if (voice == null) return Side.LEFT
        val primary = primaryVoice() ?: return Side.LEFT
        if (voice.equals(primary, ignoreCase = true)) return Side.LEFT
        val agent = agents.firstOrNull { it.id.equals(voice, ignoreCase = true) }
        if (agent != null && agent.type.equals("group", ignoreCase = true)) return Side.LEFT
        return Side.RIGHT
    }

    private fun primaryVoice(): String? {
        val used = voices
        used.firstOrNull { it.equals("v1", ignoreCase = true) }?.let { return it }
        agents.firstOrNull { !it.type.equals("group", true) && it.id in used }?.let { return it.id }
        return used.firstOrNull()
    }
}
