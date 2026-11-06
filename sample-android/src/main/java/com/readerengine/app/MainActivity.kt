package com.readerengine.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cpb.reader.engine.model.HighlightStyle
import com.cpb.reader.engine.model.InitialBookState
import com.cpb.reader.engine.model.ReadingProgress
import com.cpb.reader.engine.session.ReaderCommand
import com.cpb.reader.engine.session.ReaderEngine
import com.cpb.reader.engine.session.ReaderEvent
import com.cpb.reader.engine.session.ReaderSession
import com.cpb.reader.engine.session.ReaderUiEffect
import java.io.ByteArrayInputStream

private const val BOOK_BASE_URL = "book://content/"

/** JavaScript interface for receiving scroll position from WebView. */
class ScrollProgressInterface(
    private val onProgressChanged: (Float) -> Unit,
) {
    @JavascriptInterface
    fun reportProgress(progress: Float) {
        onProgressChanged(progress.coerceIn(0f, 1f))
    }
}

/**
 * Local host store demonstrating how each host app (CPB Books / Escola Sabatina)
 * listens to [ReaderEvent] and persists to its own local storage or Firestore backend.
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

    fun savePageHighlight(documentId: String, pageIndex: Int, firestoreValue: String) {
        prefs.edit()
            .putString("${documentId}_hl_$pageIndex", firestoreValue)
            .apply()
    }

    fun loadHighlights(documentId: String, maxPages: Int = 64): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (page in 0 until maxPages) {
            val raw = prefs.getString("${documentId}_hl_$page", null)
            if (!raw.isNullOrBlank()) {
                result[page] = raw
            }
        }
        return result
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = ReaderEngine()
        val samples = bundledSamples() + localSamples(assets)
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
                        readAsset = { path -> assets.open(path).use { it.readBytes() } },
                        readUri = { uri ->
                            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: error("Não foi possível ler $uri")
                        },
                        displayName = { uri ->
                            uri.lastPathSegment ?: "book.bin"
                        },
                    )
                }
            }
        }
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

    private fun guessMimeType(href: String): String = when {
        href.endsWith(".html") || href.endsWith(".xhtml") -> "application/xhtml+xml"
        href.endsWith(".css") -> "text/css"
        href.endsWith(".js") -> "application/javascript"
        href.endsWith(".png") -> "image/png"
        href.endsWith(".jpg") || href.endsWith(".jpeg") -> "image/jpeg"
        href.endsWith(".gif") -> "image/gif"
        href.endsWith(".svg") -> "image/svg+xml"
        href.endsWith(".webp") -> "image/webp"
        else -> "application/octet-stream"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    engine: ReaderEngine,
    samples: List<SampleBook>,
    readAsset: (String) -> ByteArray,
    readUri: (Uri) -> ByteArray,
    displayName: (Uri) -> String,
) {
    val context = LocalContext.current
    val hostStore = remember { HostBookPersistenceStore(context) }

    var session by remember { mutableStateOf<ReaderSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeSample by remember { mutableStateOf<String?>(null) }
    var lastEventSummary by remember { mutableStateOf("Aguardando eventos...") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

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
                    hostStore.savePageHighlight(event.documentId, event.pageIndex, event.firestoreValue)
                    lastEventSummary =
                        "PageHighlightsChanged(pág=${event.pageIndex + 1}, itens=${event.summaryItems.size})"
                }
                is ReaderEvent.PageFormChanged -> {
                    lastEventSummary =
                        "PageFormChanged(pág=${event.pageIndex + 1}, notas=${event.formState.annotations.size}, resp=${event.formState.answers.size})"
                }
                is ReaderEvent.PreferencesChanged -> {
                    lastEventSummary =
                        "PreferencesChanged(escala=${event.fontScale}, noite=${event.isNightMode})"
                }
            }
        }
    }

    // Collect one-shot UI effects (JS execution, scroll, verse modal)
    LaunchedEffect(session) {
        val currentSession = session ?: return@LaunchedEffect
        currentSession.uiEffects.collect { effect ->
            when (effect) {
                is ReaderUiEffect.EvaluateJs -> {
                    webViewRef?.evaluateJavascript(effect.script.removePrefix("javascript:"), null)
                }
                is ReaderUiEffect.ScrollToY -> {
                    webViewRef?.scrollTo(0, effect.scrollYPx)
                }
                is ReaderUiEffect.OpenVerseModal -> {
                    lastEventSummary = "OpenVerseModal(${effect.referenceTitle})"
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

    fun openBytes(name: String, bytes: ByteArray, sampleId: String?) {
        runCatching {
            val documentId = sampleId ?: name.substringBeforeLast('.')
            val savedProgress = hostStore.loadProgress(documentId)
            val savedHighlights = hostStore.loadHighlights(documentId)
            val opened = engine.open(
                name = name,
                bytes = bytes,
                initialState = InitialBookState(
                    documentId = documentId,
                    initialPageIndex = savedProgress?.pageIndex ?: 0,
                    initialScrollY = savedProgress?.scrollY ?: 0,
                    savedPageHighlights = savedHighlights,
                ),
            )
            session = opened
            activeSample = sampleId
            error = null
        }.onFailure {
            error = it.message ?: it.toString()
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            openBytes(displayName(uri), readUri(uri), sampleId = null)
        }.onFailure {
            error = it.message ?: it.toString()
        }
    }

    LaunchedEffect(samples) {
        val first = samples.firstOrNull() ?: return@LaunchedEffect
        openBytes(first.fileName, readAsset(first.assetPath), first.id)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { session?.dispatch(ReaderCommand.PrevPage) },
                    enabled = state != null && index > 0,
                    modifier = Modifier.height(48.dp),
                ) {
                    Text("Anterior")
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (state == null) "-" else "${index + 1} / $total",
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
                    enabled = state != null && index + 1 < total,
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
                            val authorLabel = if (authorsText.isBlank()) "CPB" else authorsText
                            "${it.metadata.title} · $authorLabel"
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
                            openBytes(sample.fileName, readAsset(sample.assetPath), sample.id)
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
                        session?.dispatch(ReaderCommand.ApplyHighlightStyle(HighlightStyle.YELLOW))
                    },
                    modifier = Modifier.height(36.dp),
                ) {
                    Text("Grifar")
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

            val currentHtml = remember(
                session,
                readerState?.pageIndex,
                readerState?.fontScale,
                readerState?.isNightMode,
                readerState?.theme,
            ) {
                session?.currentHtml().orEmpty()
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
                        setBackgroundColor(0xFFF3FAF7.toInt())
                        addJavascriptInterface(
                            ScrollProgressInterface { ratio ->
                                post {
                                    val scaledY = (ratio * 1000).toInt()
                                    session?.dispatch(
                                        ReaderCommand.UpdateScroll(
                                            scrollY = scaledY,
                                            contentHeightPx = 1200,
                                            viewportHeightPx = 200,
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
                    if (webView.tag != currentHtml) {
                        webView.tag = currentHtml
                        webView.loadDataWithBaseURL(
                            BOOK_BASE_URL,
                            currentHtml.ifBlank { "<html><body></body></html>" },
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
