import Foundation
import ReaderCore
import SwiftUI
import UIKit
import WebKit

private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    private weak var delegate: (any WKScriptMessageHandler)?

    init(delegate: any WKScriptMessageHandler) {
        self.delegate = delegate
        super.init()
    }

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        delegate?.userContentController(userContentController, didReceive: message)
    }
}

/// Componente SwiftUI *drop-in* sobre `WKWebView` conectado diretamente a um `ReaderSession`.
///
/// Gerencia ciclo de vida de assinaturas (`watchState` e `watchUiEffects`), carregamento de recursos
/// locais (`book://content/` com HTTP 200/206/416), restauração de grifos/formulários/rolagem
/// e pontes JS sem ciclos de retenção.
public struct ReaderWebView: UIViewRepresentable {
    public let session: ReaderSession
    public let schemeBase: String
    public let backgroundColor: UIColor?
    public let onUiEffect: ((any ReaderUiEffect) -> Void)?

    public init(
        session: ReaderSession,
        schemeBase: String = ReaderSession.companion.DEFAULT_BOOK_BASE_URL,
        backgroundColor: UIColor? = nil,
        onUiEffect: ((any ReaderUiEffect) -> Void)? = nil
    ) {
        self.session = session
        self.schemeBase = schemeBase
        self.backgroundColor = backgroundColor
        self.onUiEffect = onUiEffect
    }

    public func makeCoordinator() -> Coordinator {
        Coordinator(session: session, schemeBase: schemeBase, onUiEffect: onUiEffect)
    }

    public func makeUIView(context: Context) -> WKWebView {
        let contentController = WKUserContentController()
        let weakProxy = WeakScriptMessageHandler(delegate: context.coordinator)
        contentController.add(weakProxy, name: "ReaderScrollProgress")
        contentController.add(weakProxy, name: "ReaderActionBridge")

        let scheme = URL(string: schemeBase)?.scheme ?? "book"
        let config = WKWebViewConfiguration()
        config.userContentController = contentController
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.setURLSchemeHandler(context.coordinator.schemeHandler, forURLScheme: scheme)

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        let initialNightMode = context.coordinator.currentState?.isNightMode ?? false
        let resolvedBg = resolvedBackgroundColor(isNightMode: initialNightMode)
        webView.backgroundColor = resolvedBg
        webView.scrollView.backgroundColor = resolvedBg
        context.coordinator.webView = webView
        context.coordinator.loadCurrentDocumentIfNeeded()
        return webView
    }

    public func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.onUiEffect = onUiEffect
        context.coordinator.bind(session: session, schemeBase: schemeBase)
        let isNight = context.coordinator.currentState?.isNightMode ?? false
        let resolvedBg = resolvedBackgroundColor(isNightMode: isNight)
        webView.backgroundColor = resolvedBg
        webView.scrollView.backgroundColor = resolvedBg
        context.coordinator.loadCurrentDocumentIfNeeded()
    }

    public static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "ReaderScrollProgress")
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "ReaderActionBridge")
        coordinator.detach()
    }

    private func resolvedBackgroundColor(isNightMode: Bool) -> UIColor {
        if let backgroundColor = backgroundColor {
            return backgroundColor
        }
        return isNightMode
            ? UIColor(red: 0.075, green: 0.075, blue: 0.075, alpha: 1.0)
            : UIColor(red: 0.953, green: 0.980, blue: 0.969, alpha: 1.0)
    }

    public final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        private weak var session: ReaderSession?
        private var schemeBase: String
        private var stateSub: FlowSubscription?
        private var effectsSub: FlowSubscription?

        weak var webView: WKWebView?
        var currentState: ReaderState?
        var onUiEffect: ((any ReaderUiEffect) -> Void)?
        var lastLoadedSignature: String?
        let schemeHandler: ReaderBookSchemeHandler

        init(
            session: ReaderSession,
            schemeBase: String,
            onUiEffect: ((any ReaderUiEffect) -> Void)?
        ) {
            self.session = session
            self.schemeBase = schemeBase
            self.onUiEffect = onUiEffect
            self.schemeHandler = ReaderBookSchemeHandler(schemeBase: schemeBase) { [weak session] in
                session
            }
            super.init()
            subscribe(to: session)
        }

        deinit {
            detach()
        }

        func bind(session newSession: ReaderSession, schemeBase: String) {
            self.schemeBase = schemeBase
            if self.session !== newSession {
                self.session = newSession
                self.lastLoadedSignature = nil
                self.currentState = nil
                schemeHandler.updateSessionProvider { [weak newSession] in
                    newSession
                }
                subscribe(to: newSession)
            }
        }

        func detach() {
            stateSub?.cancel()
            stateSub = nil
            effectsSub?.cancel()
            effectsSub = nil
            webView = nil
        }

        private func subscribe(to session: ReaderSession) {
            stateSub?.cancel()
            effectsSub?.cancel()

            stateSub = session.watchState { [weak self] newState in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    let previous = self.currentState
                    self.currentState = newState
                    let changedVisualProps = previous == nil ||
                        previous?.pageIndex != newState.pageIndex ||
                        previous?.theme != newState.theme ||
                        previous?.fontScale != newState.fontScale ||
                        previous?.fontFamily != newState.fontFamily ||
                        previous?.fontSizeClass != newState.fontSizeClass ||
                        previous?.isNightMode != newState.isNightMode ||
                        previous?.readingMode != newState.readingMode
                    if changedVisualProps {
                        self.loadCurrentDocumentIfNeeded()
                    }
                }
            }

            effectsSub = session.watchUiEffects { [weak self] effect in
                DispatchQueue.main.async {
                    self?.handleUiEffect(effect)
                }
            }
        }

        func loadCurrentDocumentIfNeeded() {
            guard let webView = webView, let session = session else { return }
            let doc = session.currentDocument(schemeBase: schemeBase)
            let signature = "\(doc.baseUrl)|\(doc.href)|\(doc.html.hashValue)"
            guard lastLoadedSignature != signature else { return }
            lastLoadedSignature = signature
            let baseURL = URL(string: doc.baseUrl) ?? URL(string: schemeBase)
            let htmlToLoad = doc.html.isEmpty ? "<html><body></body></html>" : doc.html
            webView.loadHTMLString(htmlToLoad, baseURL: baseURL)
        }

        private func handleUiEffect(_ effect: any ReaderUiEffect) {
            if let jsEffect = effect as? ReaderUiEffectEvaluateJs,
               let webView = webView,
               let session = session {
                let rawScript = Self.stripJsPrefix(jsEffect.script)
                webView.evaluateJavaScript(rawScript) { [weak self] result, _ in
                    if let resultStr = result as? String,
                       let pair = ReaderJsBridge.shared.parseSubPageState(rawJsValue: resultStr),
                       let sub = pair.first?.int32Value,
                       let total = pair.second?.int32Value {
                        DispatchQueue.main.async {
                            session.onSubPageReported(currentSubPage: sub, totalSubPages: total)
                        }
                    }
                    if rawScript.contains("highlightSelection") ||
                        rawScript.contains("unHighlightSelection") ||
                        rawScript.contains("removeHighlight") {
                        webView.evaluateJavaScript("readerJS.getHighlights()") { serializedRaw, _ in
                            webView.evaluateJavaScript("readerJS.summaryHighlights()") { summaryRaw, _ in
                                DispatchQueue.main.async {
                                    let pageIndex = self?.currentState?.pageIndex ?? 0
                                    session.onPageHighlightsReported(
                                        rawSerializedHighlights: (serializedRaw as? String) ?? "",
                                        rawSummaryJson: summaryRaw as? String,
                                        pageIndex: pageIndex
                                    )
                                }
                            }
                        }
                    }
                }
            } else if let scrollEffect = effect as? ReaderUiEffectScrollToY {
                let cmd = ReaderJsBridge.shared.scrollToYCommand(
                    scrollYPx: scrollEffect.scrollYPx,
                    anchor: scrollEffect.anchor
                )
                webView?.evaluateJavaScript(Self.stripJsPrefix(cmd), completionHandler: nil)
            }

            onUiEffect?(effect)
        }

        public func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url,
                  let session = session else {
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

            decisionHandler(session.handleUrlNavigation(url: url.absoluteString) ? .cancel : .allow)
        }

        public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard let session = session, let state = currentState else { return }

            if !state.currentPageSerializedHighlights.isEmpty {
                let cmd = ReaderJsBridge.shared.setHighlightsCommand(
                    serializedHighlights: state.currentPageSerializedHighlights
                )
                webView.evaluateJavaScript(Self.stripJsPrefix(cmd), completionHandler: nil)
            }

            if !state.currentPageFormState.annotations.isEmpty,
               let rawAnnotations = Self.serializeFormEntries(state.currentPageFormState.annotations) {
                let cmd = ReaderJsBridge.shared.setAnnotationsCommand(annotationsJsonArray: rawAnnotations)
                webView.evaluateJavaScript(Self.stripJsPrefix(cmd), completionHandler: nil)
            }

            if !state.currentPageFormState.answers.isEmpty,
               let rawAnswers = Self.serializeFormEntries(state.currentPageFormState.answers) {
                let cmd = ReaderJsBridge.shared.setAnswersCommand(answersJsonArray: rawAnswers)
                webView.evaluateJavaScript(Self.stripJsPrefix(cmd), completionHandler: nil)
            }

            if state.scrollY > 0 && state.readingMode == ReadingMode.scroll {
                let cmd = ReaderJsBridge.shared.scrollToYCommand(scrollYPx: state.scrollY, anchor: nil)
                webView.evaluateJavaScript(Self.stripJsPrefix(cmd), completionHandler: nil)
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

        public func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            guard let session = session else { return }
            if message.name == "ReaderScrollProgress",
               let body = message.body as? [String: Any] {
                let sy = Int32((body["scrollY"] as? NSNumber)?.intValue ?? 0)
                let ch = Int32((body["contentHeightPx"] as? NSNumber)?.intValue ?? 0)
                let vh = Int32((body["viewportHeightPx"] as? NSNumber)?.intValue ?? 0)
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                _ = session.dispatch(
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
                _ = session.dispatch(
                    command: ReaderCommandTriggerAction(
                        action: actionName,
                        payload: payload?.isEmpty == true ? nil : payload
                    )
                )
            }
        }

        private static func stripJsPrefix(_ script: String) -> String {
            script.hasPrefix("javascript:") ? String(script.dropFirst("javascript:".count)) : script
        }

        private static func serializeFormEntries(_ entries: [FormEntry]) -> String? {
            let payload: [[String: Any]] = entries.map { entry in
                ["content": entry.content, "index": Int(entry.index)]
            }
            guard let data = try? JSONSerialization.data(withJSONObject: payload, options: []) else {
                return nil
            }
            return String(data: data, encoding: .utf8)
        }
    }
}
