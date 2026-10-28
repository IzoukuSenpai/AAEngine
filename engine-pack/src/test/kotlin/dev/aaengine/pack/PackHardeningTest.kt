package dev.aaengine.pack

import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.pack.PackTestSupport.id
import dev.aaengine.pack.PackTestSupport.png
import dev.aaengine.pack.PackTestSupport.write
import dev.aaengine.pack.json.Json
import dev.aaengine.pack.json.JsonObject
import dev.aaengine.pack.json.JsonString
import dev.aaengine.pack.zip.SafeZipReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Corrections de la revue de sécurité (M1, M2, M3, M5, M7, B1, B2, B7). */
class PackHardeningTest {
    @TempDir
    lateinit var dir: Path

    private fun assets() = dir.resolve("pack/assets")

    private fun build(
        input: PackInput = PackInput.EMPTY,
        limits: PackLimits = PackLimits.DEFAULT,
        force: Boolean = false,
    ): Pair<PackOutcome, DiagnosticCollector> {
        val d = DiagnosticCollector()
        return PackCompiler(PackLayout(dir), limits).build(input, d, force) to d
    }

    @Test
    fun `M1 - pack trop gros ne remplace ni ne supprime le pack precedent`() {
        write(assets(), "demo/lang/fr_fr.json", """{"a":"1"}""")
        val (ok, _) = build()
        assertEquals(PackStatus.BUILT, ok.status)
        val layout = PackLayout(dir)
        val zipBefore = Files.readAllBytes(layout.zipFile)
        val manifestBefore = Files.readAllBytes(layout.manifestFile)

        write(assets(), "demo/lang/en_us.json", """{"b":"2"}""")
        val (tooBig, d) = build(limits = PackLimits(maxOutputBytes = 64))
        assertEquals(PackStatus.FAILED, tooBig.status)
        val err = d.toList().single { it.severity == Severity.ERROR }
        assertEquals(PackCodes.LIMIT, err.code)
        assertTrue(err.message.contains("le pack précédent reste en place"))
        assertArrayEquals(zipBefore, Files.readAllBytes(layout.zipFile))
        assertArrayEquals(manifestBefore, Files.readAllBytes(layout.manifestFile))
        assertEquals(ok.pack!!.sha256, PackCompiler(PackLayout(dir)).current()!!.sha256)
        // Aucun temporaire laissé.
        assertTrue(Files.list(layout.cacheDir).use { s -> s.noneMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test
    fun `M2 - current recalcule les empreintes et refuse un manifeste falsifie`() {
        val (ok, _) = build()
        val layout = PackLayout(dir)
        val text = Files.readString(layout.manifestFile)
        val forged = text.replace(ok.pack!!.sha1, "f".repeat(40))
        assertNotEquals(text, forged)
        Files.writeString(layout.manifestFile, forged)
        assertNull(PackCompiler(layout).current(), "SHA-1 du manifeste jamais cru")
        Files.writeString(layout.manifestFile, text)
        val current = PackCompiler(layout).current()!!
        assertEquals(ok.pack!!.sha1, current.sha1)
        assertEquals(ok.pack!!.sha256, current.sha256)
    }

    @Test
    fun `M3 - le build ne change pas le pack annonce, publish le bascule`() {
        val compiler = PackCompiler(PackLayout(dir))
        build()
        assertNull(compiler.published())
        val first = compiler.publish()!!
        write(assets(), "demo/lang/fr_fr.json", """{"a":"1"}""")
        val (second, _) = build()
        assertNotEquals(first.sha1, second.pack!!.sha1)
        assertEquals(first.sha1, compiler.published()!!.sha1, "un build seul ne change pas le pack annoncé")
        assertEquals(second.pack!!.sha1, compiler.publish()!!.sha1)
        assertEquals(second.pack!!.sha1, compiler.published()!!.sha1)
    }

    @Test
    fun `M5 - plus de 5 calques - avertissement et calques tronques`() {
        val layers = (0..6).map { id("demo:item/l$it") }
        layers.forEach { write(assets(), "demo/textures/item/${it.path.removePrefix("item/")}.png", png()) }
        val item =
            PackItem(
                id("demo:multi"),
                "items/a.yml",
                "items.multi",
                PackItemModel.Generated(layers, id(PackFormat.PARENT_GENERATED)),
                id("demo:multi"),
            )
        val (outcome, d) = build(PackInput(listOf(item), emptyList()))
        assertEquals(PackStatus.BUILT, outcome.status, d.toList().toString())
        assertEquals(PackCodes.TOO_MANY_LAYERS, d.toList().single { it.severity == Severity.WARN }.code)
        val model =
            SafeZipReader().read(Files.readAllBytes(outcome.pack!!.file)).first { it.name == "assets/demo/models/item/multi.json" }
        val textures = (Json.parse(String(model.data)) as JsonObject)["textures"] as JsonObject
        assertEquals(listOf("layer0", "layer1", "layer2", "layer3", "layer4"), textures.members.keys.toList())
    }

    @Test
    fun `M7 - ratio global de l archive et PackFile sans copie`() {
        // 40 entrées de 8 Kio de zéros : chacune sous le seuil de ratio par entrée, ensemble au-delà de 50 × l'archive.
        val zip = RawZip().build((1..40).map { RawZip.E("assets/demo/f$it.bin", ByteArray(8 * 1024)) })
        val e = assertThrows<SafeZipReader.ZipRejectedException> { SafeZipReader().read(zip) }
        assertTrue(e.message!!.contains("global"), e.message)
        assertTrue(ZipLimits.MAX_TOTAL_BYTES < PackLimits.MAX_TOTAL_BYTES)

        val bytes = byteArrayOf(1, 2, 3)
        assertSame(bytes, PackFile("a", bytes, "t").raw)
        val copy = PackFile.copyOf("a", bytes, "t")
        bytes[0] = 9
        assertEquals(1, copy.raw[0].toInt())
    }

    @Test
    fun `B1 - cycle de parents et variable de texture non definie`() {
        write(assets(), "demo/models/item/a.json", """{"parent":"demo:item/b"}""")
        write(assets(), "demo/models/item/b.json", """{"parent":"demo:item/a"}""")
        write(
            assets(),
            "demo/models/item/statue.json",
            """{"parent":"demo:item/base","textures":{"side":"#top"},"elements":[{"faces":{"north":{"texture":"#side"},"up":{"texture":"#bottom"}}}]}""",
        )
        write(assets(), "demo/models/item/base.json", """{"parent":"builtin/generated","textures":{"top":"demo:item/top"}}""")
        write(assets(), "demo/textures/item/top.png", png())
        val statue =
            PackItem(id("demo:statue"), "items/a.yml", "items.statue", PackItemModel.Provided(id("demo:item/statue")), id("demo:statue"))
        val (outcome, d) = build(PackInput(listOf(statue), emptyList()))
        assertEquals(PackStatus.FAILED, outcome.status)
        val errors = d.toList().filter { it.severity == Severity.ERROR }
        assertEquals(1, errors.count { it.code == PackCodes.PARENT_CYCLE }, errors.toString())
        val undefined = errors.single { it.code == PackCodes.UNDEFINED_TEXTURE_VARIABLE }
        assertTrue(undefined.message.contains("#bottom"))

        // Variables définies par la chaîne : aucune erreur.
        write(assets(), "demo/models/item/a.json", """{"parent":"builtin/generated"}""")
        write(
            assets(),
            "demo/models/item/base.json",
            """{"parent":"builtin/generated","textures":{"top":"demo:item/top","bottom":"#top"}}""",
        )
        val (ok, d2) = build(PackInput(listOf(statue), emptyList()))
        assertEquals(PackStatus.BUILT, ok.status, d2.toList().toString())
    }

    @Test
    fun `B2 - temporaires orphelins supprimes au build`() {
        val cache = Files.createDirectories(dir.resolve("cache/pack"))
        val orphan = Files.writeString(cache.resolve(".aaengine-pack.zip.0f8fad5b-d9cb-469f-a165-70867728950e.tmp"), "x")
        val keep = Files.writeString(cache.resolve("notes.tmp"), "x")
        build()
        assertFalse(Files.exists(orphan))
        assertTrue(Files.exists(keep), "seuls les temporaires d'AAEngine sont supprimés")
    }

    @Test
    fun `B7 - les bornes et la version des regles font partie de l empreinte`() {
        val cache = PackCache(PackLayout(dir))
        val a = cache.fingerprint(emptyList(), PackInput.EMPTY, PackLimits.DEFAULT)
        val b = cache.fingerprint(emptyList(), PackInput.EMPTY, PackLimits(maxTextureSide = 2048))
        assertNotEquals(a, b)
        build()
        val (second, _) = build(limits = PackLimits(maxTextureSide = 2048))
        assertEquals(PackStatus.BUILT, second.status, "bornes changées : reconstruction")
        assertNotNull(Files.readString(PackLayout(dir).manifestFile).let { (Json.parse(it) as JsonObject)["rules"] as? JsonString })
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
