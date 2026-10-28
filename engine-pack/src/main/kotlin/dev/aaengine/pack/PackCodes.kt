package dev.aaengine.pack

/** Codes stables des diagnostics du pack (documentés dans `docs/pack.md`). */
object PackCodes {
    /** JSON ou `.mcmeta` illisible. */
    const val INVALID_JSON: String = "PACK-E101"

    /** Définition d'item (`items/<nom>.json`) ou `item_model` vers un modèle / une définition absent. */
    const val MISSING_MODEL: String = "PACK-E102"

    /** Parent de modèle absent. */
    const val MISSING_PARENT: String = "PACK-E103"

    /** Texture référencée absente. */
    const val MISSING_TEXTURE: String = "PACK-E104"

    /** Sprite de `tooltip_style` absent. */
    const val MISSING_TOOLTIP: String = "PACK-E105"

    /** Set d'équipement (`equipment/<set>.json`) absent. */
    const val MISSING_EQUIPMENT: String = "PACK-E106"

    /** Texture d'équipement absente. */
    const val MISSING_EQUIPMENT_TEXTURE: String = "PACK-E107"

    /** Conflit de fusion : même chemin, contenus différents. */
    const val MERGE_CONFLICT: String = "PACK-E108"

    /** Fichier source refusé (extension, chemin, lien, fichier spécial). */
    const val REFUSED_FILE: String = "PACK-E109"

    /** Archive externe refusée par le lecteur strict. */
    const val UNSAFE_ZIP: String = "PACK-E110"

    /** Borne dépassée (nombre, taille, volume). */
    const val LIMIT: String = "PACK-E111"

    /** Structure invalide (définition d'item, modèle, atlas, équipement). */
    const val INVALID_STRUCTURE: String = "PACK-E112"

    /** PNG invalide ou trop grand. */
    const val INVALID_PNG: String = "PACK-E113"

    /** Écriture du pack ou du cache impossible. */
    const val WRITE_FAILED: String = "PACK-E114"

    /** Cycle dans la chaîne de parents d'un modèle. */
    const val PARENT_CYCLE: String = "PACK-E115"

    /** Variable de texture `#nom` utilisée mais jamais définie dans la chaîne de parents. */
    const val UNDEFINED_TEXTURE_VARIABLE: String = "PACK-E116"

    /** Entrée d'archive externe ignorée (hors `assets/`, extension ou chemin non conforme). */
    const val SKIPPED_ENTRY: String = "PACK-W201"

    /** Sprite de `tooltip_style` sans `.mcmeta` (`nine_slice` conseillé). */
    const val TOOLTIP_NO_MCMETA: String = "PACK-W202"

    /** Plus de calques que le modèle `item/generated` n'en accepte : les suivants sont ignorés. */
    const val TOO_MANY_LAYERS: String = "PACK-W203"

    /** Référence vanilla (`minecraft:`) non vérifiable sans les assets du client. */
    const val VANILLA_REFERENCE: String = "PACK-I301"

    /** Sprites ajoutés aux atlas. */
    const val ATLAS_SPRITES: String = "PACK-I302"

    /** Bilan du build. */
    const val SUMMARY: String = "PACK-I303"
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
