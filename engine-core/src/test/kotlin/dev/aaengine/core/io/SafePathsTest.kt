package dev.aaengine.core.io

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

/** Politique de chemins (§51) et lecture bornée. */
class SafePathsTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `chemin relatif simple accepte`() {
        val p = SafePaths.resolve(root, "items/outils/a.yml")
        assertTrue(p.startsWith(root.toAbsolutePath().normalize()))
        assertEquals("items/outils/a.yml", SafePaths.display(root, p))
    }

    @Test
    fun `remontee refusee`() {
        assertThrows<SafePaths.UnsafePathException> { SafePaths.resolve(root, "../evil.yml") }
        assertThrows<SafePaths.UnsafePathException> { SafePaths.resolve(root, "items/../../evil.yml") }
        assertThrows<SafePaths.UnsafePathException> { SafePaths.resolve(root, ".") }
        assertThrows<SafePaths.UnsafePathException> { SafePaths.resolve(root, "") }
        assertThrows<SafePaths.UnsafePathException> { SafePaths.resolve(root, "a\u0000b") }
    }

    @Test
    fun `chemin absolu refuse`() {
        val absolute = root.resolveSibling("ailleurs.yml").toAbsolutePath().toString()
        assertThrows<SafePaths.UnsafePathException> { SafePaths.resolve(root, absolute) }
    }

    @Test
    fun `lien symbolique refuse`() {
        val outside = Files.createTempDirectory("aaengine-outside")
        try {
            val link = root.resolve("items")
            val created =
                try {
                    Files.createSymbolicLink(link, outside)
                    true
                } catch (_: Exception) {
                    false
                }
            assumeTrue(created, "Création de lien symbolique impossible sur cette machine")
            assertThrows<SafePaths.UnsafePathException> { SafePaths.resolve(root, "items/a.yml") }
        } finally {
            Files.deleteIfExists(root.resolve("items"))
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `lecture bornee`() {
        val f = root.resolve("a.yml")
        Files.writeString(f, 0xFEFF.toChar() + "cle: valeur")
        assertEquals("cle: valeur", BoundedFiles.readUtf8(f, 64))
        assertThrows<BoundedFiles.TooLargeException> { BoundedFiles.readUtf8(f, 4) }
        Files.write(f, byteArrayOf(0xC3.toByte(), 0x28))
        assertThrows<CharacterCodingException> { BoundedFiles.readUtf8(f, 64) }
        assertThrows<IOException> { BoundedFiles.readUtf8(root, 64) }
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
