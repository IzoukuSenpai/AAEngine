package dev.aaengine.api.glyph

import net.kyori.adventure.key.Key

/**
 * Glyphe d'une police du resource pack AAEngine.
 *
 * @property id identifiant du glyphe, en minuscules (`[a-z0-9_.-]{1,64}`)
 * @property text caractère(s) à insérer dans un texte pour afficher le glyphe (1 à 16 points de code)
 * @property font police du glyphe, `null` pour la police par défaut
 */
data class AAGlyph(
    val id: String,
    val text: String,
    val font: Key?,
) {
    init {
        require(ID.matches(id)) { "Identifiant de glyphe invalide" }
        val count = text.codePointCount(0, text.length)
        require(count in 1..MAX_CODE_POINTS) { "Glyphe : 1 à $MAX_CODE_POINTS points de code attendus" }
    }

    companion object {
        const val MAX_CODE_POINTS: Int = 16

        @JvmField
        val ID: Regex = Regex("[a-z0-9_.-]{1,64}")
    }
}
