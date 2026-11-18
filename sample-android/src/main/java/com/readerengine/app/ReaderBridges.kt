package com.readerengine.app

import android.webkit.JavascriptInterface

/** Interface JavaScript para receber métricas de rolagem reais da WebView. */
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

/** Interface JavaScript para receber ações customizadas do livro (`window.ReaderEngine.postAction`). */
class ActionBridge(
    private val onActionTriggered: (action: String, payload: String?) -> Unit,
) {
    @JavascriptInterface
    fun postAction(name: String, payload: String?) {
        onActionTriggered(name, payload)
    }
}
