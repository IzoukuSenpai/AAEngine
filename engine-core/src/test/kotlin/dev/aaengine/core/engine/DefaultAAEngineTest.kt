package dev.aaengine.core.engine

import dev.aaengine.api.AAEngineApiVersion
import dev.aaengine.api.glyph.AAGlyph
import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Logique pure de l'engine (instantané, révision, glyphes) : aucun serveur Bukkit requis. */
class DefaultAAEngineTest {
    @Test
    fun `instantane vide au depart`() {
        val engine = DefaultAAEngine("0.1.0-test")
        assertEquals(AAEngineApiVersion.CURRENT, engine.apiVersion)
        assertEquals(0L, engine.contentRevision)
        assertFalse(engine.isContentLoaded)
        assertTrue(engine.items.ids().isEmpty())
        assertFalse(engine.items.exists(Key.key("aaengine", "forest_axe")))
        assertNull(engine.items.definition(Key.key("aaengine", "forest_axe")))
        assertTrue(engine.glyphs.glyphs().isEmpty())
    }

    @Test
    fun `publication atomique et glyphes normalises`() {
        val engine = DefaultAAEngine("0.1.0-test")
        val rev = engine.publish(emptyMap(), listOf(AAGlyph("coin", "", Key.key("aaengine", "default"))))
        assertEquals(1L, rev)
        assertEquals(1L, engine.contentRevision)
        assertTrue(engine.isContentLoaded)
        assertNotNull(engine.glyphs.glyph("COIN"))
        assertEquals("", engine.glyphs.glyph("coin")!!.text)
        assertNull(engine.glyphs.glyph("absent"))

        val rev2 = engine.publish(emptyMap(), emptyList())
        assertEquals(2L, rev2)
        assertTrue(engine.glyphs.glyphs().isEmpty())
    }

    @Test
    fun `version api compatible`() {
        assertTrue(
            AAEngineApiVersion.CURRENT.isCompatibleWith(AAEngineApiVersion(0, 1, 0)),
            "0.2.0 reste compatible avec les consommateurs 0.1.0",
        )
        assertTrue(AAEngineApiVersion(0, 1, 3).isCompatibleWith(AAEngineApiVersion(0, 1, 0)))
        assertFalse(AAEngineApiVersion(1, 0, 0).isCompatibleWith(AAEngineApiVersion(0, 1, 0)))
        assertFalse(AAEngineApiVersion(0, 0, 9).isCompatibleWith(AAEngineApiVersion(0, 1, 0)))
    }
}
