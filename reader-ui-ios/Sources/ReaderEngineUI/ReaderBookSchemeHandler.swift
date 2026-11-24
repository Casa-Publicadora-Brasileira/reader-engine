import Foundation
import ReaderCore
import WebKit

internal func kotlinByteArrayToData(_ kotlinBytes: KotlinByteArray) -> Data {
    let count = Int(kotlinBytes.size)
    guard count > 0 else { return Data() }
    var data = Data(count: count)
    data.withUnsafeMutableBytes { rawBuffer in
        guard let basePtr = rawBuffer.baseAddress?.assumingMemoryBound(to: Int8.self) else { return }
        for index in 0..<count {
            basePtr[index] = kotlinBytes.get(index: Int32(index))
        }
    }
    return data
}

/// Gerencia chamadas de recursos `book://content/` no `WKWebView` com suporte completo a
/// HTTP 200 OK, HTTP 206 Partial Content (streaming por range de áudio/vídeo) e HTTP 416.
public final class ReaderBookSchemeHandler: NSObject, WKURLSchemeHandler {
    private let schemeBase: String
    private var sessionProvider: () -> ReaderSession?
    private let lock = NSLock()
    private var stoppedTaskIDs = Set<ObjectIdentifier>()

    public init(
        schemeBase: String = ReaderSession.companion.DEFAULT_BOOK_BASE_URL,
        sessionProvider: @escaping () -> ReaderSession?
    ) {
        self.schemeBase = schemeBase
        self.sessionProvider = sessionProvider
        super.init()
    }

    internal func updateSessionProvider(_ provider: @escaping () -> ReaderSession?) {
        lock.lock()
        sessionProvider = provider
        lock.unlock()
    }

    private func currentSession() -> ReaderSession? {
        lock.lock()
        let provider = sessionProvider
        lock.unlock()
        return provider()
    }

    public func webView(_ webView: WKWebView, start urlSchemeTask: any WKURLSchemeTask) {
        let taskID = ObjectIdentifier(urlSchemeTask)
        guard let url = urlSchemeTask.request.url else {
            finishNotFound(urlSchemeTask, taskID: taskID, url: URL(string: schemeBase) ?? URL(string: "book://content/")!)
            return
        }

        guard url.absoluteString.hasPrefix(schemeBase),
              let session = currentSession() else {
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
        let mimeType = session.resourceMediaType(href: cleanHref) ?? Self.guessMimeType(href: cleanHref)
        let rangeHeader = urlSchemeTask.request.value(forHTTPHeaderField: "Range")

        if let rangeHeader = rangeHeader,
           rangeHeader.lowercased().hasPrefix("bytes="),
           totalSize > 0 {
            let rangeSpec = String(rangeHeader.dropFirst("bytes=".count))
                .components(separatedBy: ",")
                .first?
                .trimmingCharacters(in: .whitespaces) ?? ""
            let parts = rangeSpec.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
            let start = Int64(parts.first.map(String.init) ?? "") ?? -1
            let maxChunk: Int64 = 1024 * 1024 // 1 MB por fatia
            let requestedEnd = parts.count > 1 && !parts[1].isEmpty ? Int64(parts[1]) : nil

            if start < 0 || start >= totalSize || (requestedEnd != nil && requestedEnd! < start) {
                let headers = [
                    "Content-Range": "bytes */\(totalSize)",
                    "Accept-Ranges": "bytes"
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
            if let requestedEnd = requestedEnd {
                effectiveEnd = min(requestedEnd, totalSize - 1, start + maxChunk - 1)
            } else {
                effectiveEnd = min(start + maxChunk - 1, totalSize - 1)
            }

            let sliceLength = Int32(min(maxChunk, effectiveEnd - start + 1))
            let chunkBytes = kotlinByteArrayToData(
                session.readResourceRange(href: cleanHref, offset: start, byteCount: sliceLength)
            )
            let actualEnd = start + Int64(chunkBytes.count) - 1

            let headers: [String: String] = [
                "Content-Type": mimeType,
                "Content-Length": "\(chunkBytes.count)",
                "Content-Range": "bytes \(start)-\(actualEnd)/\(totalSize)",
                "Accept-Ranges": "bytes",
                "Access-Control-Allow-Origin": "*"
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

        let fullData = kotlinByteArrayToData(session.readResource(href: cleanHref))
        let headers: [String: String] = [
            "Content-Type": mimeType,
            "Content-Length": "\(fullData.count)",
            "Accept-Ranges": "bytes",
            "Access-Control-Allow-Origin": "*"
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

    public func webView(_ webView: WKWebView, stop urlSchemeTask: any WKURLSchemeTask) {
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

    public static func guessMimeType(href: String) -> String {
        let clean = href.components(separatedBy: "#").first?.components(separatedBy: "?").first ?? href
        let lower = clean.lowercased()
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
        if lower.hasSuffix(".ogv") { return "video/ogg" }
        if lower.hasSuffix(".mov") { return "video/quicktime" }
        if lower.hasSuffix(".mp3") { return "audio/mpeg" }
        if lower.hasSuffix(".m4a") { return "audio/mp4" }
        if lower.hasSuffix(".aac") { return "audio/aac" }
        if lower.hasSuffix(".wav") { return "audio/wav" }
        if lower.hasSuffix(".ogg") || lower.hasSuffix(".oga") { return "audio/ogg" }
        if lower.hasSuffix(".smil") || lower.hasSuffix(".xml") { return "application/xml" }
        if lower.hasSuffix(".json") { return "application/json" }
        return "application/octet-stream"
    }
}
