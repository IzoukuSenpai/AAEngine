package dev.aaengine.core.diagnostic

/**
 * Accumulateur de diagnostics, borné en mémoire : au-delà de [limit] entrées, seuls les compteurs
 * progressent. Non thread-safe : une instance par tentative de chargement.
 */
class DiagnosticCollector(
    private val limit: Int = DEFAULT_LIMIT,
) {
    private val entries = ArrayList<Diagnostic>()

    var errorCount: Int = 0
        private set

    var warningCount: Int = 0
        private set

    var infoCount: Int = 0
        private set

    val hasErrors: Boolean
        get() = errorCount > 0

    fun add(diagnostic: Diagnostic) {
        when (diagnostic.severity) {
            Severity.ERROR -> errorCount++
            Severity.WARN -> warningCount++
            Severity.INFO -> infoCount++
        }
        if (entries.size < limit) entries.add(diagnostic)
    }

    fun error(
        file: String?,
        path: String?,
        message: String,
        suggestion: String? = null,
    ) = add(Diagnostic(Severity.ERROR, file, path, message, suggestion))

    fun warn(
        file: String?,
        path: String?,
        message: String,
        suggestion: String? = null,
    ) = add(Diagnostic(Severity.WARN, file, path, message, suggestion))

    fun info(
        file: String?,
        path: String?,
        message: String,
    ) = add(Diagnostic(Severity.INFO, file, path, message))

    /** Copie immuable des diagnostics conservés : erreurs, avertissements puis informations, chacun dans l'ordre d'apparition. */
    fun toList(): List<Diagnostic> = entries.sortedBy { it.severity.ordinal }

    companion object {
        const val DEFAULT_LIMIT: Int = 500
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
