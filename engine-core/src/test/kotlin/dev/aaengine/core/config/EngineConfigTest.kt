package dev.aaengine.core.config

import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.core.diagnostic.Suggestions
import dev.aaengine.core.log.LogCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Configuration typée, diagnostics et suggestions. */
class EngineConfigTest {
    @Test
    fun `categories de debug lues`() {
        val d = DiagnosticCollector()
        val cfg = EngineConfig.parse("config-version: 1\ndebug:\n  categories: [items, PACK]\n", "config.yml", d)
        assertEquals(setOf(LogCategory.ITEMS, LogCategory.PACK), cfg.debugCategories)
        assertEquals(0, d.errorCount + d.warningCount)
    }

    @Test
    fun `valeurs inconnues en avertissement avec suggestion`() {
        val d = DiagnosticCollector()
        val cfg = EngineConfig.parse("debg: {}\ndebug:\n  categories: [ITEMZ]\n", "config.yml", d)
        assertTrue(cfg.debugCategories.isEmpty())
        assertFalse(d.hasErrors)
        val list = d.toList()
        assertEquals("debug", list.first { it.path == "debg" }.suggestion)
        assertEquals("ITEMS", list.first { it.path == "debug.categories.0" }.suggestion)
    }

    @Test
    fun `erreur de syntaxe avec position reelle`() {
        val d = DiagnosticCollector()
        val cfg = EngineConfig.parse("debug:\n  categories: [ITEMS\n", "config.yml", d)
        assertEquals(EngineConfig.DEFAULT, cfg)
        val err = d.toList().single()
        assertEquals(Severity.ERROR, err.severity)
        assertNotNull(err.line, "la ligne exposée par le parseur YAML doit être reprise")
    }

    @Test
    fun `suggestions et echappement`() {
        assertEquals("material", Suggestions.closest("materail", listOf("material", "name", "lore")))
        assertNull(Suggestions.closest("zzzzzzzz", listOf("material", "name")))
        assertEquals("'a?b'", Diagnostic.quote("a\nb"))
        val long = Diagnostic.quote("x".repeat(500))
        assertTrue(long.length < 100)
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
