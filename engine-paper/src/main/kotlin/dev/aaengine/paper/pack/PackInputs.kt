package dev.aaengine.paper.pack

import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.items.ItemCatalog
import dev.aaengine.items.ItemDefinitionSpec
import dev.aaengine.items.ItemModelSource
import dev.aaengine.pack.PackCodes
import dev.aaengine.pack.PackEquipment
import dev.aaengine.pack.PackFormat
import dev.aaengine.pack.PackInput
import dev.aaengine.pack.PackItem
import dev.aaengine.pack.PackItemModel
import dev.aaengine.pack.ResourceId
import net.kyori.adventure.key.Key

/**
 * Conversion du catalogue d'items (types Adventure/Bukkit) en entrées pures du compilateur de pack. Une clé
 * non convertible (règles de [ResourceId] plus strictes que celles d'Adventure) n'est jamais ignorée en
 * silence : erreur explicite rattachée au YAML, ce qui bloque le build (fail-closed).
 */
object PackInputs {
    @JvmStatic
    fun from(
        catalog: ItemCatalog,
        diagnostics: DiagnosticCollector,
    ): PackInput {
        val items = catalog.items.values.mapNotNull { item(it, diagnostics) }
        val equipment =
            catalog.equipment.values.mapNotNull { set ->
                val c = Converter(diagnostics, set.source, set.nodePath)
                val id = c.id(set.id, "")
                val humanoid = set.humanoid?.let { c.id(it, ".humanoid") }
                val leggings = set.humanoidLeggings?.let { c.id(it, ".humanoid_leggings") }
                if (c.failed || id == null) null else PackEquipment(id, set.source, set.nodePath, humanoid, leggings)
            }
        return PackInput(items, equipment)
    }

    private fun item(
        spec: ItemDefinitionSpec,
        diagnostics: DiagnosticCollector,
    ): PackItem? {
        val c = Converter(diagnostics, spec.source, spec.nodePath)
        val model =
            when (val m = spec.model) {
                null -> null
                is ItemModelSource.Textures ->
                    PackItemModel.Generated(
                        m.layers.mapNotNull { c.id(it, ".model.texture") },
                        m.parent?.let { c.id(it, ".model.parent") } ?: ResourceId.parse(PackFormat.defaultParent(spec.material.name))!!,
                    )
                is ItemModelSource.Provided -> c.id(m.model, ".model.model")?.let { PackItemModel.Provided(it) }
            }
        val id = c.id(spec.id, "")
        val itemModel = spec.itemModel?.let { c.id(it, ".model.item_model") }
        val tooltipStyle = spec.tooltipStyle?.let { c.id(it, ".components.tooltip_style") }
        val equipmentAsset = spec.equippable?.asset?.let { c.id(it, ".components.equippable.asset") }
        if (c.failed || id == null) return null
        return PackItem(id, spec.source, spec.nodePath, model, itemModel, tooltipStyle, equipmentAsset)
    }

    private class Converter(
        private val diagnostics: DiagnosticCollector,
        private val source: String,
        private val nodePath: String,
    ) {
        var failed = false
            private set

        fun id(
            key: Key,
            field: String,
        ): ResourceId? {
            val id = ResourceId.parse(key.asString())
            if (id == null) {
                failed = true
                diagnostics.add(
                    Diagnostic(
                        Severity.ERROR,
                        source,
                        nodePath + field,
                        "Clé ${Diagnostic.quote(key.asString())} incompatible avec le resource pack (namespace:chemin, [a-z0-9_./-])",
                        code = PackCodes.INVALID_STRUCTURE,
                    ),
                )
            }
            return id
        }
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
