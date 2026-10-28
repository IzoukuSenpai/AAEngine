package dev.aaengine.paper.pack

import dev.aaengine.items.ItemCatalog
import dev.aaengine.items.ItemDefinitionSpec
import dev.aaengine.items.ItemModelSource
import dev.aaengine.pack.PackEquipment
import dev.aaengine.pack.PackFormat
import dev.aaengine.pack.PackInput
import dev.aaengine.pack.PackItem
import dev.aaengine.pack.PackItemModel
import dev.aaengine.pack.ResourceId
import net.kyori.adventure.key.Key

/** Conversion du catalogue d'items (types Adventure/Bukkit) en entrées pures du compilateur de pack. */
object PackInputs {
    @JvmStatic
    fun from(catalog: ItemCatalog): PackInput =
        PackInput(
            catalog.items.values.mapNotNull(::item),
            catalog.equipment.values.mapNotNull { set ->
                PackEquipment(
                    id(set.id) ?: return@mapNotNull null,
                    set.source,
                    set.nodePath,
                    set.humanoid?.let(::id),
                    set.humanoidLeggings?.let(::id),
                )
            },
        )

    private fun item(spec: ItemDefinitionSpec): PackItem? {
        val model =
            when (val m = spec.model) {
                null -> null
                is ItemModelSource.Textures ->
                    PackItemModel.Generated(
                        m.layers.map { id(it) ?: return null },
                        m.parent?.let { id(it) ?: return null } ?: ResourceId.parse(PackFormat.defaultParent(spec.material.name))!!,
                    )
                is ItemModelSource.Provided -> PackItemModel.Provided(id(m.model) ?: return null)
            }
        return PackItem(
            id = id(spec.id) ?: return null,
            source = spec.source,
            nodePath = spec.nodePath,
            model = model,
            itemModel = spec.itemModel?.let(::id),
            tooltipStyle = spec.tooltipStyle?.let(::id),
            equipmentAsset = spec.equippable?.asset?.let(::id),
        )
    }

    /** Les clés validées par le parseur d'items respectent déjà les règles de [ResourceId]. */
    private fun id(key: Key): ResourceId? = ResourceId.parse(key.asString())
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
