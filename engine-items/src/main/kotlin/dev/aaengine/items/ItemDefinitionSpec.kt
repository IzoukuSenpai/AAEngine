package dev.aaengine.items

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Material

/**
 * Définition d'item validée et immuable, issue du parsing (aucun objet serveur) : c'est l'entrée de la
 * construction du modèle `ItemStack` sur le thread principal.
 *
 * @property source fichier d'origine relatif au dossier de données (`items/outils.yml`)
 * @property nodePath chemin du nœud de l'item (`items.forest_axe`)
 * @property glint `null` = comportement vanilla, sinon valeur forcée de `enchantment_glint_override`
 */
data class ItemDefinitionSpec(
    val id: Key,
    val source: String,
    val nodePath: String,
    val material: Material,
    val name: Component?,
    val lore: List<Component>,
    val itemModel: Key?,
    val customModelData: CustomModelDataSpec?,
    val unbreakable: Boolean,
    val maxStackSize: Int?,
    val glint: Boolean?,
    val hideTooltip: Boolean,
)

/** Composant `custom_model_data` ; un entier simple `n` équivaut à `floats: [n]`. Couleurs en `0xRRGGBB`. */
data class CustomModelDataSpec(
    val floats: List<Float>,
    val flags: List<Boolean>,
    val strings: List<String>,
    val colors: List<Int>,
)

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
