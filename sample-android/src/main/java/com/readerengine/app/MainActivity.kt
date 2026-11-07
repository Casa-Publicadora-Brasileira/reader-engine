package com.readerengine.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.readerengine.core.bridge.ReaderJsBridge
import com.readerengine.core.model.Bookmark
import com.readerengine.core.model.HighlightStyle
import com.readerengine.core.model.InitialBookState
import com.readerengine.core.model.ReadingMode
import com.readerengine.core.model.ReadingProgress
import com.readerengine.core.model.SearchResult
import com.readerengine.core.session.ReaderCommand
import com.readerengine.core.session.ReaderEngine
import com.readerengine.core.session.ReaderEvent
import com.readerengine.core.session.ReaderSession
import com.readerengine.core.session.ReaderUiEffect
import com.readerengine.core.session.RenderedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private const val BOOK_BASE_URL = "book://content/"

/** JavaScript interface for receiving real scroll metrics from WebView. */
class ScrollProgressInterface(
    private val onScrollMetricsChanged: (scrollY: Int, contentHeightPx: Int, viewportHeightPx: Int) -> Unit,
) {
    @JavascriptInterface
    fun onScrollChanged(scrollY: Int, contentHeightPx: Int, viewportHeightPx: Int) {
        onScrollMetricsChanged(
            scrollY.coerceAtLeast(0),
            contentHeightPx.coerceAtLeast(0),
            viewportHeightPx.coerceAtLeast(0),
        )
    }

    @JavascriptInterface
    fun reportProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        onScrollMetricsChanged((clamped * 1000).toInt(), 1200, 200)
    }
}

/**
 * Local host store demonstrating how any app listens to [ReaderEvent]
 * and persists to its own local storage or remote backend without fixed page limits.
 */
class HostBookPersistenceStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("kmp_reader_host_store", Context.MODE_PRIVATE)

    fun saveProgress(progress: ReadingProgress) {
        prefs.edit()
            .putInt("${progress.documentId}_page", progress.pageIndex)
            .putInt("${progress.documentId}_total", progress.totalPages)
            .putInt("${progress.documentId}_scrollY", progress.scrollY)
            .putFloat("${progress.documentId}_percent", progress.totalPercentage)
            .apply()
    }

    fun loadProgress(documentId: String): ReadingProgress? {
        if (!prefs.contains("${documentId}_page")) return null
        val page = prefs.getInt("${documentId}_page", 0)
        return ReadingProgress(
            documentId = documentId,
            pageIndex = page,
            pageNumber = page + 1,
            totalPages = prefs.getInt("${documentId}_total", 1),
            scrollY = prefs.getInt("${documentId}_scrollY", 0),
            totalPercentage = prefs.getFloat("${documentId}_percent", 0f),
        )
    }

    fun savePageHighlight(documentId: String, pageIndex: Int, compressedPayload: String) {
        prefs.edit()
            .putString("${documentId}_hl_$pageIndex", compressedPayload)
            .apply()
    }

    fun loadHighlights(documentId: String): Map<Int, String> {
        val prefix = "${documentId}_hl_"
        val result = mutableMapOf<Int, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is String && value.isNotBlank()) {
                val pageIdx = key.removePrefix(prefix).toIntOrNull()
                if (pageIdx != null) {
                    result[pageIdx] = value
                }
            }
        }
        return result
    }

    fun savePageForm(documentId: String, pageIndex: Int, compressedPayload: String) {
        prefs.edit()
            .putString("${documentId}_form_$pageIndex", compressedPayload)
            .apply()
    }

    fun loadPageForms(documentId: String): Map<Int, String> {
        val prefix = "${documentId}_form_"
        val result = mutableMapOf<Int, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is String && value.isNotBlank()) {
                val pageIdx = key.removePrefix(prefix).toIntOrNull()
                if (pageIdx != null) {
                    result[pageIdx] = value
                }
            }
        }
        return result
    }

    fun saveBookmarksJson(documentId: String, json: String) {
        prefs.edit()
            .putString("${documentId}_bookmarks", json)
            .apply()
    }

    fun loadBookmarksJson(documentId: String): String? =
        prefs.getString("${documentId}_bookmarks", null)

    fun savePreferences(
        fontScale: Float,
        isNightMode: Boolean,
        fontFamily: String,
        readingMode: ReadingMode,
    ) {
        prefs.edit()
            .putFloat("pref_font_scale", fontScale)
            .putBoolean("pref_night_mode", isNightMode)
            .putString("pref_font_family", fontFamily)
            .putString("pref_reading_mode", readingMode.name)
            .apply()
    }

    fun loadFontScale(): Float = prefs.getFloat("pref_font_scale", 1.0f)
    fun loadNightMode(): Boolean = prefs.getBoolean("pref_night_mode", false)
    fun loadFontFamily(): String = prefs.getString("pref_font_family", "Default") ?: "Default"
    fun loadReadingMode(): ReadingMode =
        runCatching {
            ReadingMode.valueOf(prefs.getString("pref_reading_mode", ReadingMode.SCROLL.name) ?: ReadingMode.SCROLL.name)
        }.getOrDefault(ReadingMode.SCROLL)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = ReaderEngine()
        val samples = bundledSamples() + localSamples(assets)
        val initialIntentUri = intent?.data
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF3ECFAC),
                    onPrimary = Color(0xFF04201A),
                    background = Color(0xFF071512),
                    surface = Color(0xFF0C2420),
                    onBackground = Color(0xFFD7EFE7),
                    onSurface = Color(0xFFD7EFE7),
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReaderScreen(
                        engine = engine,
                        samples = samples,
                        initialIntentUri = initialIntentUri,
                        readAsset = { path -> assets.open(path).use { it.readBytes() } },
                        readUri = { uri ->
                            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: error("Não foi possível ler $uri")
                        },
                        displayName = { uri -> resolveDisplayName(uri) },
                    )
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "book.epub"
    }
}

private data class SampleBook(
    val id: String,
    val title: String,
    val assetPath: String,
    val fileName: String,
)

private fun bundledSamples(): List<SampleBook> = listOf(
    SampleBook("noite-clara", "Noite Clara (JSON)", "samples/noite-clara.json", "noite-clara.json"),
    SampleBook("epub-fixture", "EPUB Fixture", "samples/sample.epub", "sample.epub"),
    SampleBook("hpub-fixture", "Lição HPUB", "samples/sample.hpub", "sample.hpub"),
    SampleBook("epub-with-image", "EPUB With Image", "samples/sample-with-image.epub", "sample-with-image.epub"),
)

private fun localSamples(assets: AssetManager): List<SampleBook> {
    val names = runCatching { assets.list("samples/local") }
        .getOrNull()
        .orEmpty()
        .filter {
            it.endsWith(".json", ignoreCase = true) ||
                it.endsWith(".epub", ignoreCase = true) ||
                it.endsWith(".hpub", ignoreCase = true)
        }
        .sorted()
    return names.map { name ->
        val stem = name.substringBeforeLast('.')
        SampleBook(
            id = "local-$stem",
            title = stem,
            assetPath = "samples/local/$name",
            fileName = name,
        )
    }
}

private class BookResourceWebViewClient(
    private val sessionProvider: () -> ReaderSession?,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        val session = sessionProvider() ?: return false
        return session.handleUrlNavigation(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val webView = view ?: return
        val session = sessionProvider() ?: return
        val state = session.state.value
        if (state.currentPageSerializedHighlights.isNotBlank()) {
            val restoreCmd = ReaderJsBridge.setHighlightsCommand(state.currentPageSerializedHighlights)
            webView.evaluateJavascript(restoreCmd.removePrefix("javascript:"), null)
        }
        if (state.scrollY > 0 && state.readingMode == ReadingMode.SCROLL) {
            val scrollCmd = ReaderJsBridge.scrollToYCommand(state.scrollY)
            webView.evaluateJavascript(scrollCmd.removePrefix("javascript:"), null)
        }
        if (state.readingMode == ReadingMode.PAGINATED) {
            webView.evaluateJavascript("readerJS.getSubPageState()") { raw ->
                ReaderJsBridge.parseSubPageState(raw)?.let { (sub, total) ->
                    session.onSubPageReported(sub, total)
                }
            }
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url ?: return null
        if (url.scheme != "book" || url.host != "content") {
            return super.shouldInterceptRequest(view, request)
        }

        val session = sessionProvider() ?: return notFoundResponse()
        val href = url.path?.removePrefix("/") ?: return notFoundResponse()

        return try {
            val bytes = session.readResource(href)
            val mimeType = session.resourceMediaType(href)
                ?: guessMimeType(href)
            WebResourceResponse(mimeType, "UTF-8", ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            notFoundResponse()
        }
    }

    private fun notFoundResponse() = WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream("Resource not found".toByteArray()),
    )

    private fun guessMimeType(href: String): String {
        val lower = href.substringBefore('#').lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".xhtml") -> "application/xhtml+xml"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".woff") -> "font/woff"
            lower.endsWith(".ttf") -> "font/ttf"
            lower.endsWith(".otf") -> "font/otf"
            else -> "application/octet-stream"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    engine: ReaderEngine,
    samples: List<SampleBook>,
    initialIntentUri: Uri?,
    readAsset: (String) -> ByteArray,
    readUri: (Uri) -> ByteArray,
    displayName: (Uri) -> String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hostStore = remember { HostBookPersistenceStore(context) }

    var session by remember { mutableStateOf<ReaderSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeSample by remember { mutableStateOf<String?>(null) }
    var lastEventSummary by remember { mutableStateOf("Aguardando eventos...") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showBookmarks by remember { mutableStateOf(false) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }

    val readerState = session?.state?.collectAsState()?.value
    var sliderFontScale by remember(readerState?.fontScale) {
        mutableFloatStateOf(readerState?.fontScale ?: 1.0f)
    }

    // Collect domain events emitted by KMP ReaderSession and persist to host store
    LaunchedEffect(session) {
        val currentSession = session ?: return@LaunchedEffect
        currentSession.events.collect { event ->
            when (event) {
                is ReaderEvent.ProgressUpdated -> {
                    hostStore.saveProgress(event.progress)
                    lastEventSummary =
                        "ProgressUpdated(pág=${event.progress.pageNumber}/${event.progress.totalPages}, ${event.progress.totalPercentage.toInt()}%)"
                }
                is ReaderEvent.PageHighlightsChanged -> {
                    hostStore.savePageHighlight(event.documentId, event.pageIndex, event.compressedPayload)
                    lastEventSummary =
                        "PageHighlightsChanged(pág=${event.pageIndex + 1}, itens=${event.summaryItems.size})"
                }
                is ReaderEvent.PageFormChanged -> {
                    hostStore.savePageForm(event.documentId, event.pageIndex, event.compressedPayload)
                    lastEventSummary =
                        "PageFormChanged(pág=${event.pageIndex + 1}, notas=${event.formState.annotations.size}, resp=${event.formState.answers.size})"
                }
                is ReaderEvent.BookmarksChanged -> {
                    hostStore.saveBookmarksJson(event.documentId, event.serializedJson)
                    lastEventSummary = "BookmarksChanged(total=${event.bookmarks.size})"
                }
                is ReaderEvent.PreferencesChanged -> {
                    hostStore.savePreferences(
                        fontScale = event.fontScale,
                        isNightMode = event.isNightMode,
                        fontFamily = event.fontFamily,
                        readingMode = event.readingMode,
                    )
                    lastEventSummary =
                        "PreferencesChanged(escala=${event.fontScale}, noite=${event.isNightMode}, modo=${event.readingMode})"
                }
            }
        }
    }

    // Collect one-shot UI effects (JS execution, scroll, custom URI schemes)
    LaunchedEffect(session) {
        val currentSession = session ?: return@LaunchedEffect
        currentSession.uiEffects.collect { effect ->
            when (effect) {
                is ReaderUiEffect.EvaluateJs -> {
                    val webView = webViewRef ?: return@collect
                    val rawScript = effect.script.removePrefix("javascript:")
                    webView.evaluateJavascript(rawScript) { evalResult ->
                        ReaderJsBridge.parseSubPageState(evalResult)?.let { (sub, total) ->
                            currentSession.onSubPageReported(sub, total)
                        }
                        if (rawScript.contains("highlightSelection") ||
                            rawScript.contains("unHighlightSelection") ||
                            rawScript.contains("removeHighlight")
                        ) {
                            webView.evaluateJavascript("readerJS.getHighlights()") { serializedRaw ->
                                webView.evaluateJavascript("readerJS.summaryHighlights()") { summaryRaw ->
                                    currentSession.onPageHighlightsReported(
                                        rawSerializedHighlights = serializedRaw ?: "",
                                        rawSummaryJson = summaryRaw,
                                    )
                                }
                            }
                        }
                    }
                }
                is ReaderUiEffect.ScrollToY -> {
                    val scrollCmd = ReaderJsBridge.scrollToYCommand(effect.scrollYPx, effect.anchor)
                    webViewRef?.evaluateJavascript(scrollCmd.removePrefix("javascript:"), null)
                }
                is ReaderUiEffect.OpenCustomUri -> {
                    lastEventSummary = "OpenCustomUri(${effect.scheme}://${effect.title})"
                }
                is ReaderUiEffect.OpenExternalUrl -> {
                    lastEventSummary = "OpenExternalUrl(${effect.url})"
                }
                is ReaderUiEffect.ShowMessage -> {
                    lastEventSummary = "ShowMessage(${effect.message})"
                }
            }
        }
    }

    fun openBookAsync(
        sampleId: String?,
        loadNameAndBytes: () -> Pair<String, ByteArray>,
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val (name, bytes) = loadNameAndBytes()
                    val documentId = sampleId ?: name.substringBeforeLast('.')
                    val savedProgress = hostStore.loadProgress(documentId)
                    val savedHighlights = hostStore.loadHighlights(documentId)
                    val savedForms = hostStore.loadPageForms(documentId)
                    val opened = engine.open(
                        name = name,
                        bytes = bytes,
                        initialState = InitialBookState(
                            documentId = documentId,
                            initialPageIndex = savedProgress?.pageIndex ?: 0,
                            initialScrollY = savedProgress?.scrollY ?: 0,
                            isNightMode = hostStore.loadNightMode(),
                            readingMode = hostStore.loadReadingMode(),
                            fontScale = hostStore.loadFontScale(),
                            fontFamily = hostStore.loadFontFamily(),
                            savedPageHighlights = savedHighlights,
                            savedPageForms = savedForms,
                        ),
                    )
                    hostStore.loadBookmarksJson(documentId)?.let { opened.importBookmarksJson(it) }
                    opened
                }
            }.onSuccess { opened ->
                session?.close()
                session = opened
                activeSample = sampleId
                searchResults = emptyList()
                error = null
            }.onFailure {
                error = it.message ?: it.toString()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        openBookAsync(sampleId = null) {
            displayName(uri) to readUri(uri)
        }
    }

    LaunchedEffect(samples, initialIntentUri) {
        if (session != null) return@LaunchedEffect
        if (initialIntentUri != null) {
            openBookAsync(sampleId = null) {
                displayName(initialIntentUri) to readUri(initialIntentUri)
            }
        } else {
            val first = samples.firstOrNull() ?: return@LaunchedEffect
            openBookAsync(sampleId = first.id) {
                first.fileName to readAsset(first.assetPath)
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        bottomBar = {
            val state = readerState
            val index = state?.pageIndex ?: 0
            val total = state?.spineLen ?: 0
            val percent = state?.progress?.totalPercentage?.toInt() ?: 0
            val hasBookmark = state?.hasBookmarkAtCurrentPage ?: false
            val isPaginated = state?.readingMode == ReadingMode.PAGINATED
            val subPage = state?.currentSubPage ?: 0
            val totalSubPages = state?.totalSubPages ?: 1
            val canGoPrev = state != null && (index > 0 || (isPaginated && subPage > 0))
            val canGoNext = state != null && (index + 1 < total || (isPaginated && subPage + 1 < totalSubPages))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { session?.dispatch(ReaderCommand.ToggleBookmark()) },
                    enabled = state != null,
                ) {
                    Icon(
                        imageVector = if (hasBookmark) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (hasBookmark) "Remover marcador" else "Adicionar marcador",
                        tint = if (hasBookmark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                OutlinedButton(
                    onClick = { session?.dispatch(ReaderCommand.PrevPage) },
                    enabled = canGoPrev,
                    modifier = Modifier.height(48.dp),
                ) {
                    Text("Anterior")
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val pageLabel = when {
                        state == null -> "-"
                        isPaginated && totalSubPages > 1 -> "${index + 1} / $total (${subPage + 1}/$totalSubPages)"
                        else -> "${index + 1} / $total"
                    }
                    Text(
                        text = pageLabel,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = if (state == null) "" else "$percent% concluído",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Button(
                    onClick = { session?.dispatch(ReaderCommand.NextPage) },
                    enabled = canGoNext,
                    modifier = Modifier.height(48.dp),
                ) {
                    Text("Próxima")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "reader-engine (KMP)",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = readerState?.let {
                            val authorsText = it.metadata.authors.joinToString()
                            if (authorsText.isBlank()) it.metadata.title else "${it.metadata.title} · $authorsText"
                        } ?: "Abra um arquivo .epub / .hpub / .json",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Noite", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Switch(
                        checked = readerState?.isNightMode ?: false,
                        onCheckedChange = { enabled ->
                            session?.dispatch(ReaderCommand.SetNightMode(enabled))
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                samples.forEach { sample ->
                    FilterChip(
                        selected = activeSample == sample.id,
                        onClick = {
                            openBookAsync(sampleId = sample.id) {
                                sample.fileName to readAsset(sample.assetPath)
                            }
                        },
                        label = { Text(sample.title) },
                    )
                }
                OutlinedButton(
                    onClick = {
                        picker.launch(
                            arrayOf(
                                "application/epub+zip",
                                "application/json",
                                "application/octet-stream",
                                "*/*",
                            ),
                        )
                    },
                    modifier = Modifier.height(40.dp),
                ) {
                    Text("Abrir arquivo")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Fonte", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = sliderFontScale,
                    onValueChange = { sliderFontScale = it },
                    onValueChangeFinished = {
                        session?.dispatch(ReaderCommand.SetFontScale(sliderFontScale))
                    },
                    valueRange = 0.8f..1.8f,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        val nextMode = if (readerState?.readingMode == ReadingMode.PAGINATED) {
                            ReadingMode.SCROLL
                        } else {
                            ReadingMode.PAGINATED
                        }
                        session?.dispatch(ReaderCommand.SetReadingMode(nextMode))
                    },
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(if (readerState?.readingMode == ReadingMode.PAGINATED) "Paginado" else "Scroll")
                }
                OutlinedButton(
                    onClick = {
                        session?.dispatch(ReaderCommand.ApplyHighlightStyle(HighlightStyle.YELLOW))
                    },
                    modifier = Modifier.height(36.dp),
                ) {
                    Text("Grifar")
                }
            }

            // Full-text search bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isSearchExpanded = !isSearchExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Buscar",
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Buscar no livro",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (isSearchExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = isSearchExpanded) {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Digite sua busca...") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val active = session ?: return@IconButton
                                    val q = searchQuery
                                    scope.launch {
                                        searchResults = withContext(Dispatchers.Default) {
                                            active.search(q)
                                        }
                                    }
                                },
                            ) {
                                Icon(imageVector = Icons.Filled.Search, contentDescription = "Buscar")
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (searchResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyColumn(
                            modifier = Modifier.height(140.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(searchResults) { result ->
                                Surface(
                                    onClick = {
                                        val loc = result.locator
                                        if (loc != null) {
                                            session?.dispatch(ReaderCommand.GoToLocator(loc))
                                        } else {
                                            session?.dispatch(ReaderCommand.GoToPage(result.pageIndex))
                                        }
                                        isSearchExpanded = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            text = result.chapterTitle,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = result.snippet,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bookmarks bar
            val bookmarks = readerState?.bookmarks.orEmpty()
            if (bookmarks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showBookmarks = !showBookmarks }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Marcadores (${bookmarks.size})",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (showBookmarks) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                AnimatedVisibility(visible = showBookmarks) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(bookmarks, key = { it.id }) { bm ->
                            BookmarkChip(
                                bookmark = bm,
                                isCurrentPage = bm.pageIndex == readerState?.pageIndex,
                                onClick = { session?.dispatch(ReaderCommand.GoToBookmark(bm.id)) },
                                onRemove = { session?.dispatch(ReaderCommand.RemoveBookmark(bm.id)) },
                            )
                        }
                    }
                }
            }

            Text(
                text = "Evento KMP: $lastEventSummary",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(4.dp))
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
            }

            val progressFraction = ((readerState?.progress?.totalPercentage ?: 0f) / 100f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))

            val currentDoc: RenderedDocument? = remember(
                session,
                readerState?.pageIndex,
                readerState?.fontScale,
                readerState?.fontFamily,
                readerState?.fontSizeClass,
                readerState?.isNightMode,
                readerState?.readingMode,
                readerState?.theme,
            ) {
                session?.currentDocument(BOOK_BASE_URL)
            }

            @SuppressLint("SetJavaScriptEnabled")
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewClient = BookResourceWebViewClient { session }
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        setBackgroundColor(0xFF131313.toInt())
                        addJavascriptInterface(
                            ScrollProgressInterface { scrollY, contentHeightPx, viewportHeightPx ->
                                post {
                                    session?.dispatch(
                                        ReaderCommand.UpdateScroll(
                                            scrollY = scrollY,
                                            contentHeightPx = contentHeightPx,
                                            viewportHeightPx = viewportHeightPx,
                                            timestampEpochMs = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                            },
                            "AndroidScrollProgress",
                        )
                        webViewRef = this
                    }
                },
                update = { webView ->
                    val doc = currentDoc
                    val bgColor = if (readerState?.isNightMode == true) 0xFF131313.toInt() else 0xFFF3FAF7.toInt()
                    webView.setBackgroundColor(bgColor)
                    if (doc != null && webView.tag != doc) {
                        webView.tag = doc
                        webView.loadDataWithBaseURL(
                            doc.baseUrl,
                            doc.html.ifBlank { "<html><body></body></html>" },
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun BookmarkChip(
    bookmark: Bookmark,
    isCurrentPage: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = bookmark.label ?: "Página ${bookmark.pageIndex + 1}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isCurrentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(18.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remover marcador",
                    modifier = Modifier.size(14.dp),
                )
            }
        },
    )
}
