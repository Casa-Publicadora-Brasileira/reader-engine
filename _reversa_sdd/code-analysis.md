# Análise Técnica Consolidada de Código (`code-analysis.md`)

> **Gerado por:** Reversa Archaeologist (`escavacao` — Nível `detalhado`)
> **Módulos analisados:** `reader-core`, `sample-android`
> **Escala de confiança:** 🟢 CONFIRMADO | 🟡 INFERIDO | 🔴 LACUNA

---

## 1. Módulo `reader-core` (`com.readerengine.core`)

O módulo `:reader-core` é o motor *headless* multiplataforma (Kotlin Multiplatform — `commonMain`, `androidMain`, `iosMain`, `jvmMain`) compilado com `explicitApi()`. Ele é dividido em 9 subpacotes coesos.

### 1.1. Subpacote `parser` (`FormatParser`, `EpubParser`, `HpubParser`, `JsonParser`, `XmlEntities`)

- **`Source` (`FormatParser.kt:19-129`):**
  - 🟢 **CONFIRMADO:** Abstrai a fonte de entrada de uma publicação em dois modos mutuamente exclusivos:
    - **Memória (`inMemoryBytes: ByteArray?`):** criado via `Source.fromBytes(...)`.
    - **Disco (`path: Path?` + `fileSystem: FileSystem`):** criado via `Source.fromPath(...)`, permitindo streaming *random-access* via `Okio FileHandle`.
  - **Algoritmo `sha256DocumentId()` (`FormatParser.kt:74-98`):**
    - Em memória: calcula SHA-256 sobre todo o `ByteArray` e retorna `"doc-<16 hex chars>"`.
    - Em disco: evita carregar arquivos grandes (> 128 KB) na RAM escrevendo em um `Buffer` o tamanho total (`Long` 8 bytes), os primeiros 64 KB (`0..65536`) e os últimos 64 KB (`total - 65536..total`), extraindo os primeiros 16 caracteres hexadecimais do SHA-256 resultante.
- **`ParserRegistry` (`FormatParser.kt:137-167`):**
  - 🟢 **CONFIRMADO:** Registra parsers na ordem padrão `JsonParser` -> `EpubParser` -> `HpubParser`.
  - Ao abrir (`open(source)`), seleciona o primeiro parser cujo `canParse(source)` retorne `true` ou lança `ReaderException.UnsupportedFormat(source.name)`.
  - Se a `Publication` retornada não possuir `metadata.identifier`, injeta automaticamente `source.sha256DocumentId()`.
- **`EpubParser` (`EpubParser.kt:22-368`):**
  - 🟢 **CONFIRMADO:** Detecta pacotes pela extensão `.epub` ou presença de `META-INF/container.xml` no Central Directory do ZIP.
  - **Fluxo de parsing:**
    1. Lê `META-INF/container.xml` e extrai o atributo `full-path` da primeira tag `<rootfile>`.
    2. Calcula `baseDir` (diretório do arquivo `.opf`).
    3. Lê o arquivo `.opf` e extrai metadados Dublin Core (`title`, `creator`, `language`, `identifier`) e propriedades de paginação EPUB 3 (`rendition:layout`, `rendition:orientation`, `rendition:spread`) e EPUB 2 (`<meta name="fixed-layout" content="true|yes">`).
    4. Percorre todas as tags `<item>` construindo a lista de `Resource` e o mapa `id -> fullHref`, identificando o documento de navegação EPUB 3 (`properties` contendo `"nav"`) e o NCX EPUB 2 (`media-type == "application/x-dtbncx+xml"` ou extensão `.ncx`).
    5. Constrói o `spine` a partir das tags `<itemref>`, filtrando `linear="no"` (com fallback para todos os `itemref` caso todos sejam não-lineares).
    6. Faz o parse hierárquico do sumário (`parseNavXhtml` com máquina de estados em pilha `ArrayDeque<LiBuilder>` para listas `<ol>/<li>/<a>` aninhadas, ou `parseNcx` com pilha `ArrayDeque<NavPointBuilder>` para `<navPoint>` recursivos).
    7. Se `META-INF/encryption.xml` existir, invoca `EpubEncryptionParser.parse(...)` e envolve o `ZipResourceProvider` em um `DecryptingResourceProvider` com `StandardEpubFontDeobfuscator`.
- **`HpubParser` (`HpubParser.kt:20-169`):**
  - 🟢 **CONFIRMADO:** Suporta pacotes HPUB / Baker Framework (`.hpub` ou presença de `book.json` na raiz do ZIP).
  - Extrai `title`, `language`, `identifier`/`id`, `author`/`authors`/`creator` (aceita string única ou array JSON) e `contents` (aceita strings de URL ou objetos `{url, title, subtitle}`).
  - Caso o `toc` extraído de `contents` tenha apenas URLs como título (`it.title == it.href`), tenta enriquecer o sumário lendo `index.html`, `Index.html` ou `toc.html` (`parseIndexHtmlIfPresent`).
- **`JsonParser` (`JsonParser.kt:14-93`):**
  - 🟢 **CONFIRMADO:** Suporta arquivos `.json` cujo campo raiz `"format"` seja `"reader-json"`. Converte cada item de `pages` (`id`, `title`, `subtitle`, `html`) em um recurso virtual `<id>.html` armazenado em `MemoryResourceProvider`.
- **`XmlEntities` (`XmlEntities.kt:9-56`):**
  - 🟢 **CONFIRMADO:** Decodificador *single-pass* baseado em regex `&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z0-9]+);`. Evita vulnerabilidades e corrupções de dupla decodificação (ex.: `&amp;lt;` torna-se `&lt;`, e não `<`). Suporta code points suplementares UTF-16 (`> 0xFFFF`) calculando pares substitutos (*surrogate pairs*).

---

### 1.2. Subpacote `provider` (`ResourceProviders.kt`)

- **`ZipLimits` (`ResourceProviders.kt:41-53`):**
  - 🟢 **CONFIRMADO:** Parâmetros de proteção contra *Zip Bomb* e exaustão de memória:
    - `maxEntries = 10_000`
    - `maxEntryUncompressedBytes = 50 MB` (`52_428_800` bytes)
    - `maxTotalUncompressedBytes = 1 GB` (`1_073_741_824` bytes)
    - `maxCacheBytes = 16 MB` (`16_777_216` bytes — orçamento do cache LRU de entradas descomprimidas).
- **`RandomAccessSource` (`ResourceProviders.kt:55-134`):**
  - `ByteArrayRandomAccess`: leitura posicional sobre array em memória com verificação de limites.
  - `FileHandleRandomAccess`: leitura posicional via `okio.FileHandle` (`fileSystem.openReadOnly(path)`), reabrindo transparentemente o descritor caso tenha sido fechado anteriormente.
- **`ZipResourceProvider` (`ResourceProviders.kt:141-510`):**
  - 🟢 **CONFIRMADO:** Implementação própria do formato ZIP em `commonMain` sem depender de `java.util.zip.ZipFile`:
    1. **Localização do EOCD (`findEocdOffsetInWindow`):** lê apenas a janela final do arquivo (até `65_557` bytes = 22 bytes fixos do EOCD + 65535 bytes máximos de comentário) buscando de trás para frente a assinatura `0x06054b50`.
    2. **Indexação do Central Directory (`indexCentralDirectory`):** valida limites anti-*Zip Bomb* (rejeita `ZIP64`, valida `totalEntries <= maxEntries`, soma `declaredTotalUncompressed <= maxTotalUncompressedBytes` e verifica `uncompressedSize <= maxEntryUncompressedBytes`) e armazena apenas metadados leves (`ZipEntryMeta`) em memória.
    3. **Leitura sob demanda (`read` + Cache LRU):** ao ler um recurso, verifica `cacheSnapshot`; se ausente, localiza o *Local File Header* (`0x04034b50`), extrai os bytes (`STORED = 0` ou `DEFLATED = 8` via `okio.InflaterSource(Inflater(true))` com leitura limitada a `maxBytes`) e atualiza o `LinkedHashMap` mantendo a soma de bytes `<= maxCacheBytes`.
    4. **Leitura Parcial (`readRange` — HTTP 206):** se a entrada for `STORED (0)` (padrão para vídeos `.mp4` e áudios `.mp3` dentro de EPUBs), lê diretamente a fatia `source.readSlice(dataStartLong + offset, clampedByteCount)` no disco sem descomprimir nem carregar o arquivo inteiro na RAM!
- **`normalizeHref` (`ResourceProviders.kt:512-523`):**
  - 🟢 **CONFIRMADO:** Remove fragmentos `#anchor`, converte `\` em `/`, ignora segmentos vazios e `.`, e resolve `..` desempilhando o segmento anterior (prevenindo *Path Traversal* fora da raiz lógica do pacote).

---

### 1.3. Subpacote `drm` (`ContentProtection.kt`)

- **`EpubEncryptionParser` (`ContentProtection.kt:108-159`):**
  - 🟢 **CONFIRMADO:** Faz o parse de `META-INF/encryption.xml` extraindo blocos `<EncryptedData>`, mapeando `normalizedUri -> EncryptedResourceInfo(uri, algorithm, compressionMethod, originalLength)`.
- **`StandardEpubFontDeobfuscator` (`ContentProtection.kt:41-103`):**
  - 🟢 **CONFIRMADO:** Implementa os dois padrões oficiais de desofuscação de fontes em EPUB:
    - **IDPF (`http://www.idpf.org/2008/embedding`):** remove espaços em branco do `publicationIdentifier`, calcula o hash **SHA-1 (20 bytes)** da string UTF-8 e aplica XOR cíclico nos primeiros **1040 bytes** do arquivo de fonte.
    - **Adobe (`http://ns.adobe.com/pdf/enc#RC`):** remove prefixo `urn:uuid:` e hífens do `publicationIdentifier`, converte 32 dígitos hexadecimais em uma chave de **16 bytes** (com fallback para os primeiros 16 bytes do SHA-1 caso não haja 32 hex chars) e aplica XOR cíclico nos primeiros **1024 bytes**.
    - Delega qualquer outro algoritmo (ex.: Readium LCP AES-256) para `fallbackDecryptor: ContentDecryptor?`.
- **`DecryptingResourceProvider` (`ContentProtection.kt:165-209`):**
  - 🟢 **CONFIRMADO:** Decorator sobre `ResourceProvider` que intercepta `read`, `size` e `readRange` aplicando `decryptor.decrypt(...)`.

---

### 1.4. Subpacote `session` (`ReaderEngine`, `ReaderSession`, `FlowSubscription`)

- **`ReaderSession` (`ReaderSession.kt:193-1071`):**
  - 🟢 **CONFIRMADO:** Máquina de estados reativa central do motor:
    - Expõe `state: StateFlow<ReaderState>`, `events: SharedFlow<ReaderEvent>` (`replay = 1, extraBufferCapacity = 256`) e `uiEffects: SharedFlow<ReaderUiEffect>` (`extraBufferCapacity = 64`).
    - Expõe wrappers para iOS/Swift (`watchState`, `watchEvents`, `watchUiEffects`) que lançam coletores em `watchScope` (`SupervisorJob() + Dispatchers.Unconfined`) e retornam `FlowSubscription`.
    - **Resolução de `documentId` (`ReaderSession.kt:201-205`):** usa `initialBookState.documentId`, ou `publication.metadata.identifier`, ou `publication.metadata.title`, ou `"book-unknown"`.
    - **Despacho de Comandos (`dispatch(command: ReaderCommand)`):** processa 18 comandos de navegação, scroll, aparência, grifos, marcadores e ações customizadas.
    - **Throttling de Progresso (`UpdateScroll`, linhas 432-438):** só emite `ReaderEvent.ProgressUpdated` quando `pageIndex != lastEmittedProgressPage` ou `abs(clampedScroll - lastEmittedProgressScrollY) >= 2`, evitando tempestade de gravações por micro-oscilações de pixel na WebView.
    - **Resolução de Recursos em 3 Etapas (`resolveResourceHref`, linhas 934-951):**
      1. Tenta o caminho normalizado direto (`OEBPS/styles/main.css`).
      2. Tenta relativo ao diretório do capítulo atual (`currentChapterDir() + normalized`).
      3. Tenta correspondência por sufixo no manifesto da publicação (`publication.findResource(normalized)`).

---

### 1.5. Subpacote `bridge` (`ReaderJsBridge`, `PayloadCodec`)

- **`PayloadCodec` (`PayloadCodec.kt:16-70`):**
  - 🟢 **CONFIRMADO:** Comprime automaticamente strings UTF-8 com tamanho `>= 200_000` bytes (200 KB) usando `GzipSink` + Base64 precedido por `"gz:"`. Na descompressão (`decodeIfNeeded`), impõe limite máximo de segurança de **16 MB** (`DEFAULT_MAX_DECODED_BYTES`) contra bombas GZIP.
- **`ReaderJsBridge` (`ReaderJsBridge.kt:26-610`):**
  - 🟢 **CONFIRMADO:**
    - Injeta `<style id="reader-engine-theme">` e o polyfill completo `FALLBACK_READER_JS` (`window.readerJS`, `window.ReaderEngine.postAction`, e listener passivo de `scroll` com debounce de `80ms` que reporta `scrollY`, `scrollHeight` e `innerHeight` para `window.AndroidScrollProgress.onScrollChanged`).
    - Higieniza strings JavaScript via `jsStringLiteral` (escapando `</` como `<\/`, `\u2028` e `\u2029`) e classes CSS via `sanitizeClassToken`.
    - Roteia URLs interceptadas (`parseSpecialUrl`) classificando em `ExternalWebUrl`, `InvalidHighlightOnInput`, `ClipboardAction`, `Action`, `CustomUri` (decodificando payload JSON `[{"title":..., "text":...}]`) ou `InternalResource`.

---

### 1.6. Subpacotes `search` e `theme`

- **`BookSearchEngine` (`BookSearchEngine.kt:15-122`):**
  - 🟢 **CONFIRMADO:** Remove blocos `<script>`/`<style>` e tags HTML, decodifica entidades XML via `XmlEntities.decode`, faz cache do texto limpo em `Publication.cachedPlainText`, aplica *diacritic folding* 1-para-1 (`foldDiacritics`: converte `áàâãäå` -> `a`, `ç` -> `c`, etc. preservando exatamente os índices de caracteres) e gera `SearchResult` com snippet de contexto (`-45` a `+55` chars) e `Locator`.
- **`ThemeTokens` (`ThemeTokens.kt:61-213`):**
  - 🟢 **CONFIRMADO:** Fornece presets `material3()`, `hig()` e `fluent()`, geração de CSS (`toContentCss`) com suporte a modo noturno, colunas horizontais CSS (`column-width: calc(100vw - 32px); column-gap: 32px`), Fixed-Layout (`overflow: hidden`) e MathML (`Latin Modern Math`, `STIX Two Math`, `Cambria Math`, `Noto Sans Math`), além de sanitização rigorosa contra CSS Injection (`sanitizeCssColor` via regex `^#[0-9a-fA-F]{3,8}$` e `sanitizeCssFontFamily`).

---

## 2. Módulo `sample-android` (`com.readerengine.app`)

- **`HostBookPersistenceStore` (`MainActivity.kt:137-233`):**
  - 🟢 **CONFIRMADO:** Camada de persistência local baseada em `SharedPreferences` (`kmp_reader_host_store`) que armazena progresso (`<docId>_page`, `_total`, `_scrollY`, `_percent`), grifos por página (`<docId>_hl_<page>`), formulários por página (`<docId>_form_<page>`), marcadores (`<docId>_bookmarks`) e preferências globais (`pref_font_scale`, `pref_night_mode`, `pref_font_family`, `pref_reading_mode`).
- **`BookResourceWebViewClient` (`MainActivity.kt:322-459`):**
  - 🟢 **CONFIRMADO:** Intercepta requisições ao esquema virtual `book://content/<href>`:
    - Se o cabeçalho HTTP `Range: bytes=start-end` estiver presente e o tamanho total for conhecido, calcula fatias de até **1 MB** por chunk (`1024 * 1024`), chama `session.readResourceRange(href, start, length)` e responde com status **HTTP 206 Partial Content** e cabeçalhos `Content-Range`, `Accept-Ranges: bytes` e `Content-Length`, viabilizando streaming fluido de vídeo e áudio HTML5 sem estourar memória.
    - Caso contrário, responde **HTTP 200 OK** com `session.readResource(href)`.
- **`ReaderScreen` (`MainActivity.kt:463-1086`):**
  - 🟢 **CONFIRMADO:** Tela principal em Jetpack Compose que observa `session.state`, coleta `session.events` (persistindo no `HostBookPersistenceStore`), coleta `session.uiEffects` (executando scripts JS na `WebView` e sincronizando grifos/sub-páginas) e gerencia tela cheia de vídeo HTML5 via `WebChromeClient.onShowCustomView` + `BackHandler`.
