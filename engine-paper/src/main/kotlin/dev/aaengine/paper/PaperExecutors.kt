package dev.aaengine.paper

import org.bukkit.plugin.Plugin
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pont vers les ordonnanceurs (§26) : [async] pour le disque et le parsing, [main] pour les objets serveur
 * et le swap atomique (région globale : thread principal sous Paper, tick global sous Folia).
 *
 * [async] est un thread dédié de priorité normale et non l'ordonnanceur asynchrone de Paper : ses threads
 * ont une priorité basse que Windows peut affamer (reload observé à plus de 40 s sur un serveur au repos).
 * Un seul thread suffit, le service de reload n'ayant jamais plus d'une tentative en vol.
 */
class PaperExecutors(
    private val plugin: Plugin,
) : AutoCloseable {
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "AAEngine-Reload").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }

    val async: Executor = Executor { task -> worker.execute(task) }

    /** Thread dédié au compilateur de pack : un build long ne retarde jamais un reload. */
    private val packWorker: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "AAEngine-Pack").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }

    val pack: Executor = Executor { task -> packWorker.execute(task) }

    val main: Executor = Executor { task -> plugin.server.globalRegionScheduler.execute(plugin, task) }

    /** Arrête le thread dédié (désactivation) : la tentative en cours est interrompue puis ignorée. */
    override fun close() {
        worker.shutdownNow()
        packWorker.shutdownNow()
        try {
            worker.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
            packWorker.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val SHUTDOWN_WAIT_SECONDS = 2L
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
