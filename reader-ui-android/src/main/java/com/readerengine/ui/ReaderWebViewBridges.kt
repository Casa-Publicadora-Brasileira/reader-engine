package com.readerengine.ui

import android.webkit.JavascriptInterface

/**
 * Bridge JavaScript padrão do `reader-engine` para receber métricas reais de rolagem
 * da `WebView` (`window.AndroidScrollProgress`).
 */
public class ReaderScrollProgressBridge(
    private val onScrollMetricsChanged: (scrollY: Int, contentHeightPx: Int, viewportHeightPx: Int) -> Unit,
) {
    @JavascriptInterface
    public fun onScrollChanged(scrollY: Int, contentHeightPx: Int, viewportHeightPx: Int) {
        onScrollMetricsChanged(
            scrollY.coerceAtLeast(0),
            contentHeightPx.coerceAtLeast(0),
            viewportHeightPx.coerceAtLeast(0),
        )
    }

    @JavascriptInterface
    public fun reportProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        onScrollMetricsChanged((clamped * 1000).toInt(), 1200, 200)
    }
}

/**
 * Bridge JavaScript padrão do `reader-engine` para receber ações customizadas
 * disparadas pelo conteúdo (`window.AndroidActionBridge` / `window.ReaderEngine.postAction`).
 */
public class ReaderActionBridge(
    private val onActionTriggered: (action: String, payload: String?) -> Unit,
) {
    @JavascriptInterface
    public fun postAction(name: String, payload: String?) {
        onActionTriggered(name, payload)
    }
}
