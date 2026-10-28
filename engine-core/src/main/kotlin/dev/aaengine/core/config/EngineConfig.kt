package dev.aaengine.core.config

import dev.aaengine.core.diagnostic.Diagnostic
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.core.diagnostic.Suggestions
import dev.aaengine.core.log.LogCategory
import java.util.Locale

/**
 * Configuration typée de `config.yml` (§27). Une valeur invalide est signalée et remplacée par sa valeur
 * par défaut sûre ; la configuration ne bloque jamais le chargement du contenu.
 */
data class EngineConfig(
    val debugCategories: Set<LogCategory> = emptySet(),
) {
    companion object {
        @JvmField
        val DEFAULT: EngineConfig = EngineConfig()

        /** Version de format de `config.yml` comprise par cette version de l'engine. */
        const val CONFIG_VERSION: Int = 1

        private val ROOT_KEYS = listOf("config-version", "debug")
        private val DEBUG_KEYS = listOf("categories")

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
            return EngineConfig(categories)
        }
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
