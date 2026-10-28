package dev.aaengine.pack.stage

import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.core.io.SafePaths
import dev.aaengine.pack.BuiltPack
import dev.aaengine.pack.PackCache
import dev.aaengine.pack.PackCodes
import dev.aaengine.pack.PackFile
import dev.aaengine.pack.PackFormat
import dev.aaengine.pack.PackItemModel
import dev.aaengine.pack.ResourceId
import dev.aaengine.pack.json.Json
import dev.aaengine.pack.json.JsonArray
import dev.aaengine.pack.json.JsonObject
import dev.aaengine.pack.json.JsonValue
import dev.aaengine.pack.source.PackSourceLoader
import dev.aaengine.pack.zip.DeterministicZipWriter
import java.io.IOException

/** Identifiants des étapes du compilateur. */
object StageIds {
    const val LOAD_SOURCES: String = "LoadSources"
    const val CACHE_CHECK: String = "CacheCheck"
    const val GENERATE_ITEM_MODELS: String = "GenerateItemModels"
    const val GENERATE_EQUIPMENT: String = "GenerateEquipment"
    const val MERGE: String = "Merge"
    const val ATLAS: String = "Atlas"
    const val VALIDATE: String = "Validate"
    const val BUILD_ZIP: String = "BuildZip"
}

/** Lit `pack/external/<nom>.zip` puis `pack/assets/…`. */
class LoadSourcesStage(
    private val loader: PackSourceLoader,
) : PackStage {
    override val id = StageIds.LOAD_SOURCES
    override val dependencies = emptySet<String>()

    override fun execute(ctx: PackBuildContext) {
        val assets: java.nio.file.Path
        val external: java.nio.file.Path
        try {
            assets = ctx.layout.assetsDir
            external = ctx.layout.externalDir
        } catch (e: SafePaths.UnsafePathException) {
            ctx.report(Severity.ERROR, PackCodes.REFUSED_FILE, "pack/", null, e.message ?: "Chemin refusé")
            return
        }
        ctx.sources.addAll(loader.load(assets, external, ctx.diagnostics))
    }
}

/** Empreinte des entrées ; si le pack en cache correspond, la compilation s'arrête ici (rien à reconstruire). */
class CacheCheckStage(
    private val cache: PackCache,
) : PackStage {
    override val id = StageIds.CACHE_CHECK
    override val dependencies = setOf(StageIds.LOAD_SOURCES)

    override fun execute(ctx: PackBuildContext) {
        val fingerprint = cache.fingerprint(ctx.sources, ctx.input)
        ctx.fingerprint = fingerprint
        if (ctx.force || ctx.diagnostics.hasErrors) return
        val current = cache.current(fingerprint) ?: return
        ctx.result = current
        ctx.upToDate = true
        ctx.halted = true
    }
}

/** `models/item/<id>.json` (mode `texture`) et `items/<id>.json` (modes `texture` et `model`). */
object GenerateItemModelsStage : PackStage {
    override val id = StageIds.GENERATE_ITEM_MODELS
    override val dependencies = emptySet<String>()

    override fun execute(ctx: PackBuildContext) {
        for (item in ctx.input.items) {
            val model = item.model ?: continue
            val target =
                when (model) {
                    is PackItemModel.Generated -> {
                        val modelId = ResourceId(item.id.namespace, "item/${item.id.path}")
                        val textures =
                            JsonObject(
                                linkedMapOf(
                                    *model.layers
                                        .mapIndexed {
                                            i,
                                            t,
                                            ->
                                            "layer$i" to Json.str(t.asString())
                                        }.toTypedArray(),
                                ),
                            )
                        val json = Json.obj("parent" to Json.str(model.parent.asString()), "textures" to textures)
                        add(ctx, modelId.file("models", ".json"), json, item.source, "${item.nodePath}.model.texture")
                        modelId
                    }
                    is PackItemModel.Provided -> model.model
                }
            val definition = item.itemModel ?: item.id
            val json = Json.obj("model" to Json.obj("type" to Json.str(PackFormat.ITEM_MODEL_TYPE), "model" to Json.str(target.asString())))
            add(ctx, definition.file("items", ".json"), json, item.source, "${item.nodePath}.model")
        }
    }
}

/** `equipment/<set>.json` pour chaque set déclaré. */
object GenerateEquipmentStage : PackStage {
    override val id = StageIds.GENERATE_EQUIPMENT
    override val dependencies = emptySet<String>()

    override fun execute(ctx: PackBuildContext) {
        for (set in ctx.input.equipment) {
            val layers = LinkedHashMap<String, JsonValue>()
            set.humanoid?.let { layers[PackFormat.LAYER_HUMANOID] = Json.arr(Json.obj("texture" to Json.str(it.asString()))) }
            set.humanoidLeggings?.let {
                layers[PackFormat.LAYER_HUMANOID_LEGGINGS] =
                    Json.arr(Json.obj("texture" to Json.str(it.asString())))
            }
            add(ctx, set.id.file("equipment", ".json"), Json.obj("layers" to JsonObject(layers)), set.source, set.nodePath)
        }
    }
}

private fun add(
    ctx: PackBuildContext,
    path: String,
    json: JsonValue,
    source: String,
    nodePath: String,
) {
    ctx.generated.add(PackFile(path, Json.writeBytes(json), PackFile.GENERATED))
    ctx.generatedFor[path] = source to nodePath
}

/**
 * Fusion : sources (archives puis dossier) puis fichiers générés puis `pack.mcmeta`. Un même chemin avec un
 * contenu identique est accepté ; un contenu différent est une erreur nommant les deux origines. Les atlas
 * sont mis de côté pour être fusionnés par l'étape Atlas.
 */
object MergeStage : PackStage {
    override val id = StageIds.MERGE
    override val dependencies = setOf(StageIds.LOAD_SOURCES, StageIds.GENERATE_ITEM_MODELS, StageIds.GENERATE_EQUIPMENT)

    private val ATLAS = Regex("assets/[^/]+/atlases/[^/]+\\.json")

    override fun execute(ctx: PackBuildContext) {
        val all = ctx.sources + ctx.generated + PackFile(MCMETA, Json.writeBytes(PackFormat.packMcmeta()), PackFile.GENERATED)
        for (file in all) {
            if (ATLAS.matches(file.path)) {
                ctx.atlasVariants.getOrPut(file.path) { ArrayList() }.add(file)
                continue
            }
            val existing = ctx.merged[file.path]
            if (existing == null) {
                ctx.merged[file.path] = file
                continue
            }
            if (existing.sameContent(file)) continue
            val gen = ctx.generatedFor[file.path]
            val where = if (gen != null) " (généré pour ${gen.first} [${gen.second}])" else ""
            ctx.report(
                Severity.ERROR,
                PackCodes.MERGE_CONFLICT,
                file.path,
                null,
                "Conflit : fourni par ${existing.origin} et par ${file.origin}$where avec des contenus différents",
                "supprimer l'un des deux fichiers",
            )
        }
    }

    const val MCMETA: String = "pack.mcmeta"
}

/**
 * Atlas : fusion des variantes de chaque `atlases/<nom>.json` (union des sources, ordre conservé), puis ajout à
 * l'atlas des items des sprites utilisés par des modèles d'items hors des dossiers par défaut.
 */
object AtlasStage : PackStage {
    override val id = StageIds.ATLAS
    override val dependencies = setOf(StageIds.MERGE)

    private const val BLOCKS_ATLAS = "assets/minecraft/atlases/blocks.json"

    override fun execute(ctx: PackBuildContext) {
        val atlases = LinkedHashMap<String, MutableList<JsonValue>>()
        for ((path, variants) in ctx.atlasVariants) {
            val sources = ArrayList<JsonValue>()
            for (variant in variants) {
                val parsed =
                    try {
                        Json.parse(variant.text().removePrefix(""))
                    } catch (e: dev.aaengine.pack.json.JsonException) {
                        ctx.report(Severity.ERROR, PackCodes.INVALID_JSON, path, variant.origin, "JSON invalide : ${e.message}")
                        continue
                    }
                val list = ((parsed as? JsonObject)?.get("sources") as? JsonArray)?.items
                if (list == null) {
                    ctx.report(Severity.ERROR, PackCodes.INVALID_STRUCTURE, path, variant.origin, "Atlas : tableau \"sources\" attendu")
                    continue
                }
                for (s in list) if (s !in sources) sources.add(s)
            }
            atlases[path] = sources
        }

        val needed = sortedSetOf<ResourceId>()
        ModelGraph(ctx).forEachItemModelTexture { texture ->
            if (PackFormat.DEFAULT_ATLAS_DIRECTORIES.none { texture.path.startsWith(it) } && ctx.exists(texture.file("textures", ".png"))) {
                needed.add(texture)
            }
        }
        val covered = (atlases[PackFormat.ITEMS_ATLAS_FILE].orEmpty() + atlases[BLOCKS_ATLAS].orEmpty())
        val missing = needed.filter { sprite -> covered.none { covers(it, sprite) } }
        if (missing.isNotEmpty()) {
            val items = atlases.getOrPut(PackFormat.ITEMS_ATLAS_FILE) { ArrayList() }
            for (sprite in missing) items.add(Json.obj("type" to Json.str("minecraft:single"), "resource" to Json.str(sprite.asString())))
            ctx.report(
                Severity.INFO,
                PackCodes.ATLAS_SPRITES,
                PackFormat.ITEMS_ATLAS_FILE,
                null,
                "${missing.size} sprite(s) hors item/ et block/ ajouté(s) à l'atlas des items",
            )
        }
        for ((path, sources) in atlases) {
            val bytes = Json.writeBytes(Json.obj("sources" to JsonArray(sources)))
            val origins = ctx.atlasVariants[path]?.joinToString(", ") { it.origin }?.ifEmpty { null } ?: PackFile.GENERATED
            ctx.merged[path] = PackFile(path, bytes, origins)
        }
    }

    /** Vrai si la source d'atlas [source] fournit le sprite [sprite] (`single` exact ou `directory` équivalent). */
    internal fun covers(
        source: JsonValue,
        sprite: ResourceId,
    ): Boolean {
        val obj = source as? JsonObject ?: return false
        val type = obj.string("type")?.removePrefix("minecraft:") ?: return false
        return when (type) {
            "single" -> obj.string("resource")?.let { ResourceId.parse(it) } == sprite
            "directory" -> {
                val src = obj.string("source") ?: return false
                val prefix = obj.string("prefix") ?: ""
                sprite.path.startsWith(prefix) && "$src/${sprite.path.removePrefix(prefix)}" == sprite.path
            }
            else -> false
        }
    }
}

/**
 * Validation des références (§15) : JSON lisibles, définitions d'items → modèles, modèles → parents et
 * textures, `tooltip_style` → sprites, `equippable` → sets, sets → textures, PNG valides et bornés. Les
 * références `minecraft:` absentes du pack sont supposées vanilla (non vérifiables sans le client).
 */
object ValidateStage : PackStage {
    override val id = StageIds.VALIDATE
    override val dependencies = setOf(StageIds.ATLAS)

    private val MODEL = Regex("assets/([^/]+)/models/.+\\.json")
    private val ITEM_DEF = Regex("assets/([^/]+)/items/.+\\.json")
    private val EQUIPMENT = Regex("assets/([^/]+)/equipment/.+\\.json")

    override fun execute(ctx: PackBuildContext) {
        val vanilla = intArrayOf(0)
        for ((path, file) in ctx.merged) {
            when {
                path.endsWith(".json") || path.endsWith(".mcmeta") -> ctx.json(path)
                path.endsWith(".png") -> checkPng(ctx, file)
            }
        }
        for (path in ctx.merged.keys) {
            when {
                ITEM_DEF.matches(path) -> checkItemDefinition(ctx, path, vanilla)
                MODEL.matches(path) -> checkModel(ctx, path, vanilla)
                EQUIPMENT.matches(path) -> checkEquipment(ctx, path, vanilla)
            }
        }
        checkItems(ctx)
        if (vanilla[0] > 0) {
            ctx.report(
                Severity.INFO,
                PackCodes.VANILLA_REFERENCE,
                null,
                null,
                "${vanilla[0]} référence(s) minecraft: absente(s) du pack, supposée(s) vanilla",
            )
        }
    }

    private fun checkItemDefinition(
        ctx: PackBuildContext,
        path: String,
        vanilla: IntArray,
    ) {
        val json = ctx.json(path) as? JsonObject ?: return
        if (json["model"] !is JsonObject) {
            structure(ctx, path, "Définition d'item : objet \"model\" attendu")
            return
        }
        for (raw in ModelGraph.itemDefinitionModels(json)) {
            val ref = ResourceId.parse(raw)
            if (ref == null) {
                structure(ctx, path, "Référence de modèle invalide '$raw'")
                continue
            }
            if (ctx.exists(ref.file("models", ".json"))) continue
            if (ref.namespace == ResourceId.MINECRAFT) {
                vanilla[0]++
                continue
            }
            missing(ctx, path, PackCodes.MISSING_MODEL, "Modèle introuvable ${ref.asString()} (attendu : ${ref.file("models", ".json")})")
        }
    }

    private fun checkModel(
        ctx: PackBuildContext,
        path: String,
        vanilla: IntArray,
    ) {
        val json = ctx.json(path) as? JsonObject ?: return
        ModelGraph.parent(json)?.let { raw ->
            val ref = ResourceId.parse(raw)
            when {
                ref == null -> structure(ctx, path, "Parent invalide '$raw'")
                ref.path.startsWith("builtin/") || ctx.exists(ref.file("models", ".json")) -> Unit
                ref.namespace == ResourceId.MINECRAFT -> vanilla[0]++
                else ->
                    missing(
                        ctx,
                        path,
                        PackCodes.MISSING_PARENT,
                        "Parent introuvable ${ref.asString()} (attendu : ${ref.file("models", ".json")})",
                    )
            }
        }
        for (raw in ModelGraph.textures(json)) {
            val ref = ResourceId.parse(raw)
            when {
                ref == null -> structure(ctx, path, "Référence de texture invalide '$raw'")
                ctx.exists(ref.file("textures", ".png")) -> Unit
                ref.namespace == ResourceId.MINECRAFT -> vanilla[0]++
                else ->
                    missing(
                        ctx,
                        path,
                        PackCodes.MISSING_TEXTURE,
                        "Texture introuvable ${ref.asString()} (attendu : ${ref.file("textures", ".png")})",
                    )
            }
        }
    }

    private fun checkEquipment(
        ctx: PackBuildContext,
        path: String,
        vanilla: IntArray,
    ) {
        val json = ctx.json(path) as? JsonObject ?: return
        val layers = json["layers"] as? JsonObject
        if (layers == null) {
            structure(ctx, path, "Équipement : objet \"layers\" attendu")
            return
        }
        for ((layer, list) in layers.members) {
            val entries = (list as? JsonArray)?.items
            if (entries == null || !ResourceId.isValidPath(layer)) {
                structure(ctx, path, "Équipement : couche '$layer' invalide (tableau attendu)")
                continue
            }
            for (entry in entries) {
                val raw = (entry as? JsonObject)?.string("texture")
                val ref = raw?.let { ResourceId.parse(it) }
                if (ref == null) {
                    structure(ctx, path, "Équipement : texture invalide dans la couche '$layer'")
                    continue
                }
                val file = ref.file("textures/entity/equipment/$layer", ".png")
                when {
                    ctx.exists(file) -> Unit
                    ref.namespace == ResourceId.MINECRAFT -> vanilla[0]++
                    else ->
                        missing(
                            ctx,
                            path,
                            PackCodes.MISSING_EQUIPMENT_TEXTURE,
                            "Texture d'équipement introuvable ${ref.asString()} (attendu : $file)",
                        )
                }
            }
        }
    }

    /** Références écrites dans les fichiers d'items (diagnostic rattaché au YAML). */
    private fun checkItems(ctx: PackBuildContext) {
        for (item in ctx.input.items) {
            item.tooltipStyle?.let { style ->
                for (suffix in PackFormat.TOOLTIP_SPRITE_SUFFIXES) {
                    val sprite = ResourceId(style.namespace, "gui/sprites/tooltip/${style.path}$suffix")
                    val file = sprite.file("textures", ".png")
                    if (!ctx.exists(file)) {
                        ctx.report(
                            Severity.ERROR,
                            PackCodes.MISSING_TOOLTIP,
                            item.source,
                            "${item.nodePath}.components.tooltip_style",
                            "Sprite de tooltip_style introuvable : $file",
                        )
                    } else if (!ctx.exists("$file.mcmeta")) {
                        ctx.report(
                            Severity.WARN,
                            PackCodes.TOOLTIP_NO_MCMETA,
                            item.source,
                            "${item.nodePath}.components.tooltip_style",
                            "$file sans $file.mcmeta : le sprite sera étiré (gui.scaling nine_slice conseillé)",
                        )
                    }
                }
            }
            item.equipmentAsset?.let { asset ->
                val file = asset.file("equipment", ".json")
                if (!ctx.exists(file) && asset.namespace != ResourceId.MINECRAFT) {
                    ctx.report(
                        Severity.ERROR,
                        PackCodes.MISSING_EQUIPMENT,
                        item.source,
                        "${item.nodePath}.components.equippable.asset",
                        "Set d'équipement introuvable ${asset.asString()} : déclarez-le dans la section equipment ou fournissez $file",
                    )
                }
            }
            val manual = item.itemModel
            if (item.model == null && manual != null && manual.namespace != ResourceId.MINECRAFT) {
                val file = manual.file("items", ".json")
                if (!ctx.exists(file)) {
                    ctx.report(
                        Severity.ERROR,
                        PackCodes.MISSING_MODEL,
                        item.source,
                        "${item.nodePath}.model.item_model",
                        "Définition d'item introuvable : $file (ou utilisez model.texture / model.model)",
                    )
                }
            }
        }
    }

    /** Signature PNG, en-tête IHDR et dimensions bornées (sans décodage). */
    private fun checkPng(
        ctx: PackBuildContext,
        file: PackFile,
    ) {
        val b = file.bytes()
        val ok =
            b.size >= PNG_HEADER &&
                PNG_SIGNATURE.indices.all { b[it] == PNG_SIGNATURE[it] } &&
                String(b, 12, 4, Charsets.US_ASCII) == "IHDR"
        if (!ok) {
            ctx.report(Severity.ERROR, PackCodes.INVALID_PNG, file.path, file.origin, "PNG invalide (signature ou en-tête IHDR)")
            return
        }
        val width = be32(b, 16)
        val height = be32(b, 20)
        val max = ctx.limits.maxTextureSide
        if (width !in 1..max || height !in 1..max) {
            ctx.report(
                Severity.ERROR,
                PackCodes.INVALID_PNG,
                file.path,
                file.origin,
                "Dimensions ${width}x$height hors limites (max ${max}x$max)",
            )
        }
    }

    private fun be32(
        b: ByteArray,
        p: Int,
    ): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or
            (b[p + 3].toLong() and 0xFF)

    private fun missing(
        ctx: PackBuildContext,
        path: String,
        code: String,
        message: String,
    ) {
        val gen = ctx.generatedFor[path]
        if (gen != null) {
            ctx.report(Severity.ERROR, code, gen.first, gen.second, "$message ; fichier généré : $path")
        } else {
            ctx.report(Severity.ERROR, code, path, null, message)
        }
    }

    private fun structure(
        ctx: PackBuildContext,
        path: String,
        message: String,
    ) = ctx.report(Severity.ERROR, PackCodes.INVALID_STRUCTURE, path, null, message)

    private const val PNG_HEADER = 24
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}

/** Écrit le zip déterministe et le manifeste ; ne fait rien si une erreur a été relevée. */
class BuildZipStage(
    private val cache: PackCache,
) : PackStage {
    override val id = StageIds.BUILD_ZIP
    override val dependencies = setOf(StageIds.VALIDATE, StageIds.CACHE_CHECK)

    override fun execute(ctx: PackBuildContext) {
        if (ctx.diagnostics.hasErrors) {
            ctx.halted = true
            return
        }
        val fingerprint = ctx.fingerprint ?: cache.fingerprint(ctx.sources, ctx.input)
        val files = ctx.merged.values.toList()
        try {
            val target = ctx.layout.zipFile
            val digest = cache.atomicWrite(target) { out -> DeterministicZipWriter.write(files, out) }
            if (digest.size > ctx.limits.maxOutputBytes) {
                ctx.report(
                    Severity.ERROR,
                    PackCodes.LIMIT,
                    "${PackLayoutNames.CACHE_LABEL}/${target.fileName}",
                    null,
                    "Pack trop volumineux pour les clients (${digest.size / (1024 * 1024)} Mio, max ${ctx.limits.maxOutputBytes / (1024 * 1024)} Mio)",
                )
                java.nio.file.Files
                    .deleteIfExists(target)
                ctx.halted = true
                return
            }
            val pack = BuiltPack(target, digest.size, digest.sha1, digest.sha256, files.size)
            cache.writeManifest(fingerprint, ctx.sources, ctx.input, files, pack)
            ctx.result = pack
        } catch (e: IOException) {
            ctx.report(
                Severity.ERROR,
                PackCodes.WRITE_FAILED,
                PackLayoutNames.CACHE_LABEL,
                null,
                "Écriture du pack impossible (${e.javaClass.simpleName})",
            )
        } catch (e: SecurityException) {
            ctx.report(Severity.ERROR, PackCodes.WRITE_FAILED, PackLayoutNames.CACHE_LABEL, null, "Écriture du pack refusée : ${e.message}")
        }
    }
}

internal object PackLayoutNames {
    const val CACHE_LABEL: String = "cache/pack"
}

/** Parcours des modèles référencés par les définitions d'items (parents compris, cycles et profondeur bornés). */
internal class ModelGraph(
    private val ctx: PackBuildContext,
) {
    fun forEachItemModelTexture(action: (ResourceId) -> Unit) {
        val visited = HashSet<String>()
        for (path in ctx.merged.keys.filter { ITEM_DEF.matches(it) }) {
            val json = ctx.json(path) as? JsonObject ?: continue
            for (raw in itemDefinitionModels(json)) {
                var ref = ResourceId.parse(raw)
                var depth = 0
                while (ref != null && depth++ < MAX_PARENT_DEPTH) {
                    val file = ref.file("models", ".json")
                    if (!visited.add(file)) break
                    val model = ctx.json(file) as? JsonObject ?: break
                    textures(model).mapNotNull { ResourceId.parse(it) }.forEach(action)
                    ref = parent(model)?.let { ResourceId.parse(it) }
                }
            }
        }
    }

    companion object {
        private val ITEM_DEF = Regex("assets/([^/]+)/items/.+\\.json")
        private const val MAX_PARENT_DEPTH = 32
        private val MODEL_TYPES = setOf("minecraft:model", "model")
        private val SPECIAL_TYPES = setOf("minecraft:special", "special")

        /** Modèles cités par une définition d'item (tous les types imbriqués : condition, select, composite…). */
        fun itemDefinitionModels(json: JsonValue): List<String> {
            val out = ArrayList<String>()

            fun walk(v: JsonValue) {
                when (v) {
                    is JsonObject -> {
                        val type = v.string("type")
                        if (type in MODEL_TYPES) v.string("model")?.let(out::add)
                        if (type in SPECIAL_TYPES) v.string("base")?.let(out::add)
                        v.members.values.forEach(::walk)
                    }
                    is JsonArray -> v.items.forEach(::walk)
                    else -> Unit
                }
            }
            walk(json)
            return out
        }

        fun parent(model: JsonObject): String? = model.string("parent")

        /** Textures d'un modèle (chaînes ou objets `{ "sprite": … }`), références `#…` exclues. */
        fun textures(model: JsonObject): List<String> {
            val textures = model["textures"] as? JsonObject ?: return emptyList()
            return textures.members.values
                .mapNotNull { v ->
                    when (v) {
                        is dev.aaengine.pack.json.JsonString -> v.value
                        is JsonObject -> v.string("sprite")
                        else -> null
                    }
                }.filter { !it.startsWith("#") }
        }
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
