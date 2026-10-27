package dev.aaengine.api.item

import net.kyori.adventure.key.Key
import org.bukkit.Material

/**
 * Vue immuable d'une définition d'item AAEngine. Le nom, la lore, les composants et les mechanics ne sont
 * pas exposés ici : un consommateur qui veut un exemplaire passe par [AAItemService.create].
 */
interface AAItemDefinition {
    /** Identifiant pleinement namespacé (ex. `aaengine:forest_axe`). */
    val id: Key

    /** Matériau serveur de base. */
    val material: Material

    /** Composant `item_model` posé sur les exemplaires, s'il est défini. */
    val itemModel: Key?
}
