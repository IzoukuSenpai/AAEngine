package dev.aaengine.pack

import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.pack.source.PackSourceLoader
import dev.aaengine.pack.stage.AtlasStage
import dev.aaengine.pack.stage.BuildZipStage
import dev.aaengine.pack.stage.CacheCheckStage
import dev.aaengine.pack.stage.GenerateEquipmentStage
import dev.aaengine.pack.stage.GenerateItemModelsStage
import dev.aaengine.pack.stage.LoadSourcesStage
import dev.aaengine.pack.stage.MergeStage
import dev.aaengine.pack.stage.PackBuildContext
import dev.aaengine.pack.stage.PackGraph
import dev.aaengine.pack.stage.ValidateStage

/** Issue d'une compilation ou d'une validation. */
enum class PackStatus {
    /** Pack reconstruit et écrit. */
    BUILT,

    /** Entrées inchangées : le pack en cache est conservé. */
    UP_TO_DATE,

    /** Validation seule réussie (aucune écriture). */
    VALIDATED,

    /** Au moins une erreur : rien n'est écrit, le pack précédent reste en place. */
    FAILED,
}

class PackOutcome(
    val status: PackStatus,
    val pack: BuiltPack?,
    val fileCount: Int,
)

/**
 * Compilateur de resource pack (phase 3) : graphe d'étapes
 * `LoadSources → CacheCheck → GenerateItemModels / GenerateEquipment → Merge → Atlas → Validate → BuildZip`.
 * Fonction pure côté serveur (aucune API Bukkit) : à appeler hors du thread principal.
 */
class PackCompiler(
    private val layout: PackLayout,
    private val limits: PackLimits = PackLimits.DEFAULT,
) {
    private val cache = PackCache(layout)
    private val loader = PackSourceLoader(limits)

    private val buildGraph =
        PackGraph(
            listOf(
                LoadSourcesStage(loader),
                CacheCheckStage(cache),
                GenerateItemModelsStage,
                GenerateEquipmentStage,
                MergeStage,
                AtlasStage,
                ValidateStage,
                BuildZipStage(cache),
            ),
        )

    private val validateGraph =
        PackGraph(listOf(LoadSourcesStage(loader), GenerateItemModelsStage, GenerateEquipmentStage, MergeStage, AtlasStage, ValidateStage))

    /** Ordre d'exécution des étapes du build (diagnostic, tests). */
    val buildOrder: List<String>
        get() = buildGraph.order.map { it.id }

    /** Construit le pack, ou le garde tel quel si les entrées n'ont pas changé (sauf [force]). */
    fun build(
        input: PackInput,
        diagnostics: DiagnosticCollector,
        force: Boolean = false,
    ): PackOutcome {
        val ctx = PackBuildContext(input, layout, limits, diagnostics, force)
        buildGraph.run(ctx)
        val pack = ctx.result
        val status =
            when {
                diagnostics.hasErrors || pack == null -> PackStatus.FAILED
                ctx.upToDate -> PackStatus.UP_TO_DATE
                else -> PackStatus.BUILT
            }
        if (status == PackStatus.BUILT && pack != null) {
            ctx.report(
                Severity.INFO,
                PackCodes.SUMMARY,
                "${PackLayout.CACHE}/${PackLayout.ZIP_NAME}",
                null,
                "${pack.fileCount} fichier(s), ${pack.size} octets, SHA-1 ${pack.sha1}",
            )
        }
        return PackOutcome(status, if (status == PackStatus.FAILED) null else pack, pack?.fileCount ?: ctx.merged.size)
    }

    /** Valide sources et références sans rien écrire. */
    fun validate(
        input: PackInput,
        diagnostics: DiagnosticCollector,
    ): PackOutcome {
        val ctx = PackBuildContext(input, layout, limits, diagnostics, force = true)
        validateGraph.run(ctx)
        return PackOutcome(if (diagnostics.hasErrors) PackStatus.FAILED else PackStatus.VALIDATED, null, ctx.merged.size)
    }

    /** Dernier pack construit encore intact sur disque (manifeste + SHA-256 du zip), sans recompiler. */
    fun current(): BuiltPack? = cache.current(null)
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
