package dev.aaengine.api.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Émis sur le thread principal après chaque chargement de contenu appliqué (démarrage et reload) : le nouvel
 * instantané est déjà visible par [dev.aaengine.api.AAEngineProvider.get]. Immuable : un listener ne peut
 * rien annuler.
 *
 * @property contentRevision révision du contenu désormais actif
 * @property initial vrai pour le premier chargement après l'activation du plugin
 */
class AAEngineContentLoadedEvent(
    val contentRevision: Long,
    val initial: Boolean,
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
