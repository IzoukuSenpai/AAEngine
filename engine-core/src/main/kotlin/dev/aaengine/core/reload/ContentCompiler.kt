package dev.aaengine.core.reload

import dev.aaengine.api.glyph.AAGlyph
import dev.aaengine.core.content.ItemEntry
import dev.aaengine.core.diagnostic.DiagnosticCollector
import net.kyori.adventure.key.Key

/**
 * Compilation du contenu en deux temps (§6) :
 * 1. [parse] sur un thread asynchrone : lecture disque, parsing, validation, sans aucune API Bukkit mutante ;
 * 2. [build] sur le thread principal (région globale sous Folia) : construction des objets runtime.
 *
 * Une erreur dans les diagnostics, à l'une ou l'autre étape, annule la publication.
 *
 * @param P résultat intermédiaire immuable produit par [parse]
 */
interface ContentCompiler<P : Any> {
    /** Thread asynchrone. Renvoie `null` si rien n'est exploitable (les erreurs sont dans [diagnostics]). */
    fun parse(diagnostics: DiagnosticCollector): P?

    /** Thread principal. Renvoie `null` si la construction a échoué (erreurs dans [diagnostics]). */
    fun build(
        parsed: P,
        diagnostics: DiagnosticCollector,
    ): CompiledContent?
}

/** Contenu runtime prêt à être publié d'un seul coup. */
class CompiledContent(
    val items: Map<Key, ItemEntry>,
    val glyphs: Collection<AAGlyph> = emptyList(),
)

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
