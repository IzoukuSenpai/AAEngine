package dev.aaengine.pack

import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.pack.PackTestSupport.id
import dev.aaengine.pack.PackTestSupport.png
import dev.aaengine.pack.PackTestSupport.write
import dev.aaengine.pack.zip.SafeZipReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Compilation complète : génération (golden), fusion, validation, zip déterministe et cache. */
class PackCompilerTest {
    @TempDir
    lateinit var dir: Path

    private val sword =
        PackItem(
            id = id("demo:ruby_sword"),
            source = "items/armes.yml",
            nodePath = "items.ruby_sword",
            model = PackItemModel.Generated(listOf(id("demo:item/ruby_sword")), id(PackFormat.defaultParent("DIAMOND_SWORD"))),
            itemModel = id("demo:ruby_sword"),
        )
    private val statue =
        PackItem(
            id = id("demo:statue"),
            source = "items/deco.yml",
            nodePath = "items.statue",
            model = PackItemModel.Provided(id("demo:item/statue")),
            itemModel = id("demo:statue"),
        )
    private val chestplate =
        PackItem(
            id = id("demo:forest_chestplate"),
            source = "items/armures.yml",
            nodePath = "items.forest_chestplate",
            model =
                PackItemModel.Generated(
                    listOf(id("demo:item/forest_chestplate")),
                    id(PackFormat.defaultParent("NETHERITE_CHESTPLATE")),
                ),
            itemModel = id("demo:forest_chestplate"),
            tooltipStyle = id("demo:forest"),
            equipmentAsset = id("demo:forest"),
        )
    private val forest = PackEquipment(id("demo:forest"), "items/armures.yml", "equipment.forest", id("demo:forest"), id("demo:forest"))
    private val fullInput = PackInput(listOf(sword, statue, chestplate), listOf(forest))

    private fun root(name: String = "data"): Path = dir.resolve(name)

    private fun assets(root: Path) = root.resolve("pack/assets")

    /** Sources complètes et valides pour [fullInput]. */
    private fun fullSources(root: Path) {
        val a = assets(root)
        write(a, "demo/textures/item/ruby_sword.png", png())
        write(a, "demo/textures/item/forest_chestplate.png", png())
        write(a, "demo/textures/block/statue.png", png())
        write(
            a,
            "demo/models/item/statue.json",
            """{"parent":"minecraft:block/block","textures":{"all":"demo:block/statue","particle":"#all"}}""",
        )
        write(a, "demo/textures/entity/equipment/humanoid/forest.png", png(64, 32))
        write(a, "demo/textures/entity/equipment/humanoid_leggings/forest.png", png(64, 32))
        write(a, "demo/textures/gui/sprites/tooltip/forest_background.png", png())
        write(
            a,
            "demo/textures/gui/sprites/tooltip/forest_background.png.mcmeta",
            """{"gui":{"scaling":{"type":"nine_slice","width":16,"height":16,"border":3}}}""",
        )
        write(a, "demo/textures/gui/sprites/tooltip/forest_frame.png", png())
        write(
            a,
            "demo/textures/gui/sprites/tooltip/forest_frame.png.mcmeta",
            """{"gui":{"scaling":{"type":"nine_slice","width":16,"height":16,"border":3}}}""",
        )
    }

    private fun build(
        root: Path,
        input: PackInput = fullInput,
        force: Boolean = false,
    ): Pair<PackOutcome, DiagnosticCollector> {
        val d = DiagnosticCollector()
        return PackCompiler(PackLayout(root)).build(input, d, force) to d
    }

    private fun entries(pack: BuiltPack): Map<String, String> =
        SafeZipReader().read(Files.readAllBytes(pack.file)).associate { it.name to String(it.data, Charsets.UTF_8) }

    private fun DiagnosticCollector.errors(): List<Diagnostic> = toList().filter { it.severity == Severity.ERROR }

    @Test
    fun `pack mcmeta au format 88 (golden)`() {
        val (outcome, d) = build(root(), PackInput.EMPTY)
        assertEquals(PackStatus.BUILT, outcome.status, d.toList().toString())
        val expected =
            """
            {
              "pack": {
                "description": "AAEngine resource pack",
                "min_format": [
                  88,
                  0
                ],
                "max_format": 88
              }
            }

            """.trimIndent()
        assertEquals(expected, entries(outcome.pack!!)["pack.mcmeta"])
    }

    @Test
    fun `epee 2D, modele 3D, armure et tooltip (golden)`() {
        val r = root()
        fullSources(r)
        val (outcome, d) = build(r)
        assertEquals(PackStatus.BUILT, outcome.status, d.toList().joinToString("\n") { it.format() })
        assertEquals(0, d.warningCount, d.toList().toString())
        val files = entries(outcome.pack!!)
        assertEquals(
            """
            {
              "parent": "minecraft:item/handheld",
              "textures": {
                "layer0": "demo:item/ruby_sword"
              }
            }

            """.trimIndent(),
            files["assets/demo/models/item/ruby_sword.json"],
        )
        assertEquals(
            """
            {
              "model": {
                "type": "minecraft:model",
                "model": "demo:item/ruby_sword"
              }
            }

            """.trimIndent(),
            files["assets/demo/items/ruby_sword.json"],
        )
        // Modèle fourni : seule la définition d'item est générée.
        assertTrue(files["assets/demo/items/statue.json"]!!.contains("\"model\": \"demo:item/statue\""))
        assertTrue(files.containsKey("assets/demo/models/item/statue.json"))
        // Armure : parent item/generated (pas un outil), set d'équipement à deux couches.
        assertTrue(files["assets/demo/models/item/forest_chestplate.json"]!!.contains("minecraft:item/generated"))
        assertEquals(
            """
            {
              "layers": {
                "humanoid": [
                  {
                    "texture": "demo:forest"
                  }
                ],
                "humanoid_leggings": [
                  {
                    "texture": "demo:forest"
                  }
                ]
              }
            }

            """.trimIndent(),
            files["assets/demo/equipment/forest.json"],
        )
        // Entrées triées.
        assertEquals(files.keys.sorted(), files.keys.toList())
    }

    @Test
    fun `zip deterministe entre deux builds`() {
        val a = root("a")
        val b = root("b")
        fullSources(a)
        fullSources(b)
        val (oa, _) = build(a)
        val (ob, _) = build(b)
        assertEquals(oa.pack!!.sha1, ob.pack!!.sha1)
        assertEquals(oa.pack!!.sha256, ob.pack!!.sha256)
        // Reconstruction forcée au même endroit : mêmes octets.
        val (forced, _) = build(a, force = true)
        assertEquals(PackStatus.BUILT, forced.status)
        assertEquals(oa.pack!!.sha256, forced.pack!!.sha256)
        assertEquals(40, oa.pack!!.sha1.length)
    }

    @Test
    fun `cache - second build sans changement saute la reconstruction`() {
        val r = root()
        fullSources(r)
        val (first, _) = build(r)
        assertEquals(PackStatus.BUILT, first.status)
        val modified = Files.getLastModifiedTime(first.pack!!.file)
        val (second, d) = build(r)
        assertEquals(PackStatus.UP_TO_DATE, second.status, d.toList().toString())
        assertEquals(first.pack!!.sha1, second.pack!!.sha1)
        assertEquals(modified, Files.getLastModifiedTime(second.pack!!.file))
        assertTrue(Files.readString(PackLayout(r).manifestFile).contains("\"fingerprint\""))

        // Une source modifiée invalide le cache.
        write(assets(r), "demo/lang/fr_fr.json", """{"item.demo.ruby_sword":"Épée de rubis"}""")
        val (third, _) = build(r)
        assertEquals(PackStatus.BUILT, third.status)
        assertNotEquals(first.pack!!.sha1, third.pack!!.sha1)

        // Un zip altéré sur disque est détecté et reconstruit.
        Files.write(third.pack!!.file, byteArrayOf(1, 2, 3))
        val (fourth, _) = build(r)
        assertEquals(PackStatus.BUILT, fourth.status)
        assertEquals(third.pack!!.sha1, fourth.pack!!.sha1)
    }

    @Test
    fun `changement d item invalide le cache`() {
        val r = root()
        fullSources(r)
        build(r)
        val changed =
            PackInput(
                listOf(sword.copy(model = PackItemModel.Generated(listOf(id("demo:item/ruby_sword")), id(PackFormat.PARENT_GENERATED)))),
                emptyList(),
            )
        val (outcome, _) = build(r, changed)
        assertEquals(PackStatus.BUILT, outcome.status)
    }

    @Test
    fun `texture manquante - ERROR PACK-E104 rattachee au YAML`() {
        val (outcome, d) = build(root(), PackInput(listOf(sword), emptyList()))
        assertEquals(PackStatus.FAILED, outcome.status)
        assertNull(outcome.pack)
        val err = d.errors().single()
        assertEquals(PackCodes.MISSING_TEXTURE, err.code)
        assertEquals("items/armes.yml", err.file)
        assertEquals("items.ruby_sword.model.texture", err.path)
        assertTrue(err.message.contains("assets/demo/textures/item/ruby_sword.png"))
        assertFalse(Files.exists(PackLayout(root()).zipFile))
    }

    @Test
    fun `echec - le pack precedent reste en place`() {
        val r = root()
        fullSources(r)
        val (ok, _) = build(r)
        Files.delete(assets(r).resolve("demo/textures/item/ruby_sword.png"))
        val (ko, _) = build(r)
        assertEquals(PackStatus.FAILED, ko.status)
        assertEquals(ok.pack!!.sha256, PackCompiler(PackLayout(r)).current()!!.sha256)
    }

    @Test
    fun `conflit de fusion nommant les deux sources`() {
        val r = root()
        write(assets(r), "demo/lang/fr_fr.json", """{"a":"1"}""")
        write(r, "pack/external/marche.zip", PackTestSupport.zip(mapOf("assets/demo/lang/fr_fr.json" to """{"a":"2"}""".toByteArray())))
        val (outcome, d) = build(r, PackInput.EMPTY)
        assertEquals(PackStatus.FAILED, outcome.status)
        val err = d.errors().single()
        assertEquals(PackCodes.MERGE_CONFLICT, err.code)
        assertTrue(err.message.contains("pack/external/marche.zip") && err.message.contains("pack/assets"), err.message)
    }

    @Test
    fun `contenu identique et atlas fusionnes sans conflit`() {
        val r = root()
        val same = """{"a":"1"}"""
        write(assets(r), "demo/lang/fr_fr.json", same)
        val atlasA = """{"sources":[{"type":"minecraft:directory","source":"custom","prefix":"custom/"}]}"""
        val atlasB = """{"sources":[{"type":"minecraft:single","resource":"demo:misc/x"}]}"""
        write(assets(r), "minecraft/atlases/blocks.json", atlasA)
        write(
            r,
            "pack/external/a.zip",
            PackTestSupport.zip(
                mapOf(
                    "assets/demo/lang/fr_fr.json" to same.toByteArray(),
                    "assets/minecraft/atlases/blocks.json" to atlasB.toByteArray(),
                ),
            ),
        )
        val (outcome, d) = build(r, PackInput.EMPTY)
        assertEquals(PackStatus.BUILT, outcome.status, d.toList().toString())
        val atlas = entries(outcome.pack!!)["assets/minecraft/atlases/blocks.json"]!!
        assertTrue(atlas.contains("demo:misc/x") && atlas.contains("\"prefix\": \"custom/\""), atlas)
    }

    @Test
    fun `atlas - sprite hors item et block ajoute a l atlas des items`() {
        val r = root()
        write(assets(r), "demo/textures/weapons/axe.png", png())
        val axe =
            sword.copy(
                id = id("demo:axe"),
                itemModel = id("demo:axe"),
                model = PackItemModel.Generated(listOf(id("demo:weapons/axe")), id(PackFormat.PARENT_HANDHELD)),
            )
        val (outcome, d) = build(r, PackInput(listOf(axe), emptyList()))
        assertEquals(PackStatus.BUILT, outcome.status, d.toList().toString())
        val atlas = entries(outcome.pack!!)[PackFormat.ITEMS_ATLAS_FILE]!!
        assertTrue(atlas.contains("\"resource\": \"demo:weapons/axe\""), atlas)
        assertEquals(PackCodes.ATLAS_SPRITES, d.toList().first { it.severity == Severity.INFO }.code)
    }

    @Test
    fun `tooltip_style et equipement manquants`() {
        val r = root()
        write(assets(r), "demo/textures/item/forest_chestplate.png", png())
        write(assets(r), "demo/textures/gui/sprites/tooltip/forest_background.png", png())
        val noSet = chestplate.copy(equipmentAsset = id("demo:absent"))
        val (outcome, d) = build(r, PackInput(listOf(noSet), listOf(forest)))
        assertEquals(PackStatus.FAILED, outcome.status)
        val codes = d.toList().mapNotNull { it.code }
        assertTrue(PackCodes.MISSING_TOOLTIP in codes, codes.toString())
        assertTrue(PackCodes.TOOLTIP_NO_MCMETA in codes, codes.toString())
        assertTrue(PackCodes.MISSING_EQUIPMENT in codes, codes.toString())
        // Textures du set déclaré absentes : erreur rattachée à la section equipment.
        val tex = d.errors().filter { it.code == PackCodes.MISSING_EQUIPMENT_TEXTURE }
        assertEquals(2, tex.size)
        assertEquals("equipment.forest", tex.first().path)
    }

    @Test
    fun `modele utilisateur - parent et texture manquants, references vanilla supposees presentes`() {
        val r = root()
        write(
            assets(r),
            "demo/models/item/statue.json",
            """{"parent":"demo:item/base","textures":{"0":"demo:block/absent","1":"minecraft:block/stone"}}""",
        )
        val (outcome, d) = build(r, PackInput(listOf(statue), emptyList()))
        assertEquals(PackStatus.FAILED, outcome.status)
        val codes = d.errors().map { it.code }.toSet()
        assertEquals(setOf(PackCodes.MISSING_PARENT, PackCodes.MISSING_TEXTURE), codes)
        assertTrue(d.toList().any { it.code == PackCodes.VANILLA_REFERENCE })
    }

    @Test
    fun `item_model manuel vers une definition absente`() {
        val manual = PackItem(id("demo:gem"), "items/a.yml", "items.gem", null, id("demo:gem"))
        val (_, d) = build(root(), PackInput(listOf(manual), emptyList()))
        assertEquals(PackCodes.MISSING_MODEL, d.errors().single().code)
        val vanilla = PackItem(id("demo:gem"), "items/a.yml", "items.gem", null, id("minecraft:diamond"))
        val (ok, _) = build(root("b"), PackInput(listOf(vanilla), emptyList()))
        assertEquals(PackStatus.BUILT, ok.status)
    }

    @Test
    fun `json invalide et png invalide`() {
        val r = root()
        write(assets(r), "demo/lang/fr_fr.json", "{\"a\": 1,}")
        write(assets(r), "demo/textures/item/x.png", "pas un png")
        write(assets(r), "demo/textures/item/huge.png", png(8192, 16))
        val (_, d) = build(r, PackInput.EMPTY)
        val codes = d.errors().map { it.code }
        assertEquals(1, codes.count { it == PackCodes.INVALID_JSON })
        assertEquals(2, codes.count { it == PackCodes.INVALID_PNG })
        assertNotNull(d.errors().first { it.code == PackCodes.INVALID_JSON }.line)
    }

    @Test
    fun `fichiers refuses dans pack assets`() {
        val r = root()
        write(assets(r), "demo/textures/item/Majuscule.png", png())
        write(assets(r), "demo/scripts/run.exe", "MZ")
        write(assets(r), "demo/shaders/core/x.fsh", "void main(){}")
        write(assets(r), ".cache/ignore.bin", "x")
        val (outcome, d) = build(r, PackInput.EMPTY)
        assertEquals(PackStatus.FAILED, outcome.status)
        assertEquals(3, d.errors().count { it.code == PackCodes.REFUSED_FILE }, d.toList().toString())
    }

    @Test
    fun `archive externe - entrees non conformes ignorees, archive dangereuse refusee`() {
        val r = root()
        write(
            r,
            "pack/external/b.zip",
            PackTestSupport.zip(
                mapOf(
                    "pack.mcmeta" to "{}".toByteArray(),
                    "data/x/y.json" to "{}".toByteArray(),
                    "assets/demo/shaders/core/x.fsh" to "x".toByteArray(),
                    "assets/demo/lang/en_us.json" to "{}".toByteArray(),
                ),
            ),
        )
        write(r, "pack/external/a.zip", RawZip().build(listOf(RawZip.E("../evil.png", png()))))
        write(r, "pack/external/notes.txt", "x")
        val (outcome, d) = build(r, PackInput.EMPTY)
        assertEquals(PackStatus.FAILED, outcome.status)
        val err = d.errors().single()
        assertEquals(PackCodes.UNSAFE_ZIP, err.code)
        assertEquals("pack/external/a.zip", err.file)
        assertEquals(3, d.toList().count { it.code == PackCodes.SKIPPED_ENTRY }, d.toList().toString())

        Files.delete(r.resolve("pack/external/a.zip"))
        val (ok, d2) = build(r, PackInput.EMPTY)
        assertEquals(PackStatus.BUILT, ok.status, d2.toList().toString())
        assertTrue(entries(ok.pack!!).containsKey("assets/demo/lang/en_us.json"))
    }

    @Test
    fun `validate n ecrit rien`() {
        val r = root()
        fullSources(r)
        val d = DiagnosticCollector()
        val outcome = PackCompiler(PackLayout(r)).validate(fullInput, d)
        assertEquals(PackStatus.VALIDATED, outcome.status, d.toList().toString())
        assertFalse(Files.exists(r.resolve("cache")))
    }

    @Test
    fun `ordre des etapes du DAG`() {
        assertEquals(
            listOf("LoadSources", "CacheCheck", "GenerateItemModels", "GenerateEquipment", "Merge", "Atlas", "Validate", "BuildZip"),
            PackCompiler(PackLayout(root())).buildOrder,
        )
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
