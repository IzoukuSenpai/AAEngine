package dev.aaengine.items

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Material

/**
 * Définition d'item validée et immuable, issue du parsing (aucun objet serveur) : c'est l'entrée de la
 * construction du modèle `ItemStack` sur le thread principal et du compilateur de resource pack.
 *
 * @property source fichier d'origine relatif au dossier de données (`items/outils.yml`)
 * @property nodePath chemin du nœud de l'item (`items.forest_axe`)
 * @property itemModel composant `item_model` effectif : celui écrit (mode manuel) ou l'identifiant de l'item
 * quand le modèle est généré ([model] non nul)
 * @property glint `null` = comportement vanilla, sinon valeur forcée de `enchantment_glint_override`
 * @property model source du modèle à émettre dans le resource pack, `null` en mode manuel ou sans modèle
 * @property tooltipStyle composant `tooltip_style` (sprites `gui/sprites/tooltip/<style>_background|_frame`)
 * @property equippable composant `equippable` (emplacement + set d'équipement)
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
    val model: ItemModelSource? = null,
    val tooltipStyle: Key? = null,
    val equippable: EquippableSpec? = null,
)

/** Composant `custom_model_data` ; un entier simple `n` équivaut à `floats: [n]`. Couleurs en `0xRRGGBB`. */
data class CustomModelDataSpec(
    val floats: List<Float>,
    val flags: List<Boolean>,
    val strings: List<String>,
    val colors: List<Int>,
)

/** Origine du modèle d'un item dans le resource pack. */
sealed interface ItemModelSource {
    /**
     * `model.texture` : modèle 2D généré (`models/item/<id>.json`) à partir des calques [layers]
     * (textures `assets/<ns>/textures/<chemin>.png`). [parent] `null` = choisi selon le matériau.
     */
    data class Textures(
        val layers: List<Key>,
        val parent: Key?,
    ) : ItemModelSource

    /** `model.model` : modèle JSON fourni par l'utilisateur (`assets/<ns>/models/<chemin>.json`). */
    data class Provided(
        val model: Key,
    ) : ItemModelSource
}

/** Emplacement d'équipement accepté par `components.equippable.slot`. */
enum class EquipSlot(
    val configName: String,
) {
    HEAD("head"),
    CHEST("chest"),
    LEGS("legs"),
    FEET("feet"),
}

/** Composant `equippable` : [asset] désigne un set d'équipement (`assets/<ns>/equipment/<set>.json`). */
data class EquippableSpec(
    val slot: EquipSlot,
    val asset: Key,
)

/**
 * Set d'équipement déclaré dans la section racine `equipment:` d'un fichier d'items : textures des couches
 * `humanoid` (casque, plastron, bottes) et `humanoid_leggings` (jambières), au moins une des deux.
 * Textures : `assets/<ns>/textures/entity/equipment/<couche>/<chemin>.png`.
 */
data class EquipmentSetSpec(
    val id: Key,
    val source: String,
    val nodePath: String,
    val humanoid: Key?,
    val humanoidLeggings: Key?,
)

/** Contenu validé d'un fichier d'items : items et sets d'équipement. */
class ParsedItemFile(
    val items: List<ItemDefinitionSpec>,
    val equipment: List<EquipmentSetSpec>,
)

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
