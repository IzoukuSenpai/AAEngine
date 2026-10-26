package dev.aaengine.paper

import org.bukkit.plugin.java.JavaPlugin

class AAEnginePlugin : JavaPlugin() {
    override fun onEnable() {
        logger.info("AAEngine ${pluginMeta.version} enabled.")
    }

    override fun onDisable() {
        logger.info("AAEngine disabled.")
    }
}
