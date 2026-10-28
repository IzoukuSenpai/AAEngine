package dev.aaengine.core.config

import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.core.diagnostic.Suggestions
import org.spongepowered.configurate.ConfigurateException
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.loader.ParsingException
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import org.spongepowered.configurate.yaml.internal.snakeyaml.LoaderOptions
import org.spongepowered.configurate.yaml.internal.snakeyaml.error.Mark
import org.spongepowered.configurate.yaml.internal.snakeyaml.error.MarkedYAMLException
import org.spongepowered.configurate.yaml.internal.snakeyaml.events.AliasEvent
import org.spongepowered.configurate.yaml.internal.snakeyaml.events.Event
import org.spongepowered.configurate.yaml.internal.snakeyaml.events.NodeEvent
import org.spongepowered.configurate.yaml.internal.snakeyaml.events.ScalarEvent
import org.spongepowered.configurate.yaml.internal.snakeyaml.nodes.NodeId
import org.spongepowered.configurate.yaml.internal.snakeyaml.nodes.Tag
import org.spongepowered.configurate.yaml.internal.snakeyaml.parser.ParserImpl
import org.spongepowered.configurate.yaml.internal.snakeyaml.reader.StreamReader
import org.spongepowered.configurate.yaml.internal.snakeyaml.resolver.Resolver

/**
 * Lecture YAML commune (Configurate). Le contenu est toujours fourni en mémoire, déjà borné en taille par
 * l'appelant : aucun accès disque ici.
 *
 * Les options par défaut de Configurate ne protègent pas contre l'expansion d'alias (chaque alias est recopié
 * dans l'arbre : quelques centaines d'octets suffisent à saturer le tas) ni contre les clés en double (la
 * dernière gagne en silence). Une pré-passe sur le flux d'événements SnakeYAML refuse donc, avant toute
 * construction d'arbre : ancres et alias, clés en double, clés de fusion `<<`, clés non textuelles (`010`,
 * `yes`…), profondeur > [MAX_DEPTH] et plus de [MAX_EVENTS] événements.
 */
object YamlDocuments {
    /** Profondeur d'imbrication maximale (tables et listes). */
    const val MAX_DEPTH: Int = 32

    /** Nombre maximal d'événements YAML par document. */
    const val MAX_EVENTS: Int = 200_000

    /** Longueur maximale d'un segment de chemin affiché. */
    private const val MAX_SEGMENT = 64

    /** Résolveur implicite YAML 1.1 (celui de Configurate) ; lecture seule après construction. */
    private val RESOLVER = Resolver()

    /** Parse [content] ; en cas de problème, ajoute une erreur rattachée à [file] et renvoie `null`. */
    @JvmStatic
    fun load(
        content: String,
        file: String,
        diagnostics: DiagnosticCollector,
    ): ConfigurationNode? {
        try {
            if (!precheck(content, file, diagnostics)) return null
            return YamlConfigurationLoader.builder().buildAndLoadString(content)
        } catch (e: ConfigurateException) {
            diagnostics.add(syntaxError(file, e))
        } catch (e: MarkedYAMLException) {
            diagnostics.add(markedError(file, e, e.problem ?: e.message))
        } catch (e: RuntimeException) {
            diagnostics.error(file, null, "YAML illisible (${e.javaClass.simpleName}) : ${firstLine(e.message)}")
        } catch (_: StackOverflowError) {
            diagnostics.error(file, null, "YAML trop profondément imbriqué")
        }
        return null
    }

    /**
     * Pré-passe sur les événements YAML (aucun arbre construit). Ancres, alias, profondeur et volume arrêtent
     * immédiatement ; clés en double, clés de fusion et clés non textuelles sont toutes signalées.
     * Renvoie faux si le document est refusé.
     */
    private fun precheck(
        content: String,
        file: String,
        diagnostics: DiagnosticCollector,
    ): Boolean {
        val options =
            LoaderOptions().apply {
                setAllowDuplicateKeys(false)
                setMaxAliasesForCollections(0)
                setNestingDepthLimit(MAX_DEPTH)
                setCodePointLimit(maxOf(content.length + 1, 1))
            }
        val parser = ParserImpl(StreamReader(content), options)
        val stack = ArrayDeque<Frame>()
        var events = 0
        var ok = true
        while (true) {
            val event = parser.getEvent() ?: return ok
            if (++events > MAX_EVENTS) {
                diagnostics.add(at(file, null, event, "Document YAML trop volumineux (plus de $MAX_EVENTS éléments)"))
                return false
            }
            if (event is AliasEvent || (event is NodeEvent && event.anchor != null)) {
                diagnostics.add(at(file, stack.lastOrNull()?.path, event, "Ancres et alias YAML (& et *) interdits"))
                return false
            }
            when (event.eventId) {
                Event.ID.StreamEnd -> return ok
                Event.ID.DocumentStart -> stack.clear()
                Event.ID.Scalar, Event.ID.MappingStart, Event.ID.SequenceStart -> {
                    val parent = stack.lastOrNull()
                    val childPath = if (parent == null) null else childPath(parent, event)
                    if (parent != null && parent.isMap && parent.expectKey) {
                        parent.expectKey = false
                        if (!checkKey(parent, event, file, diagnostics)) ok = false
                    } else if (parent != null && parent.isMap) {
                        parent.expectKey = true
                    }
                    if (event.eventId != Event.ID.Scalar) {
                        if (stack.size >= MAX_DEPTH) {
                            diagnostics.add(at(file, childPath, event, "Imbrication YAML trop profonde (max $MAX_DEPTH)"))
                            return false
                        }
                        stack.addLast(Frame(event.eventId == Event.ID.MappingStart, childPath))
                    }
                }
                Event.ID.MappingEnd, Event.ID.SequenceEnd -> stack.removeLastOrNull()
                else -> Unit
            }
        }
    }

    /** Chemin du nœud [event] dans [parent] (avant mise à jour de l'état du parent). */
    private fun childPath(
        parent: Frame,
        event: Event,
    ): String =
        when {
            !parent.isMap -> join(parent.path, (parent.index++).toString())
            !parent.expectKey -> join(parent.path, parent.currentKey ?: "?")
            event is ScalarEvent -> join(parent.path, event.value)
            else -> join(parent.path, "?")
        }

    /** Contrôle d'une clé de table ; renvoie faux si elle est refusée (diagnostic ajouté). */
    private fun checkKey(
        parent: Frame,
        event: Event,
        file: String,
        diagnostics: DiagnosticCollector,
    ): Boolean {
        if (event !is ScalarEvent) {
            parent.currentKey = null
            diagnostics.add(at(file, parent.path, event, "Clé complexe (table ou liste) interdite"))
            return false
        }
        val key = event.value
        parent.currentKey = key
        val path = join(parent.path, key)
        if (event.isPlain && key == "<<") {
            diagnostics.add(at(file, parent.path, event, "Clé de fusion YAML '<<' interdite"))
            return false
        }
        // YAML 1.1 : `010`, `yes`, `1.10`, `~` deviennent nombre, booléen ou null en clé.
        if (event.isPlain && RESOLVER.resolve(NodeId.scalar, key, true) != Tag.STR) {
            diagnostics.add(
                at(file, path, event, "Clé ${Diagnostic.quote(key)} lue comme une valeur non textuelle : mettez-la entre guillemets")
                    .copy(suggestion = "\"${Diagnostic.sanitize(key, MAX_SEGMENT)}\""),
            )
            return false
        }
        val line = event.startMark?.line?.plus(1) ?: 0
        val firstLine = parent.keys.putIfAbsent(key, line)
        if (firstLine != null) {
            val where = if (firstLine > 0) " (déjà définie ligne $firstLine)" else ""
            diagnostics.add(at(file, path, event, "Clé en double ${Diagnostic.quote(key)}$where"))
            return false
        }
        return true
    }

    private class Frame(
        val isMap: Boolean,
        val path: String?,
    ) {
        val keys = HashMap<String, Int>()
        var expectKey = true
        var currentKey: String? = null
        var index = 0
    }

    private fun join(
        parent: String?,
        segment: String,
    ): String {
        val s = segment(segment)
        return if (parent == null) s else "$parent.$s"
    }

    private fun segment(raw: String): String = if (raw.length > MAX_SEGMENT) raw.take(MAX_SEGMENT) + "…" else raw

    private fun at(
        file: String,
        path: String?,
        event: Event,
        message: String,
    ): Diagnostic {
        val mark: Mark? = event.startMark
        return Diagnostic(Severity.ERROR, file, path, message, line = mark?.line?.plus(1), column = mark?.column?.plus(1))
    }

    /**
     * Erreur de syntaxe : la position n'est reprise que si Configurate ou l'exception YAML qu'il enveloppe
     * l'expose (ligne/colonne 0-based côté parseur, affichées 1-based) ; elle n'est jamais estimée.
     */
    private fun syntaxError(
        file: String,
        e: ConfigurateException,
    ): Diagnostic {
        var line: Int? = null
        var column: Int? = null
        var detail: String? = e.rawMessage()
        if (e is ParsingException && e.line() >= 0 && e.line() != ParsingException.UNKNOWN_POS) {
            line = e.line() + 1
            column = e.column().takeIf { it >= 0 && it != ParsingException.UNKNOWN_POS }?.plus(1)
        }
        val marked = generateSequence(e.cause) { it.cause }.take(8).filterIsInstance<MarkedYAMLException>().firstOrNull()
        if (marked != null) {
            detail = marked.problem ?: detail
            val mark = marked.problemMark
            if (line == null && mark != null) {
                line = mark.line + 1
                column = mark.column + 1
            }
        }
        return Diagnostic(Severity.ERROR, file, null, "YAML invalide : ${firstLine(detail)}", line = line, column = column)
    }

    private fun markedError(
        file: String,
        e: MarkedYAMLException,
        detail: String?,
    ): Diagnostic {
        val mark = e.problemMark
        return Diagnostic(
            Severity.ERROR,
            file,
            null,
            "YAML invalide : ${firstLine(detail)}",
            line = mark?.line?.plus(1),
            column = mark?.column?.plus(1),
        )
    }

    /** Chemin lisible d'un nœud (`items.forest_axe.item.material`), segments bornés. */
    @JvmStatic
    fun pathOf(node: ConfigurationNode): String = node.path().joinToString(".") { segment(it.toString()) }

    /**
     * Signale chaque clé de [node] absente de [allowed] (erreur ou avertissement selon [severity]) avec la
     * clé connue la plus proche en suggestion. Une clé qui n'est pas du texte (YAML 1.1 : `010`, `yes`, `1.10`
     * deviennent nombre ou booléen) est toujours signalée, avec la forme entre guillemets à écrire.
     * Renvoie vrai si toutes les clés sont connues.
     */
    @JvmStatic
    fun checkKeys(
        node: ConfigurationNode,
        allowed: Collection<String>,
        file: String,
        diagnostics: DiagnosticCollector,
        severity: Severity = Severity.ERROR,
    ): Boolean {
        var ok = true
        for ((key, child) in node.childrenMap()) {
            if (key !is String) {
                ok = false
                diagnostics.add(nonTextKey(severity, file, child, key))
                continue
            }
            if (key !in allowed) {
                ok = false
                diagnostics.add(
                    Diagnostic(
                        severity,
                        file,
                        pathOf(child),
                        "Clé inconnue ${Diagnostic.quote(key)} (clés acceptées : ${allowed.joinToString(", ")})",
                        Suggestions.closest(key, allowed),
                    ),
                )
            }
        }
        return ok
    }

    /** Clé lue comme nombre, booléen… par le parseur YAML : à écrire entre guillemets. */
    @JvmStatic
    fun nonTextKey(
        severity: Severity,
        file: String,
        child: ConfigurationNode,
        key: Any?,
    ): Diagnostic =
        Diagnostic(
            severity,
            file,
            pathOf(child),
            "Clé à mettre entre guillemets (lue comme ${key?.javaClass?.simpleName ?: "null"} ${Diagnostic.quote(key)})",
            "\"${Diagnostic.sanitize(key.toString(), MAX_SEGMENT)}\"",
        )

    private fun firstLine(message: String?): String =
        Diagnostic.quote(message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "erreur inconnue").trim('\'')
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
