package dev.aaengine.core.content

import dev.aaengine.api.item.AAItemDefinition
import net.kyori.adventure.key.Key
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * Définition compilée d'un item dans un [ContentSnapshot]. [template] est l'exemplaire de référence déjà
 * construit (PDC `aaengine:item_id` et `aaengine:schema` posés, composants appliqués) : chaque création
 * en renvoie un clone, jamais l'instance elle-même (§36 : « cache ItemStack templates puis clone »).
 */
class ItemEntry(
    override val id: Key,
    override val material: Material,
    override val itemModel: Key?,
    private val template: ItemStack,
) : AAItemDefinition {
    /** Taille de pile effective du modèle (composant `max_stack_size` ou valeur du matériau), au moins 1. */
    val maxStackSize: Int = maxOf(1, template.maxStackSize)

    /** Clone du modèle, quantité bornée à `1..maxStackSize`. */
    fun instantiate(amount: Int): ItemStack {
        val copy = template.clone()
        copy.amount = amount.coerceIn(1, maxStackSize)
        return copy
    }
}
