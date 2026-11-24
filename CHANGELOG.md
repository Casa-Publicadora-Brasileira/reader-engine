# Changelog

Todas as mudanças relevantes deste projeto são documentadas neste arquivo.

## [1.2.0] - 2026-11-24

### Added
- **Motor de Highlights Multi-Parágrafo & Sobreposição (`ReaderJsBridge`):**
  - Substituído `range.surroundContents()` por segmentação multi-nó via `TreeWalker` + `splitText` em `FALLBACK_READER_JS`, suportando grifos que atravessam múltiplos `<p>`, tags inline aninhadas (`<em>`, `<strong>`, `<a>`) e grifos sobrepostos sem quebrar o DOM.
  - Restauração inteligente de grifos no DOM (`_applyTextHighlightInDom`) com suporte multi-nó e desambiguação contextual via `textBefore` e `textAfter` (W3C Web Annotation).
- **`Locator` W3C de Precisão Editorial (`Models.kt` & `BookSearchEngine.kt`):**
  - Novos campos opcionais em `Locator`: `textBefore`, `textAfter`, `cssSelector`, `position` (1-based alinhado a `Publication.positions()`) e `totalProgression`, mantendo construtor secundário de 5 argumentos para retrocompatibilidade binária e Swift.
  - Preenchimento automático em `Publication.positions()`, `BookSearchEngine.search()`, `ReaderSession.buildProgress()`, `ReaderJsBridge.parseSelectionContextPayload()` e `parseSummaryHighlights()`.
- **Evolução do `ThemeTokens` para Nível `ReadiumCSS`:**
  - Novo modelo `CustomFontFace(family, srcUrl, weight, style, format)` com sanitização CSS contra XSS, injetando regras `@font-face` automaticamente.
  - Toggle `ThemeTokens.publisherStyles: Boolean = false` para alternar entre o estilo editorial original do EPUB e a tipografia customizada do leitor.
  - Proteção de quebras na paginação CSS (`break-inside: avoid` para `figure`, `table`, `pre`, `blockquote` e `overflow-x: auto` em tabelas).
- **Orquestrador de Sincronização Áudio/Texto (`MediaOverlaySynchronizer`):**
  - Nova classe `MediaOverlaySynchronizer`, modelo `MediaOverlaySyncResult` e atalhos em `ReaderSession` (`syncMediaOverlayAt`, `findMediaOverlayClipForElement`, `resetMediaOverlaySync`), com troca automática de capítulo, deduplicação de eventos por clipe, destaque no DOM e auto-virada de sub-página no modo `PAGINATED`.

## [1.1.0] - 2026-11-23

### Added
- **Suporte Completo a Audiobooks (`com.readerengine.core.audiobook`):**
  - Novo `AudiobookParser` em `commonMain` suportando **Readium Audiobook (`.audiobook` / `manifest.json`)**, pacotes ZIP de áudio (`.abz` / `.zip` com ordenação numérica natural de `.mp3`/`.m4a`/`.m4b`/`.aac`/`.ogg`/`.opus`/`.wav`/`.flac` e capa automática) e manifestos `.audiobook.json`.
  - Nova máquina de estados reativa `AudiobookSession` com `AudioTrack`, `AudioChapter` (timestamps `#t=`), `AudioBookmark`, controle de velocidade (`0.5x..3.0x`), *sleep timer*, streaming zero-copy por range (`readTrackRange`, `trackSize` pronto para Media3/ExoPlayer e AVPlayer), `FlowSubscription` (`watchState`/`watchEvents`/`watchUiEffects`), `exportAudiobookSnapshot` / `mergeAudiobookSnapshot` e atalhos `ReaderEngine.openAudiobook(...)`.
- **Suporte Híbrido Nativo a PDF (`com.readerengine.core.pdf` + `PdfParser`):**
  - Novo `PdfParser` registrado no `ParserRegistry.default()` com `PdfDocumentBackend` (`expect/actual`) usando **zero dependências externas**:
    - **Android (`androidMain`)**: `android.graphics.pdf.PdfRenderer` nativo com renderização PNG sob demanda.
    - **iOS (`iosMain`)**: `PDFKit` nativo (`PDFDocument`, `PDFPage`, `PDFOutline` para sumário hierárquico, extração de texto nativo para `BookSearchEngine` e renderização PNG).
    - **JVM (`jvmMain`)**: scanner estrutural leve (`%PDF-`, `/Info`, `/Type /Page`) + suporte a backend/extrator plugável (`PdfTextExtractor`).
  - Novos métodos em `ReaderSession`: `isPdfDocument()`, `pdfPageSize(pageIndex)` e `renderPdfPagePng(pageIndex, widthPx, heightPx)`.

## [1.0.0] - 2026-11-23

### Added
- **Acessibilidade Editorial Profunda & Presets de Tema (`ThemeTokens`):**
  - Novos parâmetros `letterSpacingEm`, `wordSpacingEm` e `paragraphSpacingEm` em `ThemeTokens.Typography`, e `maxLineWidthPx` em `ThemeTokens.Shape` para leitura confortável em tablets/desktop e acessibilidade WCAG/DAISY.
  - Novos presets prontos: `ThemeTokens.sepia()`, `ThemeTokens.amoledDark()` e `ThemeTokens.dyslexiaFriendly()`.
- **Sincronização Multi-Dispositivo com Merge Inteligente (`mergeBookStateSnapshot`):**
  - Novos tipos `ProgressMergePolicy` (`NEWEST_TIMESTAMP`, `FURTHEST_PROGRESS`, `KEEP_LOCAL`, `PREFER_REMOTE`) e `SnapshotMergeResult`.
  - Método `ReaderSession.mergeBookStateSnapshot(remotePayload, progressPolicy)` realizando união determinística sem perda de dados de bookmarks, highlights, notas e formulários, resolvendo o progresso conforme a política escolhida.
- **Seleção Contextual Enriquecida (`SelectionContext`):**
  - Novos modelos `SelectionRect` e `SelectionContext(selectedText, contextSentence, pageIndex, rect, locator)`, ação `ReaderJsBridge.ACTION_SELECTION_CHANGED`, comando `SCRIPT_GET_SELECTION_CONTEXT` e efeito `ReaderUiEffect.SelectionChanged` para dicionários, tradução e menus flutuantes nativos.
- **Pré-aquecimento de Capítulos Adjacentes (`prefetchAdjacentChapters`):**
  - Novo método `ReaderSession.prefetchAdjacentChapters(radius = 1)` instrumentado via span `session.prefetch_adjacent` para viradas de capítulo sem latência.
- **Paridade iOS (`sample-ios`):**
  - `OSLogReaderObserver` (`os.Logger`) conectado ao `ReaderEngine` em `ReaderViewModel.swift` e tratamento nativo de `ReaderUiEffectImageActivated` e `ReaderUiEffectSelectionChanged`.
- **CI Multiplataforma (Linux + macOS iOS) & Testes de Concorrência:**
  - Job `ios` (`macos-15`) adicionado ao `.github/workflows/ci.yml` validando `:reader-core:assembleReaderCoreDebugXCFramework` e `xcodebuild` do `sample-ios` em todo Push/PR.
  - Testes de concorrência multi-coroutine e entradas Unicode no `ZipResourceProvider`.

## [0.9.0] - 2026-11-19

### Added
- **Sistema de Observabilidade Multiplataforma (`com.readerengine.core.observability`):**
  - SPI plugável `ReaderObserver` (Logs estruturados + Spans monotônicos + Métricas) com implementação padrão `ReaderObserver.NoOp` (zero overhead), fan-out resiliente `CompositeReaderObserver`, ring buffer `InMemoryDiagnosticsBuffer` e helper `ReaderObservability.measureSpan(...)` baseado em `kotlin.time.TimeSource.Monotonic`.
  - Instrumentação automática em `ReaderEngine` (`book.open`, `book.spine_pages`) e `ReaderSession` (`session.render_document`, `search.query`, `search.results_count`).
  - Estatísticas em tempo real do cache LRU (`ZipCacheStats`: `hits`, `misses`, `evictions`, `entryCount`, `currentBytes`, `maxBytes`, `hitRate`) em `ZipResourceProvider` com delegação transparente em `DecryptingResourceProvider` e `CbzResourceProvider`.
- **Observabilidade dentro da WebView (JS Runtime Errors & Pagination Metrics):**
  - Novas ações `ReaderJsBridge.ACTION_JS_ERROR` (`window.onerror` e `unhandledrejection`) e `ReaderJsBridge.ACTION_JS_METRIC` (`webview.pagination_ms` medido em `_computeTotalSubPages()`) roteadas automaticamente para o `ReaderObserver` pelo `ReaderSession`.
- **Snapshot de Diagnóstico Técnico (`diagnosticsSnapshot`):**
  - Novos métodos `ReaderSession.diagnosticsSnapshot()` e `ReaderSession.exportDiagnosticsJson()` retornando versão do SDK, estado de leitura, `ZipCacheStats` e últimos logs/spans/métricas em memória para suporte e depuração.
- **Exemplo no `sample-android`:**
  - `LogcatReaderObserver` conectado ao `ReaderEngine` em `MainActivity.kt`.

## [0.8.0] - 2026-11-19

### Added
- **Navegação Reversa Inteligente (`LastSubPage`) e Histórico de Navegação (`NavigationBackstack`):**
  - No modo `PAGINATED`, retroceder capítulo (`PrevPage` ou gesto `reader_gesture_prev_page`) abre automaticamente o capítulo anterior na **última sub-página** (`data-reader-subpage="last"`, `ReaderJsBridge.INITIAL_SUB_PAGE_LAST` e `readerJS.goToLastSubPage()`).
  - Novo modelo `NavigationEntry`, campos `canNavigateBack`, `canNavigateForward` e `previousLocation` em `ReaderState`, e comandos `ReaderCommand.NavigateBack` / `ReaderCommand.NavigateForward` para voltar à posição exata após saltos não-lineares (TOC, links internos, busca, slider ou bookmarks).
- **Navegação e Destaque Visual para Resultados de Busca (`Search Match Highlight`):**
  - Campo `SearchResult.occurrenceInPage` calculado em `BookSearchEngine.search(...)`.
  - Novos comandos `ReaderCommand.NavigateToSearchResult(query, pageIndex, occurrenceInPage)` e `ReaderCommand.ClearSearchHighlight`, funções JS `readerJS.scrollToSearchMatch(...)` / `readerJS.clearSearchHighlight()` (com suporte a rolagem no modo `SCROLL` e troca automática de sub-página no modo `PAGINATED`) e estilo `.reader-search-match` em `ThemeTokens.toContentCss()`.
- **Zoom / Lightbox Nativo de Imagens e Figuras (`Image Tap Inspection`):**
  - Novo modelo `ImageActivation(src, resolvedHref, alt, caption)` e efeito `ReaderUiEffect.ImageActivated`.
  - Interceptação de toques em `<img>` / `<figure>` em `ReaderJsBridge` (`ACTION_IMAGE_TAP = "reader_image_tap"`), preservando `alt` e `<figcaption>` sem disparar virada de página lateral.
- **Exportador de Caderno de Estudos e Citações (`StudyNotebookExporter`):**
  - Novos métodos em `ReaderSession`: `exportStudyNotebookMarkdown()`, `exportStudyNotebookHtml()` (com `@media print` pronto para impressão/PDF) e `formatQuoteCitation(highlight)` com referência bibliográfica automática (título, autores, capítulo e página impressa do `pageList`).
- **Tipografia Editorial Avançada (`Hyphenation`, `lang` Automático, `Widows/Orphans`):**
  - Novos parâmetros `hyphens: Boolean = true`, `orphans: Int = 2` e `widows: Int = 2` em `ThemeTokens.Typography`, gerando `hyphens: auto; -webkit-hyphens: auto; orphans; widows;` em `ThemeTokens.toContentCss()`.
  - Injeção automática do atributo `lang` em `<html>` a partir de `Metadata.language` em `ReaderJsBridge.injectThemeAndBridge(...)` e `ReaderSession.currentDocument(...)`.
- **Suporte Nativo a FictionBook 2 (`Fb2Parser`):**
  - Novo parser `.fb2` e `.fb2.zip` registrado em `ParserRegistry.default()` reutilizando `XmlTokenizer`: extrai metadados (`<title-info>`), capa e imagens Base64 (`<binary>`), capítulos (`<section>`), sumário (`toc`) e notas de rodapé (`<body name="notes">`) integradas ao popup `epub:type="footnote"`.

## [0.7.0] - 2026-11-19

### Added
- **Tokenizer e Árvore XML Determinística em `commonMain` (`XmlTokenizer.kt` — Resolve GAP-03):**
  - Novo scanner/parser XML/XHTML em passagem única (`XmlTokenizer`, `XmlToken`, `XmlElement`), suportando comentários (`<!-- ... -->`) com tags falsas, seções `<![CDATA[ ... ]]>`, processing instructions (`<?...?>`), declarações `<!DOCTYPE ...>` com subsets `[...]`, namespaces e atributos multilinhas contendo `>`.
  - `EpubParser`, `EpubEncryptionParser` (`ContentProtection.kt`) e `SmilParser` migrados integralmente de expressões regulares frágeis para `XmlTokenizer.parseDocument(...)`.
- **Suporte a Quadrinhos e Mangás (`CbzParser.kt`):**
  - Novo formato `.cbz` registrado no `ParserRegistry.default()`, com detecção de imagens (`.jpg`, `.jpeg`, `.png`, `.webp`, `.gif`, `.bmp`, `.avif`, `.svg`), filtragem de `__MACOSX/` e arquivos ocultos, e ordenação numérica natural (`naturalCompare`, ex.: `page2.jpg` antes de `page10.jpg`).
  - Parse opcional de `ComicInfo.xml` via `XmlTokenizer` (`Title`, `Series`, `Number`, autores, idioma e `<Manga>Yes`/`YesAndRightToLeft` -> `PageProgressionDirection.RTL`), geração de páginas fixas XHTML (`RenditionLayout.PRE_PAGINATED`) e resolução transparente de caminhos percent-encoded no `CbzResourceProvider`.
- **Suporte a ZIP64 no `ZipResourceProvider`:**
  - Leitura dos registros **ZIP64 EOCD Locator** (`0x07064b50`), **ZIP64 EOCD Record** (`0x06064b50`) e **ZIP64 Extended Information Extra Field** (`0x0001`) para offsets, tamanhos e contagens de entrada 64-bit, mantendo compatibilidade com `read()`, `size()` e `readRange()`.

### Fixed
- **`ReaderJsBridge` e `EpubParser`**: Removidas sobrecargas conflitantes (`goToSubPageCommand` e `parseNcxPageList`) remanescentes de conflito de merge no Git.
- **`ParserRegistry.open(source)`**: preservados `encryptedResources`, `pageList`, `landmarks` e `mediaOverlays` ao preencher `identifier = source.sha256DocumentId()` para publicações sem identificador explícito.

## [0.6.0] - 2026-11-19

### Added
- **Contrato Multiplataforma de Leitura em Voz Alta (TTS):**
  - Modelo `TtsSentence` e métodos `ReaderSession.currentChapterSentences()` / `chapterSentences(pageIndex)` com extração limpa de sentenças, offsets e `Locator`.
  - Novos comandos `ReaderCommand.HighlightTtsSentence(sentenceIndex, text, elementId)` e `ReaderCommand.ClearTtsHighlight`, ponte JS `readerJS.highlightTtsSentence(...)` / `readerJS.clearTtsHighlight()` e classe CSS `.reader-tts-active` em `ThemeTokens.toContentCss()` (com contraste adaptado para modo claro e escuro).
- **EPUB 3 Media Overlays (Parser `.smil`):**
  - Novo `SmilParser` (SMIL 3.0) extraindo clipes sincronizados `<par>` (`<text src="...#id"/>` + `<audio src="..." clipBegin="..." clipEnd="..."/>`) com suporte completo a clock values (`hh:mm:ss.fff`, `mm:ss.fff`, `s`, `ms`, `min`, `h`).
  - Descoberta automática de arquivos `.smil` (`media-overlay` e `application/smil+xml`) em `EpubParser`, expostos em `Publication.mediaOverlays`, `ReaderSession.currentChapterMediaOverlayClips()`, `mediaOverlayClips(pageIndex)` e `findMediaOverlayClipAt(audioHref, positionSeconds)`.
- **Descriptografia por Blocos em `readRange` (Resolve GAP-02):**
  - Método opcional `ContentDecryptor.decryptRange(...)` para streaming HTTP 206 de mídias criptografadas sem alocar o arquivo inteiro na RAM.
  - Implementação nativa de `decryptRange` em `StandardEpubFontDeobfuscator` (IDPF e Adobe) e delegação automática em `DecryptingResourceProvider.readRange`.
- **Índice de Busca Persistente em Disco (`Okio Path`):**
  - Novo modelo `PersistedSearchIndex` e métodos `BookSearchEngine.buildIndex`, `exportIndexJson`, `importIndexJson`, `saveIndexToDisk` e `loadIndexFromDisk`.
  - Atalhos ergonômicos em `ReaderSession`: `exportSearchIndexJson()`, `importSearchIndexJson(rawJson)`, `saveSearchIndex(path)` e `loadSearchIndex(path)` para busca instantânea na reabertura de livros grandes.

## [0.5.1] - 2026-11-18

### Added
- **Detecção de Gestos (`Swipe` & `Touch/Tap`) para Passagem de Página:**
  - Hook `__readerGestureHooked` e `window.readerJS.handlePageTurnGesture` em `ReaderJsBridge`, suportando arrasto horizontal (`swipe`) em `PAGINATED` e `SCROLL`, toques nas zonas laterais esquerda/direita em `PAGINATED` (respeitando `dir="rtl"`), toque central (`reader_gesture_tap_center`) e preservação de links, inputs, notas de rodapé e seleção de texto ativa.
  - Tratamento automático em `ReaderSession` para `reader_sub_page_changed`, `reader_gesture_next_page` e `reader_gesture_prev_page` tanto via `ReaderCommand.TriggerAction` quanto via `handleUrlNavigation("action://...")`.
  - Interceptação de custom schemes disparados via JS (`action://`, `footnote://`, `verse://`) no `WKWebView` em `sample-ios`.

### Fixed
- **Colapso de altura no modo paginado (`100vh = 0px` em WebViews `WRAP_CONTENT`):** substituído por `--reader-page-height` calculado dinamicamente com `overflow: visible` no `body`, injeção automática de `<meta name="viewport">` e remoção de tags `<head>` na extração de texto para `getPagePreview` e `positions()`.

## [0.5.0] - 2026-11-18

### Added
- **Tema Dark, Tipografia e Layout em `ThemeTokens`:** regras `!important` preservando `.highlight_*`, `Typography.textAlign = "justify"` e `Shape.horizontalPaddingPx = 24`, além de suporte a `<html>` sem atributos no `ReaderJsBridge`.
- **Lista Global de Posições (`Publication.positions()`):** divisão determinística em blocos de 1.024 caracteres estilo Readium Positions.
- **Suporte a `page-list` e `landmarks`:** extração de `<nav epub:type="page-list">`, `<nav epub:type="landmarks">` (EPUB 3) e `<pageList>` (NCX EPUB 2).
- **Notas de Rodapé em Popup:** interceptação automática de links `epub:type="noteref"` / `role="doc-noteref"` emitindo `ReaderUiEffect.OpenCustomUri(scheme = "footnote", ...)`.
- **Spreads de Página Dupla (`RenditionSpread.LANDSCAPE` / `BOTH`)** em CSS multi-coluna.
- **Telemetria de Leitura (`ReadingStats`)** e **Helper de Preview de Capítulo (`ReaderSession.getPagePreview` / `pagePreview`)**.

## [0.4.0] - 2026-11-17

### Added
- **Persistência de Sub-página (Resolve GAP-01):** `InitialBookState.initialSubPage`, `ReadingProgress.subPage` e `ReaderJsBridge.goToSubPageCommand(index)`.
- **Descoberta e Extração de Capa:** `Metadata.coverHref` em `EpubParser`/`HpubParser` e `ReaderSession.readCoverBytes()`.
- **Notas em Destaques:** `HighlightItem.note`, `InitialBookState.savedHighlightNotes` e `ReaderCommand.UpdateHighlightNote`.
- **Suporte a Idiomas RTL (`PageProgressionDirection`):** extração de `page-progression-direction="rtl"`, injeção de `dir="rtl"` e ajuste de `translateX`.
- **Snapshot Único de Estado (`BookStateSnapshot`):** `session.exportBookStateSnapshot()` e `session.importBookStateSnapshot(payload)`.
- **Limpeza de Dívidas Técnicas (Resolve GAP-04 e GAP-05):** `PayloadCodec` unificado em `commonMain` com Okio e modularização do `sample-android`.

## [0.3.1] - 2026-11-08

### Added
- **Streaming de Vídeo e Áudio HTML5 com Suporte a Range Requests (HTTP 206 Partial Content)**:
  - Implementado `ResourceProvider.size(href)` e `ResourceProvider.readRange(href, offset, byteCount)` em `MemoryResourceProvider`, `DecryptingResourceProvider` e `ZipResourceProvider`.
  - No `ZipResourceProvider`, fatiamento direto em `RandomAccessSource` para entradas descompactadas (`STORED`), permitindo seek instantâneo sem buffering ou alocação total do arquivo em memória RAM.
  - Expansão dos tipos MIME suportados para multimídia (`.mp4`, `.m4v`, `.webm`, `.ogv`, `.mov`, `.mp3`, `.m4a`, `.aac`, `.wav`, `.ogg`, `.oga`) no `HpubParser` e no leitor Android.
  - Interceptação de cabeçalhos `Range: bytes=start-end` em `BookResourceWebViewClient.shouldInterceptRequest`, retornando respostas HTTP 206 com `Content-Range`, `Accept-Ranges: bytes` e `Content-Length`.
  - Suporte completo a vídeo em tela cheia na WebView com `WebChromeClient` (`onShowCustomView` / `onHideCustomView`) e overlay com `BackHandler` no Jetpack Compose.
  - Habilitação de reprodução de mídia sem exigência de gesto do usuário (`settings.mediaPlaybackRequiresUserGesture = false`).
- **Mecanismo de Ações Interativas (Bridge E-book <-> App Host)**:
  - Interceptação do esquema de URL `action://<nome>?q=<payload>` em `ReaderJsBridge.parseSpecialUrl` gerando `InterceptedUrl.Action`.
  - Polyfill injetado `window.ReaderEngine.postAction(name, payload)` para disparo via JavaScript interno dos capítulos.
  - Interface nativa JavaScript `@JavascriptInterface` (`AndroidActionBridge`) integrada na WebView Android.
  - Novo comando `ReaderCommand.TriggerAction(action, payload)`, evento `ReaderEvent.ActionTriggered(action, payload)` e efeito `ReaderUiEffect.CustomAction(action, payload)`.
- **Atualização da Toolchain de Build**:
  - Atualizado o **Android Gradle Plugin (AGP)** para `9.5.1` (`libs.versions.toml`).
  - Atualizado o **Gradle Wrapper** para `9.9.0` (`gradle-wrapper.properties`).
  - Atualizado o **Kotlin** para `2.4.20` (`libs.versions.toml`) com compatibilidade KMP configurada via propriedades de transição no `gradle.properties`.

## [0.3.0] - 2026-11-07

### Added
- **Streaming de Arquivos Grandes em Disco (`Okio FileSystem` + `FileHandle`)**:
  - `ReaderEngine.open(path: Path, fileSystem: FileSystem, initialState: InitialBookState)` e `ZipResourceProvider.fromPath(...)`, lendo apenas o EOCD e o Central Directory por acesso aleatório sem carregar arquivos `.epub` / `.hpub` de centenas de MB na memória RAM.
- **Ergonomia Nativa para iOS / Swift (`FlowSubscription`)**:
  - `ReaderSession.watchState`, `watchEvents`, `watchUiEffects` e `close()`, além de sobrecargas sem parâmetros default (`open(name, bytes)`, `open(path)`, `search(query)`, `currentDocument()`) para exportação limpa em Objective-C/Swift (`ReaderCore.xcframework`).
- **Paginação Horizontal CSS Multi-Column (`ReadingMode.PAGINATED`), EPUB 3 Fixed-Layout e MathML**:
  - `ReadingMode.SCROLL` vs. `ReadingMode.PAGINATED` com `ReaderCommand.SetReadingMode`, suporte a sub-páginas horizontais (`currentSubPage`, `totalSubPages`, `readerJS.nextSubPage()`, `readerJS.prevSubPage()`);
  - Extração de `RenditionLayout` (`REFLOWABLE`, `PRE_PAGINATED`), `RenditionOrientation` e `RenditionSpread` do `.opf`;
  - Regras de estilização dedicadas para blocos matemáticos `<math>` (MathML).
- **Pipeline de Proteção de Conteúdo / DRM (`ContentProtection.kt`)**:
  - `EpubEncryptionParser` para leitura de `META-INF/encryption.xml`;
  - `StandardEpubFontDeobfuscator` com suporte oficial a fontes ofuscadas **IDPF** (`http://www.idpf.org/2008/embedding`) e **Adobe** (`http://ns.adobe.com/pdf/enc#RC`);
  - Gancho extensível `ContentDecryptor` e `DecryptingResourceProvider` para integração com provedores Readium LCP ou DRM customizado.

## [0.2.0] - 2026-11-07

### Added
- **`RenderedDocument` e `currentDocument()` / `currentBaseUrl()`** em `ReaderSession`, expondo o `baseUrl` do capítulo atual (`book://content/OEBPS/`) e resolução automática de caminhos relativos (`readResource`, `resourceMediaType` e navegação interna).
- **Modelo `Locator`** (`href`, `pageIndex`, `progression`, `title`, `textQuote`) integrado em `ReadingProgress`, `Bookmark`, `HighlightItem`, `SearchResult` e no comando `ReaderCommand.GoToLocator`.
- **`ZipLimits` e descompressão lazy sob demanda** em `ZipResourceProvider`, com sniffing rápido do Central Directory (`hasZipEntry`) e proteção contra Zip/Gzip Bombs e entradas ZIP64.
- **`XmlEntities`**: decodificador em passagem única para entidades nomeadas (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`, `&nbsp;`, `&mdash;`, `&ndash;`, `&hellip;`, `&copy;`) e numéricas decimais/hexadecimais (`&#233;`, `&#x00E9;`).
- **Busca insensível a acentos (diacríticos)** e cache de texto plano por capítulo em `BookSearchEngine`.
- **Suporte a TOC hierárquico aninhado** (`TocEntry.children` e `TocItem.level`) para EPUB 3 (`<nav>`) e EPUB 2 (`toc.ncx`), além de filtragem de `linear="no"` no `<itemref>` do OPF.
- **Identificador determinístico por hash SHA-256** (`Source.sha256DocumentId()`) quando o documento não declara `identifier`.
- **`ThemeTokens.fluent()`**, cores configuráveis de modo noturno (`nightBackground`, `nightOnSurface`, `nightPrimary`) e estilo `.highlight_annotation`.
- **`ReaderStrings`** para internacionalização dos rótulos padrão (`portuguese()`, `english()`, `spanish()`).
- **Modo `explicitApi()`** habilitado no módulo `:reader-core`.

### Fixed
- **Crash `IllegalArgumentException: Illegal group reference`** em `ReaderJsBridge.injectThemeAndBridge` ao renderizar capítulos EPUB e HPUB que contêm `</head>` ou `<body`.
- **Escape seguro de strings JavaScript e sanitização de tokens CSS/HTML** em `ReaderJsBridge` e `ThemeTokens.toContentCss`.
- **Fórmula de progresso (`ReadingProgress.calculatePercentage`)** corrigida para ser contínua e monotônica (`((pageIndex + scrollFraction) / totalPages) * 100`).
- **Restauração completa em `restorePageCloudData`** reconstruindo também os itens de resumo (`currentPageHighlights`) e enviando comandos JS de formulários (`setAnnotations`/`setAnswers`).
- **Workflows de CI/CD GitHub Actions** migrados do projeto Rust legado para Kotlin Multiplatform.
