package dev.aaengine.core.io

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Politique de chemins (§51) : tout chemin issu de la configuration est résolu sous une racine autorisée,
 * sans chemin absolu, sans remontée hors de la racine et sans suivre de lien symbolique. Les jonctions et
 * autres points de réparation NTFS (que Java ne considère pas comme des liens) sont traités comme des liens.
 */
object SafePaths {
    /** Le chemin demandé sort de la zone autorisée ou traverse un lien symbolique. */
    class UnsafePathException(
        message: String,
    ) : SecurityException(message)

    /**
     * Résout [relative] sous [root]. Refuse les chemins vides, absolus, avec octet nul, sortant de la racine
     * après normalisation, ainsi que tout composant existant qui serait un lien symbolique.
     */
    @JvmStatic
    fun resolve(
        root: Path,
        relative: String,
    ): Path {
        if (relative.isBlank() || relative.indexOf('\u0000') >= 0) {
            throw UnsafePathException("Chemin vide ou invalide")
        }
        val base = root.toAbsolutePath().normalize()
        val rel =
            try {
                base.fileSystem.getPath(relative)
            } catch (_: InvalidPathException) {
                throw UnsafePathException("Chemin invalide")
            }
        if (rel.isAbsolute || rel.root != null) {
            throw UnsafePathException("Chemin absolu refusé")
        }
        val result = base.resolve(rel).normalize()
        if (result == base || !result.startsWith(base)) {
            throw UnsafePathException("Chemin hors du dossier autorisé")
        }
        requireNoSymlink(base, result)
        return result
    }

    /**
     * Vrai si [path] est un lien symbolique ou un « autre » objet (jonction NTFS, point de réparation, fichier
     * spécial), lu sans suivre de lien. Faux s'il n'existe pas ; une erreur d'accès est propagée.
     */
    @JvmStatic
    fun isLinkLike(path: Path): Boolean {
        val attrs =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return false
            }
        return attrs.isSymbolicLink || attrs.isOther
    }

    /**
     * Vérifie qu'aucun composant existant entre [base] (exclu) et [target] (inclus) n'est un lien symbolique
     * ou une jonction ([isLinkLike]). Une erreur d'accès aux attributs est un refus (fail-closed).
     */
    @JvmStatic
    fun requireNoSymlink(
        base: Path,
        target: Path,
    ) {
        val b = base.toAbsolutePath().normalize()
        val t = target.toAbsolutePath().normalize()
        if (!t.startsWith(b)) throw UnsafePathException("Chemin hors du dossier autorisé")
        var current = b
        for (part in b.relativize(t)) {
            current = current.resolve(part)
            val linkLike =
                try {
                    isLinkLike(current)
                } catch (_: IOException) {
                    throw UnsafePathException("Attributs illisibles : ${display(b, current)}")
                }
            if (linkLike) {
                throw UnsafePathException("Lien symbolique ou jonction refusé : ${display(b, current)}")
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return
        }
    }

    /**
     * Défense en profondeur après résolution : le chemin réel de [target] (liens résolus par le système) doit
     * rester sous le chemin réel de [root]. Lève [UnsafePathException] sinon, [IOException] si absent.
     */
    @JvmStatic
    fun requireRealPathUnder(
        root: Path,
        target: Path,
    ) {
        if (!target.toRealPath().startsWith(root.toRealPath())) {
            throw UnsafePathException("Chemin réel hors du dossier autorisé")
        }
    }

    /** Chemin relatif lisible (séparateur `/`), pour les diagnostics. */
    @JvmStatic
    fun display(
        root: Path,
        file: Path,
    ): String =
        root
            .toAbsolutePath()
            .normalize()
            .relativize(file.toAbsolutePath().normalize())
            .joinToString("/")
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
