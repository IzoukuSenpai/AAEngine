package dev.aaengine.api

import dev.aaengine.api.glyph.AAGlyphService
import dev.aaengine.api.item.AAItemService

/**
 * Point d'entrée public d'AAEngine, consommé par les plugins tiers (dont AAItems).
 *
 * Contrat : toutes les méthodes sont appelables depuis le thread principal du serveur ; aucune ne lève
 * d'exception pour une donnée absente (elles renvoient `null`, une collection vide ou `false`).
 * Les instantanés renvoyés sont immuables : un reload remplace l'instantané, il ne le modifie jamais.
 *
 * Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
interface AAEngine {
    /** Version d'AAEngine (ex. `0.1.0`). */
    val version: String

    /** Version de l'API publique implémentée. */
    val apiVersion: AAEngineApiVersion

    /** Révision du contenu actif : incrémentée à chaque reload appliqué (0 tant que rien n'est chargé). */
    val contentRevision: Long

    /** Vrai une fois le premier chargement de contenu terminé ([dev.aaengine.api.event.AAEngineContentLoadedEvent]). */
    val isContentLoaded: Boolean

    val items: AAItemService

    val glyphs: AAGlyphService
}
