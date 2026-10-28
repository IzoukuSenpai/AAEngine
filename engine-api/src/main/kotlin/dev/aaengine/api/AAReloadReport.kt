package dev.aaengine.api

import org.jetbrains.annotations.ApiStatus

/**
 * Bilan immuable d'une tentative de chargement de contenu (démarrage ou reload).
 *
 * Classe simple (pas de `data class`) : seuls les accesseurs font partie du contrat public, de nouveaux
 * champs pourront être ajoutés sans casser les consommateurs. Le constructeur est réservé à l'engine.
 *
 * @property revision révision du contenu actif après la tentative (inchangée en cas d'échec)
 * @property success vrai si le nouveau contenu a été publié (Java : `isSuccess()`)
 * @property errorCount nombre d'erreurs relevées (toute erreur bloque la publication)
 * @property warningCount nombre d'avertissements relevés
 * @property durationMillis durée totale de la tentative, en millisecondes
 * @since API 0.2.0
 */
class AAReloadReport
    @ApiStatus.Internal
    constructor(
        val revision: Long,
        @get:JvmName("isSuccess")
        val success: Boolean,
        val errorCount: Int,
        val warningCount: Int,
        val durationMillis: Long,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is AAReloadReport &&
                        revision == other.revision &&
                        success == other.success &&
                        errorCount == other.errorCount &&
                        warningCount == other.warningCount &&
                        durationMillis == other.durationMillis
                )

        override fun hashCode(): Int {
            var h = revision.hashCode()
            h = 31 * h + success.hashCode()
            h = 31 * h + errorCount
            h = 31 * h + warningCount
            h = 31 * h + durationMillis.hashCode()
            return h
        }

        override fun toString(): String =
            "AAReloadReport(revision=$revision, success=$success, errorCount=$errorCount, " +
                "warningCount=$warningCount, durationMillis=$durationMillis)"
    }

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
