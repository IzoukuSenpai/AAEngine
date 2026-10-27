package dev.aaengine.api

import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

/**
 * Accès statique à l'API. L'instance est installée une seule fois par le plugin AAEngine, à son activation,
 * et retirée à sa désactivation.
 *
 * Depuis Java : `AAEngineProvider.get()` ou `AAEngineProvider.find()`.
 */
object AAEngineProvider {
    private val instance = AtomicReference<AAEngine?>(null)

    /** API active ; lève [IllegalStateException] si AAEngine n'est pas chargé. */
    @JvmStatic
    fun get(): AAEngine = instance.get() ?: throw IllegalStateException("AAEngine n'est pas chargé")

    /** API active si disponible. */
    @JvmStatic
    fun find(): Optional<AAEngine> = Optional.ofNullable(instance.get())

    /** Réservé au plugin AAEngine : installe l'instance. Une seconde installation est refusée. */
    @JvmStatic
    fun install(engine: AAEngine) {
        check(instance.compareAndSet(null, engine)) { "L'API AAEngine est déjà installée" }
    }

    /** Réservé au plugin AAEngine : retire l'instance si c'est bien celle installée. */
    @JvmStatic
    fun uninstall(engine: AAEngine) {
        instance.compareAndSet(engine, null)
    }
}
