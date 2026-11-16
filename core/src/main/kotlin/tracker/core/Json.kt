package tracker.core

class JsonException(message: String) : RuntimeException(message)

/**
 * Minimal JSON reader/writer (objects, arrays, strings, integers, booleans, null).
 * Numbers must be integers and are read as Long: money is paise, so fractions never appear,
 * and refusing them keeps Float/Double out of the code base entirely.
 * Values map to: Map<String, Any?>, List<Any?>, String, Long, Boolean, null.
 */
object Json {

    fun write(value: Any?): String {
        val sb = StringBuilder()
        write(value, sb)
        return sb.toString()
    }

    private fun write(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Long -> sb.append(value)
            is Int -> sb.append(value)
            is String -> writeString(value, sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k as? String ?: throw JsonException("object keys must be strings"), sb)
                    sb.append(':')
                    write(v, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(v, sb)
                }
                sb.append(']')
            }
            else -> throw JsonException("unsupported value type " + value.javaClass.name)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) throw JsonException("unexpected trailing content at " + reader.pos)
        return value
    }

    private class Reader(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        private fun fail(what: String): Nothing = throw JsonException("$what at $pos")

        private fun expect(c: Char) {
            if (pos >= s.length || s[pos] != c) fail("expected '$c'")
            pos++
        }

        fun readValue(): Any? {
            if (pos >= s.length) fail("unexpected end")
            val c = s[pos]
            return when {
                c == '{' -> readObject()
                c == '[' -> readArray()
                c == '"' -> readString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || (c in '0'..'9') -> readNumber()
                else -> fail("unexpected character")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("bad literal")
            pos += word.length
            return value
        }

        private fun readNumber(): Long {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos < s.length && (s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E')) fail("only integer numbers are supported")
            return s.substring(start, pos).toLongOrNull() ?: fail("bad number")
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad unicode escape")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                skipWhitespace()
                out.add(readValue())
                skipWhitespace()
                if (pos < s.length && s[pos] == ',') { pos++; continue }
                expect(']')
                return out
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                out[key] = readValue()
                skipWhitespace()
                if (pos < s.length && s[pos] == ',') { pos++; continue }
                expect('}')
                return out
            }
        }
    }
}
