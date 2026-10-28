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

    /** `ns:valeur` -> clé, pour `idOf` sans parsing ni allocation (construit à la publication). */
    val keyByString: Map<String, Key> =
        java.util.Collections.unmodifiableMap(
            HashMap<String, Key>(items.size * 2).also { m ->
                items.keys.forEach { m[it.asString()] = it }
            },
        )

    /** Entrées triées par identifiant (pagination des commandes), calculées une fois par instantané. */
    val sortedEntries: List<ItemEntry> by lazy {
        java.util.Collections.unmodifiableList(this.items.values.sortedBy { it.id.asString() })
    }

    /** Index de recherche par préfixe (autocomplétion), calculé une fois par instantané. */
    val idIndex: IdPrefixIndex by lazy { IdPrefixIndex(keyByString.keys) }

    companion object {
        @JvmField
        val EMPTY: ContentSnapshot = ContentSnapshot(0, emptyMap(), emptyMap())
    }
}
