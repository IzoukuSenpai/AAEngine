package dev.aaengine.items

import dev.aaengine.core.diagnostic.DiagnosticCollector
import net.kyori.adventure.key.Key
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Clés liées au resource pack : model.texture/model/parent, tooltip_style, equippable, equipment. */
class ItemPackKeysTest {
    private val materials =
        MaterialCatalog(
            mapOf(
                "DIAMOND_SWORD" to MaterialInfo(Material.DIAMOND_SWORD, damageable = true),
                "PAPER" to MaterialInfo(Material.PAPER, damageable = false),
                "NETHERITE_CHESTPLATE" to MaterialInfo(Material.NETHERITE_CHESTPLATE, damageable = true),
            ),
        )
    private val parser = ItemFileParser(materials)

    private fun parse(yaml: String): Pair<ParsedItemFile, DiagnosticCollector> {
        val d = DiagnosticCollector()
        return parser.parseFile("items/test.yml", yaml.trimIndent(), d) to d
    }

    @Test
    fun `texture genere item_model automatiquement`() {
        val (file, d) =
            parse(
                """
                namespace: demo
                items:
                  ruby_sword:
                    item: { material: DIAMOND_SWORD }
                    model: { texture: item/ruby_sword }
                  layered:
                    item: { material: PAPER }
                    model:
                      texture: [demo:item/base, other:item/overlay]
                      parent: item/generated
                """,
            )
        assertEquals(0, d.errorCount, d.toList().toString())
        val sword = file.items.first { it.id.value() == "ruby_sword" }
        assertEquals(Key.key("demo", "ruby_sword"), sword.itemModel)
        assertEquals(ItemModelSource.Textures(listOf(Key.key("demo", "item/ruby_sword")), null), sword.model)
        val layered = file.items.first { it.id.value() == "layered" }
        val src = layered.model as ItemModelSource.Textures
        assertEquals(listOf(Key.key("demo", "item/base"), Key.key("other", "item/overlay")), src.layers)
        assertEquals(Key.key("minecraft", "item/generated"), src.parent)
    }

    @Test
    fun `modele fourni`() {
        val (file, d) = parse("items:\n  statue:\n    item: { material: PAPER }\n    model: { model: aaengine:item/statue }\n")
        assertEquals(0, d.errorCount, d.toList().toString())
        val spec = file.items.single()
        assertEquals(ItemModelSource.Provided(Key.key("aaengine", "item/statue")), spec.model)
        assertEquals(Key.key("aaengine", "statue"), spec.itemModel)
    }

    @Test
    fun `modes de modele exclusifs et parent sans texture`() {
        val (f1, d1) = parse("items:\n  a:\n    item: { material: PAPER }\n    model: { texture: item/a, item_model: x:y }\n")
        assertTrue(f1.items.isEmpty())
        assertTrue(d1.toList().any { it.message.contains("exclusifs") })
        val (f2, d2) = parse("items:\n  a:\n    item: { material: PAPER }\n    model: { parent: item/handheld }\n")
        assertTrue(f2.items.isEmpty())
        assertTrue(d2.toList().any { it.message.contains("parent") })
        val (f3, d3) = parse("items:\n  a:\n    item: { material: PAPER }\n    model: { texture: \"Item/A\" }\n")
        assertTrue(f3.items.isEmpty())
        assertEquals("item/a", d3.toList().single().suggestion)
    }

    @Test
    fun `item_model manuel conserve`() {
        val (file, d) = parse("items:\n  a:\n    item: { material: PAPER }\n    model: { item_model: minecraft:diamond }\n")
        assertEquals(0, d.errorCount)
        assertNull(file.items.single().model)
        assertEquals(Key.key("minecraft", "diamond"), file.items.single().itemModel)
    }

    @Test
    fun `tooltip_style equippable et sets d equipement`() {
        val (file, d) =
            parse(
                """
                namespace: demo
                equipment:
                  forest:
                    humanoid: demo:forest
                    humanoid_leggings: forest
                items:
                  forest_chestplate:
                    item: { material: NETHERITE_CHESTPLATE }
                    model: { texture: item/forest_chestplate }
                    components:
                      tooltip_style: demo:forest
                      equippable: { slot: chest, asset: forest }
                """,
            )
        assertEquals(0, d.errorCount, d.toList().toString())
        val set = file.equipment.single()
        assertEquals(Key.key("demo", "forest"), set.id)
        assertEquals(Key.key("demo", "forest"), set.humanoidLeggings)
        val spec = file.items.single()
        assertEquals(Key.key("demo", "forest"), spec.tooltipStyle)
        assertEquals(EquippableSpec(EquipSlot.CHEST, Key.key("demo", "forest")), spec.equippable)
    }

    @Test
    fun `equippable et equipment invalides`() {
        val (f1, d1) = parse("items:\n  a:\n    item: { material: PAPER }\n    components: { equippable: { slot: chst, asset: x } }\n")
        assertTrue(f1.items.isEmpty())
        assertEquals("chest", d1.toList().single().suggestion)
        val (f2, d2) = parse("equipment:\n  empty: {}\n  bad: { humanoide: a:b }\n")
        assertTrue(f2.equipment.isEmpty())
        assertTrue(d2.toList().any { it.suggestion == "humanoid" })
        assertTrue(d2.toList().any { it.message.contains("Au moins une couche") })
    }

    @Test
    fun `set d equipement en double entre fichiers`() {
        val loader = ItemCatalogLoader(parser)
        val d = DiagnosticCollector()
        val yaml = "equipment:\n  forest: { humanoid: a:b }\n"
        val catalog =
            loader.loadTexts(
                listOf(ItemCatalogLoader.SourceText("items/a.yml", yaml), ItemCatalogLoader.SourceText("items/b.yml", yaml)),
                d,
            )
        assertEquals(1, catalog.equipment.size)
        assertTrue(
            d
                .toList()
                .single()
                .message
                .contains("items/a.yml"),
        )
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
