package com.readerengine.core.bridge

import com.readerengine.core.model.FormEntry
import com.readerengine.core.model.HighlightItem
import com.readerengine.core.model.HighlightStyle
import com.readerengine.core.model.PageFormState
import com.readerengine.core.theme.ThemeTokens
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Centraliza a geração de scripts JavaScript, injeção de HTML/CSS,
 * parse de respostas do `evaluateJavascript` e interceptação de links (esquemas customizados, capítulos internos, HTTP),
 * mantendo compatibilidade tanto com HTML/EPUB genéricos (via polyfill automático) quanto com `readerJS` (Rangy/Baker).
 */
public object ReaderJsBridge {

    private val json = Json { ignoreUnknownKeys = true }
    private val safeCssClassRegex = Regex("""[^a-zA-Z0-9_-]""")

    public const val SCRIPT_SELECTION: String = "javascript:window.getSelection().toString()"
    public const val SCRIPT_GET_HIGHLIGHTS: String = "javascript:readerJS.getHighlights()"
    public const val SCRIPT_SUMMARY_HIGHLIGHTS: String = "javascript:readerJS.summaryHighlights()"
    public const val SCRIPT_UNHIGHLIGHT_SELECTION: String = "javascript:readerJS.unHighlightSelection()"
    public const val SCRIPT_GET_CHANGES: String = "javascript:getChanges()"
    public const val SCRIPT_CLEAN_CHANGES: String = "javascript:cleanChanges()"
    public const val SCRIPT_GET_ALL_MODIFICATIONS: String = "javascript:getAllModifications()"

    public fun highlightSelectionCommand(style: HighlightStyle): String =
        "javascript:if(typeof readerJS !== \"undefined\"){readerJS.highlightSelection('${style.jsColorArg}');}"

    public fun removeHighlightByIdCommand(highlightId: Int): String =
        "javascript:(function(){if(typeof readerJS!=='undefined'){return readerJS.removeHighlight($highlightId)||'[]';}return '[]';})()"

    public fun setHighlightsCommand(serializedHighlights: String): String {
        val decoded = PayloadCodec.decodeIfNeeded(serializedHighlights)
        val literal = jsStringLiteral(decoded)
        return "javascript:(function(){if(typeof readerJS!=='undefined'){readerJS.setHighlights($literal);}})();"
    }

    public fun setAnnotationsCommand(annotationsJsonArray: String): String {
        val literal = jsStringLiteral(annotationsJsonArray)
        return "javascript:(function(){if(typeof setAnnotations==='function'){setAnnotations($literal);}})();"
    }

    public fun setAnswersCommand(answersJsonArray: String): String {
        val literal = jsStringLiteral(answersJsonArray)
        return "javascript:(function(){if(typeof setAnswers==='function'){setAnswers($literal);}})();"
    }

    public fun setFontFamilyCommand(fontFamily: String): String {
        val target = if (fontFamily == "Default" || fontFamily == "defaultFont") "" else fontFamily
        val literal = jsStringLiteral(target)
        return "javascript:(function(){var f=$literal;var els=document.getElementsByTagName('*');for(var i=0;i<els.length;i++){els[i].style.fontFamily=f;}})();"
    }

    public fun setFontSizeClassCommand(fontSizeClass: String): String {
        val sanitized = sanitizeClassToken(fontSizeClass)
        val literal = jsStringLiteral(sanitized)
        return "javascript:if(typeof setFontSize==='function'){setFontSize($literal);}"
    }

    public fun setNightModeCommand(enabled: Boolean): String =
        "javascript:if(typeof nightMode==='function'){nightMode($enabled);}"

    public fun scrollToYCommand(scrollYPx: Int, anchor: String? = null): String {
        val clamped = scrollYPx.coerceAtLeast(0)
        if (!anchor.isNullOrBlank()) {
            val anchorLiteral = jsStringLiteral(anchor)
            return "javascript:(function(){var el=document.getElementById($anchorLiteral)||document.getElementsByName($anchorLiteral)[0];if(el){el.scrollIntoView();return;}var t=Math.max(0,$clamped/(window.devicePixelRatio||1));window.scrollTo(0,t);})();"
        }
        return "javascript:(function(){var t=Math.max(0,$clamped/(window.devicePixelRatio||1));document.documentElement.scrollTop=t;document.body.scrollTop=t;window.scrollTo(0,t);})();"
    }

    /**
     * Injeta o CSS do tema, classes opcionais de tamanho/modo noturno e um polyfill `readerJS` leve
     * (para que livros EPUB/HTML comuns tenham suporte imediato a marcações sem exigir scripts embarcados).
     */
    public fun injectThemeAndBridge(
        rawHtml: String,
        theme: ThemeTokens,
        fontScale: Float,
        fontFamily: String = "Default",
        fontSizeClass: String = "",
        isNightMode: Boolean = false,
    ): String {
        val css = theme.toContentCss(
            fontScale = fontScale,
            customFontFamily = fontFamily,
            isNightMode = isNightMode,
        )
        val safeFontClass = sanitizeClassToken(fontSizeClass)
        val classes = buildString {
            if (safeFontClass.isNotBlank()) append(safeFontClass)
            if (isNightMode) {
                if (isNotEmpty()) append(' ')
                append("nightMode")
            }
        }.trim()

        val htmlOpenRegex = Regex("<html\\s+", RegexOption.IGNORE_CASE)
        val htmlWithClass = if (classes.isNotEmpty() && htmlOpenRegex.containsMatchIn(rawHtml)) {
            rawHtml.replaceFirst(htmlOpenRegex, Regex.escapeReplacement("<html class=\"$classes\" "))
        } else {
            rawHtml
        }

        val headInjection = "<style id=\"reader-engine-theme\">$css</style><script>$FALLBACK_READER_JS</script>"
        val headCloseRegex = Regex("</head>", RegexOption.IGNORE_CASE)
        val bodyOpenRegex = Regex("<body\\b", RegexOption.IGNORE_CASE)

        return when {
            headCloseRegex.containsMatchIn(htmlWithClass) ->
                htmlWithClass.replaceFirst(headCloseRegex, Regex.escapeReplacement("$headInjection</head>"))
            bodyOpenRegex.containsMatchIn(htmlWithClass) ->
                htmlWithClass.replaceFirst(bodyOpenRegex, Regex.escapeReplacement("<head>$headInjection</head><body"))
            else -> {
                val classAttr = if (classes.isNotEmpty()) " class=\"$classes\"" else ""
                "<!doctype html><html$classAttr><head>$headInjection</head><body>$htmlWithClass</body></html>"
            }
        }
    }

    /**
     * Normaliza o retorno de `WebView.evaluateJavascript(...)` removendo aspas externas e escapes JSON.
     */
    public fun unquoteJsEvaluation(raw: String?): String {
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
    public fun parseSummaryHighlights(rawJsValue: String?, pageIndex: Int): List<HighlightItem> {
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
     * Faz o parse do JSON de formulários/anotações da página:
     * `{"annotations": [{"content": "...", "index": 0}], "answers": [{"content": "...", "index": 0}]}`
     */
    public fun parsePageFormState(rawJsOrStoredValue: String?): PageFormState {
        val decoded = PayloadCodec.decodeIfNeeded(unquoteJsEvaluation(rawJsOrStoredValue)).trim()
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

    public fun serializePageFormState(state: PageFormState): String =
        json.encodeToString(PageFormState.serializer(), state)

    /**
     * Analisa qualquer URL disparada pela WebView:
     * - Links externos (`http://`, `https://`, `mailto:`, `tel:`)
     * - Esquemas internos reservados (`highlight://`, `clipboard://`, `book://`, `file://`)
     * - Qualquer esquema customizado do livro (`verse://?q=...`, `footnote://...`, `audio://...`, `glossary://...`)
     * - Caminhos relativos para outros capítulos do spine (`ch2.xhtml#sec1`)
     */
    public fun parseSpecialUrl(url: String): InterceptedUrl {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()

        return when {
            scheme == "http" || scheme == "https" || trimmed.startsWith("mailto:", ignoreCase = true) || trimmed.startsWith("tel:", ignoreCase = true) ->
                InterceptedUrl.ExternalWebUrl(trimmed)

            scheme == "highlight" -> InterceptedUrl.InvalidHighlightOnInput
            scheme == "clipboard" -> InterceptedUrl.ClipboardAction

            scheme.isNotEmpty() && scheme != "file" && scheme != "book" && !scheme.contains('/') ->
                parseCustomSchemeUrl(scheme, trimmed)

            else -> {
                val cleanPath = trimmed
                    .removePrefix("book://content/")
                    .removePrefix("file:////")
                    .removePrefix("file:///")
                    .removePrefix("file://")
                val href = cleanPath.substringBefore('#')
                val anchor = cleanPath.substringAfter('#', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
                InterceptedUrl.InternalResource(href = href, anchor = anchor)
            }
        }
    }

    private fun parseCustomSchemeUrl(scheme: String, url: String): InterceptedUrl.CustomUri {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        val queryPayload = if (afterScheme.contains("?q=")) {
            afterScheme.substringAfter("?q=")
        } else if (afterScheme.startsWith("?")) {
            afterScheme.removePrefix("?")
        } else {
            afterScheme
        }
        val decoded = percentDecode(queryPayload)
        return try {
            val array = json.parseToJsonElement(decoded) as? JsonArray
            val firstObj = array?.firstOrNull() as? JsonObject
            if (firstObj != null) {
                val title = firstObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val text = firstObj["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                InterceptedUrl.CustomUri(
                    scheme = scheme,
                    rawUrl = url,
                    title = title,
                    content = text,
                )
            } else {
                InterceptedUrl.CustomUri(
                    scheme = scheme,
                    rawUrl = url,
                    title = decoded,
                    content = null,
                )
            }
        } catch (_: Exception) {
            InterceptedUrl.CustomUri(
                scheme = scheme,
                rawUrl = url,
                title = decoded,
                content = null,
            )
        }
    }

    private fun parseFormEntry(element: JsonElement): FormEntry? {
        val obj = element as? JsonObject ?: return null
        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return null
        val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
        return FormEntry(content = content, index = index)
    }

    internal fun sanitizeClassToken(raw: String): String =
        raw.trim().replace(safeCssClassRegex, "")

    /**
     * Serializa uma string para um literal JavaScript seguro entre aspas duplas,
     * escapando caracteres de controle, aspas, barras e fechamento de tag `</script>`.
     */
    internal fun jsStringLiteral(value: String): String =
        json.encodeToString(String.serializer(), value)
            .replace("</", "<\\/")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

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
                var _nextId = 1;
                function _rebuildSerialized() {
                    if (!_items.length) { _serialized = ''; return; }
                    _serialized = 'type:textContent|' + _items.map(function(i){
                        return i.id + '$' + i.cor + '$' + i.text.replace(/\|/g, ' ').replace(/\$/g, ' ');
                    }).join('|');
                }
                function _clearDomSpans() {
                    var spans = document.querySelectorAll('span[data-reader-hl-id]');
                    for (var i = 0; i < spans.length; i++) {
                        var sp = spans[i];
                        var parent = sp.parentNode;
                        if (parent) {
                            while (sp.firstChild) parent.insertBefore(sp.firstChild, sp);
                            parent.removeChild(sp);
                            parent.normalize();
                        }
                    }
                }
                function _wrapRangeSafe(range, id, cls) {
                    try {
                        var span = document.createElement('span');
                        span.className = cls;
                        span.setAttribute('data-reader-hl-id', String(id));
                        range.surroundContents(span);
                        return span.getBoundingClientRect().top + (window.scrollY || window.pageYOffset || 0);
                    } catch (e) {
                        return window.scrollY || window.pageYOffset || 0;
                    }
                }
                function _applyTextHighlightInDom(id, cls, text) {
                    if (!text || !document.body || !document.createTreeWalker) return;
                    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                    var node;
                    while ((node = walker.nextNode())) {
                        var idx = node.nodeValue ? node.nodeValue.indexOf(text) : -1;
                        if (idx >= 0) {
                            var r = document.createRange();
                            r.setStart(node, idx);
                            r.setEnd(node, idx + text.length);
                            _wrapRangeSafe(r, id, cls);
                            break;
                        }
                    }
                }
                window.readerJS = {
                    getHighlights: function() { return _serialized; },
                    setHighlights: function(data) {
                        _serialized = data || '';
                        _items = [];
                        _clearDomSpans();
                        if (!_serialized) return;
                        var parts = _serialized.split('|');
                        for (var i = 0; i < parts.length; i++) {
                            var p = parts[i];
                            if (!p || p.indexOf('type:') === 0) continue;
                            var tokens = p.split('$');
                            if (tokens.length >= 3) {
                                var id = parseInt(tokens[0], 10) || (_items.length + 1);
                                if (id >= _nextId) _nextId = id + 1;
                                var cor = tokens[1].indexOf('highlight_') === 0 ? tokens[1] : ('highlight_' + tokens[1]);
                                var txt = tokens.slice(2).join('$');
                                if (txt) {
                                    _items.push({ id: id, text: txt, cor: cor, pos: 0 });
                                    _applyTextHighlightInDom(id, cor, txt);
                                }
                            }
                        }
                    },
                    summaryHighlights: function() { return JSON.stringify(_items); },
                    highlightSelection: function(color) {
                        var selObj = window.getSelection ? window.getSelection() : null;
                        var sel = selObj ? selObj.toString().trim() : '';
                        if (sel) {
                            var id = _nextId++;
                            var cls = color.indexOf('highlight_') === 0 ? color : ('highlight_' + color);
                            var pos = window.scrollY || window.pageYOffset || 0;
                            if (selObj && selObj.rangeCount > 0) {
                                pos = _wrapRangeSafe(selObj.getRangeAt(0), id, cls);
                                try { selObj.removeAllRanges(); } catch (e) {}
                            }
                            _items.push({ id: id, text: sel, cor: cls, pos: pos });
                            _rebuildSerialized();
                        }
                        return _serialized;
                    },
                    unHighlightSelection: function() {
                        var selObj = window.getSelection ? window.getSelection() : null;
                        var sel = selObj ? selObj.toString().trim() : '';
                        if (sel) {
                            for (var i = _items.length - 1; i >= 0; i--) {
                                if (_items[i].text === sel || sel.indexOf(_items[i].text) >= 0) {
                                    window.readerJS.removeHighlight(_items[i].id);
                                    return _serialized;
                                }
                            }
                        }
                        if (_items.length > 0) {
                            var last = _items[_items.length - 1];
                            window.readerJS.removeHighlight(last.id);
                        }
                        return _serialized;
                    },
                    removeHighlight: function(id) {
                        _items = _items.filter(function(i){ return i.id !== id; });
                        var spans = document.querySelectorAll('span[data-reader-hl-id="' + id + '"]');
                        for (var i = 0; i < spans.length; i++) {
                            var sp = spans[i];
                            var parent = sp.parentNode;
                            if (parent) {
                                while (sp.firstChild) parent.insertBefore(sp.firstChild, sp);
                                parent.removeChild(sp);
                                parent.normalize();
                            }
                        }
                        _rebuildSerialized();
                        return JSON.stringify(_items);
                    }
                };
            }
            if (typeof window.__readerScrollHooked === 'undefined') {
                window.__readerScrollHooked = true;
                var _scrollTimer = null;
                window.addEventListener('scroll', function() {
                    if (_scrollTimer) clearTimeout(_scrollTimer);
                    _scrollTimer = setTimeout(function() {
                        if (typeof window.AndroidScrollProgress !== 'undefined' && window.AndroidScrollProgress.onScrollChanged) {
                            var sy = Math.round(window.scrollY || window.pageYOffset || 0);
                            var ch = Math.round(Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.scrollHeight : 0));
                            var vh = Math.round(window.innerHeight || 0);
                            window.AndroidScrollProgress.onScrollChanged(sy, ch, vh);
                        }
                    }, 80);
                }, { passive: true });
            }
        })();
    """
}

public sealed interface InterceptedUrl {
    public data class CustomUri(
        val scheme: String,
        val rawUrl: String,
        val title: String,
        val content: String?,
    ) : InterceptedUrl

    public data class ExternalWebUrl(val url: String) : InterceptedUrl
    public data class InternalResource(val href: String, val anchor: String?) : InterceptedUrl
    public data object InvalidHighlightOnInput : InterceptedUrl
    public data object ClipboardAction : InterceptedUrl
}
