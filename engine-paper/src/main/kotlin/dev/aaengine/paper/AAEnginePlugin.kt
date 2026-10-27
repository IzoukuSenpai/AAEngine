package dev.aaengine.paper

import dev.aaengine.api.AAEngineProvider
import dev.aaengine.api.event.AAEngineContentLoadedEvent
import dev.aaengine.api.event.AAEngineContentUnloadingEvent
import dev.aaengine.core.engine.DefaultAAEngine
import org.bukkit.plugin.java.JavaPlugin

/**
 * Entrée Paper. Phase 1 : installe l'API publique et publie un instantané vide, pour que les plugins
 * consommateurs (AAItems…) puissent déjà se brancher ; le chargement de contenu arrive avec la phase 2.
 */
class AAEnginePlugin : JavaPlugin() {
    private var engine: DefaultAAEngine? = null

    override fun onEnable() {
        val e = DefaultAAEngine(pluginMeta.version)
        AAEngineProvider.install(e)
        engine = e
        val revision = e.publish(emptyMap(), emptyList())
        server.pluginManager.callEvent(AAEngineContentLoadedEvent(revision, initial = true))
        logger.info("AAEngine ${pluginMeta.version} enabled (API ${e.apiVersion}, content revision $revision).")
    }

    override fun onDisable() {
        val e = engine ?: return
        engine = null
        server.pluginManager.callEvent(AAEngineContentUnloadingEvent())
        AAEngineProvider.uninstall(e)
        logger.info("AAEngine disabled.")
    }
}
