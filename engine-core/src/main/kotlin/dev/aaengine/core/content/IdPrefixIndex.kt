package dev.aaengine.core.content

/**
 * Index immuable d'identifiants `ns:valeur` pour l'autocomplétion : recherche dichotomique du préfixe sur
 * l'identifiant complet et sur la seule valeur (saisie sans namespace), sans tri ni allocation par saisie
 * au-delà des résultats renvoyés.
 */
class IdPrefixIndex(
    ids: Collection<String>,
) {
    private val full: Array<String> = ids.toTypedArray().also { it.sort() }

    /** Valeurs (partie après `:`), triées, avec l'identifiant complet correspondant au même indice. */
    private val values: Array<String>
    private val valueOwners: Array<String>

    init {
        val pairs = ids.map { it.substringAfter(':') to it }.sortedWith(compareBy({ it.first }, { it.second }))
        values = Array(pairs.size) { pairs[it].first }
        valueOwners = Array(pairs.size) { pairs[it].second }
    }

    /** Au plus [max] identifiants commençant par [prefix] (complet ou valeur seule), triés. */
    fun suggest(
        prefix: String,
        max: Int,
    ): List<String> {
        if (max <= 0) return emptyList()
        val out = java.util.TreeSet<String>()
        collect(full, null, prefix, max, out)
        if (prefix.indexOf(':') < 0) collect(values, valueOwners, prefix, max, out)
        return out.take(max)
    }

    private fun collect(
        sorted: Array<String>,
        owners: Array<String>?,
        prefix: String,
        max: Int,
        out: MutableSet<String>,
    ) {
        var i = lowerBound(sorted, prefix)
        var added = 0
        while (i < sorted.size && added < max && sorted[i].startsWith(prefix)) {
            if (out.add(owners?.get(i) ?: sorted[i])) added++
            i++
        }
    }

    private fun lowerBound(
        sorted: Array<String>,
        key: String,
    ): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
