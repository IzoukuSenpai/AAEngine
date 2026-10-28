package dev.aaengine.paper.items

import dev.aaengine.api.item.AAItemService
import dev.aaengine.core.engine.DefaultAAEngine
import dev.aaengine.items.ItemDefinitionSpec
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import io.papermc.paper.datacomponent.item.ItemLore
import io.papermc.paper.datacomponent.item.TooltipDisplay
import org.bukkit.Color
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * Construit l'`ItemStack` modèle d'une définition via l'API Data Components de Paper. Appelé une fois par
 * item et par reload, sur le thread principal (région globale sous Folia) ; les exemplaires distribués sont
 * ensuite des clones (§36).
 */
object TemplateBuilder {
    @JvmStatic
    fun build(spec: ItemDefinitionSpec): ItemStack {
        val stack = ItemStack.of(spec.material)
        spec.name?.let { stack.setData(DataComponentTypes.ITEM_NAME, it) }
        if (spec.lore.isNotEmpty()) stack.setData(DataComponentTypes.LORE, ItemLore.lore(spec.lore))
        spec.itemModel?.let { stack.setData(DataComponentTypes.ITEM_MODEL, it) }
        spec.customModelData?.let { cmd ->
            stack.setData(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData
                    .customModelData()
                    .addFloats(cmd.floats)
                    .addFlags(cmd.flags)
                    .addStrings(cmd.strings)
                    .addColors(cmd.colors.map(Color::fromRGB))
                    .build(),
            )
        }
        if (spec.unbreakable) stack.setData(DataComponentTypes.UNBREAKABLE)
        spec.maxStackSize?.let { stack.setData(DataComponentTypes.MAX_STACK_SIZE, it) }
        spec.glint?.let { stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, it) }
        if (spec.hideTooltip) {
            stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build())
        }
        stack.editPersistentDataContainer { pdc ->
            pdc.set(DefaultAAEngine.ITEM_ID_KEY, PersistentDataType.STRING, spec.id.asString())
            pdc.set(DefaultAAEngine.SCHEMA_KEY, PersistentDataType.INTEGER, AAItemService.SCHEMA_VERSION)
        }
        return stack
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
