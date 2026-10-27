package dev.aaengine.api.glyph

/**
 * Glyphes de l'instantané de contenu actif (immuable). Thread principal ; ne lève jamais.
 */
interface AAGlyphService {
    /** Tous les glyphes connus (copie immuable, vide tant que le contenu n'est pas chargé). */
    fun glyphs(): Collection<AAGlyph>

    /** Glyphe par identifiant (insensible à la casse), ou `null`. */
    fun glyph(id: String): AAGlyph?
}
