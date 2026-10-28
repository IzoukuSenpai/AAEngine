package dev.aaengine.items

import net.kyori.adventure.key.Key
import java.util.Locale

/**
 * Règles des identifiants d'items et des clés de modèle : `namespace:valeur`, namespace `[a-z0-9_.-]{1,64}`,
 * valeur `[a-z0-9_./-]{1,128}`. Par défense en profondeur, les segments vides, `.` et `..` sont refusés
 * dans la valeur (ces clés serviront plus tard de chemins dans le resource pack).
 */
object ItemIds {
    const val DEFAULT_NAMESPACE: String = "aaengine"
    const val MAX_NAMESPACE: Int = 64
    const val MAX_VALUE: Int = 128

    private val NAMESPACE = Regex("[a-z0-9_.-]+")
    private val VALUE = Regex("[a-z0-9_./-]+")

    @JvmStatic
    fun isValidNamespace(namespace: String): Boolean =
        namespace.length in 1..MAX_NAMESPACE && NAMESPACE.matches(namespace) && namespace != "." && namespace != ".."

    @JvmStatic
    fun isValidValue(value: String): Boolean =
        value.length in 1..MAX_VALUE &&
            VALUE.matches(value) &&
            value.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /**
     * Clé `ns:valeur` ou `valeur` (namespace [defaultNamespace]), ou `null` si invalide. Jamais d'exception.
     */
    @JvmStatic
    fun parseKey(
        raw: String,
        defaultNamespace: String,
    ): Key? {
        val idx = raw.indexOf(':')
        val ns = if (idx < 0) defaultNamespace else raw.substring(0, idx)
        val value = if (idx < 0) raw else raw.substring(idx + 1)
        if (!isValidNamespace(ns) || !isValidValue(value)) return null
        return Key.key(ns, value)
    }

    /** Proposition de correction d'un identifiant invalide (minuscules, caractères interdits remplacés). */
    @JvmStatic
    fun sanitize(raw: String): String? {
        val lowered =
            raw
                .lowercase(Locale.ROOT)
                .map { if (it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' || it == '-' || it == '/') it else '_' }
                .joinToString("")
                .take(MAX_VALUE)
        return lowered.takeIf { isValidValue(it) && it != raw }
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
