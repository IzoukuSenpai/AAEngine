package dev.aaengine.core.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.Locale

/**
 * Contrôle d'une URL publique annoncée aux clients (§18.1) : HTTPS obligatoire, pas d'identifiants dans
 * l'URL, pas de fragment, et refus par défaut des hôtes locaux ou privés (loopback, RFC 1918, lien local,
 * CGNAT, multicast, ULA IPv6…). Aucune résolution DNS n'est faite : seuls les littéraux IP et les noms
 * réservés (`localhost`, `.local`…) sont contrôlés, le serveur ne télécharge jamais cette URL.
 */
object PublicHttpsUrl {
    /** Longueur maximale acceptée. */
    const val MAX_LENGTH: Int = 2048

    /** Résultat du contrôle : [uri] si acceptée, sinon [reason]. */
    class Check private constructor(
        val uri: URI?,
        val reason: String?,
    ) {
        val ok: Boolean
            get() = uri != null

        companion object {
            internal fun ok(uri: URI) = Check(uri, null)

            internal fun rejected(reason: String) = Check(null, reason)
        }
    }

    private val RESERVED_SUFFIXES = listOf(".localhost", ".local", ".internal", ".home.arpa")
    private val HOSTNAME = Regex("""[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+""")
    private val DOTTED_QUAD = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
    private val NUMERIC_LABEL = Regex("[0-9]+|0x[0-9a-f]*")

    /**
     * Contrôle [raw]. [allowPrivate] (opt-in explicite de l'administrateur, réseau de test) autorise les
     * adresses privées et les noms locaux ; HTTPS et l'absence d'identifiants restent exigés.
     */
    @JvmStatic
    fun check(
        raw: String,
        allowPrivate: Boolean = false,
    ): Check {
        if (raw.isEmpty() || raw.length > MAX_LENGTH) return Check.rejected("URL vide ou trop longue (max $MAX_LENGTH)")
        if (raw.any { it.isWhitespace() || it.isISOControl() }) return Check.rejected("Espace ou caractère de contrôle dans l'URL")
        val uri =
            try {
                URI(raw)
            } catch (_: URISyntaxException) {
                return Check.rejected("URL invalide")
            }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return Check.rejected("HTTPS obligatoire")
        if (uri.rawUserInfo != null) return Check.rejected("Identifiants interdits dans l'URL")
        if (uri.rawFragment != null) return Check.rejected("Fragment (#) interdit dans l'URL")
        if (uri.port != -1 && uri.port !in 1..65535) return Check.rejected("Port invalide")
        val host = uri.host?.lowercase(Locale.ROOT) ?: return Check.rejected("Hôte manquant")
        val hostProblem = hostProblem(host, allowPrivate)
        return if (hostProblem == null) Check.ok(uri) else Check.rejected(hostProblem)
    }

    private fun hostProblem(
        host: String,
        allowPrivate: Boolean,
    ): String? {
        if (host.startsWith("[")) {
            val literal = host.removePrefix("[").removeSuffix("]")
            if (!literal.contains(':')) return "Adresse IPv6 invalide"
            val address =
                try {
                    // Littéral IPv6 : aucune résolution DNS.
                    InetAddress.getByName(literal)
                } catch (_: UnknownHostException) {
                    return "Adresse IPv6 invalide"
                }
            return if (!allowPrivate && isPrivate(address)) "Adresse locale ou privée refusée" else null
        }
        val quad = DOTTED_QUAD.matchEntire(host)
        if (quad != null) {
            val parts = quad.groupValues.drop(1)
            // Zéro initial (010.0.0.1) : lu en octal par certains clients, ambigu donc refusé.
            if (parts.any { it.length > 1 && it.startsWith('0') }) return "Adresse IP non canonique refusée"
            val octets = parts.map { it.toInt() }
            if (octets.any { it > 255 }) return "Adresse IPv4 invalide"
            val address = InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())
            return if (!allowPrivate && isPrivate(address)) "Adresse locale ou privée refusée" else null
        }
        // Formes IPv4 abrégées, octales ou hexadécimales (127.1, 0x7f.1, 2130706433) : ambiguës, refusées.
        if (host.split('.').all { NUMERIC_LABEL.matches(it) }) return "Adresse IP non canonique refusée"
        if (!HOSTNAME.matches(host)) return "Nom d'hôte invalide (nom de domaine complet attendu)"
        if (!allowPrivate && (host == "localhost" || RESERVED_SUFFIXES.any { host.endsWith(it) })) {
            return "Nom d'hôte local refusé"
        }
        return null
    }

    /**
     * Vrai pour toute adresse non routable publiquement ou réservée : loopback, privées, lien local,
     * multicast, CGNAT, documentation, bogons (0/8, 240/4…), ULA, Teredo, documentation IPv6 ; pour NAT64
     * (64:ff9b::/96) et 6to4 (2002::/16), l'IPv4 embarquée est réévaluée.
     */
    @JvmStatic
    fun isPrivate(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val b = address.address
        return when (address) {
            is Inet4Address -> isPrivateV4(b)
            is Inet6Address -> isPrivateV6(b)
            else -> true
        }
    }

    private fun isPrivateV4(b: ByteArray): Boolean {
        val a0 = b[0].toInt() and 0xFF
        val a1 = b[1].toInt() and 0xFF
        val a2 = b[2].toInt() and 0xFF
        return a0 == 0 ||
            a0 == 10 ||
            a0 == 127 ||
            (a0 == 100 && a1 in 64..127) ||
            (a0 == 169 && a1 == 254) ||
            (a0 == 172 && a1 in 16..31) ||
            (a0 == 192 && a1 == 168) ||
            (a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)) ||
            (a0 == 192 && a1 == 88 && a2 == 99) ||
            (a0 == 198 && a1 in 18..19) ||
            (a0 == 198 && a1 == 51 && a2 == 100) ||
            (a0 == 203 && a1 == 0 && a2 == 113) ||
            a0 >= 224
    }

    private fun isPrivateV6(b: ByteArray): Boolean {
        val w0 = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        val w1 = ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        return when {
            // fc00::/7 (ULA), fec0::/10 (site local obsolète)
            (w0 and 0xFE00) == 0xFC00 || (w0 and 0xFFC0) == 0xFEC0 -> true
            // ::/96 (IPv4 compatible, non spécifiée)
            b.take(12).all { it.toInt() == 0 } -> true
            // 2001:db8::/32 (documentation) et 2001::/32 (Teredo, IPv4 masquée)
            w0 == 0x2001 && (w1 == 0x0db8 || w1 == 0x0000) -> true
            // 64:ff9b::/96 (NAT64) : IPv4 dans les 4 derniers octets
            w0 == 0x0064 && w1 == 0xFF9B && b.copyOfRange(4, 12).all { it.toInt() == 0 } -> isPrivateV4(b.copyOfRange(12, 16))
            // 2002::/16 (6to4) : IPv4 dans les octets 2 à 5
            w0 == 0x2002 -> isPrivateV4(b.copyOfRange(2, 6))
            else -> false
        }
    }

    /**
     * Résolution DNS de [host] (à appeler hors du thread principal) : adresses non publiques renvoyées.
     * Ne sert qu'à avertir l'administrateur ; le serveur ne se connecte jamais à cet hôte.
     */
    @JvmStatic
    fun privateAddresses(host: String): List<String> =
        try {
            InetAddress
                .getAllByName(host)
                .filter(::isPrivate)
                .map { it.hostAddress }
                .distinct()
        } catch (_: UnknownHostException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
