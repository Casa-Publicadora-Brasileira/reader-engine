# Changelog

Todas as mudanças relevantes deste projeto são documentadas neste arquivo.

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
