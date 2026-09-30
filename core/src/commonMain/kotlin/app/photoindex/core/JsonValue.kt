package app.photoindex.core

internal sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val literal: String) : JsonValue
    data object Bool : JsonValue
    data object Null : JsonValue
}

private class JsonParseException : Exception()

/**
 * 读入一整段 JSON。前后空白可以有，后面再跟别的文字则失败。失败时返回 null，不交出半截值。
 */
internal fun readJsonValue(text: String): JsonValue? = try {
    val cursor = JsonCursor(text)
    val value = cursor.parseValue(depth = 0)
    cursor.skipWs()
    if (cursor.eof()) value else null
} catch (_: JsonParseException) {
    null
}

private class JsonCursor(private val text: String) {
    private var pos = 0

    fun eof(): Boolean = pos >= text.length

    fun parseValue(depth: Int): JsonValue {
        if (depth > MAX_DEPTH) fail()
        skipWs()
        if (eof()) fail()
        return when (peek()) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> JsonValue.Str(parseString())
            't' -> literal("true", JsonValue.Bool)
            'f' -> literal("false", JsonValue.Bool)
            'n' -> literal("null", JsonValue.Null)
            '-', in '0'..'9' -> JsonValue.Num(parseNumber())
            else -> fail()
        }
    }

    fun skipWs() {
        while (!eof() && text[pos].isJsonWhitespace()) pos++
    }

    private fun parseObject(depth: Int): JsonValue.Obj {
        expect('{')
        skipWs()
        if (consume('}')) return JsonValue.Obj(emptyMap())
        val fields = LinkedHashMap<String, JsonValue>()
        while (true) {
            skipWs()
            if (eof() || peek() != '"') fail()
            val key = parseString()
            skipWs()
            expect(':')
            fields[key] = parseValue(depth)
            skipWs()
            when {
                consume('}') -> return JsonValue.Obj(fields)
                consume(',') -> {
                    skipWs()
                    if (eof() || peek() == '}') fail()
                }
                else -> fail()
            }
        }
    }

    private fun parseArray(depth: Int): JsonValue.Arr {
        expect('[')
        skipWs()
        if (consume(']')) return JsonValue.Arr(emptyList())
        val items = ArrayList<JsonValue>()
        while (true) {
            items += parseValue(depth)
            skipWs()
            when {
                consume(']') -> return JsonValue.Arr(items)
                consume(',') -> {
                    skipWs()
                    if (eof() || peek() == ']') fail()
                }
                else -> fail()
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (!eof()) {
            val ch = text[pos++]
            when {
                ch == '"' -> return out.toString()
                ch == '\\' -> appendEscape(out)
                ch.code < 0x20 -> fail()
                else -> out.append(ch)
            }
        }
        fail()
    }

    private fun appendEscape(out: StringBuilder) {
        if (eof()) fail()
        when (val ch = text[pos++]) {
            '"' -> out.append('"')
            '\\' -> out.append('\\')
            '/' -> out.append('/')
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> appendUnicodeEscape(out)
            else -> fail()
        }
    }

    private fun appendUnicodeEscape(out: StringBuilder) {
        val ch = readHex4().toChar()
        if (!ch.isHighSurrogate()) {
            if (ch.isLowSurrogate()) fail()
            out.append(ch)
            return
        }
        if (pos + 6 > text.length || text[pos] != '\\' || text[pos + 1] != 'u') fail()
        pos += 2
        val low = readHex4().toChar()
        if (!low.isLowSurrogate()) fail()
        out.append(ch)
        out.append(low)
    }

    private fun readHex4(): Int {
        if (pos + 4 > text.length) fail()
        var code = 0
        repeat(4) {
            code = (code shl 4) or hexValue(text[pos++])
        }
        return code
    }

    private fun parseNumber(): String {
        val start = pos
        if (peek() == '-') pos++
        if (eof()) fail()
        when {
            peek() == '0' -> pos++
            peek() in '1'..'9' -> while (!eof() && peek() in '0'..'9') pos++
            else -> fail()
        }
        if (!eof() && peek() == '.') {
            pos++
            if (eof() || peek() !in '0'..'9') fail()
            while (!eof() && peek() in '0'..'9') pos++
        }
        if (!eof() && (peek() == 'e' || peek() == 'E')) {
            pos++
            if (!eof() && (peek() == '+' || peek() == '-')) pos++
            if (eof() || peek() !in '0'..'9') fail()
            while (!eof() && peek() in '0'..'9') pos++
        }
        return text.substring(start, pos)
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!text.startsWith(word, pos)) fail()
        pos += word.length
        return value
    }

    private fun expect(ch: Char) {
        if (eof() || text[pos] != ch) fail()
        pos++
    }

    private fun consume(ch: Char): Boolean {
        if (eof() || text[pos] != ch) return false
        pos++
        return true
    }

    private fun peek(): Char = text[pos]

    private fun fail(): Nothing = throw JsonParseException()

    companion object {
        private const val MAX_DEPTH = 32
    }
}

private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\n' || this == '\r' || this == '\t'

private fun hexValue(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    in 'A'..'F' -> ch - 'A' + 10
    else -> throw JsonParseException()
}
