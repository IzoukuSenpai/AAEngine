package dev.aaengine.pack.stage

import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.pack.BuiltPack
import dev.aaengine.pack.PackFile
import dev.aaengine.pack.PackInput
import dev.aaengine.pack.PackLayout
import dev.aaengine.pack.PackLimits
import dev.aaengine.pack.json.Json
import dev.aaengine.pack.json.JsonException
import dev.aaengine.pack.json.JsonValue
import java.util.TreeMap

/**
 * État partagé d'une compilation (un seul thread). Les étapes y déposent leurs sorties ; [merged] est
 * l'arbre final du pack, trié par chemin.
 *
 * @property force reconstruit même si le cache est à jour
 */
class PackBuildContext(
    val input: PackInput,
    val layout: PackLayout,
    val limits: PackLimits,
    val diagnostics: DiagnosticCollector,
    val force: Boolean = false,
) {
    /** Fichiers lus (archives externes puis `pack/assets`). */
    val sources: MutableList<PackFile> = ArrayList()

    /** Fichiers générés depuis les items et sets d'équipement. */
    val generated: MutableList<PackFile> = ArrayList()

    /** Origine YAML (`items/a.yml`, `items.x`) d'un fichier généré, pour des diagnostics utiles. */
    val generatedFor: MutableMap<String, Pair<String, String>> = HashMap()

    /** Arbre fusionné du pack (chemin → fichier). */
    val merged: TreeMap<String, PackFile> = TreeMap()

    /** Variantes d'un même atlas (`assets/<ns>/atlases/<nom>.json`), fusionnées par l'étape Atlas. */
    val atlasVariants: TreeMap<String, MutableList<PackFile>> = TreeMap()

    /** Empreinte des entrées, calculée par l'étape de cache. */
    var fingerprint: String? = null

    /** Résultat final (pack construit ou pack en cache à jour). */
    var result: BuiltPack? = null

    /** Vrai si le pack en cache est à jour (aucune reconstruction). */
    var upToDate: Boolean = false

    /** Arrête l'exécution des étapes suivantes. */
    var halted: Boolean = false

    private val parsed = HashMap<String, JsonValue?>()

    /** Cycles de parents déjà signalés (un diagnostic par cycle). */
    internal val reportedCycles: MutableSet<Set<String>> = HashSet()

    /** Modèles rendus dont les variables de texture ont déjà été contrôlées. */
    internal val checkedLeafModels: MutableSet<String> = HashSet()

    fun exists(path: String): Boolean = merged.containsKey(path)

    /**
     * JSON de [path] dans l'arbre fusionné (lu une fois), `null` si absent ou illisible ; une erreur de
     * syntaxe est signalée une seule fois ([dev.aaengine.pack.PackCodes.INVALID_JSON]).
     */
    fun json(path: String): JsonValue? {
        if (parsed.containsKey(path)) return parsed[path]
        val file = merged[path]
        val value =
            if (file == null) {
                null
            } else {
                try {
                    Json.parse(file.text().removePrefix("﻿"))
                } catch (e: JsonException) {
                    diagnostics.add(
                        Diagnostic(
                            Severity.ERROR,
                            path,
                            file.origin,
                            "JSON invalide : ${e.message}",
                            line = e.line,
                            column = e.column,
                            code = dev.aaengine.pack.PackCodes.INVALID_JSON,
                        ),
                    )
                    null
                }
            }
        parsed[path] = value
        return value
    }

    fun report(
        severity: Severity,
        code: String,
        file: String?,
        path: String?,
        message: String,
        suggestion: String? = null,
    ) = diagnostics.add(Diagnostic(severity, file, path, message, suggestion, code = code))
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
