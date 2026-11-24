import ReaderCore
import SwiftUI
import UIKit

/// Componente SwiftUI universal *drop-in* que alterna automaticamente entre
/// `PdfReaderView` (quando `session.isPdfDocument()` é verdadeiro) e `ReaderWebView`
/// (EPUB, HPUB, CBZ, FB2 e JSON).
public struct ReaderContentView: View {
    public let session: ReaderSession
    public let schemeBase: String
    public let backgroundColor: UIColor?
    public let pdfRenderWidthPx: Int32
    public let onUiEffect: ((any ReaderUiEffect) -> Void)?

    public init(
        session: ReaderSession,
        schemeBase: String = ReaderSession.companion.DEFAULT_BOOK_BASE_URL,
        backgroundColor: UIColor? = nil,
        pdfRenderWidthPx: Int32 = 1600,
        onUiEffect: ((any ReaderUiEffect) -> Void)? = nil
    ) {
        self.session = session
        self.schemeBase = schemeBase
        self.backgroundColor = backgroundColor
        self.pdfRenderWidthPx = pdfRenderWidthPx
        self.onUiEffect = onUiEffect
    }

    public var body: some View {
        if session.isPdfDocument() {
            let pdfBg = backgroundColor.map { Color(uiColor: $0) }
                ?? Color(red: 0.118, green: 0.118, blue: 0.118)
            PdfReaderView(
                session: session,
                renderWidthPx: pdfRenderWidthPx,
                backgroundColor: pdfBg
            )
        } else {
            ReaderWebView(
                session: session,
                schemeBase: schemeBase,
                backgroundColor: backgroundColor,
                onUiEffect: onUiEffect
            )
        }
    }
}
