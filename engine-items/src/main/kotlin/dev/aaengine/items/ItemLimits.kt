package dev.aaengine.items

/** Bornes anti-abus du chargement des items (valeurs par défaut = spécification phase 2). */
data class ItemLimits(
    val maxFiles: Int = 512,
    val maxFileBytes: Int = 1024 * 1024,
    val maxTotalBytes: Long = 64L * 1024 * 1024,
    val maxDepth: Int = 16,
    /** Entrées parcourues au plus sous `items/` (fichiers et dossiers, `*.yml` ou non). */
    val maxEntries: Int = 10_000,
    val maxItems: Int = 10_000,
    val maxLoreLines: Int = 64,
    val maxTextLength: Int = 1024,
    val maxCustomModelDataEntries: Int = 32,
    val maxCustomModelDataString: Int = 256,
) {
    companion object {
        @JvmField
        val DEFAULT: ItemLimits = ItemLimits()
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
