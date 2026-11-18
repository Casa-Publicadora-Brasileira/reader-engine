package com.readerengine.core.bridge

import com.readerengine.core.model.FormEntry
import com.readerengine.core.model.HighlightItem
import com.readerengine.core.model.HighlightStyle
import com.readerengine.core.model.PageFormState
import com.readerengine.core.model.PageProgressionDirection
import com.readerengine.core.model.ReadingMode
import com.readerengine.core.model.RenditionLayout
import com.readerengine.core.model.RenditionSpread
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

    public const val ACTION_SUB_PAGE_CHANGED: String = "reader_sub_page_changed"
    public const val ACTION_GESTURE_NEXT_PAGE: String = "reader_gesture_next_page"
    public const val ACTION_GESTURE_PREV_PAGE: String = "reader_gesture_prev_page"
    public const val ACTION_GESTURE_TAP_CENTER: String = "reader_gesture_tap_center"

    public const val SCRIPT_SELECTION: String = "javascript:window.getSelection().toString()"
    public const val SCRIPT_GET_HIGHLIGHTS: String = "javascript:readerJS.getHighlights()"
    public const val SCRIPT_SUMMARY_HIGHLIGHTS: String = "javascript:readerJS.summaryHighlights()"
    public const val SCRIPT_UNHIGHLIGHT_SELECTION: String = "javascript:readerJS.unHighlightSelection()"
    public const val SCRIPT_NEXT_SUB_PAGE: String = "javascript:readerJS.nextSubPage()"
    public const val SCRIPT_PREV_SUB_PAGE: String = "javascript:readerJS.prevSubPage()"
    public const val SCRIPT_GET_SUB_PAGE_STATE: String = "javascript:readerJS.getSubPageState()"
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

    public fun setReadingModeCommand(mode: ReadingMode): String {
        val isPaginated = mode == ReadingMode.PAGINATED
        return "javascript:(function(){var h=document.documentElement;if(h){if($isPaginated){h.classList.add('reader-paginated');if(typeof readerJS!=='undefined'&&readerJS.getSubPageState){var st=readerJS.getSubPageState();if(window.ReaderEngine&&window.ReaderEngine.postAction){window.ReaderEngine.postAction('$ACTION_SUB_PAGE_CHANGED',st,false);}return st;}}else{h.classList.remove('reader-paginated');if(typeof readerJS!=='undefined'&&readerJS.resetSubPage){readerJS.resetSubPage();}else if(document.body){document.body.style.transform='';document.body.style.transition='';}window.scrollTo(0,0);}}return '{\"subPage\":0,\"totalSubPages\":1}';})();"
    }

    public fun goToSubPageCommand(subPage: Int): String {
        val clamped = subPage.coerceAtLeast(0)
        return "javascript:(function(){if(typeof readerJS!=='undefined'&&readerJS.goToSubPage){return readerJS.goToSubPage($clamped);}return '{\"subPage\":0,\"totalSubPages\":1}';})();"
    }

    public fun scrollToYCommand(scrollYPx: Int, anchor: String? = null): String {
        val clamped = scrollYPx.coerceAtLeast(0)
        if (!anchor.isNullOrBlank()) {
            val anchorLiteral = jsStringLiteral(anchor)
            return "javascript:(function(){var el=document.getElementById($anchorLiteral)||document.getElementsByName($anchorLiteral)[0];if(el){el.scrollIntoView();return;}var t=Math.max(0,$clamped/(window.devicePixelRatio||1));window.scrollTo(0,t);})();"
        }
        return "javascript:(function(){var t=Math.max(0,$clamped/(window.devicePixelRatio||1));document.documentElement.scrollTop=t;document.body.scrollTop=t;window.scrollTo(0,t);})();"
    }

    /**
     * Sobrecarga compatível com chamadas de até 10 parâmetros mantendo compatibilidade binária e Swift.
     */
    public fun injectThemeAndBridge(
        rawHtml: String,
        theme: ThemeTokens,
        fontScale: Float,
        fontFamily: String = "Default",
        fontSizeClass: String = "",
        isNightMode: Boolean = false,
        readingMode: ReadingMode = ReadingMode.SCROLL,
        layout: RenditionLayout = RenditionLayout.REFLOWABLE,
        direction: PageProgressionDirection = PageProgressionDirection.DEFAULT,
        spread: RenditionSpread = RenditionSpread.AUTO,
    ): String = injectThemeAndBridge(
        rawHtml = rawHtml,
        theme = theme,
        fontScale = fontScale,
        fontFamily = fontFamily,
        fontSizeClass = fontSizeClass,
        isNightMode = isNightMode,
        readingMode = readingMode,
        layout = layout,
        direction = direction,
        spread = spread,
        initialSubPage = 0,
    )

    /**
     * Injeta o CSS do tema, classes opcionais de tamanho/modo noturno/paginação horizontal,
     * `data-reader-subpage` opcional e o polyfill `readerJS` completo (suportando grifos no DOM,
     * paginação por colunas CSS, detecção de gestos swipe/tap e preservação de viewport).
     */
    public fun injectThemeAndBridge(
        rawHtml: String,
        theme: ThemeTokens,
        fontScale: Float,
        fontFamily: String,
        fontSizeClass: String,
        isNightMode: Boolean,
        readingMode: ReadingMode,
        layout: RenditionLayout,
        direction: PageProgressionDirection,
        spread: RenditionSpread,
        initialSubPage: Int,
    ): String {
        val css = theme.toContentCss(
            fontScale = fontScale,
            customFontFamily = fontFamily,
            isNightMode = isNightMode,
            readingMode = readingMode,
            layout = layout,
            spread = spread,
        )
        val safeFontClass = sanitizeClassToken(fontSizeClass)
        val classes = buildString {
            if (safeFontClass.isNotBlank()) append(safeFontClass)
            if (isNightMode) {
                if (isNotEmpty()) append(' ')
                append("nightMode")
            }
            if (readingMode == ReadingMode.PAGINATED && layout == RenditionLayout.REFLOWABLE) {
                if (isNotEmpty()) append(' ')
                append("reader-paginated")
            }
            if (layout == RenditionLayout.PRE_PAGINATED) {
                if (isNotEmpty()) append(' ')
                append("reader-fixed-layout")
            }
        }.trim()

        val htmlOpenRegex = Regex("<html(\\b)", RegexOption.IGNORE_CASE)
        val isRtl = direction == PageProgressionDirection.RTL
        val hasDirAttr = Regex("""<html\b[^>]*\bdir\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(rawHtml)
        val clampedSubPage = initialSubPage.coerceAtLeast(0)

        var processedHtml = if (classes.isNotEmpty() && htmlOpenRegex.containsMatchIn(rawHtml)) {
            rawHtml.replaceFirst(htmlOpenRegex, Regex.escapeReplacement("<html class=\"$classes\""))
        } else {
            rawHtml
        }

        if (clampedSubPage > 0 && htmlOpenRegex.containsMatchIn(processedHtml)) {
            processedHtml = processedHtml.replaceFirst(
                htmlOpenRegex,
                Regex.escapeReplacement("<html data-reader-subpage=\"$clampedSubPage\""),
            )
        }

        if (isRtl && !hasDirAttr) {
            val htmlTagRegex = Regex("""<html\b""", RegexOption.IGNORE_CASE)
            processedHtml = if (htmlTagRegex.containsMatchIn(processedHtml)) {
                processedHtml.replaceFirst(htmlTagRegex, "<html dir=\"rtl\"")
            } else {
                processedHtml
            }
        }

        val hasViewportMeta = Regex("""<meta\b[^>]*\bname\s*=\s*["']viewport["']""", RegexOption.IGNORE_CASE).containsMatchIn(rawHtml)
        val viewportInjection = if (!hasViewportMeta && layout == RenditionLayout.REFLOWABLE) {
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
        } else {
            ""
        }
        val headInjection = "$viewportInjection<style id=\"reader-engine-theme\">$css</style><script>$FALLBACK_READER_JS</script>"
        val headCloseRegex = Regex("</head>", RegexOption.IGNORE_CASE)
        val bodyOpenRegex = Regex("<body\\b", RegexOption.IGNORE_CASE)

        return when {
            headCloseRegex.containsMatchIn(processedHtml) ->
                processedHtml.replaceFirst(headCloseRegex, Regex.escapeReplacement("$headInjection</head>"))
            bodyOpenRegex.containsMatchIn(processedHtml) ->
                processedHtml.replaceFirst(bodyOpenRegex, Regex.escapeReplacement("<head>$headInjection</head><body"))
            else -> {
                val classAttr = if (classes.isNotEmpty()) " class=\"$classes\"" else ""
                val dirAttr = if (isRtl && !hasDirAttr) " dir=\"rtl\"" else ""
                val subPageAttr = if (clampedSubPage > 0) " data-reader-subpage=\"$clampedSubPage\"" else ""
                "<!doctype html><html$dirAttr$classAttr$subPageAttr><head>$headInjection</head><body>$processedHtml</body></html>"
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
     * Faz o parse do retorno JSON de `readerJS.getSubPageState()` / `nextSubPage()` / `prevSubPage()`
     * (`{"subPage": 0, "totalSubPages": 3}`), retornando `Pair(subPage, totalSubPages)` ou `null`.
     */
    public fun parseSubPageState(rawJsValue: String?): Pair<Int, Int>? {
        val unquoted = unquoteJsEvaluation(rawJsValue).trim()
        if (unquoted.isEmpty() || unquoted == "null") return null
        return try {
            val obj = json.parseToJsonElement(unquoted).jsonObject
            val sub = obj["subPage"]?.jsonPrimitive?.intOrNull ?: return null
            val total = obj["totalSubPages"]?.jsonPrimitive?.intOrNull ?: return null
            sub to total.coerceAtLeast(1)
        } catch (_: Exception) {
            null
        }
    }

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
            scheme == "action" -> parseActionUrl(trimmed)

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

    private fun parseActionUrl(url: String): InterceptedUrl.Action {
        val afterScheme = url.substringAfter("action://", missingDelimiterValue = "").removePrefix("/")
        val actionName = percentDecode(afterScheme.substringBefore('?').substringBefore('/'))
        val rawPayload = if (afterScheme.contains('?')) {
            val query = afterScheme.substringAfter('?')
            if (query.startsWith("q=")) query.removePrefix("q=") else query
        } else {
            null
        }
        val decodedPayload = rawPayload?.let(::percentDecode)?.takeIf { it.isNotBlank() }
        return InterceptedUrl.Action(action = actionName, payload = decodedPayload)
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
            function _resolvePaginatedWidth() {
                var cw = document.documentElement ? (document.documentElement.clientWidth || 0) : 0;
                var iw = window.innerWidth || 0;
                var w = cw > 0 ? cw : iw;
                if (w >= 120) return w;
                var sw = (window.screen && (window.screen.availWidth || window.screen.width)) ? (window.screen.availWidth || window.screen.width) : 360;
                return Math.max(240, sw);
            }
            if (typeof window.readerJS === 'undefined') {
                var _serialized = '';
                var _items = [];
                var _nextId = 1;
                var _subPage = parseInt((document.documentElement && document.documentElement.getAttribute('data-reader-subpage')) || '0', 10) || 0;
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
                function _resolvePaginatedHeight() {
                    var ih = window.innerHeight || 0;
                    var ch = document.documentElement ? (document.documentElement.clientHeight || 0) : 0;
                    var sh = (window.screen && (window.screen.availHeight || window.screen.height)) ? (window.screen.availHeight || window.screen.height) : 720;
                    var maxReasonable = Math.max(400, Math.round(sh * 0.85));
                    var minReasonable = Math.max(240, Math.round(sh * 0.40));
                    var measured = ih >= ch ? ih : ch;
                    if (measured >= minReasonable && measured <= maxReasonable) {
                        return measured;
                    }
                    return Math.max(360, Math.round(sh * 0.68));
                }
                function _syncPaginatedMetrics() {
                    var root = document.documentElement;
                    if (!root || !root.classList.contains('reader-paginated')) return;
                    var vh = _resolvePaginatedHeight();
                    root.style.setProperty('--reader-page-height', vh + 'px');
                    if (window.scrollY > 0 || root.scrollTop > 0) {
                        window.scrollTo(0, 0);
                        root.scrollTop = 0;
                        if (document.body) document.body.scrollTop = 0;
                    }
                }
                function _computeTotalSubPages() {
                    _syncPaginatedMetrics();
                    var vw = _resolvePaginatedWidth();
                    var bodySw = document.body ? (document.body.scrollWidth || 0) : 0;
                    var docSw = document.documentElement ? (document.documentElement.scrollWidth || 0) : 0;
                    var sw = Math.max(vw, bodySw, docSw);
                    return Math.max(1, Math.round(sw / vw));
                }
                function _applySubPageOffset() {
                    _syncPaginatedMetrics();
                    var vw = _resolvePaginatedWidth();
                    if (document.body) {
                        var isRtl = (document.documentElement && document.documentElement.dir === 'rtl') || (document.body && document.body.dir === 'rtl');
                        var sign = isRtl ? '' : '-';
                        var offsetPx = _subPage * vw;
                        document.body.style.transform = 'translateX(' + (offsetPx === 0 ? '0px' : (sign + offsetPx + 'px')) + ')';
                        document.body.style.transition = 'transform 0.18s ease-out';
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
                    },
                    nextSubPage: function() {
                        var total = _computeTotalSubPages();
                        if (_subPage + 1 < total) {
                            _subPage++;
                            _applySubPageOffset();
                            return JSON.stringify({ subPage: _subPage, totalSubPages: total, advanced: true });
                        }
                        return JSON.stringify({ subPage: _subPage, totalSubPages: total, advanced: false });
                    },
                    prevSubPage: function() {
                        var total = _computeTotalSubPages();
                        if (_subPage > 0) {
                            _subPage--;
                            _applySubPageOffset();
                            return JSON.stringify({ subPage: _subPage, totalSubPages: total, advanced: true });
                        }
                        return JSON.stringify({ subPage: _subPage, totalSubPages: total, advanced: false });
                    },
                    getSubPageState: function() {
                        var total = _computeTotalSubPages();
                        if (_subPage >= total) {
                            _subPage = Math.max(0, total - 1);
                        }
                        if (document.documentElement && document.documentElement.classList.contains('reader-paginated')) {
                            _applySubPageOffset();
                        }
                        return JSON.stringify({ subPage: _subPage, totalSubPages: total });
                    },
                    goToSubPage: function(index) {
                        var total = _computeTotalSubPages();
                        _subPage = Math.max(0, Math.min(total - 1, parseInt(index, 10) || 0));
                        _applySubPageOffset();
                        return JSON.stringify({ subPage: _subPage, totalSubPages: total });
                    },
                    resetSubPage: function() {
                        _subPage = 0;
                        if (document.body) {
                            document.body.style.transform = '';
                            document.body.style.transition = '';
                        }
                        return JSON.stringify({ subPage: 0, totalSubPages: 1 });
                    }
                };
            }
            if (typeof window.ReaderEngine === 'undefined') {
                window.ReaderEngine = {
                    postAction: function(name, payload, allowUrlFallback) {
                        var str = typeof payload === 'string' ? payload : (payload ? JSON.stringify(payload) : '');
                        var useFallback = (allowUrlFallback === undefined) ? true : !!allowUrlFallback;
                        if (typeof window.AndroidActionBridge !== 'undefined' && window.AndroidActionBridge.postAction) {
                            window.AndroidActionBridge.postAction(name, str);
                            return true;
                        } else if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.ReaderActionBridge) {
                            window.webkit.messageHandlers.ReaderActionBridge.postMessage({ name: name, payload: str });
                            return true;
                        } else if (useFallback) {
                            var q = str ? ('?q=' + encodeURIComponent(str)) : '';
                            window.location.href = 'action://' + encodeURIComponent(name) + q;
                            return true;
                        }
                        return false;
                    }
                };
            }
            function _dispatchPageTurnGesture(direction, source) {
                var dir = direction === 'prev' ? 'prev' : 'next';
                var src = source || 'swipe';
                var isPaginated = !!(document.documentElement && document.documentElement.classList.contains('reader-paginated'));
                if (isPaginated && typeof window.readerJS !== 'undefined' &&
                    typeof window.readerJS.nextSubPage === 'function' &&
                    typeof window.readerJS.prevSubPage === 'function') {
                    var resStr = (dir === 'next') ? window.readerJS.nextSubPage() : window.readerJS.prevSubPage();
                    var res = null;
                    try { res = JSON.parse(resStr); } catch (e) {}
                    if (res && res.advanced) {
                        if (window.ReaderEngine && window.ReaderEngine.postAction) {
                            window.ReaderEngine.postAction('reader_sub_page_changed', JSON.stringify({
                                subPage: res.subPage,
                                totalSubPages: res.totalSubPages,
                                source: src
                            }));
                        }
                        return true;
                    }
                }
                if (window.ReaderEngine && window.ReaderEngine.postAction) {
                    var actionName = (dir === 'next') ? 'reader_gesture_next_page' : 'reader_gesture_prev_page';
                    window.ReaderEngine.postAction(actionName, JSON.stringify({ source: src }));
                }
                return true;
            }
            if (typeof window.readerJS !== 'undefined' && typeof window.readerJS.handlePageTurnGesture !== 'function') {
                window.readerJS.handlePageTurnGesture = _dispatchPageTurnGesture;
            }
            if (typeof window.__readerScrollHooked === 'undefined') {
                window.__readerScrollHooked = true;
                var _scrollTimer = null;
                window.addEventListener('scroll', function() {
                    if (_scrollTimer) clearTimeout(_scrollTimer);
                    _scrollTimer = setTimeout(function() {
                        var sy = Math.round(window.scrollY || window.pageYOffset || 0);
                        var ch = Math.round(Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.scrollHeight : 0));
                        var vh = Math.round(window.innerHeight || 0);
                        if (typeof window.AndroidScrollProgress !== 'undefined' && window.AndroidScrollProgress.onScrollChanged) {
                            window.AndroidScrollProgress.onScrollChanged(sy, ch, vh);
                        } else if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.ReaderScrollProgress) {
                            window.webkit.messageHandlers.ReaderScrollProgress.postMessage({
                                scrollY: sy,
                                contentHeightPx: ch,
                                viewportHeightPx: vh
                            });
                        }
                    }, 80);
                }, { passive: true });
            }
            if (typeof window.__readerFootnoteHooked === 'undefined') {
                window.__readerFootnoteHooked = true;
                document.addEventListener('click', function(e) {
                    var el = e.target;
                    while (el && el.tagName && el.tagName.toLowerCase() !== 'a') {
                        el = el.parentElement;
                    }
                    if (!el || !el.getAttribute) return;
                    var epubType = (el.getAttribute('epub:type') || el.getAttribute('type') || '').toLowerCase();
                    var role = (el.getAttribute('role') || '').toLowerCase();
                    var href = el.getAttribute('href') || '';
                    var isNoteref = epubType.indexOf('noteref') >= 0 || role.indexOf('doc-noteref') >= 0;
                    var hashIdx = href.indexOf('#');
                    if (hashIdx >= 0) {
                        var targetId = href.substring(hashIdx + 1);
                        var targetEl = targetId ? document.getElementById(targetId) : null;
                        if (targetEl) {
                            var targetType = (targetEl.getAttribute('epub:type') || targetEl.getAttribute('role') || '').toLowerCase();
                            var isTargetFootnote = targetEl.tagName.toLowerCase() === 'aside' || targetType.indexOf('footnote') >= 0 || targetType.indexOf('endnote') >= 0 || targetType.indexOf('note') >= 0;
                            if (isNoteref || isTargetFootnote) {
                                e.preventDefault();
                                var title = (el.textContent || targetId || 'Nota').trim();
                                var text = (targetEl.textContent || '').trim();
                                var payload = JSON.stringify([{ title: title, text: text }]);
                                window.location.href = 'footnote://?q=' + encodeURIComponent(payload);
                            }
                        }
                    }
                }, false);
            }
            if (typeof window.__readerGestureHooked === 'undefined') {
                window.__readerGestureHooked = true;
                var _touchActive = false;
                var _multiTouch = false;
                var _startedInteractive = false;
                var _hadSelectionAtStart = false;
                var _startX = 0;
                var _startY = 0;
                var _startTime = 0;
                var _lastTouchTime = 0;
                function _hasActiveTextSelection() {
                    var sel = window.getSelection ? window.getSelection() : null;
                    return !!(sel && sel.toString && sel.toString().trim().length > 0);
                }
                function _isInteractiveTarget(target) {
                    var el = target;
                    if (el && el.nodeType === 3) el = el.parentElement;
                    while (el && el.nodeType === 1) {
                        var tag = el.tagName ? el.tagName.toLowerCase() : '';
                        if (tag === 'a' || tag === 'button' || tag === 'input' || tag === 'textarea' ||
                            tag === 'select' || tag === 'option' || tag === 'label' ||
                            tag === 'video' || tag === 'audio' || tag === 'summary' || tag === 'details') {
                            return true;
                        }
                        if (el.getAttribute) {
                            var role = (el.getAttribute('role') || '').toLowerCase();
                            if (role === 'button' || role === 'link' || role === 'checkbox' ||
                                role === 'radio' || role === 'slider' || role.indexOf('doc-noteref') >= 0) {
                                return true;
                            }
                            var ce = el.getAttribute('contenteditable');
                            if (ce !== null && ce !== 'false') {
                                return true;
                            }
                            if (el.hasAttribute('onclick')) {
                                return true;
                            }
                            var epubType = (el.getAttribute('epub:type') || '').toLowerCase();
                            if (epubType.indexOf('noteref') >= 0) {
                                return true;
                            }
                        }
                        if (tag === 'body' || tag === 'html') break;
                        el = el.parentElement;
                    }
                    return false;
                }
                function _isRtlDocument() {
                    return !!((document.documentElement && document.documentElement.dir === 'rtl') ||
                        (document.body && document.body.dir === 'rtl'));
                }
                function _handleTapAt(x, y) {
                    var isPaginated = !!(document.documentElement && document.documentElement.classList.contains('reader-paginated'));
                    var vw = _resolvePaginatedWidth();
                    var ratio = vw > 0 ? (x / vw) : 0.5;
                    var isRtl = _isRtlDocument();
                    if (isPaginated) {
                        if (ratio < 0.28) {
                            _dispatchPageTurnGesture(isRtl ? 'next' : 'prev', 'tap');
                            return;
                        }
                        if (ratio > 0.72) {
                            _dispatchPageTurnGesture(isRtl ? 'prev' : 'next', 'tap');
                            return;
                        }
                    }
                    if (window.ReaderEngine && window.ReaderEngine.postAction) {
                        window.ReaderEngine.postAction('reader_gesture_tap_center', JSON.stringify({
                            x: Math.round(x),
                            y: Math.round(y)
                        }));
                    }
                }
                document.addEventListener('touchstart', function(e) {
                    if (e.touches && e.touches.length > 1) {
                        _multiTouch = true;
                        _touchActive = false;
                        return;
                    }
                    var touch = (e.touches && e.touches[0]) ? e.touches[0] : null;
                    if (!touch) return;
                    _multiTouch = false;
                    _touchActive = true;
                    _startX = touch.clientX;
                    _startY = touch.clientY;
                    _startTime = Date.now();
                    _startedInteractive = _isInteractiveTarget(e.target);
                    _hadSelectionAtStart = _hasActiveTextSelection();
                }, { passive: true });
                document.addEventListener('touchmove', function(e) {
                    if (e.touches && e.touches.length > 1) {
                        _multiTouch = true;
                        _touchActive = false;
                    }
                }, { passive: true });
                document.addEventListener('touchcancel', function() {
                    _touchActive = false;
                    _multiTouch = false;
                }, { passive: true });
                document.addEventListener('touchend', function(e) {
                    if (_multiTouch) {
                        if (!e.touches || e.touches.length === 0) _multiTouch = false;
                        _touchActive = false;
                        return;
                    }
                    if (!_touchActive) return;
                    _touchActive = false;
                    _lastTouchTime = Date.now();
                    if (_startedInteractive || _hadSelectionAtStart || _hasActiveTextSelection()) {
                        return;
                    }
                    var touch = (e.changedTouches && e.changedTouches[0]) ? e.changedTouches[0] : null;
                    if (!touch) return;
                    var dx = touch.clientX - _startX;
                    var dy = touch.clientY - _startY;
                    var dt = _lastTouchTime - _startTime;
                    var absDx = Math.abs(dx);
                    var absDy = Math.abs(dy);
                    if (dt <= 800 && absDx >= 48 && absDx > absDy * 1.5) {
                        var isRtl = _isRtlDocument();
                        var dir = (isRtl ? (dx > 0) : (dx < 0)) ? 'next' : 'prev';
                        _dispatchPageTurnGesture(dir, 'swipe');
                        return;
                    }
                    if (dt < 320 && absDx < 14 && absDy < 14) {
                        _handleTapAt(touch.clientX, touch.clientY);
                    }
                }, { passive: true });
                document.addEventListener('click', function(e) {
                    if (Date.now() - _lastTouchTime < 500) return;
                    if (_isInteractiveTarget(e.target) || _hasActiveTextSelection()) return;
                    _handleTapAt(e.clientX || 0, e.clientY || 0);
                }, false);
            }
            if (typeof window.__readerPaginatedHooked === 'undefined') {
                window.__readerPaginatedHooked = true;
                var _lastReportedSubState = '';
                var _onPaginatedReady = function() {
                    if (document.documentElement && document.documentElement.classList.contains('reader-paginated')) {
                        if (typeof window.readerJS !== 'undefined' && window.readerJS.getSubPageState) {
                            var st = window.readerJS.getSubPageState();
                            if (st && st !== _lastReportedSubState && window.ReaderEngine && window.ReaderEngine.postAction) {
                                _lastReportedSubState = st;
                                window.ReaderEngine.postAction('reader_sub_page_changed', st, false);
                            }
                        }
                    }
                };
                if (document.readyState === 'complete' || document.readyState === 'interactive') {
                    setTimeout(_onPaginatedReady, 0);
                } else {
                    window.addEventListener('DOMContentLoaded', _onPaginatedReady);
                }
                window.addEventListener('load', _onPaginatedReady);
                window.addEventListener('resize', _onPaginatedReady);
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

    public data class Action(
        val action: String,
        val payload: String?,
    ) : InterceptedUrl

    public data class ExternalWebUrl(val url: String) : InterceptedUrl
    public data class InternalResource(val href: String, val anchor: String?) : InterceptedUrl
    public data object InvalidHighlightOnInput : InterceptedUrl
    public data object ClipboardAction : InterceptedUrl
}
