package io.github.kifranei.duetlyrics.plugin

import io.github.kifranei.duetlyrics.core.BackgroundStyle
import io.github.kifranei.duetlyrics.core.SplOptions

/** Snapshot of the user settings (keys match src/main/resources/preference_config.json). */
internal data class Settings(
    val enabled: Boolean = true,
    val embeddedFirst: Boolean = true,
    val background: BackgroundStyle = BackgroundStyle.SEPARATE,
    val translations: Boolean = true,
    val romanization: Boolean = false,
    val normalizeAllElrc: Boolean = false,
    val amllBridge: Boolean = true,
    val perLineAlign: Boolean = true,
) {
    val splOptions get() = SplOptions(background, translations, romanization)

    companion object {
        /** [get] is `(key, default) -> value`, abstracting ConfigHelper for tests. */
        fun read(get: (String, Any) -> Any?): Settings {
            val d = Settings()
            fun bool(k: String, def: Boolean) = (get(k, def) as? Boolean) ?: def
            fun str(k: String, def: String) = (get(k, def) as? String) ?: def
            return Settings(
                enabled = bool("enabled", d.enabled),
                embeddedFirst = str("source.order", "EMBEDDED_FIRST") != "SIDECAR_FIRST",
                background = runCatching { BackgroundStyle.valueOf(str("bg.style", d.background.name)) }.getOrDefault(d.background),
                translations = bool("translation.enabled", d.translations),
                romanization = bool("romanization.enabled", d.romanization),
                normalizeAllElrc = bool("elrc.normalize_all", d.normalizeAllElrc),
                amllBridge = bool("amll.bridge", d.amllBridge),
                perLineAlign = bool("align.per_line", d.perLineAlign),
            )
        }
    }
}
