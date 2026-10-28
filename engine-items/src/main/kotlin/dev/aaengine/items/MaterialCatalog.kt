package dev.aaengine.items

import dev.aaengine.core.diagnostic.Suggestions
import org.bukkit.Material
import java.util.Locale

/**
 * Propriétés d'un matériau utiles à la validation.
 *
 * @property damageable vrai si l'item a une durabilité (incompatible avec une pile > 1)
 */
data class MaterialInfo(
    val material: Material,
    val damageable: Boolean,
)

/**
 * Matériaux autorisés pour un item (items réels, hors air et hors legacy). Construit une fois sur le thread
 * principal à partir du serveur, puis consulté en lecture seule depuis le thread de parsing.
 */
class MaterialCatalog(
    entries: Map<String, MaterialInfo>,
) {
    private val byName: Map<String, MaterialInfo> = entries.mapKeys { it.key.uppercase(Locale.ROOT) }

    val names: Set<String>
        get() = byName.keys

    /** `NETHERITE_AXE`, `netherite_axe` ou `minecraft:netherite_axe` ; `null` si non autorisé. */
    fun resolve(raw: String): MaterialInfo? = byName[normalize(raw)]

    /** Matériau autorisé le plus proche de [raw], pour une suggestion. */
    fun suggest(raw: String): String? = Suggestions.closest(normalize(raw), byName.keys)

    private fun normalize(raw: String): String = raw.trim().removePrefix("minecraft:").uppercase(Locale.ROOT)
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
