package dev.aaengine.items

import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.io.BoundedFiles
import dev.aaengine.core.io.SafePaths
import net.kyori.adventure.key.Key
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Path

/** Ensemble validé des définitions d'items, prêt à être compilé (immuable). */
class ItemCatalog(
    items: Map<Key, ItemDefinitionSpec>,
    val fileCount: Int,
) {
    val items: Map<Key, ItemDefinitionSpec> = java.util.Collections.unmodifiableMap(LinkedHashMap(items))
}

/**
 * Charge tout le dossier `items/` : inventaire sûr, lecture bornée, parsing puis fusion avec détection des
 * identifiants en double entre fichiers. Thread asynchrone ; aucun objet serveur créé.
 */
class ItemCatalogLoader(
    private val parser: ItemFileParser,
    private val limits: ItemLimits = ItemLimits.DEFAULT,
) {
    /** Contenu en mémoire d'un fichier d'items (tests, sources non disque). */
    class SourceText(
        val displayPath: String,
        val content: String,
    )

    /** Charge les fichiers de [itemsDir] (chemins affichés préfixés par [displayPrefix]). */
    fun load(
        itemsDir: Path,
        diagnostics: DiagnosticCollector,
        displayPrefix: String = "items/",
    ): ItemCatalog {
        val sources = ItemSourceScanner.scan(itemsDir, displayPrefix, limits, diagnostics)
        val merger = Merger(diagnostics)
        var totalBytes = 0L
        for (source in sources) {
            val text =
                try {
                    SafePaths.requireNoSymlink(itemsDir, source.path)
                    BoundedFiles.read(source.path, limits.maxFileBytes, allowedRoot = itemsDir)
                } catch (e: BoundedFiles.TooLargeException) {
                    diagnostics.error(source.displayPath, null, "Fichier trop volumineux (max ${limits.maxFileBytes / 1024} Kio)")
                    continue
                } catch (e: CharacterCodingException) {
                    diagnostics.error(source.displayPath, null, "Encodage invalide : UTF-8 attendu")
                    continue
                } catch (e: SafePaths.UnsafePathException) {
                    diagnostics.error(source.displayPath, null, e.message ?: "Chemin refusé")
                    continue
                } catch (e: IOException) {
                    diagnostics.error(source.displayPath, null, "Lecture impossible (${e.javaClass.simpleName})")
                    continue
                }
            totalBytes += text.bytes
            if (totalBytes > limits.maxTotalBytes) {
                diagnostics.error(
                    source.displayPath,
                    null,
                    "Volume total des fichiers d'items trop important (max ${limits.maxTotalBytes / (1024 * 1024)} Mio)",
                )
                break
            }
            merger.add(parser.parse(source.displayPath, text.content, diagnostics))
        }
        return ItemCatalog(merger.items, sources.size)
    }

    /** Variante sans disque : mêmes règles de parsing et de fusion. */
    fun loadTexts(
        sources: List<SourceText>,
        diagnostics: DiagnosticCollector,
    ): ItemCatalog {
        val merger = Merger(diagnostics)
        for (source in sources) {
            if (source.content.length > limits.maxFileBytes) {
                diagnostics.error(source.displayPath, null, "Fichier trop volumineux (max ${limits.maxFileBytes / 1024} Kio)")
                continue
            }
            merger.add(parser.parse(source.displayPath, source.content, diagnostics))
        }
        return ItemCatalog(merger.items, sources.size)
    }

    private inner class Merger(
        private val diagnostics: DiagnosticCollector,
    ) {
        val items = LinkedHashMap<Key, ItemDefinitionSpec>()
        private var overflow = false

        fun add(specs: List<ItemDefinitionSpec>) {
            for (spec in specs) {
                val existing = items[spec.id]
                if (existing != null) {
                    diagnostics.error(
                        spec.source,
                        spec.nodePath,
                        "Identifiant ${spec.id.asString()} déjà défini dans ${existing.source} (${existing.nodePath})",
                    )
                    continue
                }
                if (items.size >= limits.maxItems) {
                    if (!overflow) diagnostics.error(spec.source, spec.nodePath, "Trop d'items (max ${limits.maxItems})")
                    overflow = true
                    continue
                }
                items[spec.id] = spec
            }
        }
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
