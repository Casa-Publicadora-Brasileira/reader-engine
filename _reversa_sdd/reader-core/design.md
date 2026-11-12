# `reader-core`, Design Técnico

> Gerado pelo **Writer** (Reversa v1.3.6) | Unit: `reader-core` | Nível: `detalhado`

---

## Interface

### Pontos de Entrada e Símbolos Públicos Principais (`explicitApi()`)

| Símbolo | Assinatura | Retorno | Observação |
|---------|------------|---------|------------|
| `ReaderEngine.open` | `(source: Source, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default), initialState: InitialBookState? = null, strings: ReaderStrings = ReaderStrings.PORTUGUESE, fallbackDecryptor: ContentDecryptor? = null)` | `ReaderSession` | Ponto de entrada principal da biblioteca; detecta formato, faz parse, hidrata estado e retorna sessão reativa 🟢 |
| `ReaderEngine.open` (overload conciso) | `(source: Source, initialState: InitialBookState?)` | `ReaderSession` | Overload amigável para Swift/iOS e chamadas rápidas com `scope` e `strings` padrão 🟢 |
| `ReaderEngine.open` (overload idioma) | `(source: Source, language: ReaderLanguage, initialState: InitialBookState? = null)` | `ReaderSession` | Overload que resolve `ReaderStrings.forLanguage(language)` automaticamente 🟢 |
| `ReaderSession.dispatch` | `(command: ReaderCommand)` | `Unit` | Despacha qualquer um dos 18 comandos da máquina de estados 🟢 |
| `ReaderSession.renderCurrentPageHtml` | `(customCss: String? = null, includeScript: Boolean = true, viewportWidthPx: Int = 0, viewportHeightPx: Int = 0)` | `String` | Injeta variáveis CSS de `ThemeTokens`, paginação, MathML, highlights, forms e script `window.readerJS` no HTML do capítulo atual 🟢 |
| `ReaderSession.readResourceBytes` | `(rawHref: String)` | `ByteArray?` | Resolve `rawHref` em 3 etapas e retorna os bytes (já desofuscados/descriptografados se aplicável) 🟢 |
| `ReaderSession.readResourceRange` | `(rawHref: String, offset: Long, length: Int)` | `ByteArray?` | Lê fatia parcial `[offset, offset + length)` para streaming HTTP 206 de áudio/vídeo 🟢 |
| `ReaderSession.getResourceSize` | `(rawHref: String)` | `Long?` | Retorna tamanho total não comprimido declarado no Central Directory ou array em memória 🟢 |
| `ReaderSession.watchState` | `(onEach: (ReaderState) -> Unit)` | `FlowSubscription` | Wrapper de observação de `state` para iOS/Swift 🟢 |
| `ReaderSession.watchEvents` | `(onEach: (ReaderEvent) -> Unit)` | `FlowSubscription` | Wrapper de observação de `events` para iOS/Swift 🟢 |
| `ReaderSession.watchUiEffects` | `(onEach: (ReaderUiEffect) -> Unit)` | `FlowSubscription` | Wrapper de observação de `uiEffects` para iOS/Swift 🟢 |
| `ZipResourceProvider.fromSource` | `(source: Source, limits: ZipLimits = ZipLimits(), fileSystem: FileSystem = FileSystem.SYSTEM)` | `ZipResourceProvider` | Constrói provedor ZIP indexando o Central Directory nos 65.557 bytes finais 🟢 |
| `BookSearchEngine.search` | `(publication: Publication, query: String, maxResults: Int = 100)` | `List<SearchResult>` | Busca textual insensível a acentos (`foldDiacritics`) com cache de texto plano por capítulo 🟢 |
| `PayloadCodec.encode` | `(rawText: String, compressionThresholdChars: Int = 200_000)` | `String` | Retorna texto puro ou `gz:<base64>` se `rawText.length >= compressionThresholdChars` 🟢 |
| `PayloadCodec.decode` | `(encodedPayload: String)` | `String` | Descomprime `gz:<base64>` validando limite máximo de 16 MB (`MAX_DECOMPRESSED_BYTES`) 🟢 |

---

## Fluxo Principal

1. **Identificação da Fonte (`Source.kt:68-103`):**
   - `ReaderEngine.open` invoca `source.sha256DocumentId()`.
   - Se a fonte estiver em disco (`path`) e possuir mais de `131.072` bytes (128 KB), lê apenas os primeiros 64 KB e os últimos 64 KB combinados com o tamanho total de 8 bytes para gerar o hash `sha256-<hex>` em $O(1)$. 🟢
2. **Descoberta de Formato e Indexação ZIP (`ParserRegistry` + `ZipResourceProvider.kt:150-245`):**
   - `ParserRegistry` seleciona `EpubParser` (para `.epub`), `HpubParser` (para `.hpub`) ou `JsonParser` (para `.json`).
   - Em arquivos `.epub`/`.hpub`, `ZipResourceProvider.fromSource` lê os últimos `65.557` bytes do arquivo, localiza a assinatura EOCD (`0x06054b50`), percorre o Central Directory (`0x02014b50`), valida `ZipLimits` (`maxEntries = 10.000`, `maxTotalUncompressedBytes = 1 GB`) e armazena o mapa `entries: Map<String, ZipEntryMeta>`. 🟢
3. **Parsing Estrutural EPUB e Configuração DRM (`EpubParser.kt:30-190`, `DrmProvider.kt:35-199`):**
   - Lê `META-INF/container.xml` para descobrir o caminho do `.opf`.
   - Lê `META-INF/encryption.xml` (se presente) via `EpubEncryptionParser.parse` para mapear recursos criptografados/ofuscados.
   - Faz o parse do `.opf`: extrai `Metadata` (incluindo `unique-identifier`, `rendition:layout`, `rendition:orientation`, `rendition:spread`), `manifest` e `spine` (separando capítulos lineares e `linear="no"`).
   - Envolve o `ZipResourceProvider` em um `DecryptingResourceProvider` configurado com `StandardEpubFontDeobfuscator(uniqueIdentifiers, fallbackDecryptor)`.
   - Extrai a árvore de sumário (`TocEntry`) via `<nav epub:type="toc">` (EPUB 3) ou `.ncx` (`<navMap>`, EPUB 2). 🟢
4. **Inicialização da Máquina de Estados (`ReaderSession.kt:60-145`):**
   - Hidrata `ReaderState` combinando `Publication` e `InitialBookState` (clampeando `currentPage` para `0 until totalPages`, `fontSizeSp` para `12..36` e calculando `ReadingProgress` e `Locator` iniciais). 🟢
5. **Renderização e Despacho de Comandos (`ReaderSession.kt:200-480`, `ReaderJsBridge.kt:110-310`):**
   - O host chama `session.renderCurrentPageHtml(...)`, que injeta no `<head>` o bloco `<style id="reader-engine-theme">` e o script `window.readerJS`.
   - Interações do usuário chegam via `session.dispatch(command: ReaderCommand)`, que atualiza `_state: MutableStateFlow<ReaderState>` e emite eventos em `_events` e efeitos JS em `_uiEffects`. 🟢

---

## Fluxos Alternativos

- **Leitura de Intervalo Parcial Zero-Copy (`ZipResourceProvider.readRange`, `ZipResourceProvider.kt:105-143`):**
  - Quando o recurso não está criptografado e seu método de compressão no ZIP é `0` (`STORED`) e a fonte está em disco (`fileHandle != null`), `readRange(href, offset, length)` pula o cabeçalho local (`localHeaderOffset + 30 + fileNameLen + extraLen + offset`) e lê diretamente os bytes solicitados do disco sem descomprimir nem tocar no cache LRU. 🟢
- **Capítulo sem `<head>` ou Fragmento HTML Puro (`ReaderJsBridge.kt:140-165`):**
  - Se o capítulo não contiver tag `<head>` nem `<html>`, `ReaderJsBridge.injectIntoHtml` envolve o conteúdo em um envelope HTML5 completo contendo `<head>` e `<body>`. 🟢
- **Navegação Paginada em Limite de Capítulo (`ReaderSession.kt:255-260`):**
  - No modo `ReadingMode.PAGINATED`, despachar `NextSubPage` quando `currentSubPage >= totalSubPages - 1` avança automaticamente para o próximo capítulo (`NextPage`); despachar `PreviousSubPage` quando `currentSubPage == 0` retrocede para o capítulo anterior (`PreviousPage`). 🟢
- **Formato Desconhecido ou ZIP Corrompido (`ReaderException.kt`):**
  - Formatos sem parser compatível lançam `ReaderException.UnsupportedFormat`; arquivos sem EOCD válido, sem `container.xml`, sem capítulos na spine ou que violem `ZipLimits` lançam `ReaderException.InvalidContent`. 🟢

---

## Dependências

- **`org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0`** — Usado em `ReaderSession` (`StateFlow`, `SharedFlow`, `CoroutineScope`), `FlowSubscription` e `ZipResourceProvider` (`Mutex` para thread-safety do cache LRU e `FileHandle`). 🟢
- **`org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`** — Usado em `JsonParser`, `HpubParser` e `ReaderJsBridge` para parsing e serialização segura de payloads JSON sem reflexão. 🟢
- **`com.squareup.okio:okio:3.9.1`** — Usado em `Source` e `ZipResourceProvider` para I/O multiplataforma em disco (`FileSystem`, `Path`, `FileHandle`) e cálculo de hashes (`Buffer.sha256()`). 🟢
- **Bibliotecas Nativas de Plataforma (`java.util.zip` / `platform.zlib`)** — Usadas em `PlatformCompression.*.kt` para implementar `inflateRaw`, `gzipCompress` e `gzipDecompress`. 🟢

---

## Decisões de Design Identificadas

| Decisão | Evidência no código | Confiança |
|---------|---------------------|-----------|
| Uso de `explicitApi()` para travar visibilidade pública do SDK | `reader-core/build.gradle.kts:42` | 🟢 |
| Implementação própria de SHA-1 em `commonMain` para desofuscação IDPF sem dependências externas | `reader-core/src/commonMain/kotlin/com/readerengine/core/drm/DrmProvider.kt:167-199` | 🟢 |
| Cache de texto plano por capítulo (`plainTextCache`) dentro da instância de `Publication` para acelerar buscas subsequentes | `reader-core/src/commonMain/kotlin/com/readerengine/core/model/Models.kt:132-145` | 🟢 |
| Decodificador de entidades XML/HTML em passagem única (`Single-Pass Regex`) com suporte a pares substitutos UTF-16 | `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/XmlEntities.kt:1-68` | 🟢 |
| Uso de `Mutex` corrotineiro + `runBlocking` interno em `ZipResourceProvider` para proteger o cache `LinkedHashMap` e o `FileHandle` contra leituras concorrentes da WebView | `reader-core/src/commonMain/kotlin/com/readerengine/core/provider/ZipResourceProvider.kt:45-95` | 🟢 |

---

## Estado Interno

1. **`ReaderSession._state` (`MutableStateFlow<ReaderState>`):**
   - Mantém o snapshot imutável completo da sessão (`bookId`, `metadata`, `currentPage`, `totalPages`, `currentChapterTitle`, `currentPageContent`, `currentPageHref`, `scrollY`, `readingMode`, `currentSubPage`, `totalSubPages`, `progress`, `currentLocator`, `bookmarks`, `highlights`, `forms`, `searchResults`, `theme`, `fontSizeSp`). 🟢
2. **`ZipResourceProvider.lruCache` (`LinkedHashMap<String, ByteArray>`):**
   - Mantém em memória até `16 MB` (`currentCacheBytes <= maxCacheBytes`) das entradas descompactadas mais recentemente acessadas, protegidas por `cacheMutex`. 🟢
3. **`Publication.plainTextCache` (`MutableMap<Int, String>`):**
   - Armazena sob demanda a versão limpa (sem tags HTML e com entidades decodificadas) de cada capítulo `pageIndex` na primeira vez em que `BookSearchEngine.search` é executado. 🟢

---

## Observabilidade

- O `reader-core` não realiza logging direto em stdout/logcat para manter pureza multiplataforma; em vez disso, toda falha operacional não-fatal ou rejeição de comando é emitida de forma estruturada no canal `session.events: SharedFlow<ReaderEvent>` como `ReaderEvent.Error(code: ReaderErrorCode, message: String)` acompanhada de `ReaderUiEffect.ShowNotice(message)`. 🟢

---

## Riscos e Lacunas

- 🟡 **`InitialBookState` e `currentSubPage`:** Na hidratação inicial (`ReaderSession.kt:92`), `currentSubPage` sempre inicia em `0`, mesmo que o livro tenha sido fechado em uma sub-página avançada no modo `PAGINATED`.
- 🟡 **Duplicação de `PlatformCompression` entre JVM e Android:** `PlatformCompression.jvm.kt` e `PlatformCompression.android.kt` possuem código idêntico e devem ser mantidos sincronizados caso a lógica de buffer de `Inflater`/`GZIPInputStream` seja alterada.
