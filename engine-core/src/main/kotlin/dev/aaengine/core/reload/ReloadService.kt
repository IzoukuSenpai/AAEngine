package dev.aaengine.core.reload

import dev.aaengine.api.AAReloadReport
import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.engine.DefaultAAEngine
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.LongSupplier
import java.util.logging.Level
import java.util.logging.Logger

/** Résultat complet d'une tentative de chargement : bilan public + diagnostics détaillés. */
class ReloadResult(
    val report: AAReloadReport,
    val diagnostics: List<Diagnostic>,
    /** Vrai si c'était le premier chargement depuis l'activation. */
    val initial: Boolean,
)

/** Rappels du service de reload, toujours sur le thread principal (région globale sous Folia). */
interface ReloadListener {
    /** Un instantané vient d'être publié (contenu valide, ou instantané vide après un échec initial). */
    fun onPublished(
        revision: Long,
        initial: Boolean,
    ) {}

    /** Fin d'une tentative, réussie ou non, après [onPublished]. */
    fun onFinished(result: ReloadResult) {}
}

/**
 * Reload transactionnel (§6) : lecture/validation asynchrones, construction sur le thread principal puis
 * swap atomique de l'instantané. En cas d'erreur, rien n'est publié et l'instantané précédent reste actif ;
 * si rien n'a encore jamais été publié, un instantané vide l'est pour que les consommateurs passent prêts.
 *
 * Les demandes concurrentes sont fusionnées : une seule tentative en vol, et toutes les demandes reçues
 * pendant celle-ci partagent une unique tentative suivante (qui relira donc les fichiers modifiés).
 *
 * Fail-closed : toute tentative se termine, y compris sur une `Error` du compilateur ou un thread de lecture
 * bloqué (délai [attemptTimeout], traité comme une erreur). Seule la tentative qui détient la place en vol
 * peut publier ; la décision et la publication se font sous verrou, comme [close].
 */
class ReloadService<P : Any>(
    private val engine: DefaultAAEngine,
    private val compiler: ContentCompiler<P>,
    private val asyncExecutor: Executor,
    private val mainExecutor: Executor,
    private val listener: ReloadListener = object : ReloadListener {},
    private val logger: Logger = Logger.getLogger("AAEngine"),
    private val nanoClock: LongSupplier = LongSupplier { System.nanoTime() },
    private val attemptTimeout: Duration = DEFAULT_TIMEOUT,
    /** Fabrique de l'exécuteur différé du délai de garde (remplaçable en test). */
    private val delayer: (Duration) -> Executor = { CompletableFuture.delayedExecutor(it.toMillis(), TimeUnit.MILLISECONDS) },
) : AutoCloseable {
    private val lock = Any()
    private var inFlight: CompletableFuture<ReloadResult>? = null
    private var pending: CompletableFuture<ReloadResult>? = null

    @Volatile
    private var closed = false

    /** Vrai si une tentative est en cours. */
    val isReloading: Boolean
        get() = synchronized(lock) { inFlight != null }

    /**
     * Demande un reload. Le futur renvoyé (copie propre à l'appelant) se termine en général sur le thread
     * principal ; il échoue avec [CancellationException] si le service est fermé entre-temps.
     */
    fun reload(): CompletableFuture<ReloadResult> {
        val toStart: CompletableFuture<ReloadResult>
        synchronized(lock) {
            if (closed) return CompletableFuture.failedFuture(CancellationException("Service de reload fermé"))
            if (inFlight != null) {
                val next = pending ?: CompletableFuture<ReloadResult>().also { pending = it }
                return next.copy()
            }
            toStart = CompletableFuture()
            inFlight = toStart
        }
        start(toStart)
        return toStart.copy()
    }

    /** Ferme le service : plus aucune publication, les demandes en attente sont annulées. */
    override fun close() {
        val toCancel: List<CompletableFuture<ReloadResult>>
        synchronized(lock) {
            closed = true
            toCancel = listOfNotNull(inFlight, pending)
            inFlight = null
            pending = null
        }
        toCancel.forEach { it.completeExceptionally(CancellationException("Service de reload fermé")) }
    }

    private fun owns(future: CompletableFuture<ReloadResult>): Boolean = synchronized(lock) { !closed && inFlight === future }

    private fun start(future: CompletableFuture<ReloadResult>) {
        val startedAt = nanoClock.asLong
        try {
            asyncExecutor.execute { parsePhase(future, startedAt) }
            armTimeout(future, startedAt)
        } catch (t: Throwable) {
            fail(future, t)
            if (t is VirtualMachineError) throw t
        }
    }

    /** Délai de garde : une tentative encore en vol à l'échéance est terminée en erreur (thread principal). */
    private fun armTimeout(
        future: CompletableFuture<ReloadResult>,
        startedAt: Long,
    ) {
        delayer(attemptTimeout).execute {
            if (!owns(future)) return@execute
            val seconds = attemptTimeout.toSeconds()
            val diagnostics = DiagnosticCollector()
            diagnostics.error(null, null, "Délai de chargement dépassé ($seconds s) : tentative abandonnée")
            logger.severe("Reload : délai de $seconds s dépassé, tentative abandonnée")
            try {
                mainExecutor.execute { buildPhase(future, startedAt, null, diagnostics) }
            } catch (t: Throwable) {
                fail(future, t)
            }
        }
    }

    private fun parsePhase(
        future: CompletableFuture<ReloadResult>,
        startedAt: Long,
    ) {
        if (!owns(future)) return
        val diagnostics = DiagnosticCollector()
        var fatal: VirtualMachineError? = null
        val parsed =
            try {
                compiler.parse(diagnostics)
            } catch (t: Throwable) {
                if (t is VirtualMachineError) fatal = t
                internalError(diagnostics, "lecture", t)
                null
            }
        try {
            mainExecutor.execute { buildPhase(future, startedAt, parsed, diagnostics) }
        } catch (t: Throwable) {
            fail(future, t)
        }
        // Erreur JVM relancée seulement une fois la suite planifiée (le futur sera terminé).
        fatal?.let { throw it }
    }

    private fun buildPhase(
        future: CompletableFuture<ReloadResult>,
        startedAt: Long,
        parsed: P?,
        diagnostics: DiagnosticCollector,
    ) {
        try {
            buildAndPublish(future, startedAt, parsed, diagnostics)
        } catch (t: Throwable) {
            fail(future, t)
            if (t is VirtualMachineError) throw t
        }
    }

    private fun buildAndPublish(
        future: CompletableFuture<ReloadResult>,
        startedAt: Long,
        parsed: P?,
        diagnostics: DiagnosticCollector,
    ) {
        // Tentative abandonnée (délai, fermeture) : une autre a déjà terminé ce futur.
        if (!owns(future)) return
        var fatal: VirtualMachineError? = null
        var content: CompiledContent? = null
        if (parsed != null && !diagnostics.hasErrors) {
            content =
                try {
                    compiler.build(parsed, diagnostics)
                } catch (t: Throwable) {
                    if (t is VirtualMachineError) fatal = t
                    internalError(diagnostics, "construction", t)
                    null
                }
        }
        val compiled = content.takeIf { !diagnostics.hasErrors }
        val success = compiled != null
        val result: ReloadResult
        val next: CompletableFuture<ReloadResult>?
        synchronized(lock) {
            // Revérifié sous verrou : ni publication après close(), ni après le délai de garde.
            if (closed || inFlight !== future) return
            val initial = !engine.isContentLoaded
            val revision =
                when {
                    compiled != null -> engine.publish(compiled.items, compiled.glyphs)
                    // Échec du premier chargement : instantané vide publié pour que les consommateurs passent prêts.
                    initial -> engine.publish(emptyMap(), emptyList())
                    else -> engine.contentRevision
                }
            val elapsedMillis = (nanoClock.asLong - startedAt).coerceAtLeast(0) / 1_000_000
            val report = AAReloadReport(revision, success, diagnostics.errorCount, diagnostics.warningCount, elapsedMillis)
            engine.recordReload(report)
            result = ReloadResult(report, diagnostics.toList(), initial)
            if (success || initial) notify { listener.onPublished(revision, initial) }
            next = releaseLocked()
        }
        notify { listener.onFinished(result) }
        future.complete(result)
        next?.let(::start)
        fatal?.let { throw it }
    }

    private fun internalError(
        diagnostics: DiagnosticCollector,
        phase: String,
        t: Throwable,
    ) {
        try {
            logger.log(Level.SEVERE, "Erreur interne pendant la $phase du contenu", t)
        } catch (_: Throwable) {
            // Journalisation impossible (mémoire) : le diagnostic suffit.
        }
        diagnostics.error(null, null, "Erreur interne pendant la $phase du contenu (${t.javaClass.simpleName})")
    }

    private fun notify(action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            logger.log(Level.SEVERE, "Erreur dans un rappel de reload", t)
            if (t is VirtualMachineError) throw t
        }
    }

    private fun fail(
        future: CompletableFuture<ReloadResult>,
        cause: Throwable,
    ) {
        val next: CompletableFuture<ReloadResult>?
        synchronized(lock) {
            next = if (inFlight === future) releaseLocked() else null
        }
        // Pendant la fermeture (désactivation), l'ordonnanceur refuse les tâches : rien à signaler.
        if (cause !is CancellationException && !closed) logger.log(Level.SEVERE, "Reload interrompu", cause)
        future.completeExceptionally(cause)
        next?.let(::start)
    }

    /** Sous [lock] : libère la place en vol et renvoie la tentative fusionnée à démarrer, s'il y en a une. */
    private fun releaseLocked(): CompletableFuture<ReloadResult>? {
        inFlight = null
        val toStart = pending ?: return null
        pending = null
        if (closed) {
            toStart.completeExceptionally(CancellationException("Service de reload fermé"))
            return null
        }
        inFlight = toStart
        return toStart
    }

    companion object {
        /** Délai de garde par défaut d'une tentative. */
        @JvmField
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
