import ReaderCore
import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @ObservedObject var viewModel: ReaderViewModel

    @State private var showSearch = false
    @State private var searchQuery = ""
    @State private var showBookmarks = false
    @State private var showFileImporter = false
    @State private var pendingFilter: DeviceFileFilter = .all

    var body: some View {
        let state = viewModel.readerState
        let index = Int(state?.pageIndex ?? 0)
        let total = Int(state?.spineLen ?? 0)
        let percent = Int(state?.progress.totalPercentage ?? 0)
        let isPaginated = state?.readingMode == ReadingMode.paginated
        let subPage = Int(state?.currentSubPage ?? 0)
        let totalSubPages = Int(state?.totalSubPages ?? 1)
        let hasBookmark = state?.hasBookmarkAtCurrentPage ?? false
        let canGoPrev = state != nil && (index > 0 || (isPaginated && subPage > 0))
        let canGoNext = state != nil && (index + 1 < total || (isPaginated && subPage + 1 < totalSubPages))

        VStack(spacing: 8) {
            // Cabeçalho e Switch Modo Noite
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("reader-engine (KMP)")
                        .font(.title3.bold())
                    Text(subtitleText(for: state))
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                HStack(spacing: 6) {
                    Text("Noite")
                        .font(.caption)
                    Toggle("", isOn: Binding(
                        get: { state?.isNightMode ?? false },
                        set: { enabled in
                            viewModel.dispatch(ReaderCommandSetNightMode(enabled: enabled))
                        }
                    ))
                    .labelsHidden()
                }
            }
            .padding(.horizontal)

            // Barra de livros + Botão em destaque Abrir arquivo
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Button {
                        viewModel.showOpenBookSheet = true
                    } label: {
                        Label("Abrir arquivo", systemImage: "folder.badge.plus")
                            .font(.caption.bold())
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)

                    ForEach(viewModel.importedFiles, id: \.path) { fileURL in
                        let selected = viewModel.activeBookKey == fileURL.path
                        Button(fileURL.lastPathComponent) {
                            viewModel.openImportedFile(fileURL)
                        }
                        .font(.caption)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(selected ? Color.accentColor.opacity(0.25) : Color.secondary.opacity(0.15))
                        .cornerRadius(14)
                    }

                    ForEach(viewModel.samples) { sample in
                        let selected = viewModel.activeBookKey == sample.id
                        Button(sample.title) {
                            viewModel.openSample(sample)
                        }
                        .font(.caption)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(selected ? Color.accentColor.opacity(0.25) : Color.secondary.opacity(0.15))
                        .cornerRadius(14)
                    }
                }
                .padding(.horizontal)
            }

            // Controles de Fonte, Modo (Scroll / Paginado) e Grifar
            HStack(spacing: 10) {
                Text("Fonte")
                    .font(.caption)
                Slider(
                    value: Binding(
                        get: { Double(viewModel.fontScaleSlider) },
                        set: { viewModel.fontScaleSlider = Float($0) }
                    ),
                    in: 0.8...1.8,
                    onEditingChanged: { editing in
                        if !editing {
                            viewModel.dispatch(ReaderCommandSetFontScale(scale: viewModel.fontScaleSlider))
                        }
                    }
                )
                Button(isPaginated ? "Paginado" : "Scroll") {
                    let next = isPaginated ? ReadingMode.scroll : ReadingMode.paginated
                    viewModel.dispatch(ReaderCommandSetReadingMode(mode: next))
                }
                .buttonStyle(.bordered)
                .controlSize(.small)

                Button("Grifar") {
                    viewModel.dispatch(ReaderCommandApplyHighlightStyle(style: HighlightStyle.yellow))
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
            .padding(.horizontal)

            // Barra de busca expansível
            VStack(spacing: 6) {
                Button {
                    showSearch.toggle()
                } label: {
                    HStack {
                        Image(systemName: "magnifyingglass")
                        Text("Buscar no livro")
                        Spacer()
                        Image(systemName: showSearch ? "chevron.up" : "chevron.down")
                    }
                    .font(.caption)
                    .foregroundColor(.primary)
                }
                if showSearch {
                    HStack {
                        TextField("Digite sua busca...", text: $searchQuery)
                            .textFieldStyle(.roundedBorder)
                            .onSubmit {
                                viewModel.search(query: searchQuery)
                            }
                        Button("Buscar") {
                            viewModel.search(query: searchQuery)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    }
                    if !viewModel.searchResults.isEmpty {
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 6) {
                                ForEach(Array(viewModel.searchResults.enumerated()), id: \.offset) { _, result in
                                    Button {
                                        if let locator = result.locator {
                                            viewModel.dispatch(ReaderCommandGoToLocator(locator: locator))
                                        } else {
                                            viewModel.dispatch(ReaderCommandGoToPage(pageIndex: result.pageIndex, scrollY: 0))
                                        }
                                        showSearch = false
                                    } label: {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(result.chapterTitle)
                                                .font(.caption.bold())
                                                .lineLimit(1)
                                            Text(result.snippet)
                                                .font(.caption2)
                                                .foregroundColor(.secondary)
                                                .lineLimit(2)
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(8)
                                        .background(Color.secondary.opacity(0.12))
                                        .cornerRadius(8)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                        .frame(maxHeight: 140)
                    }
                }
            }
            .padding(.horizontal)

            // Marcadores salvos
            let bookmarks = state?.bookmarks ?? []
            if !bookmarks.isEmpty {
                VStack(spacing: 4) {
                    Button {
                        showBookmarks.toggle()
                    } label: {
                        HStack {
                            Text("Marcadores (\(bookmarks.count))")
                            Spacer()
                            Image(systemName: showBookmarks ? "chevron.up" : "chevron.down")
                        }
                        .font(.caption)
                    }
                    if showBookmarks {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(bookmarks, id: \.id) { bm in
                                    HStack(spacing: 4) {
                                        Button {
                                            viewModel.dispatch(ReaderCommandGoToBookmark(bookmarkId: bm.id))
                                        } label: {
                                            Label(bm.label ?? "Página \(bm.pageIndex + 1)", systemImage: "star.fill")
                                                .font(.caption2)
                                        }
                                        Button {
                                            viewModel.dispatch(ReaderCommandRemoveBookmark(bookmarkId: bm.id))
                                        } label: {
                                            Image(systemName: "xmark.circle.fill")
                                                .font(.caption2)
                                        }
                                    }
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(Color.secondary.opacity(0.15))
                                    .cornerRadius(12)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal)
            }

            // Resumo de eventos & barra de progresso
            HStack {
                Text("Evento KMP: \(viewModel.lastEventSummary)")
                    .font(.caption2)
                    .foregroundColor(.accentColor)
                    .lineLimit(1)
                Spacer()
            }
            .padding(.horizontal)

            if let err = viewModel.errorMessage {
                Text(err)
                    .font(.caption)
                    .foregroundColor(.red)
                    .padding(.horizontal)
            }

            ProgressView(value: min(max(Double(state?.progress.totalPercentage ?? 0) / 100.0, 0), 1))
                .padding(.horizontal)

            // WebView de renderização do livro
            ReaderWebView(
                viewModel: viewModel,
                document: viewModel.renderedDocument,
                isNightMode: state?.isNightMode ?? false
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            // Barra inferior de paginação
            HStack(spacing: 12) {
                Button {
                    viewModel.dispatch(ReaderCommandToggleBookmark(label: nil, timestampEpochMs: Int64(Date().timeIntervalSince1970 * 1000)))
                } label: {
                    Image(systemName: hasBookmark ? "star.fill" : "star")
                        .font(.title3)
                }
                .disabled(state == nil)

                Button("Anterior") {
                    viewModel.dispatch(ReaderCommandPrevPage.shared)
                }
                .buttonStyle(.bordered)
                .disabled(!canGoPrev)

                Spacer()
                VStack(spacing: 2) {
                    Text(pageLabel(state: state, index: index, total: total, isPaginated: isPaginated, subPage: subPage, totalSubPages: totalSubPages))
                        .font(.subheadline.bold())
                    Text(state == nil ? "" : "\(percent)% concluído")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
                Spacer()

                Button("Próxima") {
                    viewModel.dispatch(ReaderCommandNextPage.shared)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!canGoNext)
            }
            .padding(.horizontal)
            .padding(.bottom, 6)
        }
        .padding(.top, 6)
        .sheet(isPresented: $viewModel.showOpenBookSheet) {
            OpenBookSheet(
                importedFiles: viewModel.importedFiles,
                samples: viewModel.samples,
                activeBookKey: viewModel.activeBookKey,
                onPickFilter: { filter in
                    pendingFilter = filter
                    viewModel.showOpenBookSheet = false
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                        showFileImporter = true
                    }
                },
                onOpenImported: { fileURL in
                    viewModel.openImportedFile(fileURL)
                },
                onDeleteImported: { fileURL in
                    viewModel.deleteImportedFile(fileURL)
                },
                onOpenSample: { sample in
                    viewModel.showOpenBookSheet = false
                    viewModel.openSample(sample)
                }
            )
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: pendingFilter.allowedContentTypes,
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    viewModel.importFromDevice(url: url, hintExtension: pendingFilter.defaultExtension)
                }
            case .failure(let error):
                viewModel.errorMessage = error.localizedDescription
            }
        }
    }

    private func subtitleText(for state: ReaderState?) -> String {
        guard let state = state else { return "Abra um arquivo .epub / .hpub / .json" }
        let authors = state.metadata.authors.joined(separator: ", ")
        return authors.isEmpty ? state.metadata.title : "\(state.metadata.title) · \(authors)"
    }

    private func pageLabel(
        state: ReaderState?,
        index: Int,
        total: Int,
        isPaginated: Bool,
        subPage: Int,
        totalSubPages: Int
    ) -> String {
        guard state != nil else { return "-" }
        if isPaginated && totalSubPages > 1 {
            return "\(index + 1) / \(total) (\(subPage + 1)/\(totalSubPages))"
        }
        return "\(index + 1) / \(total)"
    }
}

private struct OpenBookSheet: View {
    @Environment(\.dismiss) private var dismiss

    let importedFiles: [URL]
    let samples: [SampleBook]
    let activeBookKey: String?
    let onPickFilter: (DeviceFileFilter) -> Void
    let onOpenImported: (URL) -> Void
    let onDeleteImported: (URL) -> Void
    let onOpenSample: (SampleBook) -> Void

    var body: some View {
        NavigationView {
            List {
                Section("Do seu dispositivo") {
                    ForEach(DeviceFileFilter.allCases) { filter in
                        Button {
                            onPickFilter(filter)
                        } label: {
                            HStack(spacing: 12) {
                                Text(filter.badge)
                                    .font(.caption2.bold())
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(Color.accentColor.opacity(0.18))
                                    .foregroundColor(.accentColor)
                                    .cornerRadius(6)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(filter.title)
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundColor(.primary)
                                    Text(filter.subtitle)
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                            }
                        }
                    }
                }

                if !importedFiles.isEmpty {
                    Section("Recentes do dispositivo") {
                        ForEach(importedFiles, id: \.path) { fileURL in
                            HStack {
                                Button {
                                    onOpenImported(fileURL)
                                } label: {
                                    HStack {
                                        Text(fileURL.pathExtension.uppercased())
                                            .font(.caption2.bold())
                                            .foregroundColor(.accentColor)
                                        Text(fileURL.lastPathComponent)
                                            .font(.subheadline)
                                            .foregroundColor(.primary)
                                            .lineLimit(1)
                                        Spacer()
                                        if activeBookKey == fileURL.path {
                                            Image(systemName: "checkmark.circle.fill")
                                                .foregroundColor(.accentColor)
                                        }
                                    }
                                }
                                Button(role: .destructive) {
                                    onDeleteImported(fileURL)
                                } label: {
                                    Image(systemName: "trash")
                                        .font(.caption)
                                }
                                .buttonStyle(.borderless)
                            }
                        }
                    }
                }

                Section("Exemplos inclusos no app") {
                    ForEach(samples) { sample in
                        Button {
                            onOpenSample(sample)
                        } label: {
                            HStack {
                                Text(sample.title)
                                    .font(.subheadline)
                                    .foregroundColor(.primary)
                                Spacer()
                                Text(sample.fileExtension.uppercased())
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                                if activeBookKey == sample.id {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundColor(.accentColor)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Abrir publicação")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Fechar") {
                        dismiss()
                    }
                }
            }
        }
    }
}
