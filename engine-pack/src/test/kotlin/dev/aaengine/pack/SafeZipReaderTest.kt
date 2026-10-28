package dev.aaengine.pack

import dev.aaengine.pack.zip.SafeZipReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Lecteur zip strict face à des archives malveillantes synthétiques. */
class SafeZipReaderTest {
    private val reader = SafeZipReader()

    private fun reject(
        zip: ByteArray,
        expected: String,
        r: SafeZipReader = reader,
    ) {
        val e = assertThrows<SafeZipReader.ZipRejectedException> { r.read(zip) }
        assertTrue(e.message!!.contains(expected), "message : ${e.message}")
    }

    private fun one(
        name: String,
        configure: RawZip.E.() -> Unit = {},
    ) = RawZip().build(listOf(RawZip.E(name, "contenu".toByteArray()).apply(configure)))

    @Test
    fun `zip standard lu, dossiers ignores`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("assets/"))
            z.closeEntry()
            z.putNextEntry(ZipEntry("assets/demo/lang/fr_fr.json"))
            z.write("{}".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("assets/demo/vide.txt"))
            z.closeEntry()
        }
        val entries = reader.read(out.toByteArray())
        assertEquals(listOf("assets/demo/lang/fr_fr.json", "assets/demo/vide.txt"), entries.map { it.name })
        assertArrayEquals("{}".toByteArray(), entries[0].data)
    }

    @Test
    fun `zip brut stocke et deflate lus`() {
        val zip =
            RawZip().build(
                listOf(RawZip.E("a.txt", "abc".toByteArray(), method = 0), RawZip.E("b.txt", ByteArray(1000) { 'x'.code.toByte() })),
            )
        assertEquals(listOf("a.txt", "b.txt"), reader.read(zip).map { it.name })
    }

    @Test
    fun `zip slip et chemins dangereux refuses`() {
        reject(one("../evil.png"), "Remontée")
        reject(one("assets/../../evil.png"), "Remontée")
        reject(one("/etc/passwd"), "absolu")
        reject(one("C:/Windows/evil.dll"), "':'")
        reject(one("assets\\evil.png"), "Antislash")
        reject(one("assets/file.txt:stream"), "':'")
        reject(one("assets//double.png"), "vide")
        reject(one("assets/./x.png"), "Remontée")
        reject(one("assets/nul\u0000.png"), "contrôle")
    }

    @Test
    fun `noms reserves Windows et segments finissant par point refuses`() {
        reject(one("assets/CON.png"), "réservé")
        reject(one("assets/aux/x.png"), "réservé")
        reject(one("assets/lpt1.txt"), "réservé")
        reject(one("assets/com²"), "réservé")
        reject(one("assets/dossier./x.png"), "point")
        reject(one("assets/fin "), "espace")
        assertNull(SafeZipReader.nameProblem("assets/console.png"))
    }

    @Test
    fun `doublons par casse ou normalisation Unicode refuses`() {
        reject(RawZip().build(listOf(RawZip.E("a.png", byteArrayOf(1)), RawZip.E("A.png", byteArrayOf(2)))), "double")
        reject(RawZip().build(listOf(RawZip.E("\u00e9.png", byteArrayOf(1)), RawZip.E("e\u0301.png", byteArrayOf(2)))), "double")
    }

    @Test
    fun `nom local different du nom central refuse`() {
        reject(one("assets/ok.png") { localName = "../../ko.png" }, "Nom local")
    }

    @Test
    fun `entrees qui se chevauchent refusees`() {
        val b = RawZip.E("b.txt", "bbbb".toByteArray(), method = 0)
        val a = RawZip.E("a.txt", "aaaa".toByteArray(), method = 0, claimExtra = 30 + "b.txt".length)
        reject(RawZip().build(listOf(a, b)), "chevauchent")
    }

    @Test
    fun `bombe zip par ratio refusee`() {
        reject(RawZip().build(listOf(RawZip.E("bombe.bin", ByteArray(2 * 1024 * 1024)))), "Ratio")
    }

    @Test
    fun `taille declaree jamais crue`() {
        // Annonce 10 octets, en produit 100 : refus pendant l'inflate (plafond) ou à la comparaison.
        reject(one("x.bin") { declaredSize = 3 }, "incohérente")
        val small = SafeZipReader(ZipLimits(maxEntryBytes = 50))
        reject(RawZip().build(listOf(RawZip.E("x.bin", ByteArray(100)).apply { declaredSize = 10 })), "volumineuse", small)
    }

    @Test
    fun `plafonds par archive`() {
        val entries = (1..3).map { RawZip.E("f$it.txt", ByteArray(600)) }
        reject(RawZip().build(entries), "Trop d'entrées", SafeZipReader(ZipLimits(maxEntries = 2)))
        reject(RawZip().build(entries), "volumineuse", SafeZipReader(ZipLimits(maxTotalBytes = 1000)))
        reject(ByteArray(64), "volumineuse", SafeZipReader(ZipLimits(maxArchiveBytes = 32)))
    }

    @Test
    fun `flux deflate tronque ou avec donnees en trop`() {
        val data = ByteArray(4000) { (it * 7).toByte() }
        val deflated = RawZip.deflate(data)
        reject(RawZip().build(listOf(RawZip.E("t.bin", data).apply { compressed = deflated.copyOf(deflated.size / 2) })), "deflate")
        reject(RawZip().build(listOf(RawZip.E("t.bin", data).apply { compressed = deflated + byteArrayOf(1, 2, 3) })), "en trop")
    }

    @Test
    fun `crc incorrect refuse`() {
        reject(one("x.txt") { crc = 1234 }, "CRC32")
    }

    @Test
    fun `symlink, chiffrement et encodage de nom refuses`() {
        reject(
            one("assets/lien.png") {
                madeBy = 0x0314
                external = 0xA1FFL shl 16
            },
            "Lien symbolique",
        )
        reject(one("x.txt") { flags = 0x0801 }, "chiffrée")
        reject(one("caf\u00e9.txt") { flags = 0 }, "ASCII")
    }

    @Test
    fun `structure exacte exigee`() {
        reject(one("x.txt") + byteArrayOf(0, 0, 0), "introuvable")
        reject(ByteArray(10), "courte")
        reject(one("x.txt") { localOffset = 5 }, "local")
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
