package dev.aaengine.items

import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import net.kyori.adventure.key.Key
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Chargement d'un dossier items/ : fusion, doublons, bornes et liens symboliques. */
class ItemCatalogLoaderTest {
    @TempDir
    lateinit var dir: Path

    private val materials = MaterialCatalog(mapOf("PAPER" to MaterialInfo(Material.PAPER, false)))

    private fun loader(limits: ItemLimits = ItemLimits.DEFAULT) = ItemCatalogLoader(ItemFileParser(materials, limits), limits)

    private fun item(id: String) = "items:\n  $id:\n    item: { material: PAPER }\n"

    private fun write(
        rel: String,
        content: String,
    ): Path {
        val p = dir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, content)
        return p
    }

    @Test
    fun `dossier recursif charge dans un ordre stable`() {
        write("b.yml", item("b"))
        write("outils/a.yml", item("a"))
        write("notes.txt", "ignoré")
        write(".cache/c.yml", item("c"))
        val d = DiagnosticCollector()
        val catalog = loader().load(dir, d)
        assertFalse(d.hasErrors, d.toList().toString())
        assertEquals(2, catalog.fileCount)
        assertEquals(setOf(Key.key("aaengine", "a"), Key.key("aaengine", "b")), catalog.items.keys)
        assertEquals("items/outils/a.yml", catalog.items.getValue(Key.key("aaengine", "a")).source)
    }

    @Test
    fun `doublon entre fichiers nomme les deux fichiers`() {
        val d = DiagnosticCollector()
        val catalog =
            loader().loadTexts(
                listOf(
                    ItemCatalogLoader.SourceText("items/a.yml", item("epee")),
                    ItemCatalogLoader.SourceText("items/b.yml", "namespace: aaengine\n" + item("epee")),
                ),
                d,
            )
        assertEquals(1, catalog.items.size)
        val err = d.toList().single { it.severity == Severity.ERROR }
        assertEquals("items/b.yml", err.file)
        assertTrue(err.message.contains("items/a.yml"), err.message)
        assertTrue(err.message.contains("aaengine:epee"))
    }

    @Test
    fun `nombre de fichiers borne`() {
        repeat(3) { write("f$it.yml", item("i$it")) }
        val d = DiagnosticCollector()
        loader(ItemLimits(maxFiles = 2)).load(dir, d)
        assertTrue(d.toList().any { it.severity == Severity.ERROR && it.message.contains("max 2") })
    }

    @Test
    fun `taille de fichier bornee`() {
        write("gros.yml", item("a") + "# " + "x".repeat(2048) + "\n")
        val d = DiagnosticCollector()
        val catalog = loader(ItemLimits(maxFileBytes = 1024)).load(dir, d)
        assertTrue(catalog.items.isEmpty())
        assertEquals("items/gros.yml", d.toList().single().file)
        assertTrue(d.hasErrors)
    }

    @Test
    fun `nombre d'items borne`() {
        write("a.yml", "items:\n" + (1..3).joinToString("") { "  i$it:\n    item: { material: PAPER }\n" })
        val d = DiagnosticCollector()
        val catalog = loader(ItemLimits(maxItems = 2)).load(dir, d)
        assertEquals(2, catalog.items.size)
        assertEquals(1, d.errorCount)
    }

    @Test
    fun `dossier absent en erreur`() {
        val d = DiagnosticCollector()
        loader().load(dir.resolve("absent"), d)
        assertTrue(d.hasErrors)
    }

    @Test
    fun `lien symbolique refuse`() {
        val outside = Files.createTempDirectory("aaengine-outside")
        val target = Files.writeString(outside.resolve("secret.yml"), item("secret"))
        try {
            val created =
                try {
                    Files.createSymbolicLink(dir.resolve("lien.yml"), target)
                    Files.createSymbolicLink(dir.resolve("lien-dossier"), outside)
                    true
                } catch (_: Exception) {
                    false
                }
            assumeTrue(created, "Création de lien symbolique impossible sur cette machine")
            val d = DiagnosticCollector()
            val catalog = loader().load(dir, d)
            assertTrue(catalog.items.isEmpty())
            assertEquals(2, d.errorCount)
            assertTrue(d.toList().all { it.message.contains("Lien symbolique") })
        } finally {
            Files.deleteIfExists(dir.resolve("lien.yml"))
            Files.deleteIfExists(dir.resolve("lien-dossier"))
            Files.deleteIfExists(target)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `dossier items en lien symbolique refuse`() {
        val real = Files.createDirectories(dir.resolve("reel"))
        Files.writeString(real.resolve("a.yml"), item("a"))
        val link = dir.resolve("items")
        val created =
            try {
                Files.createSymbolicLink(link, real)
                true
            } catch (_: Exception) {
                false
            }
        assumeTrue(created, "Création de lien symbolique impossible sur cette machine")
        try {
            val d = DiagnosticCollector()
            assertTrue(loader().load(link, d).items.isEmpty())
            assertTrue(d.hasErrors)
        } finally {
            Files.deleteIfExists(link)
        }
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
