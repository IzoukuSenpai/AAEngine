package dev.aaengine.core.content

import dev.aaengine.api.glyph.AAGlyph
import net.kyori.adventure.key.Key

/**
 * Instantané immuable du contenu chargé (§6 de la reconstruction : aucune registry runtime n'est mutée
 * progressivement, un reload construit un nouvel instantané puis le publie d'un seul coup).
 *
 * @property revision 0 pour l'instantané vide initial, puis incrémentée à chaque publication
 */
class ContentSnapshot(
    val revision: Long,
    items: Map<Key, ItemEntry>,
    glyphs: Map<String, AAGlyph>,
) {
    val items: Map<Key, ItemEntry> = java.util.Collections.unmodifiableMap(LinkedHashMap(items))
    val glyphs: Map<String, AAGlyph> = java.util.Collections.unmodifiableMap(LinkedHashMap(glyphs))

    companion object {
        @JvmField
        val EMPTY: ContentSnapshot = ContentSnapshot(0, emptyMap(), emptyMap())
    }
}
