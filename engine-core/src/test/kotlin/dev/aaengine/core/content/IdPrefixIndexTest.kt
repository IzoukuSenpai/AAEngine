package dev.aaengine.core.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Autocomplétion par préfixe (identifiant complet ou valeur seule). */
class IdPrefixIndexTest {
    private val index = IdPrefixIndex(listOf("demo:ruby", "aaengine:forest_axe", "aaengine:ruby", "other:forest_bow", "aaengine:axe"))

    @Test
    fun `prefixe complet et valeur seule`() {
        assertEquals(listOf("aaengine:axe", "aaengine:forest_axe", "aaengine:ruby"), index.suggest("aaengine:", 10))
        assertEquals(listOf("aaengine:forest_axe", "other:forest_bow"), index.suggest("forest", 10))
        assertEquals(listOf("aaengine:ruby", "demo:ruby"), index.suggest("ruby", 10))
        assertEquals(listOf("aaengine:forest_axe"), index.suggest("aaengine:f", 10))
        assertEquals(emptyList<String>(), index.suggest("zzz", 10))
    }

    @Test
    fun `saisie vide bornee et triee`() {
        assertEquals(listOf("aaengine:axe", "aaengine:forest_axe"), index.suggest("", 2))
        assertEquals(5, index.suggest("", 100).size)
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
