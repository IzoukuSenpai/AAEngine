package dev.aaengine.pack.zip

import dev.aaengine.pack.Hashes
import dev.aaengine.pack.PackFile
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Écrivain de zip reproductible : entrées triées par chemin, date DOS fixe (sans champ de date étendu ni
 * fuseau horaire), aucun dossier, aucun commentaire, compression deflate au niveau maximal. Deux builds des
 * mêmes fichiers produisent les mêmes octets, donc les mêmes SHA-1 / SHA-256 (cache client et CDN).
 */
object DeterministicZipWriter {
    /** Date écrite pour toutes les entrées. */
    @JvmField
    val FIXED_TIME: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)

    /** Empreintes et taille de l'archive écrite. */
    class Digest(
        val size: Long,
        val sha1: String,
        val sha256: String,
    )

    /** Écrit [files] dans [out] (non fermé) et renvoie les empreintes des octets écrits. */
    @JvmStatic
    fun write(
        files: Collection<PackFile>,
        out: OutputStream,
    ): Digest {
        val hashing = HashingOutputStream(out)
        val zip = ZipOutputStream(NonClosing(hashing), Charsets.UTF_8)
        zip.setMethod(ZipOutputStream.DEFLATED)
        zip.setLevel(Deflater.BEST_COMPRESSION)
        for (file in files.sortedBy { it.path }) {
            val entry = ZipEntry(file.path)
            entry.setTimeLocal(FIXED_TIME)
            zip.putNextEntry(entry)
            file.writeTo(zip)
            zip.closeEntry()
        }
        zip.finish()
        zip.close()
        return Digest(hashing.count, Hashes.hex(hashing.sha1.digest()), Hashes.hex(hashing.sha256.digest()))
    }

    private class HashingOutputStream(
        out: OutputStream,
    ) : FilterOutputStream(out) {
        val sha1: MessageDigest = MessageDigest.getInstance("SHA-1")
        val sha256: MessageDigest = MessageDigest.getInstance("SHA-256")
        var count = 0L

        override fun write(b: Int) {
            out.write(b)
            sha1.update(b.toByte())
            sha256.update(b.toByte())
            count++
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            out.write(b, off, len)
            sha1.update(b, off, len)
            sha256.update(b, off, len)
            count += len
        }
    }

    /** Laisse le flux de l'appelant ouvert à la fermeture du zip. */
    private class NonClosing(
        out: OutputStream,
    ) : FilterOutputStream(out) {
        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = out.write(b, off, len)

        override fun close() = out.flush()
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
