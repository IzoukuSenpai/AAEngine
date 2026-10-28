package dev.aaengine.items

import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Parsing et validation purs (aucun serveur) du format d'items. */
class ItemFileParserTest {
    private val materials =
        MaterialCatalog(
            mapOf(
                "NETHERITE_AXE" to MaterialInfo(Material.NETHERITE_AXE, damageable = true),
                "EMERALD" to MaterialInfo(Material.EMERALD, damageable = false),
                "PAPER" to MaterialInfo(Material.PAPER, damageable = false),
            ),
        )
    private val parser = ItemFileParser(materials)

    private fun parse(
        yaml: String,
        file: String = "items/test.yml",
    ): Pair<List<ItemDefinitionSpec>, DiagnosticCollector> {
        val d = DiagnosticCollector()
        return parser.parse(file, yaml.trimIndent(), d) to d
    }

    private fun DiagnosticCollector.errors(): List<Diagnostic> = toList().filter { it.severity == Severity.ERROR }

    private fun plain(c: Component?) = c?.let { PlainTextComponentSerializer.plainText().serialize(it) }

    @Test
    fun `fichier valide complet`() {
        val (specs, d) =
            parse(
                """
                namespace: demo
                items:
                  forest_axe:
                    item:
                      material: netherite_axe
                      name: "<green>Forest Axe"
                      lore: ["<gray>Ligne 1", "<italic>Ligne 2"]
                    model:
                      item_model: demo:tools/forest_axe
                      custom_model_data: 1001
                    components:
                      unbreakable: true
                      glint: false
                      hide_tooltip: true
                  ruby:
                    item:
                      material: minecraft:emerald
                    model:
                      custom_model_data:
                        floats: [1.5, 2]
                        flags: [true]
                        strings: ["rouge"]
                        colors: ["#FF0000"]
                    components:
                      max_stack_size: 16
                """,
            )
        assertEquals(0, d.errorCount, d.toList().toString())
        assertEquals(2, specs.size)
        val axe = specs.first { it.id == Key.key("demo", "forest_axe") }
        assertEquals(Material.NETHERITE_AXE, axe.material)
        assertEquals("Forest Axe", plain(axe.name))
        assertEquals(listOf("Ligne 1", "Ligne 2"), axe.lore.map { plain(it) })
        assertEquals(TextDecoration.State.FALSE, axe.lore[0].decoration(TextDecoration.ITALIC))
        val explicit = axe.lore[1]
        assertTrue(
            explicit.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE ||
                explicit.children().any { it.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE },
            "une ligne explicitement italique le reste",
        )
        assertEquals(Key.key("demo", "tools/forest_axe"), axe.itemModel)
        assertEquals(listOf(1001f), axe.customModelData!!.floats)
        assertTrue(axe.unbreakable)
        assertEquals(false, axe.glint)
        assertTrue(axe.hideTooltip)
        assertNull(axe.maxStackSize)
        assertEquals("items/test.yml", axe.source)
        assertEquals("items.forest_axe", axe.nodePath)

        val ruby = specs.first { it.id == Key.key("demo", "ruby") }
        assertEquals(Material.EMERALD, ruby.material)
        assertNull(ruby.name)
        assertEquals(CustomModelDataSpec(listOf(1.5f, 2f), listOf(true), listOf("rouge"), listOf(0xFF0000)), ruby.customModelData)
        assertEquals(16, ruby.maxStackSize)
        assertNull(ruby.glint)
    }

    @Test
    fun `namespace par defaut et modele sans namespace`() {
        val (specs, d) = parse("items:\n  a:\n    item: { material: PAPER }\n    model: { item_model: paper }\n")
        assertFalse(d.hasErrors)
        assertEquals(Key.key("aaengine", "a"), specs.single().id)
        assertEquals(Key.key("minecraft", "paper"), specs.single().itemModel)
    }

    @Test
    fun `cle inconnue avec suggestion`() {
        val (specs, d) =
            parse(
                """
                items:
                  a:
                    item:
                      materail: PAPER
                    componets: {}
                """,
            )
        assertTrue(specs.isEmpty())
        val errors = d.errors()
        val typo = errors.first { it.path == "items.a.item.materail" }
        assertEquals("material", typo.suggestion)
        assertEquals("components", errors.first { it.path == "items.a.componets" }.suggestion)
        assertTrue(errors.any { it.path == "items.a.item.material" }, "matériau manquant signalé")
        assertTrue(errors.all { it.file == "items/test.yml" })
    }

    @Test
    fun `cle racine inconnue`() {
        val (_, d) = parse("namepsace: demo\nitems: {}\n")
        assertEquals("namespace", d.errors().single().suggestion)
    }

    @Test
    fun `materiau inconnu ou invalide`() {
        val (specs, d) = parse("items:\n  a:\n    item: { material: NETHERITE_AX }\n  b:\n    item: { material: 12 }\n")
        assertTrue(specs.isEmpty())
        val errs = d.errors()
        assertEquals("NETHERITE_AXE", errs.first { it.path == "items.a.item.material" }.suggestion)
        assertNotNull(errs.first { it.path == "items.b.item.material" })
    }

    @Test
    fun `identifiants invalides`() {
        val (specs, d) =
            parse(
                """
                items:
                  "Forest Axe":
                    item: { material: PAPER }
                  "../evasion":
                    item: { material: PAPER }
                  "a//b":
                    item: { material: PAPER }
                  ok:
                    item: { material: PAPER }
                """,
            )
        assertEquals(listOf(Key.key("aaengine", "ok")), specs.map { it.id })
        assertEquals(3, d.errorCount)
        assertEquals("forest_axe", d.errors().first { it.path == "items.Forest Axe" }.suggestion)

        val (none, d2) = parse("namespace: \"Mauvais NS\"\nitems:\n  a:\n    item: { material: PAPER }\n")
        assertTrue(none.isEmpty())
        assertEquals("namespace", d2.errors().single().path)
        assertTrue(ItemIds.isValidValue("outils/hache.v2"))
        assertFalse(ItemIds.isValidValue("x".repeat(129)))
        assertFalse(ItemIds.isValidNamespace("n".repeat(65)))
        assertNull(ItemIds.parseKey("demo:../x", "aaengine"))
    }

    @Test
    fun `custom model data valide et invalide`() {
        val (specs, d) =
            parse(
                """
                items:
                  a:
                    item: { material: PAPER }
                    model:
                      custom_model_data:
                        floats: [1, .inf]
                        colors: ["red", "#00FF00"]
                        flags: ["oui"]
                        colours: []
                  b:
                    item: { material: PAPER }
                    model:
                      custom_model_data:
                        strings: [${(1..33).joinToString(", ") { "\"s$it\"" }}]
                  c:
                    item: { material: PAPER }
                    model: { custom_model_data: 1.5 }
                """,
            )
        assertTrue(specs.isEmpty())
        val errs = d.errors()
        assertTrue(errs.any { it.path == "items.a.model.custom_model_data.floats.1" })
        assertEquals("#RRGGBB", errs.first { it.path == "items.a.model.custom_model_data.colors.0" }.suggestion)
        assertTrue(errs.any { it.path == "items.a.model.custom_model_data.flags.0" })
        assertEquals("colors", errs.first { it.path == "items.a.model.custom_model_data.colours" }.suggestion)
        assertTrue(errs.any { it.path == "items.b.model.custom_model_data.strings" && it.message.contains("max 32") })
        assertTrue(errs.any { it.path == "items.c.model.custom_model_data" })
    }

    @Test
    fun `composants bornes et coherence durabilite`() {
        val (specs, d) =
            parse(
                """
                items:
                  a:
                    item: { material: PAPER }
                    components: { max_stack_size: 100 }
                  b:
                    item: { material: NETHERITE_AXE }
                    components: { max_stack_size: 2 }
                  c:
                    item: { material: PAPER }
                    components: { unbreakable: "yes please" }
                """,
            )
        assertTrue(specs.isEmpty())
        val paths = d.errors().map { it.path }
        assertTrue("items.a.components.max_stack_size" in paths)
        assertTrue("items.b.components.max_stack_size" in paths)
        assertTrue("items.c.components.unbreakable" in paths)
    }

    @Test
    fun `lore borne`() {
        val lines = (1..65).joinToString("\n") { "        - \"l$it\"" }
        val (specs, d) = parse("items:\n  a:\n    item:\n      material: PAPER\n      lore:\n$lines\n")
        assertTrue(specs.isEmpty())
        assertTrue(
            d
                .errors()
                .single()
                .message
                .contains("max 64"),
        )
    }

    @Test
    fun `erreur de syntaxe avec ligne`() {
        val (specs, d) = parse("items:\n  a:\n    item: { material: PAPER\n")
        assertTrue(specs.isEmpty())
        val err = d.errors().single()
        assertEquals("items/test.yml", err.file)
        assertNotNull(err.line)
    }

    @Test
    fun `fichier vide ou sans items en avertissement`() {
        val (s1, d1) = parse("")
        assertTrue(s1.isEmpty())
        assertFalse(d1.hasErrors)
        assertEquals(1, d1.warningCount)
        val (_, d2) = parse("namespace: demo\n")
        assertFalse(d2.hasErrors)
        val (_, d3) = parse("- a\n- b\n")
        assertTrue(d3.hasErrors)
    }

    @Test
    fun `balises MiniMessage dangereuses non interpretees`() {
        val (specs, d) = parse("items:\n  a:\n    item:\n      material: PAPER\n      name: \"<click:run_command:/op moi>Nom\"\n")
        assertFalse(d.hasErrors)
        assertNull(specs.single().name!!.clickEvent())
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
