package dev.aaengine.core.config

import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.core.diagnostic.Suggestions
import dev.aaengine.core.log.LogCategory
import dev.aaengine.core.net.PublicHttpsUrl
import org.spongepowered.configurate.ConfigurationNode
import java.util.Locale

/**
 * Configuration typée de `config.yml` (§27). Une valeur invalide est signalée et remplacée par sa valeur
 * par défaut sûre ; la configuration ne bloque jamais le chargement du contenu.
 */
data class EngineConfig(
    val debugCategories: Set<LogCategory> = emptySet(),
    val pack: PackSettings = PackSettings.DEFAULT,
) {
    companion object {
        @JvmField
        val DEFAULT: EngineConfig = EngineConfig()

        /** Version de format de `config.yml` comprise par cette version de l'engine. */
        const val CONFIG_VERSION: Int = 1

        private val ROOT_KEYS = listOf("config-version", "debug", "pack")
        private val DEBUG_KEYS = listOf("categories")
        private val PACK_KEYS = listOf("distribution", "url", "required", "prompt", "allow-private-hosts")

        /** Lit [content] (déjà borné en taille) ; les problèmes vont dans [diagnostics] comme avertissements. */
        @JvmStatic
        fun parse(
            content: String,
            file: String,
            diagnostics: DiagnosticCollector,
        ): EngineConfig {
            val root = YamlDocuments.load(content, file, diagnostics) ?: return DEFAULT
            if (root.virtual() || root.isNull) return DEFAULT
            if (!root.isMap) {
                diagnostics.warn(file, null, "La racine doit être une table ; valeurs par défaut utilisées")
                return DEFAULT
            }
            YamlDocuments.checkKeys(root, ROOT_KEYS, file, diagnostics, Severity.WARN)
            val version = root.node("config-version")
            if (!version.virtual() && version.rawScalar() != CONFIG_VERSION) {
                diagnostics.warn(
                    file,
                    "config-version",
                    "Version de configuration ${Diagnostic.quote(version.rawScalar())} inattendue",
                    "$CONFIG_VERSION",
                )
            }
            val debug = root.node("debug")
            val categories = LinkedHashSet<LogCategory>()
            if (!debug.virtual()) {
                if (!debug.isMap) {
                    diagnostics.warn(file, "debug", "Table attendue")
                } else {
                    YamlDocuments.checkKeys(debug, DEBUG_KEYS, file, diagnostics, Severity.WARN)
                    val list = debug.node("categories")
                    if (!list.virtual() && !list.isNull) {
                        if (!list.isList) {
                            diagnostics.warn(file, "debug.categories", "Liste attendue")
                        } else {
                            for (entry in list.childrenList()) {
                                val raw = entry.rawScalar()?.toString().orEmpty()
                                val cat = LogCategory.entries.firstOrNull { it.name == raw.uppercase(Locale.ROOT) }
                                if (cat == null) {
                                    diagnostics.warn(
                                        file,
                                        YamlDocuments.pathOf(entry),
                                        "Catégorie de log inconnue ${Diagnostic.quote(raw)}",
                                        Suggestions.closest(raw, LogCategory.entries.map { it.name }),
                                    )
                                } else {
                                    categories.add(cat)
                                }
                            }
                        }
                    }
                }
            }
            return EngineConfig(categories, parsePack(root.node("pack"), file, diagnostics))
        }

        /**
         * Section `pack` : toute valeur invalide est signalée ; une URL refusée désactive la distribution
         * (fail-closed : aucun client n'est envoyé vers une URL non conforme).
         */
        private fun parsePack(
            node: ConfigurationNode,
            file: String,
            diagnostics: DiagnosticCollector,
        ): PackSettings {
            if (node.virtual() || node.isNull) return PackSettings.DEFAULT
            if (!node.isMap) {
                diagnostics.warn(file, "pack", "Table attendue ; distribution du pack désactivée")
                return PackSettings.DEFAULT
            }
            YamlDocuments.checkKeys(node, PACK_KEYS, file, diagnostics, Severity.WARN)
            val required = bool(node.node("required"), file, diagnostics) ?: false
            val allowPrivate = bool(node.node("allow-private-hosts"), file, diagnostics) ?: false
            if (allowPrivate) {
                diagnostics.warn(
                    file,
                    "pack.allow-private-hosts",
                    "allow-private-hosts est activé : les URL locales et privées sont acceptées (réseau de test uniquement)",
                    "false",
                )
            }
            val rawPrompt = node.node("prompt").rawScalar()
            val prompt = (rawPrompt as? String)?.takeIf { it.isNotEmpty() && it.length <= PackSettings.MAX_PROMPT }
            if (rawPrompt != null && prompt == null && rawPrompt != "") {
                diagnostics.warn(file, "pack.prompt", "Texte de ${PackSettings.MAX_PROMPT} caractères max attendu ; ignoré")
            }
            val distNode = node.node("distribution")
            val rawDist = distNode.rawScalar()?.toString() ?: "none"
            val distribution = PackDistribution.entries.firstOrNull { it.configName == rawDist.lowercase(Locale.ROOT) }
            if (distribution == null) {
                diagnostics.warn(
                    file,
                    "pack.distribution",
                    "Mode de distribution inconnu ${Diagnostic.quote(rawDist)} ; distribution désactivée",
                    Suggestions.closest(rawDist, PackDistribution.entries.map { it.configName }),
                )
                return PackSettings(PackDistribution.NONE, null, required, prompt, allowPrivate)
            }
            val rawUrl =
                node
                    .node("url")
                    .rawScalar()
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            if (distribution ==
                PackDistribution.NONE
            ) {
                return PackSettings(distribution, rawUrl.ifEmpty { null }, required, prompt, allowPrivate)
            }
            val check = PublicHttpsUrl.check(PackSettings.expandUrl(rawUrl, PackSettings.SAMPLE_SHA1), allowPrivate)
            if (!check.ok) {
                diagnostics.warn(
                    file,
                    "pack.url",
                    "URL du pack refusée (${check.reason}) : distribution désactivée",
                    "https://cdn.example.com/aaengine-{sha1}.zip",
                )
                return PackSettings(PackDistribution.NONE, null, required, prompt, allowPrivate)
            }
            return PackSettings(distribution, rawUrl, required, prompt, allowPrivate)
        }

        private fun bool(
            node: ConfigurationNode,
            file: String,
            diagnostics: DiagnosticCollector,
        ): Boolean? {
            if (node.virtual() || node.isNull) return null
            val raw = node.rawScalar()
            if (raw !is Boolean) {
                diagnostics.warn(file, YamlDocuments.pathOf(node), "Booléen attendu (true/false), reçu ${Diagnostic.quote(raw)}")
                return null
            }
            return raw
        }
    }
}

/** Mode de distribution du resource pack. */
enum class PackDistribution(
    val configName: String,
) {
    /** Aucun envoi : l'administrateur distribue le pack lui-même (`server.properties`, proxy…). */
    NONE("none"),

    /** URL HTTPS fournie par l'administrateur, envoyée au join avec le SHA-1 du dernier build. */
    EXTERNAL("external"),
}

/**
 * Section `pack` de `config.yml`.
 *
 * @property url URL publique (HTTPS, contrôlée par [PublicHttpsUrl]) ; `{sha1}` est remplacé par le SHA-1 du pack
 * @property prompt message texte brut affiché par le client (jamais interprété en MiniMessage)
 */
data class PackSettings(
    val distribution: PackDistribution = PackDistribution.NONE,
    val url: String? = null,
    val required: Boolean = false,
    val prompt: String? = null,
    val allowPrivateHosts: Boolean = false,
) {
    companion object {
        @JvmField
        val DEFAULT: PackSettings = PackSettings()

        const val MAX_PROMPT: Int = 256
        const val SHA1_PLACEHOLDER: String = "{sha1}"
        internal const val SAMPLE_SHA1: String = "0000000000000000000000000000000000000000"

        /** [url] avec `{sha1}` remplacé par [sha1] (hexadécimal minuscule). */
        @JvmStatic
        fun expandUrl(
            url: String,
            sha1: String,
        ): String = url.replace(SHA1_PLACEHOLDER, sha1)
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
