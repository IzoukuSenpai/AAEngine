package dev.aaengine.paper.items

import dev.aaengine.core.content.ItemEntry
import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.io.SafePaths
import dev.aaengine.core.reload.CompiledContent
import dev.aaengine.core.reload.ContentCompiler
import dev.aaengine.items.ItemCatalog
import dev.aaengine.items.ItemCatalogLoader
import dev.aaengine.items.ItemDefinitionSpec
import net.kyori.adventure.key.Key
import java.nio.file.Path

/**
 * Compilateur des items : lecture/validation de `items/` en asynchrone ([parse]), puis construction des
 * modèles `ItemStack` sur le thread principal ([build]). Les autres `Error` remontent au service de reload,
 * qui les transforme en échec propre.
 */
class ItemsContentCompiler(
    private val itemsDir: () -> Path,
    private val loader: ItemCatalogLoader,
) : ContentCompiler<ItemCatalog> {
    override fun parse(diagnostics: DiagnosticCollector): ItemCatalog? {
        val dir =
            try {
                itemsDir()
            } catch (e: SafePaths.UnsafePathException) {
                diagnostics.error("items/", null, e.message ?: "Chemin refusé")
                return null
            }
        return loader.load(dir, diagnostics)
    }

    override fun build(
        parsed: ItemCatalog,
        diagnostics: DiagnosticCollector,
    ): CompiledContent? {
        val entries = LinkedHashMap<Key, ItemEntry>()
        for (spec in parsed.items.values) {
            try {
                entries[spec.id] = ItemEntry(spec.id, spec.material, spec.itemModel, TemplateBuilder.build(spec))
            } catch (e: RuntimeException) {
                buildError(spec, e, diagnostics)
            } catch (e: LinkageError) {
                // API Data Components absente ou différente (version de Paper) : erreur par item, pas de blocage.
                buildError(spec, e, diagnostics)
            }
        }
        return if (diagnostics.hasErrors) null else CompiledContent(entries)
    }

    private fun buildError(
        spec: ItemDefinitionSpec,
        e: Throwable,
        diagnostics: DiagnosticCollector,
    ) = diagnostics.error(
        spec.source,
        spec.nodePath,
        "Construction de l'item impossible (${e.javaClass.simpleName}) : ${Diagnostic.quote(e.message)}",
    )
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
