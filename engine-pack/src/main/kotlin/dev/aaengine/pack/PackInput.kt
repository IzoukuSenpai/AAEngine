package dev.aaengine.pack

import dev.aaengine.pack.json.Json
import dev.aaengine.pack.json.JsonArray
import dev.aaengine.pack.json.JsonNull
import dev.aaengine.pack.json.JsonValue

/**
 * Entrées du compilateur issues des fichiers d'items (aucun type serveur : module pur).
 *
 * @property source fichier YAML d'origine (`items/armes.yml`), pour les diagnostics
 * @property nodePath chemin YAML de l'item (`items.ruby_sword`)
 * @property itemModel composant `item_model` effectif de l'item (définition `items/<chemin>.json` attendue)
 * @property equipmentAsset `components.equippable.asset`
 */
data class PackItem(
    val id: ResourceId,
    val source: String,
    val nodePath: String,
    val model: PackItemModel?,
    val itemModel: ResourceId?,
    val tooltipStyle: ResourceId? = null,
    val equipmentAsset: ResourceId? = null,
)

/** Modèle émis pour un item. */
sealed interface PackItemModel {
    /** Modèle 2D généré : `models/item/<id>.json` (parent + calques) et `items/<id>.json`. */
    data class Generated(
        val layers: List<ResourceId>,
        val parent: ResourceId,
    ) : PackItemModel

    /** Modèle fourni par l'utilisateur : seule la définition `items/<id>.json` est générée. */
    data class Provided(
        val model: ResourceId,
    ) : PackItemModel
}

/** Set d'équipement : `equipment/<id>.json` avec les couches présentes. */
data class PackEquipment(
    val id: ResourceId,
    val source: String,
    val nodePath: String,
    val humanoid: ResourceId?,
    val humanoidLeggings: ResourceId?,
)

/** Toutes les entrées issues des items, triées pour une empreinte stable. */
class PackInput(
    items: List<PackItem>,
    equipment: List<PackEquipment>,
) {
    val items: List<PackItem> = items.sortedBy { it.id }
    val equipment: List<PackEquipment> = equipment.sortedBy { it.id }

    /** Forme canonique (JSON) : base de l'empreinte du cache, indépendante de l'ordre des fichiers YAML. */
    fun canonical(): String =
        Json.write(
            Json.obj(
                "items" to JsonArray(items.map(::itemJson)),
                "equipment" to
                    JsonArray(
                        equipment.map {
                            Json.obj(
                                "id" to Json.str(it.id.asString()),
                                "humanoid" to opt(it.humanoid),
                                "humanoid_leggings" to opt(it.humanoidLeggings),
                            )
                        },
                    ),
            ),
        )

    private fun itemJson(item: PackItem): JsonValue {
        val model: JsonValue =
            when (val m = item.model) {
                null -> JsonNull
                is PackItemModel.Generated ->
                    Json.obj("layers" to JsonArray(m.layers.map { Json.str(it.asString()) }), "parent" to Json.str(m.parent.asString()))
                is PackItemModel.Provided -> Json.obj("model" to Json.str(m.model.asString()))
            }
        return Json.obj(
            "id" to Json.str(item.id.asString()),
            "model" to model,
            "item_model" to opt(item.itemModel),
            "tooltip_style" to opt(item.tooltipStyle),
            "equipment" to opt(item.equipmentAsset),
        )
    }

    private fun opt(id: ResourceId?): JsonValue = id?.let { Json.str(it.asString()) } ?: JsonNull

    companion object {
        @JvmField
        val EMPTY: PackInput = PackInput(emptyList(), emptyList())
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
