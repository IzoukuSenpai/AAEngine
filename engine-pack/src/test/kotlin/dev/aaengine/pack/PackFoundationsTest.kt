package dev.aaengine.pack

import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.io.SafePaths
import dev.aaengine.pack.json.Json
import dev.aaengine.pack.json.JsonException
import dev.aaengine.pack.json.JsonNumber
import dev.aaengine.pack.json.JsonObject
import dev.aaengine.pack.source.PackSourceLoader
import dev.aaengine.pack.stage.PackBuildContext
import dev.aaengine.pack.stage.PackGraph
import dev.aaengine.pack.stage.PackGraphException
import dev.aaengine.pack.stage.PackStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** JSON strict, graphe d'étapes, chemins sûrs (safeResolve) et identifiants de ressources. */
class PackFoundationsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `json strict - lecture et ecriture canonique`() {
        val v = Json.parse("""{"b": [1, 2.5e3, true, null], "a": {"x": "é\n"}}""")
        assertEquals(JsonNumber("2.5e3"), ((v as JsonObject)["b"] as dev.aaengine.pack.json.JsonArray).items[1])
        assertEquals(
            "{\n  \"b\": [\n    1,\n    2.5e3,\n    true,\n    null\n  ],\n  \"a\": {\n    \"x\": \"é\\n\"\n  }\n}\n",
            Json.write(v),
        )
        assertEquals(v, Json.parse(Json.write(v)))
    }

    @Test
    fun `json strict - refus`() {
        for (bad in listOf(
            "{\"a\":1,}",
            "{\"a\":1 \"b\":2}",
            "[1,]",
            "{a:1}",
            "{\"a\":1}x",
            "// c\n{}",
            "\"\u0001\"",
            "{\"a\":1,\"a\":2}",
            "01",
            "",
        )) {
            assertThrows<JsonException>(bad) { Json.parse(bad) }
        }
        val deep = "[".repeat(100) + "]".repeat(100)
        val e = assertThrows<JsonException> { Json.parse(deep) }
        assertTrue(e.message!!.contains("profonde"))
    }

    @Test
    fun `graphe - ordre topologique, cycle et dependance inconnue`() {
        class S(
            override val id: String,
            vararg deps: String,
        ) : PackStage {
            override val dependencies = deps.toSet()

            override fun execute(ctx: PackBuildContext) = Unit
        }
        assertEquals(listOf("a", "b", "c"), PackGraph(listOf(S("c", "b"), S("b", "a"), S("a"))).order.map { it.id })
        assertThrows<PackGraphException> { PackGraph(listOf(S("a", "b"), S("b", "a"))) }
        assertThrows<PackGraphException> { PackGraph(listOf(S("a", "z"))) }
        assertThrows<PackGraphException> { PackGraph(listOf(S("a"), S("a"))) }
    }

    @Test
    fun `chemins du pack`() {
        assertNull(PackSourceLoader.pathProblem("assets/demo/textures/item/a_b-c.1.png"))
        assertNull(PackSourceLoader.pathProblem("assets/demo/textures/item/a.png.mcmeta"))
        assertNotNull(PackSourceLoader.pathProblem("assets/demo/textures/Item/a.png"))
        assertNotNull(PackSourceLoader.pathProblem("assets/demo/../x.png"))
        assertNotNull(PackSourceLoader.pathProblem("data/demo/x.json"))
        assertNotNull(PackSourceLoader.pathProblem("assets/demo/x.exe"))
        assertNotNull(PackSourceLoader.pathProblem("assets/demo/noext"))
        assertNotNull(PackSourceLoader.pathProblem("assets/Demo/x.png"))
        assertNotNull(PackSourceLoader.pathProblem("assets/demo/con.png"))
    }

    @Test
    fun `safeResolve - racine du pack`() {
        val layout = PackLayout(dir)
        assertTrue(layout.zipFile.startsWith(dir.toAbsolutePath().normalize()))
        assertThrows<SafePaths.UnsafePathException> { SafePaths.resolve(dir, "../hors.zip") }
        assertThrows<SafePaths.UnsafePathException> { SafePaths.resolve(dir, dir.resolve("abs").toAbsolutePath().toString()) }
        assertThrows<SafePaths.UnsafePathException> { SafePaths.resolve(dir, "pack/../../x") }
    }

    @Test
    fun `safeResolve - lien symbolique dans pack assets refuse`() {
        val outside = Files.createDirectories(dir.resolve("dehors"))
        PackTestSupport.write(outside, "secret.json", "{}")
        val assets = Files.createDirectories(dir.resolve("data/pack/assets/demo"))
        val link =
            try {
                Files.createSymbolicLink(assets.resolve("lang"), outside)
            } catch (_: IOException) {
                null
            } catch (_: UnsupportedOperationException) {
                null
            } catch (_: SecurityException) {
                null
            }
        assumeTrue(link != null, "création de lien symbolique non autorisée sur ce système")
        val d = DiagnosticCollector()
        val files = PackSourceLoader().load(dir.resolve("data/pack/assets"), dir.resolve("data/pack/external"), d)
        assertTrue(files.isEmpty())
        assertEquals(PackCodes.REFUSED_FILE, d.toList().single().code)
    }

    @Test
    fun `identifiants de ressources`() {
        assertEquals(ResourceId("minecraft", "item/handheld"), ResourceId.parse("item/handheld"))
        assertEquals("assets/demo/textures/item/x.png", ResourceId.parse("demo:item/x")!!.file("textures", ".png"))
        assertNull(ResourceId.parse("demo:../x"))
        assertNull(ResourceId.parse("Demo:x"))
        assertNull(ResourceId.parse("demo:a//b"))
        assertEquals(PackFormat.PARENT_HANDHELD, PackFormat.defaultParent("NETHERITE_PICKAXE"))
        assertEquals(PackFormat.PARENT_GENERATED, PackFormat.defaultParent("DIAMOND_CHESTPLATE"))
        assertEquals(PackFormat.PARENT_GENERATED, PackFormat.defaultParent("PAPER"))
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
