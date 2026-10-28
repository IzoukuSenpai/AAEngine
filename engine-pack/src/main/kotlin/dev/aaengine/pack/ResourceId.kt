package dev.aaengine.pack

/**
 * Identifiant de ressource `namespace:chemin` du resource pack. Mêmes règles que les identifiants d'items
 * (namespace `[a-z0-9_.-]`, chemin `[a-z0-9_./-]`, segments vides, `.` et `..` refusés) : un identifiant
 * valide se traduit toujours en un chemin relatif sûr sous `assets/`.
 */
data class ResourceId(
    val namespace: String,
    val path: String,
) : Comparable<ResourceId> {
    init {
        require(isValidNamespace(namespace)) { "Namespace invalide" }
        require(isValidPath(path)) { "Chemin invalide" }
    }

    fun asString(): String = "$namespace:$path"

    override fun toString(): String = asString()

    override fun compareTo(other: ResourceId): Int = compareValuesBy(this, other, { it.namespace }, { it.path })

    /** Fichier `assets/<ns>/<dossier>/<chemin><extension>` (ex. `textures`, `.png`). */
    fun file(
        folder: String,
        extension: String,
    ): String = "assets/$namespace/$folder/$path$extension"

    companion object {
        const val MINECRAFT: String = "minecraft"
        const val MAX_NAMESPACE: Int = 64
        const val MAX_PATH: Int = 256

        private val NAMESPACE = Regex("[a-z0-9_.-]+")
        private val PATH = Regex("[a-z0-9_./-]+")

        @JvmStatic
        fun isValidNamespace(ns: String): Boolean = ns.length in 1..MAX_NAMESPACE && NAMESPACE.matches(ns) && ns != "." && ns != ".."

        @JvmStatic
        fun isValidPath(path: String): Boolean =
            path.length in 1..MAX_PATH && PATH.matches(path) && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        /** `ns:chemin` ou `chemin` ([defaultNamespace]) ; `null` si invalide. Jamais d'exception. */
        @JvmStatic
        fun parse(
            raw: String,
            defaultNamespace: String = MINECRAFT,
        ): ResourceId? {
            val idx = raw.indexOf(':')
            val ns = if (idx < 0) defaultNamespace else raw.substring(0, idx)
            val path = if (idx < 0) raw else raw.substring(idx + 1)
            return if (isValidNamespace(ns) && isValidPath(path)) ResourceId(ns, path) else null
        }

        @JvmStatic
        fun of(
            namespace: String,
            path: String,
        ): ResourceId = ResourceId(namespace, path)
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
