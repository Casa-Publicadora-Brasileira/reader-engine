package com.readerengine.core.parser

/**
 * Decodificador único de entidades XML/HTML em passagem única (`commonMain`),
 * suportando entidades nomeadas (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`, `&nbsp;`, `&mdash;`, `&ndash;`, `&copy;`)
 * e entidades numéricas decimais (`&#233;`) e hexadecimais (`&#x00E9;`),
 * sem sofrer com dupla decodificação de sequências como `&amp;lt;`.
 */
internal object XmlEntities {

    private val entityRegex = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z0-9]+);""")

    fun decode(input: String): String {
        if (!input.contains('&')) return input
        return entityRegex.replace(input) { match ->
            when (val token = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos", "#39" -> "'"
                "nbsp" -> " "
                "ndash" -> "\u2013"
                "mdash" -> "\u2014"
                "hellip" -> "\u2026"
                "copy" -> "\u00A9"
                "reg" -> "\u00AE"
                else -> decodeNumericEntity(token) ?: match.value
            }
        }
    }

    private fun decodeNumericEntity(token: String): String? {
        val codePoint = when {
            token.startsWith("#x", ignoreCase = true) ->
                token.substring(2).toIntOrNull(16)
            token.startsWith("#") ->
                token.substring(1).toIntOrNull(10)
            else -> null
        } ?: return null

        if (codePoint <= 0 || codePoint > 0x10FFFF) return null
        return codePointToString(codePoint)
    }

    private fun codePointToString(codePoint: Int): String {
        return if (codePoint <= 0xFFFF) {
            codePoint.toChar().toString()
        } else {
            val offset = codePoint - 0x10000
            val high = ((offset ushr 10) + 0xD800).toChar()
            val low = ((offset and 0x3FF) + 0xDC00).toChar()
            charArrayOf(high, low).concatToString()
        }
    }
}
