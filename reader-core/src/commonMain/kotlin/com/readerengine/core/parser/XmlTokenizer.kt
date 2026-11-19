package com.readerengine.core.parser

/**
 * Tokens emitidos pelo scanner XML/XHTML determinístico em passagem única (`commonMain`).
 */
public sealed interface XmlToken {
    public data class StartTag(
        val rawName: String,
        val localName: String,
        val prefix: String?,
        val attributes: Map<String, String>,
        val selfClosing: Boolean,
    ) : XmlToken {
        public fun attr(name: String): String? = findAttributeIgnoreCase(attributes, name)
    }

    public data class EndTag(
        val rawName: String,
        val localName: String,
        val prefix: String?,
    ) : XmlToken

    public data class Text(
        val content: String,
    ) : XmlToken

    public data class CData(
        val content: String,
    ) : XmlToken
}

/**
 * Nó de elemento na árvore XML/XHTML construída por [XmlTokenizer.parseDocument].
 */
public data class XmlElement(
    val rawName: String,
    val localName: String,
    val prefix: String?,
    val attributes: Map<String, String>,
    val textContent: String,
    val children: List<XmlElement>,
) {
    public fun attr(name: String): String? = findAttributeIgnoreCase(attributes, name)

    public fun collectText(): String = textContent

    public fun childElements(localName: String): List<XmlElement> =
        children.filter { it.localName.equals(localName, ignoreCase = true) }

    public fun findFirst(localName: String): XmlElement? {
        for (child in children) {
            if (child.localName.equals(localName, ignoreCase = true)) return child
            val nested = child.findFirst(localName)
            if (nested != null) return nested
        }
        return null
    }

    public fun findAll(localName: String): List<XmlElement> {
        val out = mutableListOf<XmlElement>()
        collectByLocalName(this, localName, out)
        return out
    }

    private companion object {
        private fun collectByLocalName(
            element: XmlElement,
            targetLocalName: String,
            out: MutableList<XmlElement>,
        ) {
            for (child in element.children) {
                if (child.localName.equals(targetLocalName, ignoreCase = true)) {
                    out.add(child)
                }
                if (child.children.isNotEmpty()) {
                    collectByLocalName(child, targetLocalName, out)
                }
            }
        }
    }
}

private fun findAttributeIgnoreCase(attributes: Map<String, String>, name: String): String? {
    attributes[name]?.let { return it }
    for ((k, v) in attributes) {
        if (k.equals(name, ignoreCase = true)) return v
    }
    if (!name.contains(':')) {
        for ((k, v) in attributes) {
            val local = if (k.contains(':')) k.substringAfter(':') else k
            if (local.equals(name, ignoreCase = true)) return v
        }
    }
    return null
}

/**
 * Tokenizer e construtor de árvore XML/XHTML determinístico em Kotlin Multiplatform puro (`commonMain`).
 *
 * Suporta comentários (`<!-- ... -->`), seções `<![CDATA[ ... ]]>`, processing instructions (`<?...?>`),
 * `<!DOCTYPE ...>` com subsets internos, namespaces, atributos multilinhas com aspas e tags self-closing (`/>`),
 * substituindo o parsing frágil baseado em expressões regulares (**GAP-03**).
 */
public object XmlTokenizer {

    public fun tokenize(xml: String): List<XmlToken> {
        val tokens = mutableListOf<XmlToken>()
        val len = xml.length
        var i = if (len > 0 && xml[0] == '\uFEFF') 1 else 0

        while (i < len) {
            val lt = xml.indexOf('<', startIndex = i)
            if (lt < 0) {
                val tailText = xml.substring(i)
                if (tailText.isNotEmpty()) {
                    tokens.add(XmlToken.Text(XmlEntities.decode(tailText)))
                }
                break
            }

            if (lt > i) {
                val text = xml.substring(i, lt)
                if (text.isNotEmpty()) {
                    tokens.add(XmlToken.Text(XmlEntities.decode(text)))
                }
            }

            if (lt + 1 >= len) break

            val nextChar = xml[lt + 1]
            when {
                // Processing Instruction: <?...?>
                nextChar == '?' -> {
                    val closePi = xml.indexOf("?>", startIndex = lt + 2)
                    i = if (closePi >= 0) closePi + 2 else len
                }

                // Comment: <!-- ... -->
                xml.startsWith("<!--", startIndex = lt) -> {
                    val closeComment = xml.indexOf("-->", startIndex = lt + 4)
                    i = if (closeComment >= 0) closeComment + 3 else len
                }

                // CDATA section: <![CDATA[ ... ]]>
                xml.startsWith("<![CDATA[", startIndex = lt, ignoreCase = true) -> {
                    val contentStart = lt + 9
                    val closeCdata = xml.indexOf("]]>", startIndex = contentStart)
                    if (closeCdata >= 0) {
                        tokens.add(XmlToken.CData(xml.substring(contentStart, closeCdata)))
                        i = closeCdata + 3
                    } else {
                        tokens.add(XmlToken.CData(xml.substring(contentStart)))
                        i = len
                    }
                }

                // DOCTYPE or other declaration: <!...>
                nextChar == '!' -> {
                    i = skipDeclaration(xml, lt + 2)
                }

                // Closing tag: </name>
                nextChar == '/' -> {
                    val closeGt = findTagCloseOutsideQuotes(xml, lt + 2)
                    if (closeGt < 0) {
                        i = len
                    } else {
                        val rawName = xml.substring(lt + 2, closeGt).trim()
                        if (rawName.isNotEmpty()) {
                            val (prefix, localName) = splitQName(rawName)
                            tokens.add(XmlToken.EndTag(rawName = rawName, localName = localName, prefix = prefix))
                        }
                        i = closeGt + 1
                    }
                }

                // Opening / Self-closing tag: <name ...> or <name .../>
                isNameStartChar(nextChar) -> {
                    val closeGt = findTagCloseOutsideQuotes(xml, lt + 1)
                    if (closeGt < 0) {
                        i = len
                    } else {
                        var innerEnd = closeGt
                        while (innerEnd > lt + 1 && xml[innerEnd - 1].isWhitespace()) {
                            innerEnd--
                        }
                        val selfClosing = innerEnd > lt + 1 && xml[innerEnd - 1] == '/'
                        val tagBodyEnd = if (selfClosing) innerEnd - 1 else innerEnd
                        val body = xml.substring(lt + 1, tagBodyEnd)
                        val nameEnd = findNameEnd(body)
                        val rawName = body.substring(0, nameEnd)
                        val attrPart = if (nameEnd < body.length) body.substring(nameEnd) else ""
                        val (prefix, localName) = splitQName(rawName)
                        val attributes = if (attrPart.isBlank()) emptyMap() else parseAttributes(attrPart)
                        tokens.add(
                            XmlToken.StartTag(
                                rawName = rawName,
                                localName = localName,
                                prefix = prefix,
                                attributes = attributes,
                                selfClosing = selfClosing,
                            ),
                        )
                        i = closeGt + 1
                    }
                }

                // Stray '<' treated as literal text
                else -> {
                    tokens.add(XmlToken.Text("<"))
                    i = lt + 1
                }
            }
        }

        return tokens
    }

    public fun parseDocument(xml: String): XmlElement {
        class ElementBuilder(
            val rawName: String,
            val localName: String,
            val prefix: String?,
            val attributes: Map<String, String>,
        ) {
            val textBuffer = StringBuilder()
            val children = mutableListOf<XmlElement>()

            fun build(): XmlElement = XmlElement(
                rawName = rawName,
                localName = localName,
                prefix = prefix,
                attributes = attributes,
                textContent = textBuffer.toString(),
                children = children.toList(),
            )
        }

        val root = ElementBuilder(
            rawName = "#document",
            localName = "#document",
            prefix = null,
            attributes = emptyMap(),
        )
        val stack = ArrayDeque<ElementBuilder>()
        stack.addLast(root)

        for (token in tokenize(xml)) {
            when (token) {
                is XmlToken.StartTag -> {
                    val builder = ElementBuilder(
                        rawName = token.rawName,
                        localName = token.localName,
                        prefix = token.prefix,
                        attributes = token.attributes,
                    )
                    if (token.selfClosing) {
                        stack.last().children.add(builder.build())
                    } else {
                        stack.addLast(builder)
                    }
                }

                is XmlToken.Text -> {
                    stack.last().textBuffer.append(token.content)
                }

                is XmlToken.CData -> {
                    stack.last().textBuffer.append(token.content)
                }

                is XmlToken.EndTag -> {
                    var matchDepth = -1
                    for (idx in stack.size - 1 downTo 1) {
                        if (stack[idx].localName.equals(token.localName, ignoreCase = true)) {
                            matchDepth = idx
                            break
                        }
                    }
                    if (matchDepth > 0) {
                        while (stack.size > matchDepth) {
                            val finished = stack.removeLast()
                            val built = finished.build()
                            val parent = stack.last()
                            parent.children.add(built)
                            if (built.textContent.isNotEmpty()) {
                                parent.textBuffer.append(built.textContent)
                            }
                        }
                    }
                }
            }
        }

        while (stack.size > 1) {
            val finished = stack.removeLast()
            val built = finished.build()
            val parent = stack.last()
            parent.children.add(built)
            if (built.textContent.isNotEmpty()) {
                parent.textBuffer.append(built.textContent)
            }
        }

        return root.build()
    }

    public fun parseAttributes(rawAttributes: String): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        val len = rawAttributes.length
        var i = 0

        while (i < len) {
            while (i < len && (rawAttributes[i].isWhitespace() || rawAttributes[i] == '/')) i++
            if (i >= len) break

            val nameStart = i
            while (i < len && !rawAttributes[i].isWhitespace() && rawAttributes[i] != '=' && rawAttributes[i] != '/' && rawAttributes[i] != '>') {
                i++
            }
            val attrName = rawAttributes.substring(nameStart, i)
            if (attrName.isEmpty()) {
                i++
                continue
            }

            while (i < len && rawAttributes[i].isWhitespace()) i++
            if (i >= len || rawAttributes[i] != '=') {
                attrs[attrName] = ""
                continue
            }

            i++ // consume '='
            while (i < len && rawAttributes[i].isWhitespace()) i++
            if (i >= len) {
                attrs[attrName] = ""
                break
            }

            val quote = rawAttributes[i]
            val rawValue = if (quote == '"' || quote == '\'') {
                i++
                val valStart = i
                val closeQuote = rawAttributes.indexOf(quote, startIndex = valStart)
                if (closeQuote >= 0) {
                    i = closeQuote + 1
                    rawAttributes.substring(valStart, closeQuote)
                } else {
                    i = len
                    rawAttributes.substring(valStart)
                }
            } else {
                val valStart = i
                while (i < len && !rawAttributes[i].isWhitespace() && rawAttributes[i] != '>') i++
                rawAttributes.substring(valStart, i)
            }
            attrs[attrName] = XmlEntities.decode(rawValue)
        }

        return attrs
    }

    private fun skipDeclaration(xml: String, startIndex: Int): Int {
        val len = xml.length
        var i = startIndex
        var bracketDepth = 0
        var quoteChar = '\u0000'

        while (i < len) {
            val c = xml[i]
            if (quoteChar != '\u0000') {
                if (c == quoteChar) quoteChar = '\u0000'
            } else {
                when (c) {
                    '"', '\'' -> quoteChar = c
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                    '>' -> if (bracketDepth == 0) return i + 1
                }
            }
            i++
        }
        return len
    }

    private fun findTagCloseOutsideQuotes(xml: String, startIndex: Int): Int {
        val len = xml.length
        var i = startIndex
        var quoteChar = '\u0000'

        while (i < len) {
            val c = xml[i]
            if (quoteChar != '\u0000') {
                if (c == quoteChar) quoteChar = '\u0000'
            } else {
                when (c) {
                    '"', '\'' -> quoteChar = c
                    '>' -> return i
                }
            }
            i++
        }
        return -1
    }

    private fun findNameEnd(body: String): Int {
        var i = 0
        while (i < body.length && !body[i].isWhitespace()) i++
        return i
    }

    private fun splitQName(rawName: String): Pair<String?, String> {
        val colon = rawName.indexOf(':')
        return if (colon > 0 && colon < rawName.lastIndex) {
            rawName.substring(0, colon) to rawName.substring(colon + 1)
        } else {
            null to rawName
        }
    }

    private fun isNameStartChar(c: Char): Boolean =
        c.isLetter() || c == '_' || c == ':'
}
