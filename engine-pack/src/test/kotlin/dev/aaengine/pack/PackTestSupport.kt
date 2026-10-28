package dev.aaengine.pack

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Aides communes : PNG minimal, écriture de sources, zips externes. */
object PackTestSupport {
    /** En-tête PNG valide (signature + IHDR) de [w]×[h] ; suffisant pour le validateur (aucun décodage). */
    fun png(
        w: Int = 16,
        h: Int = 16,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream()
        ihdr.write("IHDR".toByteArray())
        be32(ihdr, w)
        be32(ihdr, h)
        ihdr.write(byteArrayOf(8, 6, 0, 0, 0))
        be32(out, 13)
        out.write(ihdr.toByteArray())
        be32(out, CRC32().apply { update(ihdr.toByteArray()) }.value.toInt())
        return out.toByteArray()
    }

    fun write(
        root: Path,
        relative: String,
        bytes: ByteArray,
    ): Path {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        return target
    }

    fun write(
        root: Path,
        relative: String,
        text: String,
    ): Path = write(root, relative, text.toByteArray())

    fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun id(raw: String): ResourceId = ResourceId.parse(raw)!!

    private fun be32(
        out: ByteArrayOutputStream,
        v: Int,
    ) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
