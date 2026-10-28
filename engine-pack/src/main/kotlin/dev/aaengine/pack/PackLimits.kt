package dev.aaengine.pack

/** Bornes anti-abus du compilateur de pack (valeurs par défaut = constantes nommées ci-dessous). */
data class PackLimits(
    val maxSourceFiles: Int = MAX_SOURCE_FILES,
    val maxSourceEntries: Int = MAX_SOURCE_ENTRIES,
    val maxSourceDepth: Int = MAX_SOURCE_DEPTH,
    val maxFileBytes: Int = MAX_FILE_BYTES,
    val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    val maxExternalZips: Int = MAX_EXTERNAL_ZIPS,
    val zip: ZipLimits = ZipLimits(),
    val maxTextureSide: Int = MAX_TEXTURE_SIDE,
    val maxOutputBytes: Long = PackFormat.MAX_CLIENT_PACK_BYTES,
) {
    companion object {
        /** Fichiers retenus au plus sous `pack/assets/`. */
        const val MAX_SOURCE_FILES: Int = 20_000

        /** Entrées parcourues au plus sous `pack/assets/` (fichiers et dossiers). */
        const val MAX_SOURCE_ENTRIES: Int = 50_000

        /** Profondeur maximale sous `pack/assets/`. */
        const val MAX_SOURCE_DEPTH: Int = 24

        /** Taille maximale d'un fichier (disque ou entrée d'archive décompressée). */
        const val MAX_FILE_BYTES: Int = 16 * 1024 * 1024

        /** Volume cumulé maximal de toutes les sources en mémoire. */
        const val MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024

        /** Archives au plus dans `pack/external/`. */
        const val MAX_EXTERNAL_ZIPS: Int = 32

        /** Côté maximal d'une texture PNG (largeur et hauteur). */
        const val MAX_TEXTURE_SIDE: Int = 4096

        @JvmField
        val DEFAULT: PackLimits = PackLimits()
    }
}

/** Bornes de [dev.aaengine.pack.zip.SafeZipReader]. */
data class ZipLimits(
    val maxArchiveBytes: Int = MAX_ARCHIVE_BYTES,
    val maxEntries: Int = MAX_ENTRIES,
    val maxEntryBytes: Int = PackLimits.MAX_FILE_BYTES,
    val maxTotalBytes: Long = PackLimits.MAX_TOTAL_BYTES,
    val maxRatio: Int = MAX_RATIO,
    val ratioThresholdBytes: Int = RATIO_THRESHOLD_BYTES,
    val maxNameLength: Int = MAX_NAME_LENGTH,
) {
    companion object {
        /** Taille maximale d'une archive (lue entièrement en mémoire, puis analysée). */
        const val MAX_ARCHIVE_BYTES: Int = 128 * 1024 * 1024

        /** Entrées au plus par archive. */
        const val MAX_ENTRIES: Int = 20_000

        /** Ratio décompressé / compressé maximal d'une entrée. */
        const val MAX_RATIO: Int = 200

        /** Taille décompressée en dessous de laquelle le ratio n'est pas contrôlé (petits JSON très compressibles). */
        const val RATIO_THRESHOLD_BYTES: Int = 256 * 1024

        /** Longueur maximale d'un nom d'entrée. */
        const val MAX_NAME_LENGTH: Int = 512
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
