package io.github.kifranei.duetlyrics.plugin

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import java.util.function.Consumer

@OptIn(UnstableSpwWorkshopApi::class)
class DuetLyricsPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {
    private var manager: ConfigManager? = null
    private val listener = Consumer<ConfigHelper> { settings = readSettings(it) }

    override fun start() {
        runCatching {
            val m = WorkshopApi.manager.createConfigManager()
            manager = m
            settings = readSettings(m.getConfig())
            m.addConfigChangeListener(listener)
        }.onFailure { settings = Settings() }
        bridge = AmllBridge()
        active = true
    }

    override fun stop() {
        active = false
        runCatching { manager?.removeConfigChangeListener(listener) }
        manager = null
        bridge?.close()
        bridge = null
    }

    override fun delete() = stop()

    private fun readSettings(c: ConfigHelper) = Settings.read { k, d -> runCatching { c.get(k, d) }.getOrNull() }

    companion object {
        @Volatile internal var active: Boolean = false
        @Volatile internal var settings: Settings = Settings()
        @Volatile internal var bridge: AmllBridge? = null
    }
}
