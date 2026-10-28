package dev.aaengine.api.item

import net.kyori.adventure.key.Key
import org.bukkit.inventory.ItemStack

/**
 * Registre d'items AAEngine. Toutes les méthodes lisent l'instantané de contenu actif (immuable) ;
 * aucune ne lève pour un identifiant inconnu. Thread principal.
 */
interface AAItemService {
    /**
     * Identifiants de l'instantané actif : vue immuable, sans copie, qui ne change pas après un reload
     * (le reload remplace l'instantané). Vide tant que le contenu n'est pas chargé.
     */
    fun ids(): Set<Key>

    fun exists(id: Key): Boolean

    /** Définition active, ou `null` si inconnue. */
    fun definition(id: Key): AAItemDefinition?

    /**
     * Nouvel exemplaire (jamais une instance partagée) en quantité [amount], bornée à `1..maxStackSize`,
     * ou `null` si l'identifiant est inconnu ou si le contenu n'est pas chargé.
     */
    fun create(
        id: Key,
        amount: Int,
    ): ItemStack?

    fun create(id: Key): ItemStack? = create(id, 1)

    /** Identifiant AAEngine porté par cet exemplaire (lecture du PDC, l'objet n'est pas modifié), ou `null`. */
    fun idOf(stack: ItemStack): Key?

    companion object {
        /** Clé PDC de l'identifiant d'item (chaîne `ns:valeur`). */
        const val PDC_ITEM_ID: String = "aaengine:item_id"

        /** Clé PDC de la version de schéma de l'item (entier), pour les migrations. */
        const val PDC_SCHEMA: String = "aaengine:schema"

        /** Version de schéma écrite par cette version de l'engine. */
        const val SCHEMA_VERSION: Int = 1
    }
}
