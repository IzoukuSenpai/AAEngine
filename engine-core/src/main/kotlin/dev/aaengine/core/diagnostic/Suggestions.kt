package dev.aaengine.core.diagnostic

import java.util.Locale

/** Recherche de la valeur connue la plus proche d'une saisie erronée (distance de Levenshtein). */
object Suggestions {
    private const val MAX_INPUT = 64

    /**
     * Candidat le plus proche de [input] (insensible à la casse), ou `null` si aucun n'est assez proche.
     * Les chaînes trop longues ne sont pas comparées (coût borné).
     */
    @JvmStatic
    fun closest(
        input: String,
        candidates: Iterable<String>,
    ): String? {
        if (input.isEmpty() || input.length > MAX_INPUT) return null
        val needle = input.lowercase(Locale.ROOT)
        val threshold = maxOf(2, needle.length / 3)
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in candidates) {
            if (candidate.length > MAX_INPUT) continue
            val d = distance(needle, candidate.lowercase(Locale.ROOT))
            if (d < bestDistance || (d == bestDistance && best != null && candidate < best)) {
                best = candidate
                bestDistance = d
            }
        }
        return if (bestDistance in 1..threshold) best else null
    }

    /** Distance d'édition (insertion, suppression, substitution). */
    @JvmStatic
    fun distance(
        a: String,
        b: String,
    ): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
