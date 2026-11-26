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
    public let pageTurnAnimation: PageTurnAnimation?
    public let onUiEffect: ((any ReaderUiEffect) -> Void)?

    public init(
        session: ReaderSession,
        schemeBase: String = ReaderSession.companion.DEFAULT_BOOK_BASE_URL,
        backgroundColor: UIColor? = nil,
        pageTurnAnimation: PageTurnAnimation? = nil,
        onUiEffect: ((any ReaderUiEffect) -> Void)? = nil
    ) {
        self.session = session
        self.schemeBase = schemeBase
        self.backgroundColor = backgroundColor
        self.pageTurnAnimation = pageTurnAnimation
        self.onUiEffect = onUiEffect
    }

    public func makeCoordinator() -> Coordinator {
        Coordinator(
            session: session,
            schemeBase: schemeBase,
            pageTurnAnimationOverride: pageTurnAnimation,
            onUiEffect: onUiEffect
        )
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
        context.coordinator.attachBoundsObserver(to: webView)
        context.coordinator.loadCurrentDocumentIfNeeded()
        return webView
    }

    public func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.onUiEffect = onUiEffect
        context.coordinator.pageTurnAnimationOverride = pageTurnAnimation
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
        private var boundsObservation: NSKeyValueObservation?
        private var lastSyncedViewport: (Int32, Int32) = (0, 0)
        private weak var transitionSnapshotView: UIView?
        private var pendingTransitionDirection: PageTransitionDirection = .none
        private var pendingTransitionWorkItem: DispatchWorkItem?
        private var lastLoadedPageIndex: Int32?

        weak var webView: WKWebView?
        var currentState: ReaderState?
        var pageTurnAnimationOverride: PageTurnAnimation?
        var onUiEffect: ((any ReaderUiEffect) -> Void)?
        var lastLoadedSignature: String?
        let schemeHandler: ReaderBookSchemeHandler

        init(
            session: ReaderSession,
            schemeBase: String,
            pageTurnAnimationOverride: PageTurnAnimation?,
            onUiEffect: ((any ReaderUiEffect) -> Void)?
        ) {
            self.session = session
            self.schemeBase = schemeBase
            self.pageTurnAnimationOverride = pageTurnAnimationOverride
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
                self.lastLoadedPageIndex = nil
                self.currentState = nil
                clearTransitionSnapshot()
                schemeHandler.updateSessionProvider { [weak newSession] in
                    newSession
                }
                subscribe(to: newSession)
            }
        }

        func detach() {
            clearTransitionSnapshot()
            boundsObservation?.invalidate()
            boundsObservation = nil
            stateSub?.cancel()
            stateSub = nil
            effectsSub?.cancel()
            effectsSub = nil
            webView = nil
        }

        private func clearTransitionSnapshot() {
            pendingTransitionWorkItem?.cancel()
            pendingTransitionWorkItem = nil
            pendingTransitionDirection = .none
            transitionSnapshotView?.removeFromSuperview()
            transitionSnapshotView = nil
        }

        private func effectiveAnimationMode() -> PageTurnAnimation {
            if UIAccessibility.isReduceMotionEnabled {
                return .none
            }
            return pageTurnAnimationOverride ?? currentState?.pageTurnAnimation ?? .slide
        }

        func attachBoundsObserver(to webView: WKWebView) {
            boundsObservation?.invalidate()
            boundsObservation = webView.observe(\.bounds, options: [.old, .new]) { [weak self, weak webView] _, change in
                guard let self = self, let webView = webView else { return }
                if change.oldValue?.size == change.newValue?.size {
                    return
                }
                DispatchQueue.main.async {
                    self.syncViewportSizeIfNeeded(webView: webView, force: false)
                }
            }
        }

        private func syncViewportSizeIfNeeded(webView: WKWebView, force: Bool) {
            guard let session = session, currentState?.readingMode == ReadingMode.paginated else { return }
            let w = Int32(floor(webView.bounds.width))
            let h = Int32(floor(webView.bounds.height))
            guard w >= 120 && h >= 120 else {
                if force {
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
                return
            }
            if !force && lastSyncedViewport == (w, h) {
                return
            }
            lastSyncedViewport = (w, h)
            let cmd = ReaderJsBridge.shared.setViewportSizeCommand(widthCssPx: w, heightCssPx: h)
            webView.evaluateJavaScript(Self.stripJsPrefix(cmd)) { result, _ in
                guard let raw = result as? String,
                      let pair = ReaderJsBridge.shared.parseSubPageState(rawJsValue: raw),
                      let sub = pair.first?.int32Value,
                      let total = pair.second?.int32Value else { return }
                DispatchQueue.main.async {
                    session.onSubPageReported(currentSubPage: sub, totalSubPages: total)
                }
            }
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

            let currentPageIndex = currentState?.pageIndex
            let chapterChanged = lastLoadedPageIndex != nil && currentPageIndex != nil && lastLoadedPageIndex != currentPageIndex
            lastLoadedPageIndex = currentPageIndex

            let anim = effectiveAnimationMode()
            if chapterChanged,
               doc.transitionDirection != .none,
               anim != .none,
               webView.bounds.width > 0,
               webView.bounds.height > 0,
               let snapshot = webView.snapshotView(afterScreenUpdates: false) {
                clearTransitionSnapshot()
                snapshot.frame = webView.bounds
                snapshot.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                snapshot.isUserInteractionEnabled = false
                webView.addSubview(snapshot)
                transitionSnapshotView = snapshot
                pendingTransitionDirection = doc.transitionDirection

                let fallbackWork = DispatchWorkItem { [weak self, weak webView] in
                    guard let self = self, let webView = webView else { return }
                    self.completeChapterTransition(in: webView)
                }
                pendingTransitionWorkItem = fallbackWork
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: fallbackWork)
            }

            let baseURL = URL(string: doc.baseUrl) ?? URL(string: schemeBase)
            let htmlToLoad = doc.html.isEmpty ? "<html><body></body></html>" : doc.html
            webView.loadHTMLString(htmlToLoad, baseURL: baseURL)
        }

        private func completeChapterTransition(in webView: WKWebView) {
            pendingTransitionWorkItem?.cancel()
            pendingTransitionWorkItem = nil
            guard let snapshot = transitionSnapshotView else { return }
            transitionSnapshotView = nil
            let direction = pendingTransitionDirection
            pendingTransitionDirection = .none
            let anim = effectiveAnimationMode()

            guard anim != .none, direction != .none else {
                snapshot.removeFromSuperview()
                return
            }

            let isRtl = currentState?.metadata.pageProgressionDirection == .rtl
            if anim == .fade {
                UIView.animate(withDuration: 0.18, delay: 0, options: [.curveEaseOut]) {
                    snapshot.alpha = 0
                } completion: { _ in
                    snapshot.removeFromSuperview()
                }
            } else {
                let width = webView.bounds.width
                let sign: CGFloat
                switch direction {
                case .forward:
                    sign = isRtl ? 1.0 : -1.0
                case .backward:
                    sign = isRtl ? -1.0 : 1.0
                default:
                    sign = 0.0
                }
                UIView.animate(
                    withDuration: 0.24,
                    delay: 0,
                    options: [.curveEaseOut]
                ) {
                    snapshot.transform = CGAffineTransform(translationX: sign * width, y: 0)
                } completion: { _ in
                    snapshot.removeFromSuperview()
                }
            }
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
            guard session != nil, let state = currentState else { return }

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
                syncViewportSizeIfNeeded(webView: webView, force: true)
            }
            completeChapterTransition(in: webView)
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
