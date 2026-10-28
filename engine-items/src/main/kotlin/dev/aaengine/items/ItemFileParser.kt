package dev.aaengine.items

import dev.aaengine.core.config.YamlDocuments
import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.core.diagnostic.Suggestions
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.spongepowered.configurate.ConfigurationNode
import java.util.Locale

/**
 * Lecture et validation d'un fichier d'items (format §47) :
 *
 * ```yaml
 * namespace: aaengine        # optionnel
 * items:
 *   forest_axe:
 *     item: { material: NETHERITE_AXE, name: "<green>Forest Axe", lore: ["..."] }
 *     model: { texture: aaengine:item/forest_axe }   # ou model: <ns:chemin> ou item_model: <ns:id>
 *     components: { unbreakable: true, glint: true, tooltip_style: aaengine:forest }
 * equipment:                 # sets d'équipement (armures), référencés par components.equippable.asset
 *   forest: { humanoid: aaengine:forest, humanoid_leggings: aaengine:forest }
 * ```
 *
 * Fonction pure (aucun accès disque ni serveur), utilisable hors du thread principal. Toute clé inconnue
 * est une erreur ; un item en erreur n'est jamais renvoyé.
 */
class ItemFileParser(
    private val materials: MaterialCatalog,
    private val limits: ItemLimits = ItemLimits.DEFAULT,
) {
    /** Définitions valides de [content] ; les problèmes sont ajoutés à [diagnostics] avec le fichier [file]. */
    fun parse(
        file: String,
        content: String,
        diagnostics: DiagnosticCollector,
    ): List<ItemDefinitionSpec> = parseFile(file, content, diagnostics).items

    /** Comme [parse], avec les sets d'équipement de la section racine `equipment`. */
    fun parseFile(
        file: String,
        content: String,
        diagnostics: DiagnosticCollector,
    ): ParsedItemFile {
        val empty = ParsedItemFile(emptyList(), emptyList())
        val root = YamlDocuments.load(content, file, diagnostics) ?: return empty
        if (root.virtual() || root.isNull || (root.isMap && root.childrenMap().isEmpty())) {
            diagnostics.warn(file, null, "Fichier vide")
            return empty
        }
        if (!root.isMap) {
            diagnostics.error(file, null, "La racine doit être une table (clés : ${ROOT_KEYS.joinToString(", ")})")
            return empty
        }
        YamlDocuments.checkKeys(root, ROOT_KEYS, file, diagnostics)
        val namespace = namespaceOf(root.node("namespace"), file, diagnostics) ?: return empty
        val equipment = parseEquipmentSection(file, namespace, root.node("equipment"), diagnostics)
        val items = root.node("items")
        if (items.virtual() || items.isNull) {
            if (equipment.isEmpty()) diagnostics.warn(file, "items", "Aucune section items")
            return ParsedItemFile(emptyList(), equipment)
        }
        if (!items.isMap) {
            diagnostics.error(file, "items", "Table attendue : identifiant local -> définition")
            return ParsedItemFile(emptyList(), equipment)
        }
        val result = ArrayList<ItemDefinitionSpec>()
        for ((key, node) in items.childrenMap()) {
            // Clé lue comme nombre ou booléen (YAML 1.1) : l'id produit ne serait pas celui écrit.
            if (key !is String) {
                diagnostics.add(YamlDocuments.nonTextKey(Severity.ERROR, file, node, key))
                continue
            }
            parseItem(file, namespace, key, node, diagnostics)?.let(result::add)
        }
        return ParsedItemFile(result, equipment)
    }

    private fun parseEquipmentSection(
        file: String,
        namespace: String,
        section: ConfigurationNode,
        diagnostics: DiagnosticCollector,
    ): List<EquipmentSetSpec> {
        if (section.virtual() || section.isNull) return emptyList()
        if (!section.isMap) {
            diagnostics.error(file, "equipment", "Table attendue : identifiant du set -> couches")
            return emptyList()
        }
        val ctx = Ctx(file, diagnostics)
        val result = ArrayList<EquipmentSetSpec>()
        for ((key, node) in section.childrenMap()) {
            if (key !is String) {
                diagnostics.add(YamlDocuments.nonTextKey(Severity.ERROR, file, node, key))
                continue
            }
            val path = YamlDocuments.pathOf(node)
            if (!ItemIds.isValidValue(key)) {
                diagnostics.error(file, path, "Identifiant de set invalide ${Diagnostic.quote(key)}", ItemIds.sanitize(key))
                continue
            }
            if (!ctx.requireMap(node, EQUIPMENT_KEYS)) continue
            val errorsBefore = diagnostics.errorCount
            val humanoid = ctx.optKey(node.node("humanoid"), namespace)
            val leggings = ctx.optKey(node.node("humanoid_leggings"), namespace)
            if (diagnostics.errorCount > errorsBefore) continue
            if (humanoid == null && leggings == null) {
                diagnostics.error(file, path, "Au moins une couche attendue (${EQUIPMENT_KEYS.joinToString(", ")})")
                continue
            }
            result.add(EquipmentSetSpec(Key.key(namespace, key), file, path, humanoid, leggings))
        }
        return result
    }

    private fun namespaceOf(
        node: ConfigurationNode,
        file: String,
        diagnostics: DiagnosticCollector,
    ): String? {
        if (node.virtual()) return ItemIds.DEFAULT_NAMESPACE
        val raw = node.rawScalar()
        if (raw !is String || !ItemIds.isValidNamespace(raw)) {
            diagnostics.error(
                file,
                "namespace",
                "Namespace invalide ${Diagnostic.quote(raw)} : [a-z0-9_.-], ${ItemIds.MAX_NAMESPACE} caractères max",
                (raw as? String)?.let(ItemIds::sanitize),
            )
            return null
        }
        return raw
    }

    private fun parseItem(
        file: String,
        namespace: String,
        localId: String,
        node: ConfigurationNode,
        diagnostics: DiagnosticCollector,
    ): ItemDefinitionSpec? {
        val path = YamlDocuments.pathOf(node)
        if (!ItemIds.isValidValue(localId)) {
            diagnostics.error(
                file,
                path,
                "Identifiant d'item invalide ${Diagnostic.quote(localId)} : [a-z0-9_./-], ${ItemIds.MAX_VALUE} caractères max",
                ItemIds.sanitize(localId),
            )
            return null
        }
        if (!node.isMap) {
            diagnostics.error(file, path, "Table attendue (sections : ${ITEM_KEYS.joinToString(", ")})")
            return null
        }
        val ctx = Ctx(file, diagnostics)
        val errorsBefore = diagnostics.errorCount
        YamlDocuments.checkKeys(node, ITEM_KEYS, file, diagnostics)

        // Section item (obligatoire)
        var material: MaterialInfo? = null
        var name: Component? = null
        var lore: List<Component> = emptyList()
        val item = node.node("item")
        if (item.virtual() || item.isNull) {
            diagnostics.error(file, "$path.item", "Section item obligatoire (au moins item.material)")
        } else if (ctx.requireMap(item, ITEM_SECTION_KEYS)) {
            material = parseMaterial(item.node("material"), ctx)
            name = ctx.optText(item.node("name"))?.let { ctx.mini(item.node("name"), it, ItemText::name) }
            lore = parseLore(item.node("lore"), ctx)
        }

        // Section model (optionnelle)
        var itemModel: Key? = null
        var cmd: CustomModelDataSpec? = null
        var modelSource: ItemModelSource? = null
        val model = node.node("model")
        if (!model.virtual() && ctx.requireMap(model, MODEL_KEYS)) {
            itemModel = parseItemModel(model.node("item_model"), ctx)
            cmd = parseCustomModelData(model.node("custom_model_data"), ctx)
            modelSource = parseModelSource(model, namespace, itemModel != null, ctx)
            // Modèle généré : item_model pointe sur la définition émise sous le même identifiant que l'item.
            if (modelSource != null) itemModel = Key.key(namespace, localId)
        }

        // Section components (optionnelle)
        var unbreakable = false
        var maxStack: Int? = null
        var glint: Boolean? = null
        var hideTooltip = false
        var tooltipStyle: Key? = null
        var equippable: EquippableSpec? = null
        val components = node.node("components")
        if (!components.virtual() && ctx.requireMap(components, COMPONENT_KEYS)) {
            unbreakable = ctx.optBool(components.node("unbreakable")) ?: false
            maxStack = ctx.optInt(components.node("max_stack_size"), 1..MAX_STACK)
            glint = ctx.optBool(components.node("glint"))
            hideTooltip = ctx.optBool(components.node("hide_tooltip")) ?: false
            tooltipStyle = ctx.optKey(components.node("tooltip_style"), namespace)
            equippable = parseEquippable(components.node("equippable"), namespace, ctx)
        }
        if (material != null && material.damageable && maxStack != null && maxStack > 1) {
            diagnostics.error(
                file,
                "$path.components.max_stack_size",
                "${material.material.name} a une durabilité : un item endommageable ne peut pas être empilable",
                "1",
            )
        }

        if (diagnostics.errorCount > errorsBefore || material == null) return null
        return ItemDefinitionSpec(
            id = Key.key(namespace, localId),
            source = file,
            nodePath = path,
            material = material.material,
            name = name,
            lore = lore,
            itemModel = itemModel,
            customModelData = cmd,
            unbreakable = unbreakable,
            maxStackSize = maxStack,
            glint = glint,
            hideTooltip = hideTooltip,
            model = modelSource,
            tooltipStyle = tooltipStyle,
            equippable = equippable,
        )
    }

    /**
     * `model.texture` (clé ou liste de calques), `model.model` (modèle fourni) et `model.parent` (avec
     * `texture` seulement). `texture`, `model` et `item_model` sont exclusifs.
     */
    private fun parseModelSource(
        model: ConfigurationNode,
        namespace: String,
        hasItemModel: Boolean,
        ctx: Ctx,
    ): ItemModelSource? {
        val textureNode = model.node("texture")
        val modelNode = model.node("model")
        val parentNode = model.node("parent")
        val hasTexture = !textureNode.virtual() && !textureNode.isNull
        val hasModel = !modelNode.virtual() && !modelNode.isNull
        if (listOf(hasTexture, hasModel, hasItemModel).count { it } > 1) {
            ctx.error(model, "texture, model et item_model sont exclusifs : un seul mode de modèle par item")
            return null
        }
        if (!parentNode.virtual() && !parentNode.isNull && !hasTexture) {
            ctx.error(parentNode, "parent n'est utilisable qu'avec texture (modèle généré)")
            return null
        }
        if (hasModel) return ctx.key(modelNode, namespace)?.let { ItemModelSource.Provided(it) }
        if (!hasTexture) return null
        val layers =
            if (textureNode.isList) {
                val children = textureNode.childrenList()
                if (children.isEmpty() || children.size > MAX_TEXTURE_LAYERS) {
                    ctx.error(textureNode, "Entre 1 et $MAX_TEXTURE_LAYERS calques attendus")
                    return null
                }
                val keys = children.map { ctx.key(it, namespace) }
                if (keys.any { it == null }) return null
                keys.filterNotNull()
            } else {
                listOf(ctx.key(textureNode, namespace) ?: return null)
            }
        // Parent : namespace minecraft par défaut (item/generated, item/handheld…).
        val parent = if (parentNode.virtual() || parentNode.isNull) null else (ctx.key(parentNode, "minecraft") ?: return null)
        return ItemModelSource.Textures(layers, parent)
    }

    private fun parseEquippable(
        node: ConfigurationNode,
        namespace: String,
        ctx: Ctx,
    ): EquippableSpec? {
        if (node.virtual() || node.isNull) return null
        if (!ctx.requireMap(node, EQUIPPABLE_KEYS)) return null
        val slotNode = node.node("slot")
        val assetNode = node.node("asset")
        var slot: EquipSlot? = null
        if (slotNode.virtual() || slotNode.isNull) {
            ctx.error(node, "slot obligatoire (${EquipSlot.entries.joinToString("|") { it.configName }})")
        } else {
            val raw = ctx.text(slotNode)
            if (raw != null) {
                slot = EquipSlot.entries.firstOrNull { it.configName == raw.lowercase(Locale.ROOT) }
                if (slot == null) {
                    ctx.error(
                        slotNode,
                        "Emplacement inconnu ${Diagnostic.quote(raw)} (${EquipSlot.entries.joinToString("|") { it.configName }})",
                        Suggestions.closest(raw, EquipSlot.entries.map { it.configName }),
                    )
                }
            }
        }
        if (assetNode.virtual() || assetNode.isNull) {
            ctx.error(node, "asset obligatoire (set d'équipement, ex. $namespace:forest)")
            return null
        }
        val asset = ctx.key(assetNode, namespace) ?: return null
        return slot?.let { EquippableSpec(it, asset) }
    }

    private fun parseMaterial(
        node: ConfigurationNode,
        ctx: Ctx,
    ): MaterialInfo? {
        if (node.virtual() || node.isNull) {
            ctx.error(node, "Matériau obligatoire (ex. NETHERITE_AXE)")
            return null
        }
        val raw = ctx.text(node) ?: return null
        val info = materials.resolve(raw)
        if (info == null) {
            ctx.error(node, "Matériau inconnu ou non utilisable comme item ${Diagnostic.quote(raw)}", materials.suggest(raw))
        }
        return info
    }

    private fun parseLore(
        node: ConfigurationNode,
        ctx: Ctx,
    ): List<Component> {
        if (node.virtual() || node.isNull) return emptyList()
        if (!node.isList) {
            ctx.error(node, "Liste de lignes attendue")
            return emptyList()
        }
        val lines = node.childrenList()
        if (lines.size > limits.maxLoreLines) {
            ctx.error(node, "Trop de lignes de lore (${lines.size}, max ${limits.maxLoreLines})")
            return emptyList()
        }
        return lines.mapNotNull { line -> ctx.text(line)?.let { ctx.mini(line, it, ItemText::loreLine) } }
    }

    private fun parseItemModel(
        node: ConfigurationNode,
        ctx: Ctx,
    ): Key? {
        if (node.virtual() || node.isNull) return null
        val raw = ctx.text(node) ?: return null
        val key = ItemIds.parseKey(raw, "minecraft")
        if (key == null) {
            ctx.error(node, "Clé de modèle invalide ${Diagnostic.quote(raw)} (format namespace:chemin)", ItemIds.sanitize(raw))
        }
        return key
    }

    private fun parseCustomModelData(
        node: ConfigurationNode,
        ctx: Ctx,
    ): CustomModelDataSpec? {
        if (node.virtual() || node.isNull) return null
        val raw = node.rawScalar()
        if (raw is Int || raw is Long) {
            val value = (raw as Number).toLong()
            if (value !in -MAX_EXACT_FLOAT..MAX_EXACT_FLOAT) {
                ctx.error(node, "Entier hors limites (±$MAX_EXACT_FLOAT)")
                return null
            }
            return CustomModelDataSpec(listOf(value.toFloat()), emptyList(), emptyList(), emptyList())
        }
        if (!node.isMap) {
            ctx.error(node, "Entier ou table {floats, flags, strings, colors} attendu")
            return null
        }
        if (!ctx.requireMap(node, CMD_KEYS)) return null
        val floats =
            ctx.list(node.node("floats")) { e ->
                val v = e.rawScalar()
                if (v is Number && v.toDouble().isFinite() && kotlin.math.abs(v.toDouble()) <= Float.MAX_VALUE) {
                    v.toFloat()
                } else {
                    ctx.error(e, "Nombre fini attendu, reçu ${Diagnostic.quote(v)}")
                    null
                }
            }
        val flags = ctx.list(node.node("flags")) { e -> ctx.bool(e) }
        val strings =
            ctx.list(node.node("strings")) { e ->
                ctx.text(e)?.takeIf {
                    if (it.length > limits.maxCustomModelDataString) {
                        ctx.error(e, "Texte trop long (max ${limits.maxCustomModelDataString})")
                        false
                    } else {
                        true
                    }
                }
            }
        val colors =
            ctx.list(node.node("colors")) { e ->
                val text = ctx.text(e) ?: return@list null
                if (!COLOR.matches(text)) {
                    ctx.error(e, "Couleur invalide ${Diagnostic.quote(text)}", "#RRGGBB")
                    null
                } else {
                    text.substring(1).toInt(16)
                }
            }
        return CustomModelDataSpec(floats, flags, strings, colors)
    }

    /** Helpers de lecture typée, rattachés au fichier en cours. */
    private inner class Ctx(
        val file: String,
        val diagnostics: DiagnosticCollector,
    ) {
        fun error(
            node: ConfigurationNode,
            message: String,
            suggestion: String? = null,
        ) = diagnostics.error(file, YamlDocuments.pathOf(node), message, suggestion)

        fun requireMap(
            node: ConfigurationNode,
            keys: List<String>,
        ): Boolean {
            if (!node.isMap) {
                error(node, "Table attendue (clés : ${keys.joinToString(", ")})")
                return false
            }
            YamlDocuments.checkKeys(node, keys, file, diagnostics)
            return true
        }

        fun text(node: ConfigurationNode): String? {
            val raw = node.rawScalar()
            if (raw !is String) {
                val found =
                    when {
                        node.isMap -> "une table"
                        node.isList -> "une liste"
                        else -> Diagnostic.quote(raw)
                    }
                error(node, "Texte attendu, reçu $found")
                return null
            }
            if (raw.length > limits.maxTextLength) {
                error(node, "Texte trop long (${raw.length} caractères, max ${limits.maxTextLength})")
                return null
            }
            return raw
        }

        fun optText(node: ConfigurationNode): String? = if (node.virtual() || node.isNull) null else text(node)

        /** Clé `ns:chemin` ou `chemin` (namespace [defaultNamespace]) ; erreur avec suggestion si invalide. */
        fun key(
            node: ConfigurationNode,
            defaultNamespace: String,
        ): Key? {
            val raw = text(node) ?: return null
            val key = ItemIds.parseKey(raw, defaultNamespace)
            if (key == null) {
                error(node, "Clé invalide ${Diagnostic.quote(raw)} (format namespace:chemin, [a-z0-9_./-])", ItemIds.sanitize(raw))
            }
            return key
        }

        fun optKey(
            node: ConfigurationNode,
            defaultNamespace: String,
        ): Key? = if (node.virtual() || node.isNull) null else key(node, defaultNamespace)

        fun bool(node: ConfigurationNode): Boolean? {
            val raw = node.rawScalar()
            if (raw !is Boolean) {
                error(node, "Booléen attendu (true/false), reçu ${Diagnostic.quote(raw)}")
                return null
            }
            return raw
        }

        fun optBool(node: ConfigurationNode): Boolean? = if (node.virtual() || node.isNull) null else bool(node)

        fun optInt(
            node: ConfigurationNode,
            range: IntRange,
        ): Int? {
            if (node.virtual() || node.isNull) return null
            val raw = node.rawScalar()
            val value = (raw as? Int)?.toLong() ?: (raw as? Long)
            if (value == null || value !in range.first.toLong()..range.last.toLong()) {
                error(node, "Entier entre ${range.first} et ${range.last} attendu, reçu ${Diagnostic.quote(raw)}")
                return null
            }
            return value.toInt()
        }

        fun mini(
            node: ConfigurationNode,
            raw: String,
            parse: (String) -> Component,
        ): Component? =
            try {
                parse(raw)
            } catch (e: RuntimeException) {
                error(node, "MiniMessage invalide : ${Diagnostic.quote(e.message)}")
                null
            }

        fun <T : Any> list(
            node: ConfigurationNode,
            element: (ConfigurationNode) -> T?,
        ): List<T> {
            if (node.virtual() || node.isNull) return emptyList()
            if (!node.isList) {
                error(node, "Liste attendue")
                return emptyList()
            }
            val children = node.childrenList()
            if (children.size > limits.maxCustomModelDataEntries) {
                error(node, "Trop d'entrées (${children.size}, max ${limits.maxCustomModelDataEntries})")
                return emptyList()
            }
            return children.mapNotNull(element)
        }
    }

    companion object {
        /** Pile maximale autorisée par le composant `max_stack_size`. */
        const val MAX_STACK: Int = 99

        /** Plus grand entier représentable exactement en float (2^24). */
        private const val MAX_EXACT_FLOAT: Long = 16_777_216

        private val COLOR = Regex("#[0-9a-fA-F]{6}")

        /** Calques au plus pour `model.texture` (`layer0`..`layer7`). */
        const val MAX_TEXTURE_LAYERS: Int = 8

        @JvmField
        val ROOT_KEYS: List<String> = listOf("namespace", "items", "equipment")

        @JvmField
        val EQUIPMENT_KEYS: List<String> = listOf("humanoid", "humanoid_leggings")

        @JvmField
        val EQUIPPABLE_KEYS: List<String> = listOf("slot", "asset")

        @JvmField
        val ITEM_KEYS: List<String> = listOf("item", "model", "components")

        @JvmField
        val ITEM_SECTION_KEYS: List<String> = listOf("material", "name", "lore")

        @JvmField
        val MODEL_KEYS: List<String> = listOf("item_model", "custom_model_data", "texture", "model", "parent")

        @JvmField
        val CMD_KEYS: List<String> = listOf("floats", "flags", "strings", "colors")

        @JvmField
        val COMPONENT_KEYS: List<String> =
            listOf("unbreakable", "max_stack_size", "glint", "hide_tooltip", "tooltip_style", "equippable")
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
