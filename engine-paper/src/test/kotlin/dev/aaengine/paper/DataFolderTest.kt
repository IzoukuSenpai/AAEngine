package dev.aaengine.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/** Création des fichiers par défaut : jamais d'écrasement, exemple uniquement au premier démarrage. */
class DataFolderTest {
    @TempDir
    lateinit var root: Path

    private val resources: (String) -> InputStream? = { javaClass.classLoader.getResourceAsStream(it) }

    @Test
    fun `premier demarrage cree config et exemple`() {
        val folder = DataFolder(root.resolve("AAEngine"))
        val created = folder.ensureDefaults(resources)
        assertEquals(listOf("config.yml", "items/", "items/exemple.yml"), created)
        assertTrue(Files.isRegularFile(folder.configFile))
        assertTrue(Files.isRegularFile(folder.itemsDir.resolve("exemple.yml")))
        assertTrue(folder.readConfig()!!.contains("config-version"))
    }

    @Test
    fun `fichiers existants jamais ecrases ni recrees`() {
        val folder = DataFolder(root)
        folder.ensureDefaults(resources)
        Files.writeString(folder.configFile, "debug: {}\n")
        Files.delete(folder.itemsDir.resolve("exemple.yml"))

        val created = folder.ensureDefaults(resources)
        assertTrue(created.isEmpty())
        assertEquals("debug: {}\n", Files.readString(folder.configFile))
        assertFalse(Files.exists(folder.itemsDir.resolve("exemple.yml")), "l'exemple n'est créé qu'au premier démarrage")
    }

    @Test
    fun `items supprime recree vide sans exemple`() {
        val folder = DataFolder(root)
        folder.ensureDefaults(resources)
        Files.delete(folder.itemsDir.resolve("exemple.yml"))
        Files.delete(folder.itemsDir)

        val created = folder.ensureDefaults(resources)
        assertEquals(listOf("items/"), created)
        assertTrue(Files.isDirectory(folder.itemsDir))
        assertFalse(Files.exists(folder.itemsDir.resolve("exemple.yml")), "pas d'exemple hors premier démarrage")
    }

    @Test
    fun `config absente lue comme nulle`() {
        val folder = DataFolder(root)
        assertEquals(null, folder.readConfig())
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
