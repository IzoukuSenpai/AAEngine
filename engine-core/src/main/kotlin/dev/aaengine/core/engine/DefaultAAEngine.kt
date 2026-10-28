package dev.aaengine.core.engine

import dev.aaengine.api.AAEngine
import dev.aaengine.api.AAEngineApiVersion
import dev.aaengine.api.AAReloadReport
import dev.aaengine.api.glyph.AAGlyph
import dev.aaengine.api.glyph.AAGlyphService
import dev.aaengine.api.item.AAItemDefinition
import dev.aaengine.api.item.AAItemService
import dev.aaengine.core.content.ContentSnapshot
import dev.aaengine.core.content.ItemEntry
import net.kyori.adventure.key.Key
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Implémentation de l'API publique au-dessus d'un [ContentSnapshot] remplacé atomiquement ([publish]).
 * Les services lisent toujours l'instantané courant : un reload en cours n'est jamais visible à moitié.
 */
class DefaultAAEngine(
    override val version: String,
) : AAEngine {
    private val current = AtomicReference(ContentSnapshot.EMPTY)

    override val apiVersion: AAEngineApiVersion = AAEngineApiVersion.CURRENT

    override val contentRevision: Long
        get() = current.get().revision

    override val isContentLoaded: Boolean
        get() = current.get().revision > 0

    override val items: AAItemService = SnapshotItemService()

    override val glyphs: AAGlyphService = SnapshotGlyphService()

    @Volatile
    private var lastReloadReport: AAReloadReport? = null

    override val lastReload: AAReloadReport?
        get() = lastReloadReport

    /** Enregistre le bilan de la dernière tentative de chargement (appelé par le service de reload). */
    fun recordReload(report: AAReloadReport) {
        lastReloadReport = report
    }

    /** Instantané courant (lecture). */
    fun snapshot(): ContentSnapshot = current.get()

    /**
     * Publie un nouvel instantané (swap atomique) et renvoie sa révision. Les [items] et [glyphs] sont copiés ;
     * les identifiants de glyphes sont normalisés en minuscules.
     */
    fun publish(
        items: Map<Key, ItemEntry>,
        glyphs: Collection<AAGlyph>,
    ): Long {
        val byId = LinkedHashMap<String, AAGlyph>()
        for (g in glyphs) {
            byId.putIfAbsent(g.id.lowercase(Locale.ROOT), g)
        }
        while (true) {
            val old = current.get()
            val next = ContentSnapshot(old.revision + 1, items, byId)
            if (current.compareAndSet(old, next)) {
                return next.revision
            }
        }
    }

    private inner class SnapshotItemService : AAItemService {
        // Vue non modifiable de la table immuable de l'instantané : stable après un reload, sans copie.
        override fun ids(): Set<Key> = current.get().items.keys

        override fun exists(id: Key): Boolean = current.get().items.containsKey(id)

        override fun definition(id: Key): AAItemDefinition? = current.get().items[id]

        override fun create(
            id: Key,
            amount: Int,
        ): ItemStack? = current.get().items[id]?.instantiate(amount)

        override fun idOf(stack: ItemStack): Key? {
            if (stack.isEmpty) {
                return null
            }
            // Vue PDC en lecture seule : aucun clone d'ItemMeta sur ce chemin chaud (§36).
            val raw = stack.persistentDataContainer.get(ITEM_ID_KEY, PersistentDataType.STRING) ?: return null
            // Cas courant : identifiant connu, sans parsing ni allocation. Sinon (item retiré) : parsing validé.
            current.get().keyByString[raw]?.let { return it }
            return if (Key.parseable(raw)) Key.key(raw) else null
        }
    }

    private inner class SnapshotGlyphService : AAGlyphService {
        override fun glyphs(): Collection<AAGlyph> = current.get().glyphs.values

        override fun glyph(id: String): AAGlyph? = current.get().glyphs[id.lowercase(Locale.ROOT)]
    }

    companion object {
        /** `aaengine:item_id`. */
        @JvmField
        val ITEM_ID_KEY: NamespacedKey = NamespacedKey("aaengine", "item_id")

        /** `aaengine:schema`. */
        @JvmField
        val SCHEMA_KEY: NamespacedKey = NamespacedKey("aaengine", "schema")
    }
}
