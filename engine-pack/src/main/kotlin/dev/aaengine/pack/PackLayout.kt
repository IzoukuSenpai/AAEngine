package dev.aaengine.pack

import dev.aaengine.core.io.SafePaths
import java.nio.file.Path

/**
 * Emplacements du pack sous le dossier de données (§50), tous résolus par [SafePaths] (§51) :
 *
 * ```text
 * plugins/AAEngine/
 * ├─ pack/assets/…        sources copiées telles quelles
 * ├─ pack/external/<nom>.zip   packs externes (lecture stricte)
 * └─ cache/pack/           sorties : aaengine-pack.zip + manifest.json (générés, jamais à éditer)
 * ```
 */
class PackLayout(
    dataRoot: Path,
) {
    val root: Path = dataRoot.toAbsolutePath().normalize()

    val assetsDir: Path
        get() = SafePaths.resolve(root, ASSETS)

    val externalDir: Path
        get() = SafePaths.resolve(root, EXTERNAL)

    val cacheDir: Path
        get() = SafePaths.resolve(root, CACHE)

    val zipFile: Path
        get() = SafePaths.resolve(root, "$CACHE/$ZIP_NAME")

    val manifestFile: Path
        get() = SafePaths.resolve(root, "$CACHE/$MANIFEST_NAME")

    companion object {
        const val ASSETS: String = "pack/assets"
        const val EXTERNAL: String = "pack/external"
        const val CACHE: String = "cache/pack"
        const val ZIP_NAME: String = "aaengine-pack.zip"
        const val MANIFEST_NAME: String = "manifest.json"
    }
}

/**
 * Pack construit (ou en cache à jour).
 *
 * @property sha1 SHA-1 hexadécimal (minuscules) annoncé aux clients
 */
data class BuiltPack(
    val file: Path,
    val size: Long,
    val sha1: String,
    val sha256: String,
    val fileCount: Int,
)

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
