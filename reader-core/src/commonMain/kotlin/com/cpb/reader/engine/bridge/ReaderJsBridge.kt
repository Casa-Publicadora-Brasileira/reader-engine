package com.cpb.reader.engine.bridge

import com.cpb.reader.engine.model.FormEntry
import com.cpb.reader.engine.model.HighlightItem
import com.cpb.reader.engine.model.HighlightStyle
import com.cpb.reader.engine.model.PageFormState
import com.cpb.reader.engine.theme.ThemeTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Centraliza a geração de scripts JavaScript, injeção de HTML/CSS,
 * parse de respostas do `evaluateJavascript` e interceptação de links (`verse://`, capítulos, HTTP),
 * mantendo 100% de compatibilidade com `readerJS` (Rangy/Baker) usado no `CPBBooks` e `EscolaSabatina`.
 */
object ReaderJsBridge {

    private val json = Json { ignoreUnknownKeys = true }

    const val SCRIPT_SELECTION = "javascript:window.getSelection().toString()"
    const val SCRIPT_GET_HIGHLIGHTS = "javascript:readerJS.getHighlights()"
    const val SCRIPT_SUMMARY_HIGHLIGHTS = "javascript:readerJS.summaryHighlights()"
    const val SCRIPT_UNHIGHLIGHT_SELECTION = "javascript:readerJS.unHighlightSelection()"
    const val SCRIPT_GET_CHANGES = "javascript:getChanges()"
    const val SCRIPT_CLEAN_CHANGES = "javascript:cleanChanges()"
    const val SCRIPT_GET_ALL_MODIFICATIONS = "javascript:getAllModifications()"

    fun highlightSelectionCommand(style: HighlightStyle): String =
        "javascript:if(typeof readerJS !== \"undefined\"){readerJS.highlightSelection('${style.jsColorArg}');}"

    fun removeHighlightByIdCommand(highlightId: Int): String =
        "javascript:(function(){if(typeof readerJS!=='undefined'){return readerJS.removeHighlight($highlightId)||'[]';}return '[]';})()"

    fun setHighlightsCommand(serializedHighlights: String): String {
        val decoded = FirestorePayloadCodec.decodeIfNeeded(serializedHighlights)
        val escaped = escapeJsSingleQuoted(decoded)
        return "javascript:(function(){if(typeof readerJS!=='undefined'){readerJS.setHighlights('$escaped');}})();"
    }

    fun setAnnotationsCommand(annotationsJsonArray: String): String {
        val escaped = escapeJsSingleQuoted(annotationsJsonArray)
        return "javascript:(function(){if(typeof setAnnotations==='function'){setAnnotations('$escaped');}})();"
    }

    fun setAnswersCommand(answersJsonArray: String): String {
        val escaped = escapeJsSingleQuoted(answersJsonArray)
        return "javascript:(function(){if(typeof setAnswers==='function'){setAnswers('$escaped');}})();"
    }

    fun setFontFamilyCommand(fontFamily: String): String {
        val target = if (fontFamily == "Default" || fontFamily == "defaultFont") "" else escapeJsSingleQuoted(fontFamily)
        return "javascript:(function(){var els=document.getElementsByTagName('*');for(var i=0;i<els.length;i++){els[i].style.fontFamily='$target';}})();"
    }

    fun setFontSizeClassCommand(fontSizeClass: String): String =
        "javascript:if(typeof setFontSize==='function'){setFontSize(\"${escapeJsSingleQuoted(fontSizeClass)}\");}"

    fun setNightModeCommand(enabled: Boolean): String =
        "javascript:if(typeof nightMode==='function'){nightMode($enabled);}"

    fun scrollToYCommand(scrollYPx: Int): String {
        val clamped = scrollYPx.coerceAtLeast(0)
        return "javascript:(function(){var t=Math.max(0,$clamped/(window.devicePixelRatio||1));document.documentElement.scrollTop=t;document.body.scrollTop=t;window.scrollTo(0,t);})();"
    }

    /**
     * Injeta o CSS do tema, classes de tamanho/modo noturno e um polyfill `readerJS` leve
     * (caso o HTML do livro não tenha o script legado embutido).
     */
    fun injectThemeAndBridge(
        rawHtml: String,
        theme: ThemeTokens,
        fontScale: Float,
        fontFamily: String = "Default",
        fontSizeClass: String = "ESFontSize2",
        isNightMode: Boolean = false,
    ): String {
        val css = theme.toContentCss(
            fontScale = fontScale,
            customFontFamily = fontFamily,
            isNightMode = isNightMode,
        )
        val classes = buildString {
            append(fontSizeClass)
            if (isNightMode) append(" nightMode")
        }.trim()

        val htmlWithClass = if (rawHtml.contains("<html ", ignoreCase = true)) {
            rawHtml.replaceFirst(Regex("<html\\s+", RegexOption.IGNORE_CASE), "<html class=\"$classes\" ")
        } else {
            rawHtml
        }

        val headInjection = "<style id=\"reader-engine-theme\">$css</style><script>$FALLBACK_READER_JS</script>"

        return when {
            htmlWithClass.contains("</head>", ignoreCase = true) ->
                htmlWithClass.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$headInjection</head>")
            htmlWithClass.contains("<body", ignoreCase = true) ->
                htmlWithClass.replaceFirst(Regex("<body", RegexOption.IGNORE_CASE), "<head>$headInjection</head><body")
            else ->
                "<!doctype html><html class=\"$classes\"><head>$headInjection</head><body>$htmlWithClass</body></html>"
        }
    }

    /**
     * Normaliza o retorno de `WebView.evaluateJavascript(...)` removendo aspas externas e escapes JSON.
     */
    fun unquoteJsEvaluation(raw: String?): String {
        if (raw == null) return ""
        val trimmed = raw.trim()
        if (trimmed == "null" || trimmed.isEmpty()) return ""
        if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return try {
                json.decodeFromString<String>(trimmed)
            } catch (_: Exception) {
                trimmed.substring(1, trimmed.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\\\", "\\")
            }
        }
        return trimmed
    }

    /**
     * Faz o parse do JSON retornado por `readerJS.summaryHighlights()`:
     * `[{"id": 1, "text": "Trecho grifado", "cor": "highlight_yellow", "pos": 480.0}]`
     */
    fun parseSummaryHighlights(rawJsValue: String?, pageIndex: Int): List<HighlightItem> {
        val unquoted = unquoteJsEvaluation(rawJsValue).trim()
        if (unquoted.isEmpty() || unquoted == "null") return emptyList()

        return try {
            val array = json.parseToJsonElement(unquoted) as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.intOrNull ?: pageIndex
                val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val rawColor = obj["cor"]?.jsonPrimitive?.contentOrNull
                    ?: obj["typeHighlight"]?.jsonPrimitive?.contentOrNull
                    ?: "yellow"
                val pos = obj["pos"]?.jsonPrimitive?.doubleOrNull
                    ?: obj["position"]?.jsonPrimitive?.doubleOrNull
                    ?: 0.0

                HighlightItem(
                    id = id,
                    pageIndex = pageIndex,
                    text = text,
                    style = HighlightStyle.fromRaw(rawColor),
                    scrollPositionY = pos,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Faz o parse do JSON retornado por `getAllModifications()` ou salvo na coleção `"annotations"` do Firestore:
     * `{"annotations": [{"content": "...", "index": 0}], "answers": [{"content": "...", "index": 0}]}`
     */
    fun parsePageFormState(rawJsOrFirestoreValue: String?): PageFormState {
        val decoded = FirestorePayloadCodec.decodeIfNeeded(unquoteJsEvaluation(rawJsOrFirestoreValue)).trim()
        if (decoded.isEmpty() || decoded == "null") return PageFormState()

        return try {
            val obj = json.parseToJsonElement(decoded).jsonObject
            val annotations = (obj["annotations"] as? JsonArray)?.mapNotNull(::parseFormEntry).orEmpty()
            val answers = (obj["answers"] as? JsonArray)?.mapNotNull(::parseFormEntry).orEmpty()
            PageFormState(annotations = annotations, answers = answers)
        } catch (_: Exception) {
            PageFormState()
        }
    }

    fun serializePageFormState(state: PageFormState): String =
        json.encodeToString(PageFormState.serializer(), state)

    /**
     * Analisa URLs customizadas disparadas pela WebView (`verse://?q=...`, `http://`, `https://`, `highlight://`).
     */
    fun parseSpecialUrl(url: String): InterceptedUrl {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("verse://", ignoreCase = true) -> parseVerseUrl(trimmed)
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> InterceptedUrl.ExternalWebUrl(trimmed)
            trimmed.startsWith("highlight://", ignoreCase = true) -> InterceptedUrl.InvalidHighlightOnInput
            trimmed.startsWith("clipboard://", ignoreCase = true) -> InterceptedUrl.ClipboardAction
            else -> {
                val cleanPath = trimmed
                    .removePrefix("file:////")
                    .removePrefix("file:///")
                    .removePrefix("file://")
                val href = cleanPath.substringBefore('#')
                val anchor = cleanPath.substringAfter('#', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
                InterceptedUrl.InternalResource(href = href, anchor = anchor)
            }
        }
    }

    private fun parseVerseUrl(url: String): InterceptedUrl.BibleVerse {
        val queryPart = url.substringAfter("verse://?q=", missingDelimiterValue = "")
            .ifEmpty { url.substringAfter("verse://", missingDelimiterValue = "") }
        val decoded = percentDecode(queryPart)
        return try {
            val array = json.parseToJsonElement(decoded) as? JsonArray
            val firstObj = array?.firstOrNull() as? JsonObject
            val title = firstObj?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
            val text = firstObj?.get("text")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            InterceptedUrl.BibleVerse(referenceTitle = title, verseText = text)
        } catch (_: Exception) {
            InterceptedUrl.BibleVerse(referenceTitle = decoded, verseText = null)
        }
    }

    private fun parseFormEntry(element: kotlinx.serialization.json.JsonElement): FormEntry? {
        val obj = element as? JsonObject ?: return null
        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return null
        val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
        return FormEntry(content = content, index = index)
    }

    private fun escapeJsSingleQuoted(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    internal fun percentDecode(encoded: String): String {
        val out = ByteArray(encoded.length)
        var outIdx = 0
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            when {
                c == '+' -> {
                    out[outIdx++] = ' '.code.toByte()
                    i++
                }
                c == '%' && i + 2 < encoded.length -> {
                    val hex = encoded.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out[outIdx++] = hex.toByte()
                        i += 3
                    } else {
                        out[outIdx++] = c.code.toByte()
                        i++
                    }
                }
                else -> {
                    val bytes = c.toString().encodeToByteArray()
                    for (b in bytes) {
                        out[outIdx++] = b
                    }
                    i++
                }
            }
        }
        return out.decodeToString(0, outIdx)
    }

    private const val FALLBACK_READER_JS = """
        (function(){
            if (typeof window.readerJS === 'undefined') {
                var _serialized = '';
                var _items = [];
                window.readerJS = {
                    getHighlights: function() { return _serialized; },
                    setHighlights: function(data) { _serialized = data || ''; },
                    summaryHighlights: function() { return JSON.stringify(_items); },
                    highlightSelection: function(color) {
                        var sel = window.getSelection ? window.getSelection().toString().trim() : '';
                        if (sel) {
                            var id = _items.length + 1;
                            _items.push({ id: id, text: sel, cor: 'highlight_' + color, pos: window.scrollY || 0 });
                            _serialized = 'type:textContent|' + _items.map(function(i){ return i.id + '$' + i.cor + '$' + i.text; }).join('|');
                        }
                        return _serialized;
                    },
                    unHighlightSelection: function() {
                        _items.pop();
                        return _serialized;
                    },
                    removeHighlight: function(id) {
                        _items = _items.filter(function(i){ return i.id !== id; });
                        return JSON.stringify(_items);
                    }
                };
            }
        })();
    """
}

sealed interface InterceptedUrl {
    data class BibleVerse(val referenceTitle: String, val verseText: String?) : InterceptedUrl
    data class ExternalWebUrl(val url: String) : InterceptedUrl
    data class InternalResource(val href: String, val anchor: String?) : InterceptedUrl
    data object InvalidHighlightOnInput : InterceptedUrl
    data object ClipboardAction : InterceptedUrl
}
