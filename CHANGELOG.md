# Changelog

Todas as mudanças relevantes deste projeto são documentadas neste arquivo.

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
