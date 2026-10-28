package dev.aaengine.pack.source

import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.core.io.BoundedFiles
import dev.aaengine.core.io.SafePaths
import dev.aaengine.pack.PackCodes
import dev.aaengine.pack.PackFile
import dev.aaengine.pack.PackLimits
import dev.aaengine.pack.ResourceId
import dev.aaengine.pack.zip.SafeZipReader
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/**
 * Lecture des sources du pack, toutes en mémoire et bornées :
 * - `pack/external/<nom>.zip` (ordre alphabétique) via [SafeZipReader] : une archive non conforme est refusée en
 *   entier (ERROR) ; une entrée hors `assets/`, d'extension ou de chemin non conforme est ignorée (WARN) ;
 * - `pack/assets/…` copié tel quel : tout fichier non conforme (extension, chemin, lien, fichier spécial)
 *   est une erreur (l'administrateur contrôle ce dossier).
 *
 * Ordre de sortie : archives puis dossier, chacun trié par chemin.
 */
class PackSourceLoader(
    private val limits: PackLimits = PackLimits.DEFAULT,
) {
    private enum class Policy { STRICT, LENIENT }

    private class Budget(
        val limits: PackLimits,
    ) {
        var bytes = 0L
        var files = 0
        var exceeded = false
    }

    fun load(
        assetsDir: Path,
        externalDir: Path,
        diagnostics: DiagnosticCollector,
    ): List<PackFile> {
        val budget = Budget(limits)
        val result = ArrayList<PackFile>()
        result.addAll(loadExternal(externalDir, budget, diagnostics))
        result.addAll(loadAssets(assetsDir, budget, diagnostics))
        return result
    }

    private fun loadExternal(
        dir: Path,
        budget: Budget,
        diagnostics: DiagnosticCollector,
    ): List<PackFile> {
        if (!directoryOk(dir, EXTERNAL_LABEL, diagnostics)) return emptyList()
        val zips = ArrayList<Path>()
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (p in stream) {
                    val name = p.fileName.toString()
                    if (name.startsWith('.')) continue
                    val attrs = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    val label = "$EXTERNAL_LABEL/$name"
                    if (attrs.isSymbolicLink || attrs.isOther) {
                        error(diagnostics, label, PackCodes.REFUSED_FILE, "Lien symbolique, jonction ou fichier spécial refusé")
                        continue
                    }
                    if (!attrs.isRegularFile || !name.lowercase(Locale.ROOT).endsWith(".zip")) {
                        diagnostics.add(
                            Diagnostic(
                                Severity.WARN,
                                label,
                                null,
                                "Ignoré : seules les archives .zip sont lues",
                                code = PackCodes.SKIPPED_ENTRY,
                            ),
                        )
                        continue
                    }
                    if (zips.size >= limits.maxExternalZips) {
                        error(diagnostics, EXTERNAL_LABEL, PackCodes.LIMIT, "Trop d'archives externes (max ${limits.maxExternalZips})")
                        break
                    }
                    zips.add(p)
                }
            }
        } catch (e: IOException) {
            error(diagnostics, EXTERNAL_LABEL, PackCodes.REFUSED_FILE, "Lecture du dossier impossible (${e.javaClass.simpleName})")
            return emptyList()
        }
        val result = ArrayList<PackFile>()
        val reader = SafeZipReader(limits.zip)
        for (zip in zips.sortedBy { it.fileName.toString() }) {
            val label = "$EXTERNAL_LABEL/${zip.fileName}"
            val bytes = readFile(dir, zip, limits.zip.maxArchiveBytes, label, diagnostics) ?: continue
            val entries =
                try {
                    reader.read(bytes)
                } catch (e: SafeZipReader.ZipRejectedException) {
                    error(diagnostics, label, PackCodes.UNSAFE_ZIP, "Archive refusée : ${e.message}")
                    continue
                }
            for (entry in entries) {
                if (entry.name == "pack.mcmeta" || entry.name == "pack.png") {
                    diagnostics.add(Diagnostic(Severity.INFO, label, entry.name, "Ignoré : pack.mcmeta est généré par AAEngine"))
                    continue
                }
                if (!entry.name.startsWith("assets/")) {
                    diagnostics.add(
                        Diagnostic(Severity.WARN, label, entry.name, "Entrée hors assets/ ignorée", code = PackCodes.SKIPPED_ENTRY),
                    )
                    continue
                }
                if (!accept(entry.name, label, Policy.LENIENT, diagnostics)) continue
                if (!charge(budget, entry.data.size.toLong(), label, diagnostics)) return result
                result.add(PackFile(entry.name, entry.data, label))
            }
        }
        return result
    }

    private fun loadAssets(
        dir: Path,
        budget: Budget,
        diagnostics: DiagnosticCollector,
    ): List<PackFile> {
        if (!directoryOk(dir, ASSETS_LABEL, diagnostics)) return emptyList()
        val root = dir.toAbsolutePath().normalize()
        val found = ArrayList<Path>()
        var entries = 0
        val visitor =
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    d: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isSymbolicLink || attrs.isOther) {
                        error(diagnostics, label(d), PackCodes.REFUSED_FILE, "Lien symbolique, jonction ou dossier spécial refusé")
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (d == root) return FileVisitResult.CONTINUE
                    if (!count()) return FileVisitResult.TERMINATE
                    return if (hidden(d)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    f: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!count()) return FileVisitResult.TERMINATE
                    if (hidden(f)) return FileVisitResult.CONTINUE
                    if (attrs.isSymbolicLink || attrs.isOther || !(attrs.isRegularFile || attrs.isDirectory)) {
                        error(diagnostics, label(f), PackCodes.REFUSED_FILE, "Lien symbolique ou fichier spécial refusé")
                        return FileVisitResult.CONTINUE
                    }
                    if (attrs.isDirectory) {
                        error(diagnostics, label(f), PackCodes.LIMIT, "Profondeur maximale (${limits.maxSourceDepth}) atteinte")
                        return FileVisitResult.CONTINUE
                    }
                    if (found.size >= limits.maxSourceFiles) {
                        error(diagnostics, ASSETS_LABEL, PackCodes.LIMIT, "Trop de fichiers (max ${limits.maxSourceFiles})")
                        return FileVisitResult.TERMINATE
                    }
                    found.add(f)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    f: Path,
                    exc: IOException,
                ): FileVisitResult {
                    error(diagnostics, label(f), PackCodes.REFUSED_FILE, "Lecture impossible (${exc.javaClass.simpleName})")
                    return FileVisitResult.CONTINUE
                }

                private fun count(): Boolean {
                    if (++entries <= limits.maxSourceEntries) return true
                    error(diagnostics, ASSETS_LABEL, PackCodes.LIMIT, "Trop d'entrées (max ${limits.maxSourceEntries})")
                    return false
                }

                private fun label(p: Path) = "$ASSETS_LABEL/" + SafePaths.display(root, p)
            }
        try {
            Files.walkFileTree(root, emptySet(), limits.maxSourceDepth, visitor)
        } catch (e: IOException) {
            error(diagnostics, ASSETS_LABEL, PackCodes.REFUSED_FILE, "Parcours impossible (${e.javaClass.simpleName})")
            return emptyList()
        }
        val result = ArrayList<PackFile>()
        val relative = found.filter { it.toAbsolutePath().normalize().startsWith(root) }.map { SafePaths.display(root, it) to it }
        for ((rel, file) in relative.sortedBy { it.first }) {
            val packPath = "assets/$rel"
            val label = "$ASSETS_LABEL/$rel"
            if (!accept(packPath, label, Policy.STRICT, diagnostics)) continue
            // Résolution revérifiée sous la racine (§51), sans lien.
            val resolved =
                try {
                    SafePaths.resolve(root, rel)
                } catch (e: SafePaths.UnsafePathException) {
                    error(diagnostics, label, PackCodes.REFUSED_FILE, e.message ?: "Chemin refusé")
                    continue
                }
            val bytes = readFile(root, resolved, limits.maxFileBytes, label, diagnostics) ?: continue
            if (!charge(budget, bytes.size.toLong(), label, diagnostics)) break
            result.add(PackFile(packPath, bytes, ASSETS_LABEL))
        }
        return result
    }

    /** Contrôle d'un chemin de pack : nom sûr, `assets/<ns>/…` en minuscules, extension autorisée. */
    private fun accept(
        packPath: String,
        label: String,
        policy: Policy,
        diagnostics: DiagnosticCollector,
    ): Boolean {
        val problem = pathProblem(packPath)
        if (problem == null) return true
        if (policy == Policy.STRICT) {
            error(diagnostics, label, PackCodes.REFUSED_FILE, problem)
        } else {
            diagnostics.add(Diagnostic(Severity.WARN, label, packPath, "Entrée ignorée : $problem", code = PackCodes.SKIPPED_ENTRY))
        }
        return false
    }

    private fun charge(
        budget: Budget,
        size: Long,
        label: String,
        diagnostics: DiagnosticCollector,
    ): Boolean {
        budget.bytes += size
        budget.files++
        if (budget.bytes <= limits.maxTotalBytes && budget.files <= limits.maxSourceFiles) return true
        if (!budget.exceeded) {
            error(
                diagnostics,
                label,
                PackCodes.LIMIT,
                "Volume des sources trop important (max ${limits.maxTotalBytes / (1024 * 1024)} Mio, ${limits.maxSourceFiles} fichiers)",
            )
        }
        budget.exceeded = true
        return false
    }

    private fun readFile(
        root: Path,
        file: Path,
        max: Int,
        label: String,
        diagnostics: DiagnosticCollector,
    ): ByteArray? =
        try {
            SafePaths.requireNoSymlink(root, file)
            BoundedFiles.readBytes(file, max, allowedRoot = root)
        } catch (_: BoundedFiles.TooLargeException) {
            error(diagnostics, label, PackCodes.LIMIT, "Fichier trop volumineux (max ${max / (1024 * 1024)} Mio)")
            null
        } catch (e: SafePaths.UnsafePathException) {
            error(diagnostics, label, PackCodes.REFUSED_FILE, e.message ?: "Chemin refusé")
            null
        } catch (e: IOException) {
            error(diagnostics, label, PackCodes.REFUSED_FILE, "Lecture impossible (${e.javaClass.simpleName})")
            null
        }

    /** Dossier absent : aucune source ; lien ou jonction : refusé. */
    private fun directoryOk(
        dir: Path,
        label: String,
        diagnostics: DiagnosticCollector,
    ): Boolean {
        val attrs =
            try {
                Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                return false
            }
        if (attrs.isSymbolicLink || attrs.isOther) {
            error(diagnostics, "$label/", PackCodes.REFUSED_FILE, "Le dossier ne doit pas être un lien symbolique ni une jonction")
            return false
        }
        return attrs.isDirectory
    }

    private fun error(
        diagnostics: DiagnosticCollector,
        file: String,
        code: String,
        message: String,
    ) = diagnostics.add(Diagnostic(Severity.ERROR, file, null, message, code = code))

    companion object {
        const val ASSETS_LABEL: String = "pack/assets"
        const val EXTERNAL_LABEL: String = "pack/external"

        /** Extensions copiées dans le pack (`txt` : textes et langues au format texte). */
        @JvmField
        val ALLOWED_EXTENSIONS: Set<String> = setOf("png", "mcmeta", "json", "ogg", "ttf", "otf", "bin", "txt")

        private val SEGMENT = Regex("[a-z0-9_.-]+")

        /**
         * Problème d'un chemin de pack ([SafeZipReader.nameProblem], puis `assets/<namespace>/<fichier>` en
         * minuscules `[a-z0-9_.-]`, extension de [ALLOWED_EXTENSIONS]) ou `null` s'il est accepté.
         */
        @JvmStatic
        fun pathProblem(packPath: String): String? {
            SafeZipReader.nameProblem(packPath)?.let { return it }
            val parts = packPath.split('/')
            if (parts.size < 3 || parts[0] != "assets") return "Chemin assets/<namespace>/… attendu"
            if (!ResourceId.isValidNamespace(parts[1])) return "Namespace invalide (minuscules [a-z0-9_.-])"
            if (parts.drop(2).any { !SEGMENT.matches(it) }) return "Chemin invalide : minuscules, chiffres et _ . - uniquement"
            val name = parts.last()
            val ext = name.substringAfterLast('.', "")
            if (ext.isEmpty() || ext !in ALLOWED_EXTENSIONS) {
                return "Extension non autorisée (${ALLOWED_EXTENSIONS.sorted().joinToString(", ")})"
            }
            return null
        }

        /** Fichiers système déposés par les explorateurs, ignorés comme les fichiers cachés. */
        private val SYSTEM_FILES = setOf("thumbs.db", "desktop.ini")

        private fun hidden(p: Path): Boolean {
            val name = p.fileName?.toString() ?: return false
            return name.startsWith('.') || name.lowercase(Locale.ROOT) in SYSTEM_FILES
        }
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
