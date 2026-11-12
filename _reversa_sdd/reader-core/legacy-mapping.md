# Mapeamento Legado — `reader-core`

> Gerado pelo **Archaeologist** (Reversa v1.3.6) | Granularidade: `module`

## 1. Visão Geral da Unidade

O módulo `reader-core` (`com.readerengine.core`) concentra 100% da lógica de domínio multiplataforma (Kotlin Multiplatform: `commonMain`, `androidMain`, `iosMain`, `jvmMain`) do motor de leitura EPUB 2/3, HPUB e `reader-json`.

## 2. Mapeamento de Arquivos e Responsabilidades

| Subpacote | Arquivo Fonte | Linhas | Símbolos Públicos Principais | Responsabilidade Legada | Confiança |
|-----------|---------------|--------|------------------------------|-------------------------|-----------|
| `error` | `reader-core/src/commonMain/kotlin/com/readerengine/core/error/ReaderException.kt` | 29 | `ReaderException`, `UnsupportedFormat`, `InvalidContent`, `ResourceNotFound` | Hierarquia selada de exceções de domínio | 🟢 CONFIRMADO |
| `model` | `reader-core/src/commonMain/kotlin/com/readerengine/core/model/Models.kt` | 275 | `Metadata`, `Resource`, `TocEntry`, `TocItem`, `Locator`, `Publication`, `ReadingProgress`, `HighlightStyle`, `HighlightItem`, `Bookmark`, `SearchResult`, `FormEntry`, `PageFormState`, `InitialBookState`, `ReaderStrings`, `ReaderLanguage` | Entidades imutáveis de domínio, cálculo monotônico de progresso e i18n (PT/EN/ES) | 🟢 CONFIRMADO |
| `parser` | `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/Source.kt` | 150 | `Source`, `DocumentParser`, `ParserRegistry`, `ResourceProvider`, `EmptyResourceProvider`, `MemoryResourceProvider` | Abstração de fonte em memória/disco, hash determinístico `sha256DocumentId` (amostragem 64KB início/fim) e registro de parsers | 🟢 CONFIRMADO |
| `parser` | `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/EpubParser.kt` | 645 | `EpubParser` | Parser completo de EPUB 2/3 (`container.xml`, `.opf`, `<nav>` HTML, `.ncx`, spine linear/não-linear, `rendition:*` e integração DRM) | 🟢 CONFIRMADO |
| `parser` | `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/HpubParser.kt` | 140 | `HpubParser` | Parser de pacotes HPUB (`book.json` + fallback para `index.html`) | 🟢 CONFIRMADO |
| `parser` | `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/JsonParser.kt` | 91 | `JsonParser` | Parser do formato sintético `reader-json` para testes e documentos em memória | 🟢 CONFIRMADO |
| `parser` | `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/XmlEntities.kt` | 68 | `XmlEntities` (interno) | Decodificador single-pass de entidades XML/HTML nominais, decimais e hexadecimais (com pares substitutos UTF-16) | 🟢 CONFIRMADO |
| `provider` | `reader-core/src/commonMain/kotlin/com/readerengine/core/provider/ZipResourceProvider.kt` | 316 | `ZipLimits`, `ZipResourceProvider`, `normalizeHref` | Leitor customizado do Central Directory ZIP (65.557 bytes finais), cache LRU 16MB, proteção anti-Zip Bomb e `readRange` zero-copy para entradas `STORED` | 🟢 CONFIRMADO |
| `provider` | `reader-core/src/commonMain/kotlin/com/readerengine/core/provider/PlatformCompression.kt` | 16 | `inflateRaw`, `gzipCompress`, `gzipDecompress` (internos `expect`) | Declarações `expect` para compressão/descompressão nativa por target | 🟢 CONFIRMADO |
| `provider` | `reader-core/src/jvmMain/kotlin/com/readerengine/core/provider/PlatformCompression.jvm.kt` | 61 | `inflateRaw`, `gzipCompress`, `gzipDecompress` (`actual`) | Implementação JVM via `java.util.zip` (`Inflater`, `GZIPOutputStream`, `GZIPInputStream`) | 🟢 CONFIRMADO |
| `provider` | `reader-core/src/androidMain/kotlin/com/readerengine/core/provider/PlatformCompression.android.kt` | 61 | `inflateRaw`, `gzipCompress`, `gzipDecompress` (`actual`) | Implementação Android via `java.util.zip` | 🟢 CONFIRMADO |
| `provider` | `reader-core/src/iosMain/kotlin/com/readerengine/core/provider/PlatformCompression.ios.kt` | 91 | `inflateRaw`, `gzipCompress`, `gzipDecompress` (`actual`) | Implementação iOS via C-interop `platform.zlib` (`z_stream`, `inflateInit2`, `deflateInit2`) | 🟢 CONFIRMADO |
| `drm` | `reader-core/src/commonMain/kotlin/com/readerengine/core/drm/DrmProvider.kt` | 200 | `EncryptedResourceInfo`, `EpubEncryptionParser`, `ContentDecryptor`, `StandardEpubFontDeobfuscator`, `DecryptingResourceProvider` | Parser de `META-INF/encryption.xml`, desofuscação IDPF (1040B SHA-1) e Adobe (1024B UUID hex), e delegação para `fallbackDecryptor` | 🟢 CONFIRMADO |
| `session` | `reader-core/src/commonMain/kotlin/com/readerengine/core/session/ReaderSession.kt` | 599 | `ReaderEngine`, `ReaderSession`, `ReaderState`, `ReaderCommand`, `ReaderEvent`, `ReaderUiEffect`, `ReaderErrorCode` | Máquina de estados reativa (`StateFlow`/`SharedFlow`), 18 comandos, resolução de href em 3 etapas, throttling de scroll (2px) | 🟢 CONFIRMADO |
| `session` | `reader-core/src/commonMain/kotlin/com/readerengine/core/session/FlowSubscription.kt` | 30 | `FlowSubscription` | Wrapper cancelável (`close()`) sobre `CoroutineScope` para interoperabilidade iOS/Swift | 🟢 CONFIRMADO |
| `bridge` | `reader-core/src/commonMain/kotlin/com/readerengine/core/bridge/PayloadCodec.kt` | 48 | `PayloadCodec` | Codec transparente `gz:<base64>` para payloads >= 200KB com trava de segurança em 16MB | 🟢 CONFIRMADO |
| `bridge` | `reader-core/src/commonMain/kotlin/com/readerengine/core/bridge/ReaderJsBridge.kt` | 538 | `ReaderJsBridge`, `SpecialUrlRoute` | Injeção de HTML/CSS, polyfill `FALLBACK_READER_JS` (`window.readerJS`), roteamento de `parseSpecialUrl` e serialização JS segura | 🟢 CONFIRMADO |
| `search` | `reader-core/src/commonMain/kotlin/com/readerengine/core/search/BookSearchEngine.kt` | 150 | `BookSearchEngine` | Busca full-text insensível a caixa e acentos (`foldDiacritics` 1-para-1) com cache de texto plano por capítulo e snippets (`-45..+55` chars) | 🟢 CONFIRMADO |
| `theme` | `reader-core/src/commonMain/kotlin/com/readerengine/core/theme/ThemeTokens.kt` | 184 | `DesignLanguage`, `ThemeTokens` | Sistemas de design (`MATERIAL3`, `HIG`, `FLUENT`, `CUSTOM`), gerador de CSS de paginação/modo noturno/MathML e sanitização anti-CSS Injection | 🟢 CONFIRMADO |

## 3. Pontos de Entrada e Fronteiras

- **Ponto de Entrada Principal:** `ReaderEngine.open(source, scope, initialState, strings, fallbackDecryptor)` -> retorna `ReaderSession`.
- **Fronteira de Saída Reativa:** `session.state: StateFlow<ReaderState>`, `session.events: SharedFlow<ReaderEvent>`, `session.uiEffects: SharedFlow<ReaderUiEffect>`.
- **Fronteira de Entrada de Comandos:** `session.dispatch(command: ReaderCommand)`.
- **Fronteira de Renderização WebView:** `session.renderCurrentPageHtml(customCss, includeScript)` e `session.readResourceBytes(href)`.
