package dev.aaengine.paper

import dev.aaengine.api.event.AAEngineContentLoadedEvent
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.core.engine.DefaultAAEngine
import dev.aaengine.core.log.EngineLog
import dev.aaengine.core.log.LogCategory
import dev.aaengine.core.reload.ReloadListener
import dev.aaengine.core.reload.ReloadResult
import org.bukkit.plugin.PluginManager

/**
 * Effets d'un chargement côté serveur : émission de [AAEngineContentLoadedEvent] après chaque publication et
 * journalisation du bilan et des diagnostics. Thread principal (région globale sous Folia).
 */
class ContentReloadListener(
    private val engine: DefaultAAEngine,
    private val pluginManager: PluginManager,
    private val log: EngineLog,
) : ReloadListener {
    override fun onPublished(
        revision: Long,
        initial: Boolean,
    ) {
        pluginManager.callEvent(AAEngineContentLoadedEvent(revision, initial))
    }

    override fun onFinished(result: ReloadResult) {
        val r = result.report
        val count = engine.snapshot().items.size
        if (r.success) {
            log.info(
                LogCategory.ITEMS,
                "Contenu chargé : révision ${r.revision}, $count item(s) en ${r.durationMillis} ms, ${r.warningCount} avertissement(s).",
            )
        } else if (result.initial) {
            log.error(
                LogCategory.ITEMS,
                "ÉCHEC DU CHARGEMENT INITIAL : ${r.errorCount} erreur(s). AAEngine reste actif avec un contenu VIDE " +
                    "(révision ${r.revision}) ; corrigez les fichiers puis /aaengine reload.",
            )
        } else {
            log.error(
                LogCategory.ITEMS,
                "Échec du rechargement : ${r.errorCount} erreur(s). Le contenu précédent (révision ${r.revision}) reste actif.",
            )
        }
        for (d in result.diagnostics.take(MAX_LOGGED)) {
            if (d.severity == Severity.ERROR) log.error(LogCategory.ITEMS, d.format()) else log.warn(LogCategory.ITEMS, d.format())
        }
        val hidden = r.errorCount + r.warningCount - minOf(result.diagnostics.size, MAX_LOGGED)
        if (hidden > 0) log.warn(LogCategory.ITEMS, "… $hidden diagnostic(s) supplémentaire(s) non affiché(s).")
        log.debug(LogCategory.ITEMS) {
            "Items actifs : " +
                engine
                    .snapshot()
                    .items.keys
                    .joinToString(", ") { it.asString() }
        }
    }

    private companion object {
        const val MAX_LOGGED = 200
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
