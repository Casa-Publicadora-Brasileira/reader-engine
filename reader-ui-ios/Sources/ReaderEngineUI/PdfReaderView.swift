import Foundation
@preconcurrency import ReaderCore
import SwiftUI
import UIKit

@MainActor
private final class PdfPageController: ObservableObject {
    @Published var pageIndex: Int32 = 0
    @Published var renderedImage: UIImage?

    private weak var boundSession: ReaderSession?
    private var stateSub: FlowSubscription?
    private var renderWidthPx: Int32 = 1600
    private var lastRenderedPage: Int32 = -1

    deinit {
        stateSub?.cancel()
    }

    func bind(session: ReaderSession, renderWidthPx: Int32) {
        self.renderWidthPx = max(320, renderWidthPx)
        guard boundSession !== session else { return }
        boundSession = session
        lastRenderedPage = -1
        renderedImage = nil
        stateSub?.cancel()
        stateSub = session.watchState { [weak self] newState in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.pageIndex = newState.pageIndex
                self.renderPageIfNeeded(session: session, pageIndex: newState.pageIndex)
            }
        }
    }

    private func renderPageIfNeeded(session: ReaderSession, pageIndex: Int32) {
        guard lastRenderedPage != pageIndex else { return }
        lastRenderedPage = pageIndex
        let targetWidth = renderWidthPx
        DispatchQueue.global(qos: .userInitiated).async { [weak self, weak session] in
            guard let session = session,
                  let pngBytes = session.renderPdfPagePng(pageIndex: pageIndex, widthPx: targetWidth, heightPx: 0) else {
                return
            }
            let data = kotlinByteArrayToData(pngBytes)
            let image = UIImage(data: data)
            DispatchQueue.main.async {
                guard let self = self, self.pageIndex == pageIndex else { return }
                self.renderedImage = image
            }
        }
    }
}

/// Componente SwiftUI *drop-in* para exibição de documentos PDF nativos via `PDFKit` (`renderPdfPagePng`)
/// com suporte a *pinch-to-zoom*, arraste e duplo toque.
public struct PdfReaderView: View {
    public let session: ReaderSession
    public let renderWidthPx: Int32
    public let minZoom: CGFloat
    public let maxZoom: CGFloat
    public let backgroundColor: Color

    @StateObject private var controller = PdfPageController()
    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    public init(
        session: ReaderSession,
        renderWidthPx: Int32 = 1600,
        minZoom: CGFloat = 1.0,
        maxZoom: CGFloat = 4.0,
        backgroundColor: Color = Color(red: 0.118, green: 0.118, blue: 0.118)
    ) {
        self.session = session
        self.renderWidthPx = renderWidthPx
        self.minZoom = minZoom
        self.maxZoom = maxZoom
        self.backgroundColor = backgroundColor
    }

    public var body: some View {
        ZStack {
            backgroundColor
            if let image = controller.renderedImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(scale)
                    .offset(offset)
                    .gesture(magnificationGesture)
                    .simultaneousGesture(panGesture)
                    .onTapGesture(count: 2) {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            if scale > 1.05 {
                                resetTransform()
                            } else {
                                scale = min(maxZoom, max(minZoom, 2.0))
                                lastScale = scale
                                offset = .zero
                                lastOffset = .zero
                            }
                        }
                    }
            } else {
                ProgressView()
                    .tint(.white)
            }
        }
        .clipped()
        .onAppear {
            controller.bind(session: session, renderWidthPx: renderWidthPx)
        }
        .onChange(of: ObjectIdentifier(session)) { _ in
            resetTransform()
            controller.bind(session: session, renderWidthPx: renderWidthPx)
        }
        .onChange(of: controller.pageIndex) { _ in
            resetTransform()
        }
    }

    private var magnificationGesture: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                let proposed = lastScale * value
                scale = min(maxZoom, max(minZoom, proposed))
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= 1.01 {
                    resetTransform()
                }
            }
    }

    private var panGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                guard scale > 1.01 else { return }
                offset = CGSize(
                    width: lastOffset.width + value.translation.width,
                    height: lastOffset.height + value.translation.height
                )
            }
            .onEnded { _ in
                if scale > 1.01 {
                    lastOffset = offset
                } else {
                    resetTransform()
                }
            }
    }

    private func resetTransform() {
        scale = 1.0
        lastScale = 1.0
        offset = .zero
        lastOffset = .zero
    }
}
