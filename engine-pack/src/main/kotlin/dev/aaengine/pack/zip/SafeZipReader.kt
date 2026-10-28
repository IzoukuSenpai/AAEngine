package dev.aaengine.pack.zip

import dev.aaengine.pack.ZipLimits
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Lecteur zip strict pour les packs externes (WP0, `docs/itemsadder-compat.md` §3.2, spec §18.3-18.4).
 * L'archive est entièrement en mémoire (bornée) : aucune extraction sur disque, rien n'est exécuté.
 *
 * Contrôles, tous actifs en permanence :
 * - fin de répertoire central exacte (commentaire compris), un seul disque, pas de ZIP64, pas de chiffrement ;
 * - noms pris dans le répertoire central et identiques octet pour octet au nom de l'en-tête local ;
 * - bornes de chaque en-tête et de chaque donnée, données avant le répertoire central, intervalles des
 *   entrées disjoints (bombe par chevauchement) ;
 * - inflate : consomme exactement la taille compressée et produit exactement la taille déclarée, sans jamais
 *   dépasser les plafonds (par entrée, cumulé, ratio) ; CRC32 vérifié contre le répertoire central, l'en-tête
 *   local et le data descriptor ;
 * - noms : `..`, `.`, segments vides, `/` initial, `\`, `:` (lecteur, flux NTFS), NUL et contrôles, noms
 *   réservés Windows, segments finissant par `.` ou une espace refusés ; doublons NFC + casse repliée refusés ;
 * - entrées symlink et fichiers spéciaux (attributs Unix) refusées.
 */
class SafeZipReader(
    private val limits: ZipLimits = ZipLimits(),
) {
    /** Archive refusée : [message] explique la règle violée (sans contenu de l'archive non assaini). */
    class ZipRejectedException(
        message: String,
    ) : IOException(message)

    /** Entrée fichier lue et vérifiée. */
    class Entry(
        val name: String,
        val data: ByteArray,
    )

    private class Central(
        val name: String,
        val nameStart: Int,
        val nameLength: Int,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressed: Long,
        val uncompressed: Long,
        val localOffset: Long,
        val directory: Boolean,
    )

    /** Lit [archive] ; lève [ZipRejectedException] à la première règle violée. */
    fun read(archive: ByteArray): List<Entry> {
        val n = archive.size
        if (n > limits.maxArchiveBytes) reject("Archive trop volumineuse (max ${limits.maxArchiveBytes / (1024 * 1024)} Mio)")
        if (n < EOCD_SIZE) reject("Archive trop courte")
        val eocd = findEndOfCentralDirectory(archive)
        if (eocd >= ZIP64_LOCATOR_SIZE && u32(archive, eocd - ZIP64_LOCATOR_SIZE) == SIG_ZIP64_LOCATOR) reject("ZIP64 non pris en charge")
        val disk = u16(archive, eocd + 4)
        val cdDisk = u16(archive, eocd + 6)
        val entriesOnDisk = u16(archive, eocd + 8)
        val entries = u16(archive, eocd + 10)
        val cdSize = u32(archive, eocd + 12)
        val cdOffset = u32(archive, eocd + 16)
        if (disk != 0 || cdDisk != 0 || entriesOnDisk != entries) reject("Archive multi-volumes refusée")
        if (entries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) reject("ZIP64 non pris en charge")
        if (entries > limits.maxEntries) reject("Trop d'entrées ($entries, max ${limits.maxEntries})")
        if (cdOffset + cdSize != eocd.toLong()) reject("Répertoire central mal placé ou données en trop")

        val centrals = readCentralDirectory(archive, cdOffset.toInt(), eocd, entries)
        val intervals = ArrayList<LongArray>(centrals.size)
        val dataStarts = IntArray(centrals.size)
        centrals.forEachIndexed { idx, c ->
            val (start, end) = checkLocal(archive, c, cdOffset)
            dataStarts[idx] = start
            intervals.add(longArrayOf(c.localOffset, end))
        }
        intervals.sortBy { it[0] }
        for (k in 1 until intervals.size) {
            if (intervals[k][0] < intervals[k - 1][1]) reject("Entrées qui se chevauchent")
        }

        val result = ArrayList<Entry>()
        var total = 0L
        centrals.forEachIndexed { idx, c ->
            if (c.directory) return@forEachIndexed
            val cap = minOf(limits.maxEntryBytes.toLong(), limits.maxTotalBytes - total)
            if (c.uncompressed > cap) reject(tooLarge(c.name))
            val data = extract(archive, c, dataStarts[idx], cap)
            if (data.size.toLong() != c.uncompressed) reject("Taille décompressée incohérente : ${shown(c.name)}")
            val crc = CRC32().apply { update(data) }.value
            if (crc != c.crc) reject("CRC32 incorrect : ${shown(c.name)}")
            if (data.size > limits.ratioThresholdBytes && data.size.toLong() > maxOf(c.compressed, 1L) * limits.maxRatio) {
                reject("Ratio de compression suspect (> ${limits.maxRatio}) : ${shown(c.name)}")
            }
            total += data.size
            result.add(Entry(c.name, data))
        }
        return result
    }

    private fun findEndOfCentralDirectory(a: ByteArray): Int {
        val last = a.size - EOCD_SIZE
        val first = maxOf(0, last - MAX_COMMENT)
        for (p in last downTo first) {
            if (u32(a, p) == SIG_EOCD && p + EOCD_SIZE + u16(a, p + 20) == a.size) return p
        }
        reject("Fin de répertoire central introuvable")
    }

    private fun readCentralDirectory(
        a: ByteArray,
        start: Int,
        end: Int,
        count: Int,
    ): List<Central> {
        val result = ArrayList<Central>(count)
        val seen = HashSet<String>()
        var p = start
        repeat(count) {
            if (p + CENTRAL_SIZE > end || u32(a, p) != SIG_CENTRAL) reject("Répertoire central corrompu")
            val madeBy = u16(a, p + 4)
            val flags = u16(a, p + 8)
            val method = u16(a, p + 10)
            val crc = u32(a, p + 16)
            val compressed = u32(a, p + 20)
            val uncompressed = u32(a, p + 24)
            val nameLen = u16(a, p + 28)
            val extraLen = u16(a, p + 30)
            val commentLen = u16(a, p + 32)
            val diskStart = u16(a, p + 34)
            val external = u32(a, p + 38)
            val localOffset = u32(a, p + 42)
            val nameStart = p + CENTRAL_SIZE
            val next = nameStart + nameLen + extraLen + commentLen
            if (next > end) reject("Répertoire central corrompu")
            if (flags and (FLAG_ENCRYPTED or FLAG_STRONG_ENCRYPTION or FLAG_MASKED_HEADERS) != 0) reject("Entrée chiffrée refusée")
            if (compressed == 0xFFFFFFFFL || uncompressed == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) reject("ZIP64 non pris en charge")
            if (diskStart != 0) reject("Archive multi-volumes refusée")
            val name = decodeName(a, nameStart, nameLen, flags)
            nameProblem(name, limits.maxNameLength)?.let { reject("$it : ${shown(name)}") }
            if (method != METHOD_STORED &&
                method != METHOD_DEFLATED
            ) {
                reject("Méthode de compression $method non prise en charge : ${shown(name)}")
            }
            if (madeBy ushr 8 == HOST_UNIX) {
                val mode = (external ushr 16).toInt() and UNIX_TYPE_MASK
                if (mode == UNIX_SYMLINK) reject("Lien symbolique refusé : ${shown(name)}")
                if (mode != 0 && mode != UNIX_REGULAR && mode != UNIX_DIRECTORY) reject("Fichier spécial refusé : ${shown(name)}")
            }
            val directory = name.endsWith("/")
            if (directory && uncompressed != 0L) reject("Dossier avec contenu : ${shown(name)}")
            if (!seen.add(foldKey(name))) reject("Entrée en double (casse ou normalisation Unicode) : ${shown(name)}")
            result.add(Central(name, nameStart, nameLen, flags, method, crc, compressed, uncompressed, localOffset, directory))
            p = next
        }
        if (p != end) reject("Répertoire central : données en trop")
        return result
    }

    /** Vérifie l'en-tête local de [c] ; renvoie le début des données et la fin de l'entrée (descriptor compris). */
    private fun checkLocal(
        a: ByteArray,
        c: Central,
        cdOffset: Long,
    ): Pair<Int, Long> {
        val lo = c.localOffset
        if (lo + LOCAL_SIZE > cdOffset || u32(a, lo.toInt()) != SIG_LOCAL) reject("En-tête local invalide : ${shown(c.name)}")
        val l = lo.toInt()
        val flags = u16(a, l + 6)
        val method = u16(a, l + 8)
        val crc = u32(a, l + 14)
        val compressed = u32(a, l + 18)
        val uncompressed = u32(a, l + 22)
        val nameLen = u16(a, l + 26)
        val extraLen = u16(a, l + 28)
        val nameAt = l + LOCAL_SIZE
        val dataStart = nameAt.toLong() + nameLen + extraLen
        if (dataStart > cdOffset) reject("En-tête local hors limites : ${shown(c.name)}")
        if (nameLen != c.nameLength ||
            !a.copyOfRange(nameAt, nameAt + nameLen).contentEquals(a.copyOfRange(c.nameStart, c.nameStart + c.nameLength))
        ) {
            reject("Nom local différent du nom central : ${shown(c.name)}")
        }
        if (method != c.method) reject("Méthode locale différente : ${shown(c.name)}")
        if ((flags xor c.flags) and (FLAG_DESCRIPTOR or FLAG_ENCRYPTED) != 0) reject("Indicateurs locaux incohérents : ${shown(c.name)}")
        if (flags and FLAG_ENCRYPTED != 0) reject("Entrée chiffrée refusée")
        val dataEnd = dataStart + c.compressed
        if (dataEnd > cdOffset) reject("Données hors limites : ${shown(c.name)}")
        var end = dataEnd
        if (flags and FLAG_DESCRIPTOR != 0) {
            var d = dataEnd
            if (d + 4 <= cdOffset && u32(a, d.toInt()) == SIG_DESCRIPTOR) d += 4
            if (d + DESCRIPTOR_SIZE > cdOffset) reject("Data descriptor hors limites : ${shown(c.name)}")
            val di = d.toInt()
            if (u32(a, di) != c.crc || u32(a, di + 4) != c.compressed || u32(a, di + 8) != c.uncompressed) {
                reject("Data descriptor incohérent : ${shown(c.name)}")
            }
            // En-tête local à zéro (usage normal) ou identique au répertoire central.
            val zero = crc == 0L && compressed == 0L && uncompressed == 0L
            if (!zero && (crc != c.crc || compressed != c.compressed || uncompressed != c.uncompressed)) {
                reject("En-tête local incohérent : ${shown(c.name)}")
            }
            end = d + DESCRIPTOR_SIZE
        } else if (crc != c.crc || compressed != c.compressed || uncompressed != c.uncompressed) {
            reject("En-tête local incohérent : ${shown(c.name)}")
        }
        return dataStart.toInt() to end
    }

    private fun extract(
        a: ByteArray,
        c: Central,
        dataStart: Int,
        cap: Long,
    ): ByteArray {
        val len = c.compressed.toInt()
        if (c.method == METHOD_STORED) {
            if (c.compressed != c.uncompressed) reject("Entrée stockée de taille incohérente : ${shown(c.name)}")
            return a.copyOfRange(dataStart, dataStart + len)
        }
        val inflater = Inflater(true)
        try {
            inflater.setInput(a, dataStart, len)
            val out = ByteArrayOutputStream(minOf(cap, INITIAL_BUFFER.toLong()).toInt().coerceAtLeast(1))
            val chunk = ByteArray(CHUNK)
            while (!inflater.finished()) {
                val produced =
                    try {
                        inflater.inflate(chunk)
                    } catch (_: DataFormatException) {
                        reject("Flux deflate invalide : ${shown(c.name)}")
                    }
                if (produced == 0 && !inflater.finished()) {
                    // Pas de progrès sans fin de flux : données tronquées (ou dictionnaire demandé).
                    if (inflater.needsInput() || inflater.needsDictionary()) reject("Flux deflate tronqué : ${shown(c.name)}")
                    continue
                }
                if (out.size().toLong() + produced > cap) reject(tooLarge(c.name))
                out.write(chunk, 0, produced)
            }
            if (inflater.remaining != 0 || inflater.bytesRead != c.compressed) reject("Données compressées en trop : ${shown(c.name)}")
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun tooLarge(name: String) =
        "Entrée trop volumineuse (max ${limits.maxEntryBytes / (1024 * 1024)} Mio par entrée, " +
            "${limits.maxTotalBytes / (1024 * 1024)} Mio au total) : ${shown(name)}"

    private fun decodeName(
        a: ByteArray,
        start: Int,
        len: Int,
        flags: Int,
    ): String {
        if (flags and FLAG_UTF8 == 0) {
            // Sans l'indicateur UTF-8, l'encodage (CP437…) est ambigu : seuls les noms ASCII sont acceptés.
            for (k in start until start + len) if (a[k] < 0) reject("Nom non ASCII sans indicateur UTF-8")
            return String(a, start, len, StandardCharsets.US_ASCII)
        }
        return try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(a, start, len))
                .toString()
        } catch (_: CharacterCodingException) {
            reject("Nom UTF-8 invalide")
        }
    }

    companion object {
        private const val SIG_EOCD = 0x06054b50L
        private const val SIG_CENTRAL = 0x02014b50L
        private const val SIG_LOCAL = 0x04034b50L
        private const val SIG_DESCRIPTOR = 0x08074b50L
        private const val SIG_ZIP64_LOCATOR = 0x07064b50L
        private const val EOCD_SIZE = 22
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val CENTRAL_SIZE = 46
        private const val LOCAL_SIZE = 30
        private const val DESCRIPTOR_SIZE = 12
        private const val MAX_COMMENT = 0xFFFF
        private const val FLAG_ENCRYPTED = 0x0001
        private const val FLAG_DESCRIPTOR = 0x0008
        private const val FLAG_STRONG_ENCRYPTION = 0x0040
        private const val FLAG_UTF8 = 0x0800
        private const val FLAG_MASKED_HEADERS = 0x2000
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val HOST_UNIX = 3
        private const val UNIX_TYPE_MASK = 0xF000
        private const val UNIX_SYMLINK = 0xA000
        private const val UNIX_REGULAR = 0x8000
        private const val UNIX_DIRECTORY = 0x4000
        private const val CHUNK = 16 * 1024
        private const val INITIAL_BUFFER = 64 * 1024
        private const val MAX_SHOWN = 120

        private val RESERVED =
            buildSet {
                addAll(listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"))
                for (i in 1..9) {
                    add("COM$i")
                    add("LPT$i")
                }
                // Chiffres en exposant, également réservés par Windows.
                for (sup in listOf("¹", "²", "³")) {
                    add("COM$sup")
                    add("LPT$sup")
                }
            }

        /**
         * Problème du nom d'entrée [name] (séparateur `/`, `/` final pour un dossier), ou `null` s'il est sûr.
         * Utilisé aussi pour les chemins du pack.
         */
        @JvmStatic
        fun nameProblem(
            name: String,
            maxLength: Int = ZipLimits.MAX_NAME_LENGTH,
        ): String? {
            if (name.isEmpty()) return "Nom vide"
            if (name.length > maxLength) return "Nom trop long (max $maxLength)"
            if (name.any { it < ' ' || it == '\u007F' }) return "Caractère de contrôle dans le nom"
            if (name.contains('\\')) return "Antislash interdit dans le nom"
            if (name.startsWith("/")) return "Chemin absolu refusé"
            if (name.contains(':')) return "':' interdit (lecteur, flux de données NTFS)"
            val body = name.removeSuffix("/")
            if (body.isEmpty()) return "Nom vide"
            for (segment in body.split('/')) {
                if (segment.isEmpty()) return "Segment de chemin vide"
                if (segment == "." || segment == "..") return "Remontée de dossier refusée"
                if (segment.endsWith('.') || segment.endsWith(' ')) return "Segment finissant par un point ou une espace"
                if (segment.substringBefore('.').trimEnd().uppercase(Locale.ROOT) in RESERVED) return "Nom réservé Windows"
            }
            return null
        }

        /** Clé de détection des doublons : NFC puis casse repliée, sans `/` final. */
        @JvmStatic
        fun foldKey(name: String): String =
            Normalizer
                .normalize(name.removeSuffix("/"), Normalizer.Form.NFC)
                .uppercase(Locale.ROOT)
                .lowercase(Locale.ROOT)

        private fun shown(name: String): String {
            val clean = name.map { if (it < ' ' || it == '\u007F' || it in '‪'..'‮' || it in '⁦'..'⁩') '?' else it }
            return "'" + clean.joinToString("").take(MAX_SHOWN) + "'"
        }

        private fun reject(message: String): Nothing = throw ZipRejectedException(message)

        private fun u16(
            a: ByteArray,
            p: Int,
        ): Int = (a[p].toInt() and 0xFF) or ((a[p + 1].toInt() and 0xFF) shl 8)

        private fun u32(
            a: ByteArray,
            p: Int,
        ): Long = (u16(a, p).toLong()) or (u16(a, p + 2).toLong() shl 16)
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
