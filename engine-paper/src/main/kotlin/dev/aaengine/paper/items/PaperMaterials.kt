package dev.aaengine.paper.items

import dev.aaengine.items.MaterialCatalog
import dev.aaengine.items.MaterialInfo
import org.bukkit.Material

/** Catalogue des matériaux utilisables comme item sur ce serveur. Thread principal (lit les registres). */
object PaperMaterials {
    @JvmStatic
    fun catalog(): MaterialCatalog {
        val entries = HashMap<String, MaterialInfo>()
        for (material in Material.entries) {
            if (material.name.startsWith("LEGACY_")) continue
            if (!material.isItem || material.isAir) continue
            entries[material.name] = MaterialInfo(material, material.maxDurability > 0)
        }
        return MaterialCatalog(entries)
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
