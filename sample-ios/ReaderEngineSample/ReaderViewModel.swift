import Foundation
import ReaderCore
import SwiftUI
import UniformTypeIdentifiers
import WebKit

struct SampleBook: Identifiable, Equatable {
    let id: String
    let title: String
    let resourceName: String
    let fileExtension: String

    var fileName: String { "\(resourceName).\(fileExtension)" }
}

enum DeviceFileFilter: String, CaseIterable, Identifiable {
    case epub
    case hpub
    case json
    case all

    var id: String { rawValue }

    var title: String {
        switch self {
        case .epub: return "Abrir arquivo .epub"
        case .hpub: return "Abrir arquivo .hpub"
        case .json: return "Abrir arquivo .json"
        case .all: return "Procurar qualquer formato"
        }
    }

    var subtitle: String {
        switch self {
        case .epub: return "EPUB 2 e EPUB 3 (Reflowable, Fixed-Layout, Mídia)"
        case .hpub: return "Pacote HPUB / Baker Framework (book.json + HTML)"
        case .json: return "Documento estruturado reader-json"
        case .all: return "Selecionar .epub, .hpub ou .json do dispositivo"
        }
    }

    var badge: String {
        switch self {
        case .epub: return "EPUB"
        case .hpub: return "HPUB"
        case .json: return "JSON"
        case .all: return "TODOS"
        }
    }

    var defaultExtension: String? {
        switch self {
        case .epub: return "epub"
        case .hpub: return "hpub"
        case .json: return "json"
        case .all: return nil
        }
    }

    var allowedContentTypes: [UTType] {
        let hpubType = UTType(filenameExtension: "hpub") ?? .zip
        switch self {
        case .epub:
            return [.epub, .zip, .data]
        case .hpub:
            return [hpubType, .zip, .data]
        case .json:
            return [.json, .plainText, .data]
        case .all:
            return [.epub, hpubType, .json, .zip, .data]
        }
    }
}

@MainActor
final class ReaderViewModel: ObservableObject {
    @Published var readerState: ReaderState?
    @Published var renderedDocument: RenderedDocument?
    @Published var activeBookKey: String?
    @Published var importedFiles: [URL] = []
    @Published var lastEventSummary: String = "Aguardando eventos..."
    @Published var errorMessage: String?
    @Published var searchResults: [SearchResult] = []
    @Published var fontScaleSlider: Float = 1.0
    @Published var showOpenBookSheet: Bool = false

    let samples: [SampleBook] = [
        SampleBook(id: "noite-clara", title: "Noite Clara (JSON)", resourceName: "noite-clara", fileExtension: "json"),
        SampleBook(id: "epub-fixture", title: "EPUB Fixture", resourceName: "sample", fileExtension: "epub"),
        SampleBook(id: "hpub-fixture", title: "Lição HPUB", resourceName: "sample", fileExtension: "hpub"),
        SampleBook(id: "epub-with-image", title: "EPUB With Image", resourceName: "sample-with-image", fileExtension: "epub"),
    ]

    private let zipLimits: ZipLimits
    private let engine: ReaderEngine
    private let hostStore = HostBookPersistenceStore()
    private let importedDir: URL

    private(set) var activeSession: ReaderSession?
    private weak var webView: WKWebView?

    private var stateSub: FlowSubscription?
    private var eventsSub: FlowSubscription?
    private var effectsSub: FlowSubscription?

    init() {
        let companionLimits = ZipLimits.companion
        let limits = ZipLimits(
            maxEntries: companionLimits.DEFAULT_MAX_ENTRIES,
            maxEntryUncompressedBytes: companionLimits.DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES,
            maxTotalUncompressedBytes: companionLimits.DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES,
            maxCacheBytes: companionLimits.DEFAULT_MAX_CACHE_BYTES
        )
        self.zipLimits = limits
        self.engine = ReaderEngine(
            registry: ParserRegistry.companion.default(),
            zipLimits: limits,
            customDecryptor: nil
        )

        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = caches.appendingPathComponent("imported_books", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        self.importedDir = dir
        self.importedFiles = loadImportedBookFiles()

        if let first = samples.first {
            openSample(first)
        }
    }

    deinit {
        stateSub?.cancel()
        eventsSub?.cancel()
        effectsSub?.cancel()
        activeSession?.close()
    }

    func attachWebView(_ webView: WKWebView) {
        self.webView = webView
    }

    func openSample(_ sample: SampleBook) {
        guard let url = locateSampleURL(sample),
              let data = try? Data(contentsOf: url) else {
            errorMessage = "Fixture não encontrada no bundle: \(sample.fileName)"
            return
        }

        let initialState = buildInitialBookState(documentId: sample.id)
        let session = engine.open(
            name: sample.fileName,
            bytes: data.toKotlinByteArray(),
            initialState: initialState
        )
        if let savedBookmarks = hostStore.loadBookmarksJson(documentId: sample.id) {
            _ = session.importBookmarksJson(rawJson: savedBookmarks)
        }
        activateSession(session, bookKey: sample.id)
    }

    func openImportedFile(_ fileURL: URL) {
        let okioPath = OkioPath.companion.toPath(fileURL.path, normalize: true)
        let fileSystem = PlatformFileSystem_iosKt.defaultFileSystem
        let source = Source.companion.fromPath(
            path: okioPath,
            fileSystem: fileSystem,
            zipLimits: zipLimits,
            customDecryptor: nil
        )
        let documentId = source.sha256DocumentId()
        let initialState = buildInitialBookState(documentId: documentId)
        let session = engine.open(
            path: okioPath,
            fileSystem: fileSystem,
            initialState: initialState
        )
        if let savedBookmarks = hostStore.loadBookmarksJson(documentId: documentId) {
            _ = session.importBookmarksJson(rawJson: savedBookmarks)
        }
        importedFiles = loadImportedBookFiles()
        showOpenBookSheet = false
        activateSession(session, bookKey: fileURL.path)
    }

    func importFromDevice(url: URL, hintExtension: String?) {
        let hasSecurityScope = url.startAccessingSecurityScopedResource()
        defer {
            if hasSecurityScope {
                url.stopAccessingSecurityScopedResource()
            }
        }

        do {
            let data = try Data(contentsOf: url)
            let safeName = normalizeDeviceFileName(
                rawName: url.lastPathComponent,
                hintExtension: hintExtension,
                headerData: data.prefix(256)
            )
            let destURL = importedDir.appendingPathComponent(safeName)
            if FileManager.default.fileExists(atPath: destURL.path) {
                try FileManager.default.removeItem(at: destURL)
            }
            try data.write(to: destURL, options: .atomic)
            openImportedFile(destURL)
        } catch {
            errorMessage = "Falha ao abrir arquivo do dispositivo: \(error.localizedDescription)"
        }
    }

    func deleteImportedFile(_ fileURL: URL) {
        try? FileManager.default.removeItem(at: fileURL)
        importedFiles = loadImportedBookFiles()
    }

    func dispatch(_ command: any ReaderCommand) {
        _ = activeSession?.dispatch(command: command)
    }

    func search(query: String) {
        guard let session = activeSession else { return }
        searchResults = session.search(query: query)
    }

    private func activateSession(_ session: ReaderSession, bookKey: String) {
        stateSub?.cancel()
        eventsSub?.cancel()
        effectsSub?.cancel()
        activeSession?.close()

        activeSession = session
        activeBookKey = bookKey
        searchResults = []
        errorMessage = nil

        stateSub = session.watchState { [weak self] newState in
            DispatchQueue.main.async {
                guard let self = self else { return }
                let previous = self.readerState
                self.readerState = newState
                self.fontScaleSlider = newState.fontScale

                let needsReload = previous == nil ||
                    previous?.pageIndex != newState.pageIndex ||
                    previous?.theme != newState.theme ||
                    previous?.fontScale != newState.fontScale ||
                    previous?.fontFamily != newState.fontFamily ||
                    previous?.fontSizeClass != newState.fontSizeClass ||
                    previous?.isNightMode != newState.isNightMode ||
                    previous?.readingMode != newState.readingMode

                if needsReload {
                    self.renderedDocument = session.currentDocument(
                        schemeBase: ReaderSession.companion.DEFAULT_BOOK_BASE_URL
                    )
                }
            }
        }

        eventsSub = session.watchEvents { [weak self] event in
            DispatchQueue.main.async {
                self?.handleEvent(event)
            }
        }

        effectsSub = session.watchUiEffects { [weak self] effect in
            DispatchQueue.main.async {
                self?.handleUiEffect(effect)
            }
        }
    }

    private func handleEvent(_ event: any ReaderEvent) {
        if let progressEvent = event as? ReaderEventProgressUpdated {
            hostStore.saveProgress(progressEvent.progress)
            let pct = Int(progressEvent.progress.totalPercentage)
            lastEventSummary = "ProgressUpdated(pág=\(progressEvent.progress.pageNumber)/\(progressEvent.progress.totalPages), \(pct)%)"
        } else if let hlEvent = event as? ReaderEventPageHighlightsChanged {
            hostStore.savePageHighlight(
                documentId: hlEvent.documentId,
                pageIndex: hlEvent.pageIndex,
                compressedPayload: hlEvent.compressedPayload
            )
            lastEventSummary = "PageHighlightsChanged(pág=\(hlEvent.pageIndex + 1), itens=\(hlEvent.summaryItems.count))"
        } else if let formEvent = event as? ReaderEventPageFormChanged {
            hostStore.savePageForm(
                documentId: formEvent.documentId,
                pageIndex: formEvent.pageIndex,
                compressedPayload: formEvent.compressedPayload
            )
            lastEventSummary = "PageFormChanged(pág=\(formEvent.pageIndex + 1), notas=\(formEvent.formState.annotations.count), resp=\(formEvent.formState.answers.count))"
        } else if let bmEvent = event as? ReaderEventBookmarksChanged {
            hostStore.saveBookmarksJson(documentId: bmEvent.documentId, json: bmEvent.serializedJson)
            lastEventSummary = "BookmarksChanged(total=\(bmEvent.bookmarks.count))"
        } else if let prefEvent = event as? ReaderEventPreferencesChanged {
            hostStore.savePreferences(
                fontScale: prefEvent.fontScale,
                isNightMode: prefEvent.isNightMode,
                fontFamily: prefEvent.fontFamily,
                readingMode: prefEvent.readingMode
            )
            lastEventSummary = "PreferencesChanged(escala=\(prefEvent.fontScale), noite=\(prefEvent.isNightMode), modo=\(prefEvent.readingMode.name))"
        } else if let actionEvent = event as? ReaderEventActionTriggered {
            let suffix = actionEvent.payload.map { ": \($0)" } ?? ""
            lastEventSummary = "ActionTriggered(\(actionEvent.action)\(suffix))"
        }
    }

    private func handleUiEffect(_ effect: any ReaderUiEffect) {
        if let jsEffect = effect as? ReaderUiEffectEvaluateJs {
            guard let webView = webView, let session = activeSession else { return }
            let rawScript = jsEffect.script.hasPrefix("javascript:")
                ? String(jsEffect.script.dropFirst("javascript:".count))
                : jsEffect.script
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
                                let pageIndex = self?.readerState?.pageIndex ?? 0
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
            let rawScript = cmd.hasPrefix("javascript:") ? String(cmd.dropFirst("javascript:".count)) : cmd
            webView?.evaluateJavaScript(rawScript, completionHandler: nil)
        } else if let customUri = effect as? ReaderUiEffectOpenCustomUri {
            lastEventSummary = "OpenCustomUri(\(customUri.scheme)://\(customUri.title))"
        } else if let customAction = effect as? ReaderUiEffectCustomAction {
            let suffix = customAction.payload.map { ": \($0)" } ?? ""
            lastEventSummary = "CustomAction(\(customAction.action)\(suffix))"
        } else if let externalUrl = effect as? ReaderUiEffectOpenExternalUrl {
            lastEventSummary = "OpenExternalUrl(\(externalUrl.url))"
            if let url = URL(string: externalUrl.url) {
                UIApplication.shared.open(url)
            }
        } else if let msgEffect = effect as? ReaderUiEffectShowMessage {
            lastEventSummary = "ShowMessage(\(msgEffect.message))"
        }
    }

    private func buildInitialBookState(documentId: String) -> InitialBookState {
        let savedProgress = hostStore.loadProgress(documentId: documentId)
        return InitialBookState(
            documentId: documentId,
            initialPageIndex: savedProgress?.pageIndex ?? 0,
            initialScrollY: savedProgress?.scrollY ?? 0,
            initialSubPage: savedProgress?.subPage ?? 0,
            isNightMode: hostStore.loadNightMode(),
            readingMode: hostStore.loadReadingMode(),
            fontFamily: hostStore.loadFontFamily(),
            fontSizeClass: "",
            fontScale: hostStore.loadFontScale(),
            theme: ThemeTokens.companion.hig(),
            savedPageHighlights: hostStore.loadHighlights(documentId: documentId),
            savedHighlightNotes: [:],
            savedPageForms: hostStore.loadPageForms(documentId: documentId),
            savedBookmarks: [],
            strings: ReaderStrings.companion.portuguese()
        )
    }

    private func locateSampleURL(_ sample: SampleBook) -> URL? {
        Bundle.main.url(
            forResource: sample.resourceName,
            withExtension: sample.fileExtension,
            subdirectory: "Samples"
        ) ?? Bundle.main.url(
            forResource: sample.resourceName,
            withExtension: sample.fileExtension
        )
    }

    private func loadImportedBookFiles() -> [URL] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .contentModificationDateKey]
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: importedDir,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }
        let supported = Set(["epub", "hpub", "json"])
        return urls.filter { supported.contains($0.pathExtension.lowercased()) }
            .sorted { a, b in
                let dateA = (try? a.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let dateB = (try? b.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return dateA > dateB
            }
    }

    private func normalizeDeviceFileName(
        rawName: String,
        hintExtension: String?,
        headerData: Data
    ) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._-"))
        let cleaned = rawName.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" }
        let sanitized = String(cleaned).isEmpty ? "livro_importado" : String(cleaned)
        let ext = URL(fileURLWithPath: sanitized).pathExtension.lowercased()
        if ["epub", "hpub", "json"].contains(ext) {
            return sanitized
        }
        let detectedExt: String
        if let hintExtension = hintExtension {
            detectedExt = hintExtension
        } else {
            let trimmed = String(decoding: headerData, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            detectedExt = (trimmed.hasPrefix("{") || trimmed.hasPrefix("[")) ? "json" : "epub"
        }
        let stem = URL(fileURLWithPath: sanitized).deletingPathExtension().lastPathComponent
        return "\(stem.isEmpty ? "livro_importado" : stem).\(detectedExt)"
    }
}
