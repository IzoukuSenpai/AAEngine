package dev.aaengine.core.io

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Lecture de fichiers texte bornée, sans suivre de lien symbolique ni de jonction. */
object BoundedFiles {
    private const val BOM: Char = 0xFEFF.toChar()

    /** Fichier plus gros que la limite autorisée. */
    class TooLargeException(
        val limit: Long,
    ) : IOException("Fichier trop volumineux (limite : $limit octets)")

    /** Texte lu et nombre exact d'octets lus sur le disque. */
    class Text(
        val content: String,
        val bytes: Long,
    )

    /** Comme [read], contenu seul. */
    @JvmStatic
    fun readUtf8(
        file: Path,
        maxBytes: Int,
    ): String = read(file, maxBytes).content

    /**
     * Lit [file] en UTF-8 strict. La taille est contrôlée pendant la lecture (et non sur les seuls attributs)
     * pour rester sûre si le fichier change entre-temps ; le tampon est dimensionné sur la taille réelle et ne
     * grandit qu'au besoin, jusqu'à `maxBytes + 1`. Si [allowedRoot] est fourni, le chemin réel du fichier,
     * vérifié après ouverture, doit rester dessous (un dossier remplacé par un lien entre l'inventaire et la
     * lecture est refusé). Lève [TooLargeException], [CharacterCodingException] (UTF-8 invalide),
     * [SafePaths.UnsafePathException] ou [IOException] (lien, jonction, fichier spécial, erreur disque).
     */
    @JvmStatic
    fun read(
        file: Path,
        maxBytes: Int,
        allowedRoot: Path? = null,
    ): Text {
        val bytes = ByteBuffer.wrap(readBytes(file, maxBytes, allowedRoot))
        val size = bytes.remaining().toLong()
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(bytes).toString()
        // BOM UTF-8 éventuel ignoré.
        return Text(if (text.startsWith(BOM)) text.substring(1) else text, size)
    }

    /**
     * Lit les octets de [file], au plus [maxBytes], avec les mêmes garanties que [read] (taille contrôlée
     * pendant la lecture, fichier ordinaire sans lien, chemin réel sous [allowedRoot] vérifié après ouverture).
     * Lève [TooLargeException], [SafePaths.UnsafePathException] ou [IOException].
     */
    @JvmStatic
    fun readBytes(
        file: Path,
        maxBytes: Int,
        allowedRoot: Path? = null,
    ): ByteArray {
        requireRegular(file)
        return Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            // Relu après ouverture : le fichier ouvert est bien ordinaire et sous la racine autorisée.
            requireRegular(file)
            if (allowedRoot != null) SafePaths.requireRealPathUnder(allowedRoot, file)
            val limit = maxBytes.toLong() + 1
            var buffer = ByteBuffer.allocate(minOf(channel.size() + 1, limit).coerceAtLeast(1).toInt())
            while (true) {
                if (!buffer.hasRemaining()) {
                    if (buffer.capacity() >= limit) break
                    val grown = ByteBuffer.allocate(minOf(buffer.capacity().toLong() * 2, limit).toInt())
                    buffer.flip()
                    grown.put(buffer)
                    buffer = grown
                }
                if (channel.read(buffer) < 0) break
            }
            if (buffer.position() > maxBytes) throw TooLargeException(maxBytes.toLong())
            buffer.flip()
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
    }

    private fun requireRegular(file: Path) {
        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attrs.isSymbolicLink || attrs.isOther || !attrs.isRegularFile) throw IOException("Pas un fichier ordinaire")
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
