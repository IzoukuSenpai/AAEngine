package dev.aaengine.paper.items

import dev.aaengine.api.event.AAEngineContentLoadedEvent
import dev.aaengine.api.item.AAItemService
import dev.aaengine.core.content.ItemEntry
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.engine.DefaultAAEngine
import dev.aaengine.core.log.EngineLog
import dev.aaengine.core.reload.ReloadService
import dev.aaengine.items.CustomModelDataSpec
import dev.aaengine.items.ItemCatalogLoader
import dev.aaengine.items.ItemDefinitionSpec
import dev.aaengine.items.ItemFileParser
import dev.aaengine.paper.ContentReloadListener
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.logging.Logger

/** Construction des modèles, création d'exemplaires et reload transactionnel sur serveur simulé. */
class PaperItemsTest {
    private lateinit var server: ServerMock

    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun loadedEvents() =
        server.pluginManager.firedEvents
            .toList()
            .filterIsInstance<AAEngineContentLoadedEvent>()

    private fun spec(
        id: String = "forest_axe",
        material: Material = Material.NETHERITE_AXE,
    ) = ItemDefinitionSpec(
        id = Key.key("aaengine", id),
        source = "items/test.yml",
        nodePath = "items.$id",
        material = material,
        name = Component.text("Forest Axe"),
        lore = listOf(Component.text("Ligne")),
        itemModel = Key.key("aaengine", "forest_axe"),
        customModelData = CustomModelDataSpec(listOf(1001f), listOf(true), listOf("rouge"), listOf(0x00FF00)),
        unbreakable = true,
        maxStackSize = null,
        glint = true,
        hideTooltip = true,
    )

    @Test
    fun `modele avec composants et PDC`() {
        val stack = TemplateBuilder.build(spec())
        assertEquals(Material.NETHERITE_AXE, stack.type)
        assertEquals(Component.text("Forest Axe"), stack.getData(DataComponentTypes.ITEM_NAME))
        assertEquals(listOf(Component.text("Ligne")), stack.getData(DataComponentTypes.LORE)?.lines())
        assertEquals(Key.key("aaengine", "forest_axe"), stack.getData(DataComponentTypes.ITEM_MODEL))
        val cmd = stack.getData(DataComponentTypes.CUSTOM_MODEL_DATA)
        assertNotNull(cmd)
        assertEquals(listOf(1001f), cmd!!.floats())
        assertEquals(listOf(true), cmd.flags())
        assertEquals(listOf("rouge"), cmd.strings())
        assertEquals(listOf(Color.fromRGB(0x00FF00)), cmd.colors())
        assertTrue(stack.hasData(DataComponentTypes.UNBREAKABLE))
        assertEquals(true, stack.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE))
        assertEquals(true, stack.getData(DataComponentTypes.TOOLTIP_DISPLAY)?.hideTooltip())
        val pdc = stack.persistentDataContainer
        assertEquals("aaengine:forest_axe", pdc.get(DefaultAAEngine.ITEM_ID_KEY, PersistentDataType.STRING))
        assertEquals(AAItemService.SCHEMA_VERSION, pdc.get(DefaultAAEngine.SCHEMA_KEY, PersistentDataType.INTEGER))

        val stackable = TemplateBuilder.build(spec("ruby", Material.EMERALD).copy(maxStackSize = 16, unbreakable = false))
        assertEquals(16, stackable.getData(DataComponentTypes.MAX_STACK_SIZE))
        assertFalse(stackable.hasData(DataComponentTypes.UNBREAKABLE))
    }

    @Test
    fun `create clone le modele et idOf fait l'aller-retour`() {
        val engine = DefaultAAEngine("test")
        val s = spec("ruby", Material.EMERALD).copy(maxStackSize = 16, unbreakable = false)
        engine.publish(mapOf(s.id to ItemEntry(s.id, s.material, s.itemModel, TemplateBuilder.build(s))), emptyList())

        val a = engine.items.create(s.id, 64)!!
        val b = engine.items.create(s.id)!!
        assertNotSame(a, b)
        // Borne = maxStackSize de l'exemplaire (composant max_stack_size sur Paper ; matériau sous MockBukkit).
        assertEquals(minOf(64, a.maxStackSize), a.amount)
        assertEquals(1, b.amount)
        // Modifier un exemplaire ne touche ni le modèle ni les exemplaires suivants.
        val marker = org.bukkit.NamespacedKey("test", "marker")
        a.editPersistentDataContainer { it.set(marker, PersistentDataType.BYTE, 1) }
        val c = engine.items.create(s.id)!!
        assertFalse(c.persistentDataContainer.has(marker))
        assertEquals(s.id, engine.items.idOf(c))
        assertEquals(s.id, engine.items.idOf(a))
        assertEquals(s.id, engine.items.idOf(b))
        assertNull(
            engine.items.idOf(
                org.bukkit.inventory.ItemStack
                    .of(Material.EMERALD),
            ),
        )
        assertNull(engine.items.create(Key.key("aaengine", "absent")))
    }

    @Test
    fun `reload transactionnel bout en bout`() {
        val engine = DefaultAAEngine("test")
        val direct = Executor { it.run() }
        val loader = ItemCatalogLoader(ItemFileParser(PaperMaterials.catalog()))
        val listener = ContentReloadListener(engine, server.pluginManager, EngineLog(Logger.getLogger("AAEngineTest")))
        val service = ReloadService(engine, ItemsContentCompiler({ dir }, loader), direct, direct, listener)
        val file = dir.resolve("outils.yml")
        Files.writeString(file, "items:\n  axe:\n    item: { material: NETHERITE_AXE, name: \"<green>Axe\" }\n")

        val first = service.reload().get()
        assertTrue(first.report.success, first.diagnostics.toString())
        assertEquals(1L, engine.contentRevision)
        assertTrue(loadedEvents().any { it.contentRevision == 1L && it.initial })
        val axe = Key.key("aaengine", "axe")
        val snapshot = engine.snapshot()

        // Fichier cassé : rien n'est publié, l'instantané précédent reste actif.
        server.pluginManager.clearEvents()
        Files.writeString(file, "items:\n  axe:\n    item: { material: NETHERITE_AXE }\n  bad:\n    item: { material: NOPE }\n")
        val failed = service.reload().get()
        assertFalse(failed.report.success)
        assertEquals(1, failed.report.errorCount)
        assertEquals("items/outils.yml", failed.diagnostics.first().file)
        assertTrue(snapshot === engine.snapshot())
        assertNotNull(engine.items.create(axe))
        assertTrue(loadedEvents().isEmpty())
        assertEquals(failed.report, engine.lastReload)

        // Correction : nouvelle révision, événement non initial.
        Files.writeString(file, "items:\n  axe:\n    item: { material: NETHERITE_AXE }\n  ruby:\n    item: { material: EMERALD }\n")
        val ok = service.reload().get()
        assertTrue(ok.report.success)
        assertEquals(2L, engine.contentRevision)
        assertEquals(setOf(axe, Key.key("aaengine", "ruby")), engine.items.ids())
        assertTrue(loadedEvents().any { it.contentRevision == 2L && !it.initial })
    }

    @Test
    fun `fichier d'exemple livre valide`() {
        val content = javaClass.classLoader.getResourceAsStream("defaults/items/exemple.yml")!!.use { String(it.readAllBytes()) }
        val d = DiagnosticCollector()
        val specs = ItemFileParser(PaperMaterials.catalog()).parse("items/exemple.yml", content, d)
        assertEquals(0, d.errorCount + d.warningCount, d.toList().toString())
        assertEquals(setOf("aaengine:forest_axe", "aaengine:ruby"), specs.map { it.id.asString() }.toSet())
        specs.forEach { TemplateBuilder.build(it) }
    }

    @Test
    fun `catalogue des materiaux`() {
        val catalog = PaperMaterials.catalog()
        assertNotNull(catalog.resolve("netherite_axe"))
        assertTrue(catalog.resolve("NETHERITE_AXE")!!.damageable)
        assertFalse(catalog.resolve("EMERALD")!!.damageable)
        assertNull(catalog.resolve("AIR"))
        assertEquals("DIAMOND_SWORD", catalog.suggest("DIAMOND_SWROD"))
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
