package dev.aaengine.api.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Émis sur le thread principal juste avant que l'API ne soit retirée (désactivation du plugin) : les
 * consommateurs doivent lâcher leurs références et repasser en état « absent ».
 */
class AAEngineContentUnloadingEvent : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
