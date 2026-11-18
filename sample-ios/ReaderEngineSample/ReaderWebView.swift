import Foundation
import ReaderCore
import SwiftUI
import WebKit

/// Gerencia chamadas de recursos `book://content/` no `WKWebView` com suporte a HTTP 200 e HTTP 206 Partial Content.
final class BookSchemeHandler: NSObject, WKURLSchemeHandler {
    private let sessionProvider: () -> ReaderSession?
    private let lock = NSLock()
    private var stoppedTaskIDs = Set<ObjectIdentifier>()

    init(sessionProvider: @escaping () -> ReaderSession?) {
        self.sessionProvider = sessionProvider
        super.init()
    }

    func webView(_ webView: WKWebView, start urlSchemeTask: any WKURLSchemeTask) {
        let taskID = ObjectIdentifier(urlSchemeTask)
        guard let url = urlSchemeTask.request.url else {
            finishNotFound(urlSchemeTask, taskID: taskID, url: URL(string: "book://content/")!)
            return
        }

        let schemeBase = ReaderSession.companion.DEFAULT_BOOK_BASE_URL
        guard url.absoluteString.hasPrefix(schemeBase),
              let session = sessionProvider() else {
            finishNotFound(urlSchemeTask, taskID: taskID, url: url)
            return
        }

        let stripped = String(url.absoluteString.dropFirst(schemeBase.count))
        let href = stripped.components(separatedBy: "#").first?.components(separatedBy: "?").first ?? ""
        let cleanHref = href.removingPercentEncoding ?? href

        guard !cleanHref.isEmpty, let sizeNumber = session.resourceSize(href: cleanHref) else {
            finishNotFound(urlSchemeTask, taskID: taskID, url: url)
            return
        }

        let totalSize = sizeNumber.int64Value
        let mimeType = session.resourceMediaType(href: cleanHref) ?? guessMimeType(href: cleanHref)
        let rangeHeader = urlSchemeTask.request.value(forHTTPHeaderField: "Range")

        if let rangeHeader = rangeHeader,
           rangeHeader.lowercased().hasPrefix("bytes="),
           totalSize > 0 {
            let rangeSpec = String(rangeHeader.dropFirst("bytes=".count)).trimmingCharacters(in: .whitespaces)
            let parts = rangeSpec.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
            let start = Int64(parts.first.map(String.init) ?? "") ?? 0
            let maxChunk: Int64 = 1024 * 1024 // 1 MB por fatia
            let requestedEnd = parts.count > 1 ? Int64(parts[1]) : nil

            if start < 0 || start >= totalSize {
                let headers = [
                    "Content-Range": "bytes */\(totalSize)",
                    "Accept-Ranges": "bytes",
                ]
                if let response = HTTPURLResponse(
                    url: url,
                    statusCode: 416,
                    httpVersion: "HTTP/1.1",
                    headerFields: headers
                ), !isStopped(taskID) {
                    urlSchemeTask.didReceive(response)
                    urlSchemeTask.didFinish()
                }
                return
            }

            let effectiveEnd: Int64
            if let requestedEnd = requestedEnd, requestedEnd >= start {
                effectiveEnd = min(requestedEnd, totalSize - 1)
            } else {
                effectiveEnd = min(start + maxChunk - 1, totalSize - 1)
            }

            let sliceLength = Int32(min(maxChunk, effectiveEnd - start + 1))
            let chunkBytes = session.readResourceRange(href: cleanHref, offset: start, byteCount: sliceLength).toData()
            let actualEnd = start + Int64(chunkBytes.count) - 1

            let headers: [String: String] = [
                "Content-Type": mimeType,
                "Content-Length": "\(chunkBytes.count)",
                "Content-Range": "bytes \(start)-\(actualEnd)/\(totalSize)",
                "Accept-Ranges": "bytes",
                "Access-Control-Allow-Origin": "*",
            ]

            guard !isStopped(taskID),
                  let response = HTTPURLResponse(
                      url: url,
                      statusCode: 206,
                      httpVersion: "HTTP/1.1",
                      headerFields: headers
                  ) else { return }

            urlSchemeTask.didReceive(response)
            urlSchemeTask.didReceive(chunkBytes)
            if !isStopped(taskID) {
                urlSchemeTask.didFinish()
            }
            return
        }

        let fullData = session.readResource(href: cleanHref).toData()
        let headers: [String: String] = [
            "Content-Type": mimeType,
            "Content-Length": "\(fullData.count)",
            "Accept-Ranges": "bytes",
            "Access-Control-Allow-Origin": "*",
        ]

        guard !isStopped(taskID),
              let response = HTTPURLResponse(
                  url: url,
                  statusCode: 200,
                  httpVersion: "HTTP/1.1",
                  headerFields: headers
              ) else { return }

        urlSchemeTask.didReceive(response)
        urlSchemeTask.didReceive(fullData)
        if !isStopped(taskID) {
            urlSchemeTask.didFinish()
        }
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: any WKURLSchemeTask) {
        lock.lock()
        stoppedTaskIDs.insert(ObjectIdentifier(urlSchemeTask))
        lock.unlock()
    }

    private func isStopped(_ taskID: ObjectIdentifier) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return stoppedTaskIDs.contains(taskID)
    }

    private func finishNotFound(_ task: any WKURLSchemeTask, taskID: ObjectIdentifier, url: URL) {
        guard !isStopped(taskID) else { return }
        if let response = HTTPURLResponse(
            url: url,
            statusCode: 404,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/plain; charset=utf-8"]
        ) {
            task.didReceive(response)
            task.didReceive(Data("Resource not found".utf8))
            if !isStopped(taskID) {
                task.didFinish()
            }
        }
    }

    private func guessMimeType(href: String) -> String {
        let lower = href.lowercased()
        if lower.hasSuffix(".html") || lower.hasSuffix(".xhtml") { return "application/xhtml+xml" }
        if lower.hasSuffix(".css") { return "text/css" }
        if lower.hasSuffix(".js") { return "application/javascript" }
        if lower.hasSuffix(".png") { return "image/png" }
        if lower.hasSuffix(".jpg") || lower.hasSuffix(".jpeg") { return "image/jpeg" }
        if lower.hasSuffix(".gif") { return "image/gif" }
        if lower.hasSuffix(".svg") { return "image/svg+xml" }
        if lower.hasSuffix(".webp") { return "image/webp" }
        if lower.hasSuffix(".woff2") { return "font/woff2" }
        if lower.hasSuffix(".woff") { return "font/woff" }
        if lower.hasSuffix(".ttf") { return "font/ttf" }
        if lower.hasSuffix(".otf") { return "font/otf" }
        if lower.hasSuffix(".mp4") || lower.hasSuffix(".m4v") { return "video/mp4" }
        if lower.hasSuffix(".webm") { return "video/webm" }
        if lower.hasSuffix(".mp3") { return "audio/mpeg" }
        if lower.hasSuffix(".m4a") { return "audio/mp4" }
        if lower.hasSuffix(".wav") { return "audio/wav" }
        if lower.hasSuffix(".ogg") || lower.hasSuffix(".oga") { return "audio/ogg" }
        return "application/octet-stream"
    }
}

/// Wrapper SwiftUI sobre `WKWebView` conectado ao `ReaderViewModel`.
struct ReaderWebView: UIViewRepresentable {
    @ObservedObject var viewModel: ReaderViewModel
    let document: RenderedDocument?
    let isNightMode: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(viewModel: viewModel)
    }

    func makeUIView(context: Context) -> WKWebView {
        let contentController = WKUserContentController()
        contentController.add(context.coordinator, name: "ReaderScrollProgress")
        contentController.add(context.coordinator, name: "ReaderActionBridge")

        let config = WKWebViewConfiguration()
        config.userContentController = contentController
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.setURLSchemeHandler(context.coordinator.schemeHandler, forURLScheme: "book")

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = isNightMode ? UIColor(red: 0.075, green: 0.075, blue: 0.075, alpha: 1.0) : .white
        webView.scrollView.backgroundColor = webView.backgroundColor
        context.coordinator.webView = webView
        viewModel.attachWebView(webView)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let bgColor = isNightMode ? UIColor(red: 0.075, green: 0.075, blue: 0.075, alpha: 1.0) : UIColor(red: 0.953, green: 0.980, blue: 0.969, alpha: 1.0)
        webView.backgroundColor = bgColor
        webView.scrollView.backgroundColor = bgColor

        guard let doc = document else { return }
        let docSignature = "\(doc.baseUrl)|\(doc.href)|\(doc.html.hashValue)"
        if context.coordinator.lastLoadedSignature != docSignature {
            context.coordinator.lastLoadedSignature = docSignature
            let baseURL = URL(string: doc.baseUrl) ?? URL(string: ReaderSession.companion.DEFAULT_BOOK_BASE_URL)
            let htmlToLoad = doc.html.isEmpty ? "<html><body></body></html>" : doc.html
            webView.loadHTMLString(htmlToLoad, baseURL: baseURL)
        }
    }

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "ReaderScrollProgress")
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "ReaderActionBridge")
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        private weak var viewModel: ReaderViewModel?
        weak var webView: WKWebView?
        var lastLoadedSignature: String?
        let schemeHandler: BookSchemeHandler

        init(viewModel: ReaderViewModel) {
            self.viewModel = viewModel
            self.schemeHandler = BookSchemeHandler { [weak viewModel] in
                viewModel?.activeSession
            }
            super.init()
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url,
                  let session = viewModel?.activeSession else {
                decisionHandler(.allow)
                return
            }

            let scheme = (url.scheme ?? "").lowercased()
            let isCustomScheme = !scheme.isEmpty &&
                scheme != "book" &&
                scheme != "http" &&
                scheme != "https" &&
                scheme != "file" &&
                scheme != "about" &&
                scheme != "data" &&
                scheme != "blob" &&
                scheme != "javascript"

            guard navigationAction.navigationType == .linkActivated || isCustomScheme else {
                decisionHandler(.allow)
                return
            }

            if session.handleUrlNavigation(url: url.absoluteString) {
                decisionHandler(.cancel)
            } else {
                decisionHandler(.allow)
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard let vm = viewModel,
                  let session = vm.activeSession,
                  let state = vm.readerState else { return }

            if !state.currentPageSerializedHighlights.isEmpty {
                let cmd = ReaderJsBridge.shared.setHighlightsCommand(
                    serializedHighlights: state.currentPageSerializedHighlights
                )
                let rawScript = cmd.hasPrefix("javascript:") ? String(cmd.dropFirst("javascript:".count)) : cmd
                webView.evaluateJavaScript(rawScript, completionHandler: nil)
            }

            if state.scrollY > 0 && state.readingMode == ReadingMode.scroll {
                let cmd = ReaderJsBridge.shared.scrollToYCommand(scrollYPx: state.scrollY, anchor: nil)
                let rawScript = cmd.hasPrefix("javascript:") ? String(cmd.dropFirst("javascript:".count)) : cmd
                webView.evaluateJavaScript(rawScript, completionHandler: nil)
            }

            if state.readingMode == ReadingMode.paginated {
                webView.evaluateJavaScript("readerJS.getSubPageState()") { result, _ in
                    guard let raw = result as? String,
                          let pair = ReaderJsBridge.shared.parseSubPageState(rawJsValue: raw),
                          let sub = pair.first?.int32Value,
                          let total = pair.second?.int32Value else { return }
                    DispatchQueue.main.async {
                        session.onSubPageReported(currentSubPage: sub, totalSubPages: total)
                    }
                }
            }
        }

        func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            guard let session = viewModel?.activeSession else { return }
            if message.name == "ReaderScrollProgress",
               let body = message.body as? [String: Any] {
                let sy = Int32((body["scrollY"] as? NSNumber)?.intValue ?? 0)
                let ch = Int32((body["contentHeightPx"] as? NSNumber)?.intValue ?? 0)
                let vh = Int32((body["viewportHeightPx"] as? NSNumber)?.intValue ?? 0)
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                session.dispatch(
                    command: ReaderCommandUpdateScroll(
                        scrollY: max(0, sy),
                        contentHeightPx: max(0, ch),
                        viewportHeightPx: max(0, vh),
                        timestampEpochMs: nowMs
                    )
                )
            } else if message.name == "ReaderActionBridge",
                      let body = message.body as? [String: Any],
                      let actionName = body["name"] as? String {
                let payload = body["payload"] as? String
                session.dispatch(
                    command: ReaderCommandTriggerAction(
                        action: actionName,
                        payload: payload?.isEmpty == true ? nil : payload
                    )
                )
            }
        }
    }
}
