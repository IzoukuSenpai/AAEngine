package dev.aaengine.core.reload

import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.engine.DefaultAAEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** Orchestration du reload transactionnel, sans serveur : exécuteurs contrôlés par le test. */
class ReloadServiceTest {
    /** Exécuteur manuel nommé : les tâches attendent [drain] ; [CURRENT] indique l'exécuteur en cours. */
    private class QueueExecutor(
        val name: String = "queue",
    ) : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                val task = tasks.poll()
                CURRENT.set(name)
                try {
                    task.run()
                } finally {
                    CURRENT.remove()
                }
            }
        }

        companion object {
            val CURRENT = ThreadLocal<String>()
        }
    }

    private fun drain(vararg executors: QueueExecutor) {
        while (executors.any { it.tasks.isNotEmpty() }) {
            executors.forEach { it.runAll() }
        }
    }

    /** Compilateur factice : erreurs à la demande, compte les parsings et note l'exécuteur de chaque phase. */
    private class FakeCompiler : ContentCompiler<String> {
        var parseErrors = false
        var buildThrows = false
        var parseThrowable: (() -> Throwable)? = null
        var buildThrowable: (() -> Throwable)? = null
        var parses = 0
        val parseExecutors = ArrayList<String?>()
        val buildExecutors = ArrayList<String?>()

        override fun parse(diagnostics: DiagnosticCollector): String? {
            parses++
            parseExecutors += QueueExecutor.CURRENT.get()
            parseThrowable?.let { throw it() }
            diagnostics.warn("items/a.yml", "items.x", "avertissement")
            if (parseErrors) {
                diagnostics.error("items/a.yml", "items.x.item.material", "erreur")
                return null
            }
            return "ok"
        }

        override fun build(
            parsed: String,
            diagnostics: DiagnosticCollector,
        ): CompiledContent? {
            buildExecutors += QueueExecutor.CURRENT.get()
            buildThrowable?.let { throw it() }
            if (buildThrows) throw IllegalStateException("boom")
            return CompiledContent(emptyMap())
        }
    }

    /** Journal muet (les erreurs internes sont attendues dans ces tests). */
    private val quiet: Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }

    private class RecordingListener : ReloadListener {
        val published = ArrayList<Pair<Long, Boolean>>()
        val finished = ArrayList<ReloadResult>()

        override fun onPublished(
            revision: Long,
            initial: Boolean,
        ) {
            published += revision to initial
        }

        override fun onFinished(result: ReloadResult) {
            finished += result
        }
    }

    private val direct = Executor { it.run() }

    @Test
    fun `succes publie et emet initial puis non initial`() {
        val engine = DefaultAAEngine("test")
        val listener = RecordingListener()
        val service = ReloadService(engine, FakeCompiler(), direct, direct, listener)

        val first = service.reload().get()
        assertTrue(first.report.success)
        assertEquals(1L, first.report.revision)
        assertEquals(0, first.report.errorCount)
        assertEquals(1, first.report.warningCount)
        assertTrue(first.initial)
        assertEquals(first.report, engine.lastReload)

        val second = service.reload().get()
        assertEquals(2L, second.report.revision)
        assertFalse(second.initial)
        assertEquals(listOf(1L to true, 2L to false), listener.published)
        assertEquals(2, listener.finished.size)
    }

    @Test
    fun `echec garde l'instantane precedent`() {
        val engine = DefaultAAEngine("test")
        val compiler = FakeCompiler()
        val listener = RecordingListener()
        val service = ReloadService(engine, compiler, direct, direct, listener)
        service.reload().get()
        val before = engine.snapshot()

        compiler.parseErrors = true
        val failed = service.reload().get()
        assertFalse(failed.report.success)
        assertEquals(1, failed.report.errorCount)
        assertEquals(1L, failed.report.revision)
        assertTrue(before === engine.snapshot(), "l'instantané actif ne doit pas changer")
        assertEquals(listOf(1L to true), listener.published)
        assertEquals(failed.report, engine.lastReload)
        assertTrue(
            failed.diagnostics
                .first()
                .severity.name == "ERROR",
        )
    }

    @Test
    fun `echec initial publie un instantane vide`() {
        val engine = DefaultAAEngine("test")
        val compiler = FakeCompiler().apply { parseErrors = true }
        val listener = RecordingListener()
        val result = ReloadService(engine, compiler, direct, direct, listener).reload().get()
        assertFalse(result.report.success)
        assertTrue(result.initial)
        assertTrue(engine.isContentLoaded)
        assertEquals(1L, engine.contentRevision)
        assertTrue(engine.items.ids().isEmpty())
        assertEquals(listOf(1L to true), listener.published)
    }

    @Test
    fun `exception de construction bloque la publication`() {
        val engine = DefaultAAEngine("test")
        val compiler = FakeCompiler()
        val service = ReloadService(engine, compiler, direct, direct)
        service.reload().get()
        compiler.buildThrows = true
        val result = service.reload().get()
        assertFalse(result.report.success)
        assertEquals(1L, engine.contentRevision)
        assertTrue(result.diagnostics.any { it.message.contains("IllegalStateException") })
    }

    @Test
    fun `demandes concurrentes fusionnees`() {
        val engine = DefaultAAEngine("test")
        val compiler = FakeCompiler()
        val async = QueueExecutor()
        val main = QueueExecutor()
        val service = ReloadService(engine, compiler, async, main)

        val a = service.reload()
        assertTrue(service.isReloading)
        val b = service.reload()
        val c = service.reload()
        drain(async, main)

        assertEquals(2, compiler.parses, "une tentative en vol + une seule tentative fusionnée")
        assertEquals(1L, a.get().report.revision)
        assertEquals(2L, b.get().report.revision)
        assertEquals(2L, c.get().report.revision)
        assertFalse(service.isReloading)
    }

    @Test
    fun `fermeture annule et ne publie plus`() {
        val engine = DefaultAAEngine("test")
        val async = QueueExecutor()
        val main = QueueExecutor()
        val service = ReloadService(engine, FakeCompiler(), async, main)
        val a = service.reload()
        val b = service.reload()
        service.close()
        drain(async, main)

        assertEquals(0L, engine.contentRevision)
        val ex = assertThrows<ExecutionException> { a.get() }
        assertTrue(ex.cause is CancellationException)
        assertTrue(b.isCompletedExceptionally)
        assertTrue(service.reload().isCompletedExceptionally)
    }

    @Test
    fun `lecture en asynchrone et construction sur le thread principal`() {
        val compiler = FakeCompiler()
        val async = QueueExecutor("async")
        val main = QueueExecutor("main")
        val service = ReloadService(DefaultAAEngine("test"), compiler, async, main)
        val future = service.reload()

        main.runAll()
        assertEquals(0, compiler.parses, "rien ne doit être lu sur le thread principal")
        async.runAll()
        assertEquals(listOf<String?>("async"), compiler.parseExecutors)
        assertTrue(compiler.buildExecutors.isEmpty(), "la construction attend le thread principal")
        main.runAll()
        assertEquals(listOf<String?>("main"), compiler.buildExecutors)
        assertTrue(future.get().report.success)
    }

    @Test
    fun `erreur JVM de construction termine la tentative et laisse recharger`() {
        val engine = DefaultAAEngine("test")
        val compiler = FakeCompiler().apply { buildThrowable = { NoSuchMethodError("DataComponentTypes.TOOLTIP_DISPLAY") } }
        val listener = RecordingListener()
        val service = ReloadService(engine, compiler, direct, direct, listener, quiet)

        val failed = service.reload().get(5, TimeUnit.SECONDS)
        assertFalse(failed.report.success)
        assertTrue(failed.diagnostics.any { it.message.contains("NoSuchMethodError") })
        assertFalse(service.isReloading)
        // Échec initial : instantané vide publié malgré l'Error, les consommateurs passent prêts.
        assertTrue(engine.isContentLoaded)
        assertEquals(listOf(1L to true), listener.published)

        compiler.buildThrowable = null
        val ok = service.reload().get(5, TimeUnit.SECONDS)
        assertTrue(ok.report.success)
        assertEquals(2L, engine.contentRevision)
    }

    @Test
    fun `erreur de lecture termine la tentative et laisse recharger`() {
        val engine = DefaultAAEngine("test")
        val compiler = FakeCompiler().apply { parseThrowable = { AssertionError("boom") } }
        val service = ReloadService(engine, compiler, direct, direct, logger = quiet)

        val failed = service.reload().get(5, TimeUnit.SECONDS)
        assertFalse(failed.report.success)
        assertTrue(failed.diagnostics.any { it.message.contains("AssertionError") })
        assertFalse(service.isReloading)

        compiler.parseThrowable = null
        assertTrue(
            service
                .reload()
                .get(5, TimeUnit.SECONDS)
                .report.success,
        )
    }

    @Test
    fun `erreur de machine virtuelle relancee apres avoir termine la tentative`() {
        val engine = DefaultAAEngine("test")
        val compiler = FakeCompiler().apply { buildThrowable = { StackOverflowError() } }
        val async = QueueExecutor("async")
        val main = QueueExecutor("main")
        val service = ReloadService(engine, compiler, async, main, logger = quiet)

        val future = service.reload()
        async.runAll()
        assertThrows<StackOverflowError> { main.runAll() }
        assertFalse(future.get().report.success)
        assertFalse(service.isReloading)

        compiler.buildThrowable = null
        val next = service.reload()
        drain(async, main)
        assertTrue(next.get().report.success)
    }

    @Test
    fun `delai de garde termine une lecture bloquee`() {
        val engine = DefaultAAEngine("test")
        val compiler = FakeCompiler()
        val async = QueueExecutor("async")
        val main = QueueExecutor("main")
        val timers = QueueExecutor("timer")
        val listener = RecordingListener()
        val service =
            ReloadService(engine, compiler, async, main, listener, quiet, attemptTimeout = Duration.ofSeconds(1), delayer = { timers })

        val stuck = service.reload()
        // La lecture ne s'exécute jamais (thread bloqué) : le délai expire.
        timers.runAll()
        main.runAll()
        val result = stuck.get()
        assertFalse(result.report.success)
        assertTrue(result.diagnostics.any { it.message.contains("Délai") })
        assertFalse(service.isReloading)
        assertTrue(engine.isContentLoaded, "échec initial : instantané vide publié")

        // La lecture bloquée finit par aboutir : elle ne publie plus rien.
        async.runAll()
        main.runAll()
        assertEquals(1L, engine.contentRevision)
        assertEquals(listOf(1L to true), listener.published)

        // Le service reste utilisable.
        val next = service.reload()
        drain(async, main)
        assertTrue(next.get().report.success)
        assertEquals(2L, engine.contentRevision)
    }

    @Test
    fun `fermeture pendant la construction ne publie pas`() {
        val engine = DefaultAAEngine("test")
        val listener = RecordingListener()
        val async = QueueExecutor("async")
        val main = QueueExecutor("main")
        val service = ReloadService(engine, FakeCompiler(), async, main, listener)
        val future = service.reload()
        async.runAll()
        service.close()
        main.runAll()
        assertEquals(0L, engine.contentRevision)
        assertTrue(listener.published.isEmpty())
        assertTrue(future.isCompletedExceptionally)
    }

    @Test
    fun `duree mesuree avec l'horloge fournie`() {
        val engine = DefaultAAEngine("test")
        var now = 0L
        val service =
            ReloadService(
                engine,
                FakeCompiler(),
                direct,
                Executor {
                    now += 5_000_000
                    it.run()
                },
                nanoClock = { now },
            )
        val report = service.reload().get().report
        assertEquals(5L, report.durationMillis)
        assertNotNull(engine.lastReload)
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
