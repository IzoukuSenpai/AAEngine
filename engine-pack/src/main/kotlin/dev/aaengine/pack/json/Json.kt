package dev.aaengine.pack.json

import java.util.Locale

/**
 * Modèle JSON minimal du compilateur de pack. Pas de dépendance externe : lecture stricte et bornée
 * (RFC 8259, sans commentaires ni virgules finales, clés en double refusées, profondeur limitée) et écriture
 * canonique (indentation de 2 espaces, fins de ligne LF, ordre des clés conservé) pour des sorties
 * reproductibles octet pour octet.
 */
sealed interface JsonValue

/** Objet : l'ordre d'insertion des membres est conservé à l'écriture ; l'égalité ignore cet ordre. */
data class JsonObject(
    val members: Map<String, JsonValue>,
) : JsonValue {
    operator fun get(key: String): JsonValue? = members[key]

    fun string(key: String): String? = (members[key] as? JsonString)?.value
}

data class JsonArray(
    val items: List<JsonValue>,
) : JsonValue

data class JsonString(
    val value: String,
) : JsonValue

/** Nombre conservé sous sa forme textuelle d'origine (aucune perte de précision, sortie stable). */
data class JsonNumber(
    val text: String,
) : JsonValue

data class JsonBool(
    val value: Boolean,
) : JsonValue

data object JsonNull : JsonValue

/** JSON refusé : [line] et [column] (1-based) désignent la position du problème. */
class JsonException(
    message: String,
    val line: Int,
    val column: Int,
) : Exception(message)

object Json {
    /** Profondeur d'imbrication maximale acceptée à la lecture. */
    const val MAX_DEPTH: Int = 64

    @JvmStatic
    fun obj(vararg members: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*members))

    @JvmStatic
    fun arr(vararg items: JsonValue): JsonArray = JsonArray(items.toList())

    @JvmStatic
    fun str(value: String): JsonString = JsonString(value)

    @JvmStatic
    fun num(value: Int): JsonNumber = JsonNumber(value.toString())

    /** Lit [text] (document complet). Lève [JsonException]. */
    @JvmStatic
    fun parse(
        text: String,
        maxDepth: Int = MAX_DEPTH,
    ): JsonValue = Parser(text, maxDepth).document()

    /** Écriture canonique terminée par un saut de ligne. */
    @JvmStatic
    fun write(value: JsonValue): String =
        buildString {
            writeValue(this, value, 0)
            append('\n')
        }

    @JvmStatic
    fun writeBytes(value: JsonValue): ByteArray = write(value).toByteArray(Charsets.UTF_8)

    private fun writeValue(
        out: StringBuilder,
        value: JsonValue,
        indent: Int,
    ) {
        when (value) {
            is JsonObject -> {
                if (value.members.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                var first = true
                for ((k, v) in value.members) {
                    if (!first) out.append(",\n")
                    first = false
                    pad(out, indent + 1)
                    writeString(out, k)
                    out.append(": ")
                    writeValue(out, v, indent + 1)
                }
                out.append('\n')
                pad(out, indent)
                out.append('}')
            }
            is JsonArray -> {
                if (value.items.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                value.items.forEachIndexed { i, v ->
                    if (i > 0) out.append(",\n")
                    pad(out, indent + 1)
                    writeValue(out, v, indent + 1)
                }
                out.append('\n')
                pad(out, indent)
                out.append(']')
            }
            is JsonString -> writeString(out, value.value)
            is JsonNumber -> out.append(value.text)
            is JsonBool -> out.append(value.value)
            JsonNull -> out.append("null")
        }
    }

    private fun pad(
        out: StringBuilder,
        indent: Int,
    ) {
        repeat(indent) { out.append("  ") }
    }

    private fun writeString(
        out: StringBuilder,
        s: String,
    ) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else ->
                    if (c < ' ' || c == ' ' || c == ' ') {
                        out.append("\\u").append(String.format(Locale.ROOT, "%04x", c.code))
                    } else {
                        out.append(c)
                    }
            }
        }
        out.append('"')
    }

    private class Parser(
        private val s: String,
        private val maxDepth: Int,
    ) {
        private var i = 0

        fun document(): JsonValue {
            skipWs()
            val v = value(0)
            skipWs()
            if (i != s.length) fail("Contenu inattendu après la fin du document")
            return v
        }

        private fun value(depth: Int): JsonValue {
            if (i >= s.length) fail("Fin de document inattendue")
            return when (val c = s[i]) {
                '{' -> obj(depth + 1)
                '[' -> array(depth + 1)
                '"' -> JsonString(string())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') number() else fail("Caractère inattendu '${printable(c)}'")
            }
        }

        private fun obj(depth: Int): JsonObject {
            if (depth > maxDepth) fail("Imbrication trop profonde (max $maxDepth)")
            i++ // {
            val members = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') {
                i++
                return JsonObject(members)
            }
            while (true) {
                skipWs()
                if (peek() != '"') fail("Clé entre guillemets attendue")
                val keyAt = i
                val key = string()
                skipWs()
                expect(':')
                skipWs()
                val v = value(depth)
                if (members.put(key, v) != null) {
                    i = keyAt
                    fail("Clé en double \"${key.take(MAX_SHOWN)}\"")
                }
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonObject(members)
                    }
                    else -> fail("',' ou '}' attendu")
                }
            }
        }

        private fun array(depth: Int): JsonArray {
            if (depth > maxDepth) fail("Imbrication trop profonde (max $maxDepth)")
            i++ // [
            val items = ArrayList<JsonValue>()
            skipWs()
            if (peek() == ']') {
                i++
                return JsonArray(items)
            }
            while (true) {
                skipWs()
                items.add(value(depth))
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonArray(items)
                    }
                    else -> fail("',' ou ']' attendu")
                }
            }
        }

        private fun string(): String {
            i++ // "
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Chaîne non terminée")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("Échappement incomplet")
                        when (val e = s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Échappement \\u incomplet")
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: fail("Échappement \\u invalide")
                                if (hex.any { it == '+' || it == '-' }) fail("Échappement \\u invalide")
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> {
                                i--
                                fail("Échappement inconnu '\\${printable(e)}'")
                            }
                        }
                    }
                    c < ' ' -> {
                        i--
                        fail("Caractère de contrôle dans une chaîne")
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): JsonNumber {
            val start = i
            if (peek() == '-') i++
            when {
                peek() == '0' -> i++
                peek() in '1'..'9' -> while (peek() in '0'..'9') i++
                else -> fail("Nombre invalide")
            }
            if (peek() == '.') {
                i++
                if (peek() !in '0'..'9') fail("Nombre invalide")
                while (peek() in '0'..'9') i++
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (peek() !in '0'..'9') fail("Nombre invalide")
                while (peek() in '0'..'9') i++
            }
            return JsonNumber(s.substring(start, i))
        }

        private fun literal(
            word: String,
            v: JsonValue,
        ): JsonValue {
            if (!s.startsWith(word, i)) fail("Valeur inconnue")
            i += word.length
            return v
        }

        private fun expect(c: Char) {
            if (peek() != c) fail("'$c' attendu")
            i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun printable(c: Char): String =
            if (c.isISOControl() ||
                c.isWhitespace()
            ) {
                String.format(Locale.ROOT, "U+%04X", c.code)
            } else {
                c.toString()
            }

        private fun fail(message: String): Nothing {
            var line = 1
            var col = 1
            for (k in 0 until minOf(i, s.length)) {
                if (s[k] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
            }
            throw JsonException(message, line, col)
        }

        private companion object {
            const val MAX_SHOWN = 64
        }
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
