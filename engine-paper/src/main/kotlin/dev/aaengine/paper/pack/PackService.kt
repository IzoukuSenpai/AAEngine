package dev.aaengine.paper.pack

import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.io.SafePaths
import dev.aaengine.items.ItemCatalogLoader
import dev.aaengine.pack.BuiltPack
import dev.aaengine.pack.PackCache
import dev.aaengine.pack.PackCompiler
import dev.aaengine.pack.PackLayout
import dev.aaengine.pack.PackOutcome
import dev.aaengine.pack.PackStatus
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/** Bilan d'un build ou d'une validation, prêt à être affiché. */
class PackReport(
    val outcome: PackOutcome,
    val diagnostics: List<Diagnostic>,
    val errorCount: Int,
    val warningCount: Int,
    val infoCount: Int,
    val durationMillis: Long,
)

/**
 * Build et validation du pack hors du thread principal (§26), sur l'exécuteur dédié [executor] : relecture
 * des fichiers d'items (mêmes règles que le reload), conversion, puis compilation. Une seule opération à la
 * fois.
 *
 * Deux états distincts : [current] (dernier build intact) et [published] (pack annoncé aux joueurs). Un build
 * ne change jamais [published] : l'administrateur téléverse le zip puis lance `/aaengine pack publish`.
 */
class PackService(
    private val layout: PackLayout,
    private val itemsDir: () -> Path,
    private val loader: ItemCatalogLoader,
    private val executor: Executor,
    private val logger: Logger = Logger.getLogger("AAEngine"),
) {
    private val compiler = PackCompiler(layout)
    private val busy = AtomicBoolean(false)

    @Volatile
    var current: BuiltPack? = null
        private set

    /** Pack annoncé aux joueurs (SHA-1 envoyé au join), `null` tant que rien n'a été publié. */
    @Volatile
    var published: PackCache.Published? = null
        private set

    val isBusy: Boolean
        get() = busy.get()

    /**
     * Démarrage : supprime les temporaires orphelins, relit le dernier build (empreintes recalculées sur le
     * zip) et le pack annoncé, sans recompiler. Asynchrone.
     */
    fun loadCurrent(): CompletableFuture<BuiltPack?> =
        CompletableFuture.supplyAsync({
            compiler.cleanTemporaries()
            published = compiler.published()
            compiler.current().also { current = it }
        }, executor)

    /**
     * Annonce le dernier build (après téléversement du zip). Le futur vaut `null` s'il n'y a aucun pack intact ;
     * la méthode renvoie `null` si une opération est déjà en cours.
     */
    fun publish(): CompletableFuture<PackCache.Published?>? {
        if (!busy.compareAndSet(false, true)) return null
        return try {
            CompletableFuture
                .supplyAsync({ compiler.publish().also { if (it != null) published = it } }, executor)
                .whenComplete { _, _ -> busy.set(false) }
        } catch (e: RuntimeException) {
            busy.set(false)
            CompletableFuture.failedFuture(e)
        }
    }

    /** Construit le pack ([force] : ignore le cache). `null` si une opération est déjà en cours. */
    fun build(force: Boolean): CompletableFuture<PackReport>? =
        run { diagnostics ->
            val outcome = compiler.build(PackInputs.from(loadItems(diagnostics), diagnostics), diagnostics, force)
            if (outcome.pack != null) current = outcome.pack
            outcome
        }

    /** Valide sources et références sans rien écrire. `null` si une opération est déjà en cours. */
    fun validate(): CompletableFuture<PackReport>? =
        run { diagnostics ->
            compiler.validate(PackInputs.from(loadItems(diagnostics), diagnostics), diagnostics)
        }

    private fun loadItems(diagnostics: DiagnosticCollector) =
        try {
            loader.load(itemsDir(), diagnostics)
        } catch (e: SafePaths.UnsafePathException) {
            diagnostics.error("items/", null, e.message ?: "Chemin refusé")
            dev.aaengine.items.ItemCatalog(emptyMap(), 0)
        }

    /** Bilan complet en console (les commandes n'en affichent qu'une partie). */
    private fun log(report: PackReport) {
        try {
            val pack = report.outcome.pack
            val summary =
                "[PACK] ${report.outcome.status} en ${report.durationMillis} ms : ${report.errorCount} erreur(s), " +
                    "${report.warningCount} avertissement(s)" + (pack?.let { ", SHA-1 ${it.sha1}" } ?: "")
            if (report.errorCount > 0) logger.severe(summary) else logger.info(summary)
            for (d in report.diagnostics.take(MAX_LOGGED)) {
                when (d.severity) {
                    dev.aaengine.core.diagnostic.Severity.ERROR -> logger.severe("[PACK] " + d.format())
                    dev.aaengine.core.diagnostic.Severity.WARN -> logger.warning("[PACK] " + d.format())
                    dev.aaengine.core.diagnostic.Severity.INFO -> logger.info("[PACK] " + d.format())
                }
            }
        } catch (_: RuntimeException) {
            // Journalisation impossible : le bilan reste renvoyé à l'appelant.
        }
    }

    private fun run(action: (DiagnosticCollector) -> PackOutcome): CompletableFuture<PackReport>? {
        if (!busy.compareAndSet(false, true)) return null
        val future = CompletableFuture<PackReport>()
        try {
            executor.execute {
                val started = System.nanoTime()
                val diagnostics = DiagnosticCollector()
                try {
                    val outcome =
                        try {
                            action(diagnostics)
                        } catch (e: RuntimeException) {
                            logger.log(Level.SEVERE, "Erreur interne du compilateur de pack", e)
                            diagnostics.error(null, null, "Erreur interne du compilateur de pack (${e.javaClass.simpleName})")
                            PackOutcome(PackStatus.FAILED, null, 0)
                        }
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val report =
                        PackReport(
                            outcome,
                            diagnostics.toList(),
                            diagnostics.errorCount,
                            diagnostics.warningCount,
                            diagnostics.infoCount,
                            elapsed,
                        )
                    log(report)
                    future.complete(report)
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                    if (t is VirtualMachineError) throw t
                } finally {
                    busy.set(false)
                }
            }
        } catch (e: RuntimeException) {
            busy.set(false)
            future.completeExceptionally(e)
        }
        return future
    }
}

private const val MAX_LOGGED = 200

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
