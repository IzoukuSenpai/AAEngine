package dev.aaengine.core.diagnostic

/** Gravité d'un diagnostic : toute [ERROR] bloque la publication du contenu. */
enum class Severity {
    ERROR,
    WARN,
}

/**
 * Problème structuré relevé pendant la lecture ou la compilation du contenu (§28).
 *
 * Toutes les parties textuelles peuvent provenir de fichiers (clés YAML, noms de fichiers) : elles sont
 * assainies et bornées une seule fois ici, dans [format], avant d'aller en console ou dans le chat.
 *
 * @property file chemin relatif au dossier de données (séparateur `/`), `null` pour un problème global
 * @property path chemin du nœud Configurate (ex. `items.forest_axe.item.material`), `null` si sans objet
 * @property suggestion correction proposée (valeur la plus proche, format attendu…)
 * @property line ligne (1-based) uniquement quand le parseur YAML l'expose, jamais estimée
 * @property column colonne (1-based), mêmes règles que [line]
 */
data class Diagnostic(
    val severity: Severity,
    val file: String?,
    val path: String?,
    val message: String,
    val suggestion: String? = null,
    val line: Int? = null,
    val column: Int? = null,
) {
    /** Forme lisible sur une seule ligne, sans caractère de contrôle, pour la console et les commandes. */
    fun format(): String =
        buildString {
            append(severity.name)
            if (file != null) {
                append(' ').append(sanitize(file, MAX_FILE))
                if (line != null) {
                    append(':').append(line)
                    if (column != null) append(':').append(column)
                }
            }
            if (!path.isNullOrEmpty()) append(" [").append(sanitize(path, MAX_PATH)).append(']')
            append(" : ").append(sanitize(message, MAX_MESSAGE))
            if (suggestion != null) append(" (suggestion : ").append(sanitize(suggestion, MAX_SUGGESTION)).append(')')
        }

    companion object {
        private const val MAX_QUOTED = 80
        private const val MAX_FILE = 256
        private const val MAX_PATH = 256
        private const val MAX_SUGGESTION = 128
        private const val MAX_MESSAGE = 1024

        /**
         * Valeur issue d'un fichier, prête à être insérée dans un message : caractères de contrôle et de
         * direction remplacés (pas d'injection de lignes dans les logs) et longueur bornée.
         */
        @JvmStatic
        fun quote(value: Any?): String = "'${sanitize(value?.toString() ?: "null", MAX_QUOTED)}'"

        /**
         * Remplace par `?` les caractères de contrôle (sauts de ligne, ESC/ANSI…) et de direction bidi
         * (U+202A..U+202E, U+2066..U+2069), puis tronque à [max] caractères avec `…`.
         */
        @JvmStatic
        fun sanitize(
            value: String,
            max: Int,
        ): String {
            val bounded = if (value.length > max) value.take(max) else value
            val out = StringBuilder(bounded.length + 1)
            for (c in bounded) out.append(if (isUnsafe(c)) '?' else c)
            if (value.length > max) out.append('…')
            return out.toString()
        }

        private fun isUnsafe(c: Char): Boolean = c.isISOControl() || c in '‪'..'‮' || c in '⁦'..'⁩'
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
