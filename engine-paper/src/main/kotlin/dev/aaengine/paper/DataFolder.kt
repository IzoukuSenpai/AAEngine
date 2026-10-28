package dev.aaengine.paper

import dev.aaengine.core.io.BoundedFiles
import dev.aaengine.core.io.SafePaths
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Dossier de données `plugins/AAEngine/` (§50). Tous les chemins sont résolus sous la racine
 * ([SafePaths]) ; les fichiers par défaut sont créés en `CREATE_NEW` : jamais d'écrasement, jamais
 * d'écriture à travers un lien symbolique.
 */
class DataFolder(
    root: Path,
) {
    val root: Path = root.toAbsolutePath().normalize()

    val configFile: Path
        get() = SafePaths.resolve(root, CONFIG)

    val itemsDir: Path
        get() = SafePaths.resolve(root, ITEMS)

    /**
     * Crée la racine, `config.yml` s'il est absent et `items/` s'il n'existe pas. Le fichier d'exemple n'est
     * copié qu'au premier démarrage (`config.yml` absent au même appel) : un `items/` supprimé ensuite est
     * recréé vide. Renvoie les chemins relatifs créés.
     *
     * @param resources accès aux ressources du jar du plugin
     */
    fun ensureDefaults(resources: (String) -> InputStream?): List<String> {
        val created = ArrayList<String>()
        Files.createDirectories(root)
        val config = configFile
        val firstStart = !Files.exists(config, LinkOption.NOFOLLOW_LINKS)
        if (firstStart && copyResource(resources, CONFIG_RESOURCE, config)) {
            created.add(CONFIG)
        }
        val items = itemsDir
        if (!Files.exists(items, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(items)
            created.add("$ITEMS/")
            if (firstStart) {
                val example = SafePaths.resolve(items, EXAMPLE)
                if (copyResource(resources, EXAMPLE_RESOURCE, example)) created.add("$ITEMS/$EXAMPLE")
            }
        }
        return created
    }

    /**
     * Crée `pack/assets/` et `pack/external/` s'ils sont absents (jamais à travers un lien). Si
     * [withExample] (premier démarrage) et que `pack/assets/` vient d'être créé, y copie la texture de
     * l'item d'exemple. Renvoie les chemins relatifs créés.
     */
    fun ensurePackDirs(
        resources: (String) -> InputStream?,
        withExample: Boolean,
    ): List<String> {
        val created = ArrayList<String>()
        for (relative in listOf(PACK_ASSETS, PACK_EXTERNAL)) {
            val dir = SafePaths.resolve(root, relative)
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) continue
            Files.createDirectories(dir)
            SafePaths.requireNoSymlink(root, dir)
            created.add("$relative/")
            if (relative == PACK_ASSETS && withExample) {
                val texture = SafePaths.resolve(root, "$PACK_ASSETS/$EXAMPLE_TEXTURE")
                Files.createDirectories(texture.parent)
                SafePaths.requireNoSymlink(root, texture.parent)
                if (copyResource(resources, "$DEFAULTS_PACK/$EXAMPLE_TEXTURE", texture)) created.add("$PACK_ASSETS/$EXAMPLE_TEXTURE")
            }
        }
        return created
    }

    /** Contenu de `config.yml` (borné), ou `null` s'il est absent. */
    fun readConfig(): String? {
        val config = configFile
        if (!Files.exists(config, LinkOption.NOFOLLOW_LINKS)) return null
        return BoundedFiles.readUtf8(config, MAX_CONFIG_BYTES)
    }

    private fun copyResource(
        resources: (String) -> InputStream?,
        resource: String,
        target: Path,
    ): Boolean {
        val input = resources(resource) ?: throw IOException("Ressource absente du jar : $resource")
        input.use { source ->
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { source.copyTo(it) }
        }
        return true
    }

    companion object {
        const val CONFIG: String = "config.yml"
        const val ITEMS: String = "items"
        const val EXAMPLE: String = "exemple.yml"
        const val PACK_ASSETS: String = "pack/assets"
        const val PACK_EXTERNAL: String = "pack/external"
        const val EXAMPLE_TEXTURE: String = "aaengine/textures/item/forest_axe.png"
        const val DEFAULTS_PACK: String = "defaults/pack/assets"
        const val CONFIG_RESOURCE: String = "config.yml"
        const val EXAMPLE_RESOURCE: String = "defaults/items/exemple.yml"

        /** Taille maximale de `config.yml`. */
        const val MAX_CONFIG_BYTES: Int = 256 * 1024
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
