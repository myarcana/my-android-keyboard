package com.offlinekeyboard.ime.gesture

/**
 * A minimal JSON reader and writer.
 *
 * The gesture bank is JSONL so that anything can read it -- jq, python, a notebook -- but this
 * app declares no dependencies, and `org.json` is only an unimplemented stub in JVM unit tests,
 * which have to parse the very same file in order to replay it. So both ends share this
 * instead. It is small, exact about the handful of value types the bank emits, and being one
 * implementation it cannot disagree with itself the way a writer and a separate reader could.
 */
object Json {

    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(value, out)
            is Boolean -> out.append(if (value) "true" else "false")
            is Enum<*> -> writeString(value.name, out)
            // Floats are written through Float.toString, not Double's: widening 92.13333f to a
            // double first prints 92.13333129882812, which is noise dressed up as precision.
            is Float, is Double, is Int, is Long -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                value.forEach { (k, v) ->
                    if (!first) out.append(',')
                    first = false
                    writeString(k.toString(), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(v, out)
                }
                out.append(']')
            }
            else -> writeString(value.toString(), out)
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        s.forEach { c ->
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append("%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    /** Parses one JSON document into Kotlin natives: Map, List, String, Double/Long, Boolean, null. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWhitespace()
        require(p.atEnd) { "trailing content at offset ${p.offset}" }
        return v
    }

    private class Parser(private val s: String) {
        var offset = 0
            private set

        val atEnd get() = offset >= s.length

        fun skipWhitespace() {
            while (offset < s.length && s[offset].isWhitespace()) offset++
        }

        fun value(): Any? {
            skipWhitespace()
            require(!atEnd) { "unexpected end of JSON" }
            return when (s[offset]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { offset++; return map }
            while (true) {
                skipWhitespace()
                val key = str()
                skipWhitespace()
                expect(':')
                map[key] = value()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    '}' -> return map
                    else -> error("expected , or } at offset ${offset - 1}, got '$c'")
                }
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') { offset++; return list }
            while (true) {
                list += value()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    ']' -> return list
                    else -> error("expected , or ] at offset ${offset - 1}, got '$c'")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(offset, offset + 4)
                            offset += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> error("bad escape at offset ${offset - 1}")
                    }
                    else -> sb.append(c)
                }
            }
        }

        /** Whole numbers come back as Long so integer fields survive the round trip exactly. */
        private fun num(): Any {
            val start = offset
            while (offset < s.length && s[offset] in "+-0123456789.eE") offset++
            val text = s.substring(start, offset)
            require(text.isNotEmpty()) { "expected a number at offset $start" }
            return if (text.any { it == '.' || it == 'e' || it == 'E' }) text.toDouble() else text.toLong()
        }

        private fun <T> literal(word: String, result: T): T {
            require(s.startsWith(word, offset)) { "expected $word at offset $offset" }
            offset += word.length
            return result
        }

        private fun peek(): Char? = if (atEnd) null else s[offset]

        private fun next(): Char {
            require(!atEnd) { "unexpected end of JSON" }
            return s[offset++]
        }

        private fun expect(c: Char) {
            require(next() == c) { "expected '$c' at offset ${offset - 1}" }
        }
    }
}
