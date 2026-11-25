package com.readerengine.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.view.View
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import com.readerengine.core.bridge.ReaderJsBridge
import com.readerengine.core.session.ReaderCommand
import com.readerengine.core.session.ReaderSession
import com.readerengine.core.session.ReaderUiEffect
import com.readerengine.core.session.RenderedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cor de fundo padrão do leitor no modo claro (`#F3FAF7`). */
public val DefaultLightReaderBackground: Int = 0xFFF3FAF7.toInt()

/** Cor de fundo padrão do leitor no modo noturno (`#131313`). */
public val DefaultDarkReaderBackground: Int = 0xFF131313.toInt()

/**
 * Componente Jetpack Compose *drop-in* que encapsula uma `WebView` segura e conectada
 * automaticamente a um [ReaderSession]:
 *
 * - Serve recursos locais e mídia via [ReaderResourceWebViewClient] (`book://content/` com HTTP 200/206/416).
 * - Conecta as bridges `AndroidScrollProgress` e `AndroidActionBridge`.
 * - Observa `session.state` e recarrega o HTML quando o capítulo ou tema muda.
 * - Consome `session.uiEffects` para `EvaluateJs` (incluindo sync de paginação e highlights) e `ScrollToY`,
 *   repassando todos os efeitos para [onUiEffect].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
public fun ReaderWebView(
    session: ReaderSession,
    modifier: Modifier = Modifier,
    schemeBase: String = ReaderSession.DEFAULT_BOOK_BASE_URL,
    backgroundColor: Int? = null,
    onUiEffect: ((ReaderUiEffect) -> Unit)? = null,
    onJsAlert: ((message: String) -> Unit)? = null,
    onShowFullscreenView: ((view: View, callback: WebChromeClient.CustomViewCallback) -> Unit)? = null,
    onHideFullscreenView: (() -> Unit)? = null,
    onWebViewCreated: ((WebView) -> Unit)? = null,
) {
    val currentSessionState = rememberUpdatedState(session)
    val currentOnUiEffect = rememberUpdatedState(onUiEffect)
    val currentOnJsAlert = rememberUpdatedState(onJsAlert)
    val currentOnShowFullscreen = rememberUpdatedState(onShowFullscreenView)
    val currentOnHideFullscreen = rememberUpdatedState(onHideFullscreenView)
    val currentOnWebViewCreated = rememberUpdatedState(onWebViewCreated)

    val readerState by session.state.collectAsState()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val currentDoc: RenderedDocument = remember(
        session,
        schemeBase,
        readerState.pageIndex,
        readerState.fontScale,
        readerState.fontFamily,
        readerState.fontSizeClass,
        readerState.isNightMode,
        readerState.readingMode,
        readerState.theme,
    ) {
        session.currentDocument(schemeBase)
    }

    LaunchedEffect(session) {
        session.uiEffects.collect { effect ->
            when (effect) {
                is ReaderUiEffect.EvaluateJs -> {
                    val webView = webViewRef
                    if (webView != null) {
                        val rawScript = effect.script.removePrefix("javascript:")
                        webView.evaluateJavascript(rawScript) { evalResult ->
                            ReaderJsBridge.parseSubPageState(evalResult)?.let { (sub, total) ->
                                session.onSubPageReported(sub, total)
                            }
                            if (rawScript.contains("highlightSelection") ||
                                rawScript.contains("unHighlightSelection") ||
                                rawScript.contains("removeHighlight")
                            ) {
                                webView.evaluateJavascript("readerJS.getHighlights()") { serializedRaw ->
                                    webView.evaluateJavascript("readerJS.summaryHighlights()") { summaryRaw ->
                                        session.onPageHighlightsReported(
                                            rawSerializedHighlights = serializedRaw ?: "",
                                            rawSummaryJson = summaryRaw,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is ReaderUiEffect.ScrollToY -> {
                    val scrollCmd = ReaderJsBridge.scrollToYCommand(effect.scrollYPx, effect.anchor)
                    webViewRef?.evaluateJavascript(scrollCmd.removePrefix("javascript:"), null)
                }

                else -> Unit
            }
            currentOnUiEffect.value?.invoke(effect)
        }
    }

    var layoutChangeListener by remember { mutableStateOf<View.OnLayoutChangeListener?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            val existingListener = layoutChangeListener
            if (existingListener != null) {
                webViewRef?.removeOnLayoutChangeListener(existingListener)
            }
            layoutChangeListener = null
            webViewRef?.apply {
                stopLoading()
                removeJavascriptInterface("AndroidScrollProgress")
                removeJavascriptInterface("AndroidActionBridge")
                destroy()
            }
            webViewRef = null
        }
    }

    val resolvedBg = backgroundColor ?: if (readerState.isNightMode) {
        DefaultDarkReaderBackground
    } else {
        DefaultLightReaderBackground
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = ReaderResourceWebViewClient(
                    sessionProvider = { currentSessionState.value },
                )
                webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                        currentOnShowFullscreen.value?.invoke(view, callback)
                    }

                    override fun onHideCustomView() {
                        currentOnHideFullscreen.value?.invoke()
                    }

                    override fun onJsAlert(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        result: JsResult?,
                    ): Boolean {
                        currentOnJsAlert.value?.invoke(message.orEmpty())
                        result?.confirm()
                        return true
                    }
                }
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(resolvedBg)
                addJavascriptInterface(
                    ReaderScrollProgressBridge { scrollY, contentHeightPx, viewportHeightPx ->
                        post {
                            currentSessionState.value.dispatch(
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
                addJavascriptInterface(
                    ReaderActionBridge { name, payload ->
                        post {
                            currentSessionState.value.dispatch(
                                ReaderCommand.TriggerAction(name, payload),
                            )
                        }
                    },
                    "AndroidActionBridge",
                )
                var lastSyncedWidthCss = 0
                var lastSyncedHeightCss = 0
                val listener = View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    val newW = right - left
                    val newH = bottom - top
                    val oldW = oldRight - oldLeft
                    val oldH = oldBottom - oldTop
                    if (newW == oldW && newH == oldH) return@OnLayoutChangeListener
                    val density = view.resources.displayMetrics.density.coerceAtLeast(1f)
                    val widthCssPx = (newW / density).toInt()
                    val heightCssPx = (newH / density).toInt()
                    if (widthCssPx < 120 || heightCssPx < 120) return@OnLayoutChangeListener
                    if (widthCssPx == lastSyncedWidthCss && heightCssPx == lastSyncedHeightCss) return@OnLayoutChangeListener
                    lastSyncedWidthCss = widthCssPx
                    lastSyncedHeightCss = heightCssPx
                    val activeSession = currentSessionState.value
                    if (activeSession.state.value.readingMode == com.readerengine.core.model.ReadingMode.PAGINATED) {
                        val cmd = ReaderJsBridge.setViewportSizeCommand(widthCssPx, heightCssPx)
                            .removePrefix("javascript:")
                        (view as? WebView)?.evaluateJavascript(cmd) { raw ->
                            ReaderJsBridge.parseSubPageState(raw)?.let { (sub, total) ->
                                activeSession.onSubPageReported(sub, total)
                            }
                        }
                    }
                }
                addOnLayoutChangeListener(listener)
                layoutChangeListener = listener
                webViewRef = this
                currentOnWebViewCreated.value?.invoke(this)
            }
        },
        update = { webView ->
            webView.setBackgroundColor(resolvedBg)
            if (webView.tag != currentDoc) {
                webView.tag = currentDoc
                webView.loadDataWithBaseURL(
                    currentDoc.baseUrl,
                    currentDoc.html.ifBlank { "<html><body></body></html>" },
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
    )
}

/**
 * Componente Jetpack Compose *drop-in* para exibição de documentos PDF nativos via
 * `session.renderPdfPagePng(...)` com suporte a *pinch-to-zoom*, arraste e duplo toque.
 */
@Composable
public fun PdfReaderCanvas(
    session: ReaderSession,
    modifier: Modifier = Modifier,
    renderWidthPx: Int = 1600,
    minZoom: Float = 1f,
    maxZoom: Float = 4f,
    backgroundColor: Color = Color(0xFF1E1E1E),
) {
    val readerState by session.state.collectAsState()
    val pageIndex = readerState.pageIndex

    var pageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
    var offset by remember(pageIndex) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(session, pageIndex, renderWidthPx) {
        val decoded = withContext(Dispatchers.IO) {
            val bytes = session.renderPdfPagePng(
                pageIndex = pageIndex,
                widthPx = renderWidthPx.coerceAtLeast(320),
            ) ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
        pageBitmap = decoded
    }

    Box(
        modifier = modifier
            .background(backgroundColor)
            .clipToBounds()
            .pointerInput(pageIndex, minZoom, maxZoom) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(minZoom, maxZoom)
                    scale = newScale
                    offset = if (newScale > 1.01f) {
                        offset + pan
                    } else {
                        Offset.Zero
                    }
                }
            }
            .pointerInput(pageIndex, minZoom, maxZoom) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1.05f) 1f else 2f.coerceIn(minZoom, maxZoom)
                        offset = Offset.Zero
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = pageBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "PDF page ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

/**
 * Componente universal *drop-in* para exibição de qualquer publicação suportada pelo `reader-core`.
 *
 * Seleciona automaticamente [PdfReaderCanvas] quando `session.isPdfDocument()` é `true` e
 * [ReaderWebView] para EPUB, HPUB, CBZ, FB2 e JSON.
 */
@Composable
public fun ReaderContentView(
    session: ReaderSession,
    modifier: Modifier = Modifier,
    schemeBase: String = ReaderSession.DEFAULT_BOOK_BASE_URL,
    backgroundColor: Int? = null,
    pdfRenderWidthPx: Int = 1600,
    onUiEffect: ((ReaderUiEffect) -> Unit)? = null,
    onJsAlert: ((message: String) -> Unit)? = null,
    onShowFullscreenView: ((view: View, callback: WebChromeClient.CustomViewCallback) -> Unit)? = null,
    onHideFullscreenView: (() -> Unit)? = null,
    onWebViewCreated: ((WebView) -> Unit)? = null,
) {
    if (session.isPdfDocument()) {
        val readerState by session.state.collectAsState()
        val resolvedPdfBg = backgroundColor?.let { Color(it) } ?: if (readerState.isNightMode) {
            Color(DefaultDarkReaderBackground)
        } else {
            Color(0xFF1E1E1E)
        }
        PdfReaderCanvas(
            session = session,
            modifier = modifier,
            renderWidthPx = pdfRenderWidthPx,
            backgroundColor = resolvedPdfBg,
        )
    } else {
        ReaderWebView(
            session = session,
            modifier = modifier,
            schemeBase = schemeBase,
            backgroundColor = backgroundColor,
            onUiEffect = onUiEffect,
            onJsAlert = onJsAlert,
            onShowFullscreenView = onShowFullscreenView,
            onHideFullscreenView = onHideFullscreenView,
            onWebViewCreated = onWebViewCreated,
        )
    }
}
