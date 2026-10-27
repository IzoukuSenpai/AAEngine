package dev.aaengine.api

/**
 * Version sémantique de l'API publique d'AAEngine. Un consommateur doit vérifier [isCompatibleWith]
 * plutôt que comparer des chaînes.
 */
data class AAEngineApiVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AAEngineApiVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "Version négative" }
    }

    /** Vrai si cette version est au moins [other] et de même version majeure. */
    fun isCompatibleWith(other: AAEngineApiVersion): Boolean = major == other.major && compareTo(other) >= 0

    override fun compareTo(other: AAEngineApiVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** Version courante d'`engine-api`. */
        @JvmField
        val CURRENT: AAEngineApiVersion = AAEngineApiVersion(0, 1, 0)
    }
}
