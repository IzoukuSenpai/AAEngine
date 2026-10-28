package dev.aaengine.core.log

import java.util.logging.Level
import java.util.logging.Logger

/** Catégories de logs (§53) : le mode debug s'active par catégorie, jamais globalement. */
enum class LogCategory {
    BOOT,
    CONFIG,
    ITEMS,
    MECHANICS,
    BLOCKS,
    FURNITURE,
    PACK,
    NETWORK,
    VERSION,
    INTEGRATION,
    SECURITY,
}

/** Journal préfixé par catégorie ; les messages de debug ne sortent que pour les catégories activées. */
class EngineLog(
    private val logger: Logger,
    @Volatile var debugCategories: Set<LogCategory> = emptySet(),
) {
    fun info(
        category: LogCategory,
        message: String,
    ) = logger.info("[$category] $message")

    fun warn(
        category: LogCategory,
        message: String,
    ) = logger.warning("[$category] $message")

    fun error(
        category: LogCategory,
        message: String,
        cause: Throwable? = null,
    ) = logger.log(Level.SEVERE, "[$category] $message", cause)

    fun isDebug(category: LogCategory): Boolean = category in debugCategories

    /** [message] n'est évalué que si la catégorie est en debug. */
    fun debug(
        category: LogCategory,
        message: () -> String,
    ) {
        if (isDebug(category)) logger.info("[$category/debug] ${message()}")
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
