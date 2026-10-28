package dev.aaengine.pack

import dev.aaengine.pack.json.Json
import dev.aaengine.pack.json.JsonArray
import dev.aaengine.pack.json.JsonValue

/**
 * Constantes du format vanilla visé : Minecraft 26.2 (resource pack format 88.0, `version.json` du jar
 * Paper 26.2 : `resource_major` 88, `resource_minor` 0). Une seule version cible : aucun overlay.
 *
 * Les points notés [A VÉRIFIER client 26.2] dans `docs/pack.md` sont isolés ici.
 */
object PackFormat {
    /** Version majeure du format de resource pack de 26.2. */
    const val RESOURCE_MAJOR: Int = 88

    /** Version mineure du format de resource pack de 26.2. */
    const val RESOURCE_MINOR: Int = 0

    /** Description écrite dans `pack.mcmeta`. */
    const val DESCRIPTION: String = "AAEngine resource pack"

    /** Version du compilateur, incluse dans l'empreinte du cache : la changer invalide les builds en cache. */
    const val COMPILER_VERSION: String = "aaengine-pack/1"

    /** Parent du modèle généré pour un outil ou une arme tenu en main. */
    const val PARENT_HANDHELD: String = "minecraft:item/handheld"

    /** Parent du modèle généré par défaut (item plat). */
    const val PARENT_GENERATED: String = "minecraft:item/generated"

    /** Type de modèle d'une définition d'item (`items/<id>.json`). */
    const val ITEM_MODEL_TYPE: String = "minecraft:model"

    /** Dossiers de textures couverts par les atlas par défaut (pas de déclaration nécessaire). [A VÉRIFIER client 26.2] */
    @JvmField
    val DEFAULT_ATLAS_DIRECTORIES: List<String> = listOf("item/", "block/")

    /** Atlas où sont ajoutés les sprites hors des dossiers par défaut référencés par des modèles d'items. [A VÉRIFIER client 26.2] */
    const val ITEMS_ATLAS_FILE: String = "assets/minecraft/atlases/items.json"

    /** Suffixes des sprites d'un `tooltip_style` (atlas GUI, `textures/gui/sprites/tooltip/`). */
    @JvmField
    val TOOLTIP_SPRITE_SUFFIXES: List<String> = listOf("_background", "_frame")

    /** Couches d'équipement émises (dossier de texture `textures/entity/equipment/<couche>/`). */
    const val LAYER_HUMANOID: String = "humanoid"
    const val LAYER_HUMANOID_LEGGINGS: String = "humanoid_leggings"

    /** Taille maximale d'un pack servi aux clients. [A VÉRIFIER client 26.2] */
    const val MAX_CLIENT_PACK_BYTES: Long = 250L * 1024 * 1024

    /**
     * `pack.mcmeta` au schéma `min_format` / `max_format` (format ≥ 65, sans `pack_format`) : formats
     * `[majeur, mineur]`, plage réduite à 88.0 … 88.x. [A VÉRIFIER client 26.2]
     */
    @JvmStatic
    fun packMcmeta(): JsonValue =
        Json.obj(
            "pack" to
                Json.obj(
                    "description" to Json.str(DESCRIPTION),
                    "min_format" to JsonArray(listOf(Json.num(RESOURCE_MAJOR), Json.num(RESOURCE_MINOR))),
                    "max_format" to Json.num(RESOURCE_MAJOR),
                ),
        )

    /** Matériaux dont le modèle généré utilise [PARENT_HANDHELD] (outils et armes). */
    private val HANDHELD_SUFFIXES = listOf("_SWORD", "_AXE", "_PICKAXE", "_SHOVEL", "_HOE")
    private val HANDHELD_EXACT = setOf("MACE", "STICK", "BLAZE_ROD", "BREEZE_ROD", "TRIDENT")

    /** Parent par défaut du modèle généré selon le nom du matériau Bukkit (`DIAMOND_SWORD`…). */
    @JvmStatic
    fun defaultParent(materialName: String): String =
        if (materialName in HANDHELD_EXACT || HANDHELD_SUFFIXES.any { materialName.endsWith(it) }) PARENT_HANDHELD else PARENT_GENERATED
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
