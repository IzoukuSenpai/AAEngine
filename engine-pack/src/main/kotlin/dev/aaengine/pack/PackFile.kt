package dev.aaengine.pack

import java.security.MessageDigest

/**
 * Fichier du pack en mémoire.
 *
 * @property path chemin dans le pack (`assets/aaengine/textures/item/x.png`, séparateur `/`)
 * @property origin source lisible pour les diagnostics (`pack/assets`, `pack/external/foo.zip`, `généré`)
 */
class PackFile(
    val path: String,
    bytes: ByteArray,
    val origin: String,
) {
    private val data: ByteArray = bytes.copyOf()

    val size: Int
        get() = data.size

    /** SHA-256 hexadécimal du contenu (calculé une fois). */
    val sha256: String by lazy { Hashes.hex(MessageDigest.getInstance("SHA-256").digest(data)) }

    /** Copie défensive du contenu. */
    fun bytes(): ByteArray = data.copyOf()

    /** Écrit le contenu dans [sink] sans copie. */
    internal fun writeTo(sink: java.io.OutputStream) = sink.write(data)

    internal fun text(): String = String(data, Charsets.UTF_8)

    internal fun sameContent(other: PackFile): Boolean = data.contentEquals(other.data)

    companion object {
        const val GENERATED: String = "généré"
    }
}

/** Empreintes hexadécimales. */
object Hashes {
    private val HEX = "0123456789abcdef".toCharArray()

    @JvmStatic
    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    @JvmStatic
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
