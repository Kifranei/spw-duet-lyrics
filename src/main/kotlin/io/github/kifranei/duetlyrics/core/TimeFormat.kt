package io.github.kifranei.duetlyrics.core

import kotlin.math.roundToLong

internal object TimeFormat {
    /** `[mm:ss.xxx]` */
    fun lrc(ms: Long, open: Char = '[', close: Char = ']'): String {
        val safe = ms.coerceAtLeast(0)
        val m = safe / 60_000
        val s = safe % 60_000 / 1_000
        val f = safe % 1_000
        return "%c%02d:%02d.%03d%c".format(open, m, s, f, close)
    }

    /** TTML clock value `mm:ss.fff` (hours added when needed). */
    fun ttml(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val h = safe / 3_600_000
        val m = safe % 3_600_000 / 60_000
        val s = safe % 60_000 / 1_000
        val f = safe % 1_000
        return if (h > 0) "%d:%02d:%02d.%03d".format(h, m, s, f) else "%02d:%02d.%03d".format(m, s, f)
    }

    /**
     * LRC/SPL fraction semantics: fewer than 3 digits are right-padded
     * (`.5` = 500 ms, `.05` = 50 ms); more than 3 digits are truncated to ms.
     */
    fun lrcFraction(digits: String?): Long {
        if (digits.isNullOrEmpty()) return 0
        val d = if (digits.length >= 3) digits.substring(0, 3) else digits.padEnd(3, '0')
        return d.toLong()
    }

    /** TTML time expression (clock or offset time). Returns null when unparsable. */
    fun parseTtml(value: String?, frameRate: Double = 30.0, tickRate: Double = 1.0): Long? {
        val s = value?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching {
            val parts = s.split(':')
            val ms = when (parts.size) {
                4 -> parts[0].toDouble() * 3_600_000 + parts[1].toDouble() * 60_000 +
                    parts[2].toDouble() * 1_000 + parts[3].toDouble() * 1_000 / frameRate
                3 -> parts[0].toDouble() * 3_600_000 + parts[1].toDouble() * 60_000 + parts[2].toDouble() * 1_000
                2 -> parts[0].toDouble() * 60_000 + parts[1].toDouble() * 1_000
                else -> {
                    val m = OFFSET.matchEntire(s)
                    if (m == null) s.toDouble() * 1_000 else {
                        m.groupValues[1].toDouble() * when (m.groupValues[2]) {
                            "h" -> 3_600_000.0
                            "m" -> 60_000.0
                            "s" -> 1_000.0
                            "ms" -> 1.0
                            "f" -> 1_000.0 / frameRate
                            else -> 1_000.0 / tickRate
                        }
                    }
                }
            }
            if (!ms.isFinite() || ms < 0) null else ms.roundToLong()
        }.getOrNull()
    }

    private val OFFSET = Regex("([0-9]+(?:\\.[0-9]+)?)(ms|h|m|s|f|t)")
}
