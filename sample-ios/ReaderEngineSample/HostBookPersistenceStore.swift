import Foundation
import ReaderCore

/// Armazenamento local simples em UserDefaults espelhando o `HostBookPersistenceStore` do sample Android.
final class HostBookPersistenceStore {
    private let defaults: UserDefaults

    init(suiteName: String = "com.readerengine.ios.sample.store") {
        self.defaults = UserDefaults(suiteName: suiteName) ?? .standard
    }

    func saveProgress(_ progress: ReadingProgress) {
        let id = progress.documentId
        defaults.set(Int(progress.pageIndex), forKey: "\(id)_page")
        defaults.set(Int(progress.subPage), forKey: "\(id)_subPage")
        defaults.set(Int(progress.totalPages), forKey: "\(id)_total")
        defaults.set(Int(progress.scrollY), forKey: "\(id)_scrollY")
        defaults.set(Double(progress.totalPercentage), forKey: "\(id)_percent")
    }

    func loadProgress(documentId: String) -> ReadingProgress? {
        guard defaults.object(forKey: "\(documentId)_page") != nil else {
            return nil
        }
        let page = Int32(defaults.integer(forKey: "\(documentId)_page"))
        let subPage = Int32(defaults.integer(forKey: "\(documentId)_subPage"))
        let total = Int32(max(1, defaults.integer(forKey: "\(documentId)_total")))
        let scrollY = Int32(defaults.integer(forKey: "\(documentId)_scrollY"))
        let percent = Float(defaults.double(forKey: "\(documentId)_percent"))
        return ReadingProgress(
            documentId: documentId,
            pageIndex: page,
            pageNumber: page + 1,
            totalPages: total,
            scrollY: scrollY,
            subPage: subPage,
            pageProgression: 0,
            totalPercentage: percent,
            chapterTitle: "",
            lastUpdateEpochMs: 0,
            locator: nil
        )
    }

    func savePageHighlight(documentId: String, pageIndex: Int32, compressedPayload: String) {
        defaults.set(compressedPayload, forKey: "\(documentId)_hl_\(pageIndex)")
    }

    func loadHighlights(documentId: String) -> [KotlinInt: String] {
        let prefix = "\(documentId)_hl_"
        var result: [KotlinInt: String] = [:]
        for (key, value) in defaults.dictionaryRepresentation() {
            if key.hasPrefix(prefix),
               let payload = value as? String,
               !payload.isEmpty,
               let pageIndex = Int32(key.dropFirst(prefix.count)) {
                result[KotlinInt(int: pageIndex)] = payload
            }
        }
        return result
    }

    func savePageForm(documentId: String, pageIndex: Int32, compressedPayload: String) {
        defaults.set(compressedPayload, forKey: "\(documentId)_form_\(pageIndex)")
    }

    func loadPageForms(documentId: String) -> [KotlinInt: String] {
        let prefix = "\(documentId)_form_"
        var result: [KotlinInt: String] = [:]
        for (key, value) in defaults.dictionaryRepresentation() {
            if key.hasPrefix(prefix),
               let payload = value as? String,
               !payload.isEmpty,
               let pageIndex = Int32(key.dropFirst(prefix.count)) {
                result[KotlinInt(int: pageIndex)] = payload
            }
        }
        return result
    }

    func saveBookmarksJson(documentId: String, json: String) {
        defaults.set(json, forKey: "\(documentId)_bookmarks")
    }

    func loadBookmarksJson(documentId: String) -> String? {
        defaults.string(forKey: "\(documentId)_bookmarks")
    }

    func savePreferences(
        fontScale: Float,
        isNightMode: Bool,
        fontFamily: String,
        readingMode: ReadingMode
    ) {
        defaults.set(Double(fontScale), forKey: "pref_font_scale")
        defaults.set(isNightMode, forKey: "pref_night_mode")
        defaults.set(fontFamily, forKey: "pref_font_family")
        defaults.set(readingMode.name, forKey: "pref_reading_mode")
    }

    func loadFontScale() -> Float {
        guard defaults.object(forKey: "pref_font_scale") != nil else {
            return 1.0
        }
        return Float(defaults.double(forKey: "pref_font_scale"))
    }

    func loadNightMode() -> Bool {
        defaults.bool(forKey: "pref_night_mode")
    }

    func loadFontFamily() -> String {
        defaults.string(forKey: "pref_font_family") ?? "Default"
    }

    func loadReadingMode() -> ReadingMode {
        let raw = defaults.string(forKey: "pref_reading_mode") ?? ""
        return raw == ReadingMode.paginated.name ? ReadingMode.paginated : ReadingMode.scroll
    }
}

extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            result.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return result
    }
}

extension KotlinByteArray {
    func toData() -> Data {
        let length = Int(size)
        guard length > 0 else { return Data() }
        var bytes = [UInt8](repeating: 0, count: length)
        for index in 0..<length {
            bytes[index] = UInt8(bitPattern: get(index: Int32(index)))
        }
        return Data(bytes)
    }
}
