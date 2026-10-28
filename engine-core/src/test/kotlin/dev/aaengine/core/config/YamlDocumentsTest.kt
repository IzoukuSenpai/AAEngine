package dev.aaengine.core.config

import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pré-passe YAML anti-abus (alias, doublons, clés non textuelles, profondeur) et assainissement. */
class YamlDocumentsTest {
    private fun load(yaml: String): Pair<Any?, DiagnosticCollector> {
        val d = DiagnosticCollector()
        return YamlDocuments.load(yaml, "items/test.yml", d) to d
    }

    private fun DiagnosticCollector.single(): Diagnostic {
        val list = toList()
        assertEquals(1, list.size, list.toString())
        return list.first()
    }

    /** `l0: &l0 [x, x]` puis `lN: &lN [*lN-1, *lN-1]` : 2^25 nœuds une fois les alias recopiés. */
    private fun aliasBomb(levels: Int): String =
        buildString {
            append("l0: &l0 [x, x]\n")
            for (i in 1 until levels) append("l$i: &l$i [*l${i - 1}, *l${i - 1}]\n")
        }

    @Test
    fun `bombe d'alias refusee immediatement`() {
        val bomb = aliasBomb(25)
        load(bomb) // préchauffage (chargement des classes)
        val start = System.nanoTime()
        val (node, d) = load(bomb)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        assertNull(node)
        assertTrue(elapsedMillis < 100, "refus en $elapsedMillis ms")
        val diag = d.single()
        assertEquals(Severity.ERROR, diag.severity)
        assertTrue(diag.message.contains("Ancres et alias"), diag.message)
        assertEquals(1, diag.line)
    }

    @Test
    fun `ancre simple refusee`() {
        val (node, d) = load("a: &x 1\nb: 2\n")
        assertNull(node)
        assertTrue(d.single().message.contains("Ancres et alias"))
        val (node2, d2) = load("defaults: &d {material: PAPER}\n")
        assertNull(node2)
        assertEquals(1, d2.errorCount)
    }

    @Test
    fun `cle en double refusee avec chemin et ligne`() {
        val (node, d) =
            load(
                """
                items:
                  axe:
                    item: {material: STONE}
                  axe:
                    item: {material: DIRT}
                """.trimIndent(),
            )
        assertNull(node)
        val diag = d.single()
        assertEquals("items.axe", diag.path)
        assertEquals(4, diag.line)
        assertTrue(diag.message.contains("Clé en double 'axe'") && diag.message.contains("ligne 2"), diag.message)
    }

    @Test
    fun `cles en double a la racine et en section`() {
        val (_, d) = load("namespace: a\nnamespace: b\nitems:\n  x: {item: {material: A, material: B}}\n")
        assertEquals(2, d.errorCount, d.toList().toString())
        assertEquals(listOf("namespace", "items.x.item.material"), d.toList().map { it.path })
    }

    @Test
    fun `cles non textuelles refusees avec la forme a ecrire`() {
        val (node, d) = load("items:\n  010: {}\n  yes: {}\n  1.10: {}\n  \"011\": {}\n  ok: {}\n")
        assertNull(node)
        val errors = d.toList()
        assertEquals(listOf("items.010", "items.yes", "items.1.10"), errors.map { it.path })
        assertEquals(listOf("\"010\"", "\"yes\"", "\"1.10\""), errors.map { it.suggestion })
    }

    @Test
    fun `cle de fusion refusee`() {
        val (node, d) = load("items:\n  x:\n    <<: {item: {material: STONE}}\n")
        assertNull(node)
        assertTrue(d.single().message.contains("<<"))
    }

    @Test
    fun `profondeur bornee`() {
        val deep = "a: " + "[".repeat(YamlDocuments.MAX_DEPTH + 5) + "]".repeat(YamlDocuments.MAX_DEPTH + 5) + "\n"
        val (node, d) = load(deep)
        assertNull(node)
        // Refus par la pré-passe ou par la limite du parseur, toujours en erreur rattachée au fichier.
        assertEquals("items/test.yml", d.single().file)
    }

    @Test
    fun `document valide accepte`() {
        val (node, d) = load("namespace: demo\nitems:\n  axe:\n    item: {material: STONE, lore: [a, b]}\n")
        assertNotNull(node)
        assertEquals(0, d.errorCount + d.warningCount)
    }

    @Test
    fun `format sur une seule ligne sans sequence de controle`() {
        val (_, d) =
            load(
                "items:\n  \"x\\n[12:00:00 INFO]: faux\\u001b[31m\\u202e\": {}\n  \"x\\n[12:00:00 INFO]: faux\\u001b[31m\\u202e\": {}\n",
            )
        val text = d.single().format()
        assertFalse(text.contains('\n'), text)
        assertFalse(text.contains('\u001b'), text)
        assertFalse(text.contains('\u202e'), text)
    }

    @Test
    fun `diagnostic borne et assaini`() {
        val longKey = "k".repeat(5000)
        val diag = Diagnostic(Severity.ERROR, "items/a\nb.yml", "items.$longKey", "message\r\nforge", "s\u001b".repeat(200))
        val text = diag.format()
        assertFalse(text.any { it.isISOControl() }, text)
        assertTrue(text.length < 2000, "longueur ${text.length}")
        assertTrue(text.contains("items/a?b.yml"))
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
