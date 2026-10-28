package dev.aaengine.paper.pack

import dev.aaengine.core.config.PackDistribution
import dev.aaengine.core.config.PackSettings
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.log.EngineLog
import dev.aaengine.items.ItemCatalogLoader
import dev.aaengine.items.ItemFileParser
import dev.aaengine.pack.PackCache
import dev.aaengine.pack.PackCodes
import dev.aaengine.pack.PackCompiler
import dev.aaengine.pack.PackLayout
import dev.aaengine.pack.PackStatus
import dev.aaengine.pack.zip.SafeZipReader
import dev.aaengine.paper.DataFolder
import dev.aaengine.paper.items.PaperMaterials
import dev.aaengine.paper.items.TemplateBuilder
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** Chaîne complète YAML → pack, composants tooltip_style / equippable et distribution `external`. */
class PaperPackTest {
    @TempDir
    lateinit var dir: Path

    private val resources: (String) -> InputStream? = { javaClass.classLoader.getResourceAsStream(it) }

    @BeforeEach
    fun setUp() {
        MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private val yaml =
        """
        namespace: demo
        equipment:
          forest:
            humanoid: forest
            humanoid_leggings: forest
        items:
          ruby_sword:
            item: { material: DIAMOND_SWORD, name: "<red>Épée de rubis" }
            model: { texture: item/ruby_sword }
          forest_chestplate:
            item: { material: NETHERITE_CHESTPLATE }
            model: { texture: item/forest_chestplate }
            components:
              tooltip_style: forest
              equippable: { slot: chest, asset: forest }
        """.trimIndent()

    private fun png(): ByteArray = resources("defaults/pack/assets/aaengine/textures/item/forest_axe.png")!!.use { it.readAllBytes() }

    private fun put(relative: String) {
        val target = dir.resolve("pack/assets").resolve(relative)
        Files.createDirectories(target.parent)
        Files.write(target, png())
    }

    @Test
    fun `yaml vers pack complet`() {
        put("demo/textures/item/ruby_sword.png")
        put("demo/textures/item/forest_chestplate.png")
        put("demo/textures/entity/equipment/humanoid/forest.png")
        put("demo/textures/entity/equipment/humanoid_leggings/forest.png")
        put("demo/textures/gui/sprites/tooltip/forest_background.png")
        put("demo/textures/gui/sprites/tooltip/forest_frame.png")
        val d = DiagnosticCollector()
        val catalog =
            ItemCatalogLoader(
                ItemFileParser(PaperMaterials.catalog()),
            ).loadTexts(listOf(ItemCatalogLoader.SourceText("items/demo.yml", yaml)), d)
        assertEquals(0, d.errorCount, d.toList().toString())
        val input = PackInputs.from(catalog, d)
        assertEquals(
            "minecraft:item/handheld",
            (
                input.items
                    .first {
                        it.id.path == "ruby_sword"
                    }.model as dev.aaengine.pack.PackItemModel.Generated
            ).parent.asString(),
        )

        val outcome = PackCompiler(PackLayout(dir)).build(input, d)
        assertEquals(PackStatus.BUILT, outcome.status, d.toList().joinToString("\n") { it.format() })
        val names = SafeZipReader().read(Files.readAllBytes(outcome.pack!!.file)).map { it.name }.toSet()
        assertTrue("assets/demo/items/ruby_sword.json" in names)
        assertTrue("assets/demo/models/item/forest_chestplate.json" in names)
        assertTrue("assets/demo/equipment/forest.json" in names)
        // Sprites de tooltip sans .mcmeta : avertissements.
        assertEquals(2, d.warningCount)

        // Composants de l'ItemStack.
        val chest = catalog.items.getValue(Key.key("demo", "forest_chestplate"))
        val stack = TemplateBuilder.build(chest)
        assertEquals(Key.key("demo", "forest_chestplate"), stack.getData(DataComponentTypes.ITEM_MODEL))
        assertEquals(Key.key("demo", "forest"), stack.getData(DataComponentTypes.TOOLTIP_STYLE))
        val equippable = stack.getData(DataComponentTypes.EQUIPPABLE)
        assertNotNull(equippable)
        assertEquals(EquipmentSlot.CHEST, equippable!!.slot())
        assertEquals(Key.key("demo", "forest"), equippable.assetId())
    }

    @Test
    fun `service de pack - build hors thread puis cache`() {
        val folder = DataFolder(dir)
        folder.ensureDefaults(resources)
        folder.ensurePackDirs(resources, withExample = true)
        val direct = Executor { it.run() }
        val service =
            PackService(PackLayout(dir), {
                folder.itemsDir
            }, ItemCatalogLoader(ItemFileParser(PaperMaterials.catalog())), direct, Logger.getLogger("AAEngineTest"))
        val first = service.build(force = false)!!.get(10, TimeUnit.SECONDS)
        assertEquals(PackStatus.BUILT, first.outcome.status, first.diagnostics.joinToString("\n") { it.format() })
        assertEquals(first.outcome.pack, service.current)
        val second = service.build(force = false)!!.get(10, TimeUnit.SECONDS)
        assertEquals(PackStatus.UP_TO_DATE, second.outcome.status)
        assertEquals(
            PackStatus.VALIDATED,
            service
                .validate()!!
                .get(10, TimeUnit.SECONDS)
                .outcome.status,
        )
        assertEquals(first.outcome.pack!!.sha1, service.loadCurrent().get(10, TimeUnit.SECONDS)!!.sha1)

        // M3 : le build ne change pas le pack annoncé ; publish le bascule.
        assertNull(service.published)
        assertEquals(first.outcome.pack!!.sha1, service.publish()!!.get(10, TimeUnit.SECONDS)!!.sha1)
        assertEquals(first.outcome.pack!!.sha1, service.published!!.sha1)
        Files.writeString(
            dir.resolve("pack/assets/aaengine/lang/fr_fr.json"),
            "{}".also {
                Files.createDirectories(dir.resolve("pack/assets/aaengine/lang"))
            },
        )
        val rebuilt = service.build(force = false)!!.get(10, TimeUnit.SECONDS)
        assertEquals(PackStatus.BUILT, rebuilt.outcome.status)
        assertEquals(first.outcome.pack!!.sha1, service.published!!.sha1, "build seul : pack annoncé inchangé")
    }

    @Test
    fun `cle non convertible - erreur explicite, jamais ignoree`() {
        val spec =
            ItemCatalogLoader(ItemFileParser(PaperMaterials.catalog()))
                .loadTexts(
                    listOf(ItemCatalogLoader.SourceText("items/a.yml", "items:\n  x:\n    item: { material: PAPER }\n")),
                    DiagnosticCollector(),
                ).items.values
                .single()
        // Clé Adventure valide mais chemin trop long pour le pack (> 256).
        val long = spec.copy(tooltipStyle = Key.key("demo", "a".repeat(300)))
        val d = DiagnosticCollector()
        val input = PackInputs.from(dev.aaengine.items.ItemCatalog(mapOf(long.id to long), 1), d)
        assertTrue(input.items.isEmpty())
        val err = d.toList().single()
        assertEquals(PackCodes.INVALID_STRUCTURE, err.code)
        assertEquals("items.x.components.tooltip_style", err.path)
    }

    @Test
    fun `dossiers du pack crees avec la texture d exemple`() {
        val folder = DataFolder(dir)
        assertEquals(
            listOf("pack/assets/", "pack/assets/aaengine/textures/item/forest_axe.png", "pack/external/"),
            folder.ensurePackDirs(resources, withExample = true),
        )
        assertTrue(folder.ensurePackDirs(resources, withExample = true).isEmpty())
    }

    @Test
    fun `distribution external - requete avec sha1 et url substituee`() {
        val sha1 = "0123456789abcdef0123456789abcdef01234567"
        val pack = PackCache.Published(10, sha1, "0".repeat(64))
        val log = EngineLog(Logger.getLogger("AAEngineTest"))
        val settings =
            PackSettings(PackDistribution.EXTERNAL, "https://cdn.example.com/aaengine-{sha1}.zip", required = true, prompt = "Pack requis")
        val request = PackDistributor(settings, { pack }, log).request()
        assertNotNull(request)
        val info = request!!.packs().single()
        assertEquals("https://cdn.example.com/aaengine-$sha1.zip", info.uri().toString())
        assertEquals(sha1, info.hash())
        assertEquals(PackDistributor.PACK_ID, info.id())
        assertTrue(request.required())

        assertNull(PackDistributor(settings, { null }, log).request(), "aucun pack construit : rien n'est envoyé")
        assertNull(PackDistributor(PackSettings(), { pack }, log).request(), "distribution none")
        val local = settings.copy(url = "https://127.0.0.1/{sha1}.zip")
        assertNull(PackDistributor(local, { pack }, log).request(), "adresse locale refusée")
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
