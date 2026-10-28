package dev.aaengine.items

import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.io.SafePaths
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/**
 * Inventaire sûr des fichiers `*.yml` sous `items/` (§51) : parcours sans suivre de lien, profondeur, nombre
 * de fichiers et nombre total d'entrées bornés, tout lien symbolique, jonction NTFS ou fichier spécial est une
 * erreur (fail-closed), fichiers
 * et dossiers cachés (`.xxx`) ignorés. Ordre de sortie stable (chemin relatif).
 */
object ItemSourceScanner {
    /** Fichier retenu : chemin absolu vérifié et chemin affichable relatif au dossier de données. */
    class Source(
        val path: Path,
        val displayPath: String,
    )

    /**
     * @param itemsDir dossier `items/`
     * @param displayPrefix préfixe des chemins affichés (ex. `items/`)
     */
    @JvmStatic
    fun scan(
        itemsDir: Path,
        displayPrefix: String,
        limits: ItemLimits,
        diagnostics: DiagnosticCollector,
    ): List<Source> {
        val root = itemsDir.toAbsolutePath().normalize()
        val rootLabel = displayPrefix.trimEnd('/').ifEmpty { "." } + "/"
        // Racine lue sans suivre de lien : lien symbolique et jonction NTFS (isOther) refusés.
        val rootAttrs =
            try {
                Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                diagnostics.error(rootLabel, null, "Dossier des items introuvable")
                return emptyList()
            }
        if (rootAttrs.isSymbolicLink || rootAttrs.isOther) {
            diagnostics.error(rootLabel, null, "Le dossier des items ne doit pas être un lien symbolique ni une jonction")
            return emptyList()
        }
        if (!rootAttrs.isDirectory) {
            diagnostics.error(rootLabel, null, "Dossier des items introuvable")
            return emptyList()
        }
        val found = ArrayList<Path>()
        var entries = 0
        val visitor =
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // Contrôlé aussi pour la racine (jonction remplacée entre-temps).
                    if (attrs.isSymbolicLink || attrs.isOther) {
                        diagnostics.error(label(dir), null, "Lien symbolique, jonction ou dossier spécial refusé")
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (dir == root) return FileVisitResult.CONTINUE
                    if (!countEntry()) return FileVisitResult.TERMINATE
                    return if (isHidden(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!countEntry()) return FileVisitResult.TERMINATE
                    if (isHidden(file)) return FileVisitResult.CONTINUE
                    if (attrs.isSymbolicLink || attrs.isOther) {
                        diagnostics.error(label(file), null, "Lien symbolique ou fichier spécial refusé")
                        return FileVisitResult.CONTINUE
                    }
                    if (attrs.isDirectory) {
                        diagnostics.warn(label(file), null, "Dossier ignoré : profondeur maximale (${limits.maxDepth}) atteinte")
                        return FileVisitResult.CONTINUE
                    }
                    if (!attrs.isRegularFile ||
                        !file.fileName
                            .toString()
                            .lowercase(Locale.ROOT)
                            .endsWith(".yml")
                    ) {
                        return FileVisitResult.CONTINUE
                    }
                    if (found.size >= limits.maxFiles) {
                        diagnostics.error(rootLabel, null, "Trop de fichiers d'items (max ${limits.maxFiles})")
                        return FileVisitResult.TERMINATE
                    }
                    found.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    diagnostics.error(label(file), null, "Lecture impossible (${exc.javaClass.simpleName})")
                    return FileVisitResult.CONTINUE
                }

                /** Toute entrée (fichier, dossier, autre) compte : un arbre géant n'est jamais parcouru en entier. */
                private fun countEntry(): Boolean {
                    if (++entries <= limits.maxEntries) return true
                    diagnostics.error(rootLabel, null, "Trop d'entrées dans le dossier des items (max ${limits.maxEntries})")
                    return false
                }

                private fun label(p: Path) = displayPrefix + SafePaths.display(root, p)
            }
        try {
            Files.walkFileTree(root, emptySet(), limits.maxDepth, visitor)
        } catch (e: IOException) {
            diagnostics.error(rootLabel, null, "Parcours du dossier impossible (${e.javaClass.simpleName})")
            return emptyList()
        }
        return found
            .filter { it.toAbsolutePath().normalize().startsWith(root) }
            .map { Source(it, displayPrefix + SafePaths.display(root, it)) }
            .sortedBy { it.displayPath }
    }

    private fun isHidden(p: Path): Boolean = p.fileName?.toString()?.startsWith('.') == true
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
