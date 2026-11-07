# Changelog

Todas as mudanças relevantes deste projeto são documentadas neste arquivo.

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
