# Conhecimento de Domínio e Regras de Negócio — `reader-engine`

> Gerado pelo **Detective** (Reversa v1.3.6) | Nível: `detalhado`

---

## 1. Glossário Ubiquitous Language (Linguagem Ubíqua)

| Termo | Definição no Domínio | Representação no Código | Confiança |
|-------|----------------------|-------------------------|-----------|
| **Publication (Publicação)** | Obra digital completa carregada em memória/disco após o parsing de um container EPUB 2/3, HPUB ou `reader-json`, contendo metadados, recursos, spine de páginas e sumário (TOC). | `Publication` (`Models.kt:120`) | 🟢 CONFIRMADO |
| **Source (Fonte de Documento)** | Abstração da origem física do livro digital: em memória (`ByteArray`) ou em disco (`Okio Path` + `FileSystem`), capaz de gerar um `bookId` determinístico sem ler o arquivo inteiro. | `Source` (`Source.kt:16`) | 🟢 CONFIRMADO |
| **Spine (Espinha Dorsal)** | Sequência ordenada de documentos XHTML/HTML que compõem a ordem de leitura principal (`pages` e `pageHrefs`). Capítulos marcados com `linear="no"` no OPF são movidos para o final da espinha se não houver itens lineares ou mantidos separadamente. | `EpubParser.kt:162-182` | 🟢 CONFIRMADO |
| **Resource (Recurso Interno)** | Qualquer ativo empacotado na publicação (XHTML, CSS, fontes TTF/OTF/WOFF, imagens, áudio, vídeo) endereçado por um `href` relativo normalizado. | `Resource` (`Models.kt:40`) | 🟢 CONFIRMADO |
| **Locator (Localizador)** | Coordenada agnóstica de posição dentro da publicação (`href`, `pageIndex`, `progression` `0.0..1.0`, `totalProgression` `0.0..1.0`, `title`, `textQuote`). Inspirado no padrão Readium Locator. | `Locator` (`Models.kt:85`) | 🟢 CONFIRMADO |
| **ReadingProgress (Progresso Monotônico)** | Métrica contínua de avanço na obra (`0.0%` a `100.0%`) que combina o índice do capítulo atual (`currentPage`) com a fração de rolagem vertical (`scrollY / (contentHeightPx - viewportHeightPx)`). | `ReadingProgress` (`Models.kt:166`) | 🟢 CONFIRMADO |
| **TOC (Table of Contents / Sumário)** | Estrutura hierárquica (em árvore via `TocEntry.children`) e achatada (`TocItem` com `level`) que mapeia títulos de seções para índices de página (`pageIndex`) e âncoras (`fragment`). | `TocEntry`, `TocItem` (`Models.kt:50-83`) | 🟢 CONFIRMADO |
| **Font Obfuscation (Ofuscação de Fontes)** | Técnica padronizada do EPUB (`META-INF/encryption.xml`) que aplica XOR nos primeiros 1040 bytes (IDPF SHA-1) ou 1024 bytes (Adobe UUID) de arquivos de fonte para impedir extração direta sem ser um DRM completo de conteúdo. | `StandardEpubFontDeobfuscator` (`DrmProvider.kt:95`) | 🟢 CONFIRMADO |
| **Fallback Decryptor (Descriptografador Plugável)** | Interface injetada pelo app host (`ContentDecryptor`) para descriptografar recursos protegidos por algoritmos proprietários (ex.: Readium LCP, Adobe ADEPT) não suportados nativamente pelo core. | `ContentDecryptor` (`DrmProvider.kt:82`) | 🟢 CONFIRMADO |
| **Highlight (Destaque)** | Marcação textual persistente associada a uma página (`pageIndex`), contendo o trecho selecionado (`text`), estilo visual (`YELLOW`, `GREEN`, `BLUE`, `PINK`, `UNDERLINE`), nota opcional e prefixo/sufixo de contexto. | `HighlightItem`, `HighlightStyle` (`Models.kt:200-220`) | 🟢 CONFIRMADO |
| **FormEntry (Entrada de Formulário)** | Par chave-valor (`key`, `value`) que armazena respostas de exercícios ou campos interativos (`<input>`, `<textarea>`, `<select>`) dentro de uma página específica (`pageIndex`). | `FormEntry` (`Models.kt:241`) | 🟢 CONFIRMADO |
| **ThemeTokens (Tokens de Tema)** | Conjunto de variáveis visuais (cores de superfície, texto, destaque, link, borda, raio de canto, escala tipográfica e modo noturno) derivado de sistemas de design (`MATERIAL3`, `HIG`, `FLUENT`, `CUSTOM`). | `ThemeTokens` (`ThemeTokens.kt:20`) | 🟢 CONFIRMADO |
| **Special URL Route (Rota de URL Especial)** | Esquemas de URL interceptados pelo motor (`reader://action/...`, `mailto:`, `tel:`, `http://`, `https://`, links internos `.xhtml#anchor`) que disparam comandos de sessão ou intenções externas no sistema operacional. | `SpecialUrlRoute` (`ReaderJsBridge.kt:15`) | 🟢 CONFIRMADO |

---

## 2. Catálogo de Regras de Negócio Explícitas e Implícitas

### RN-01 — Identificação Determinística de Livro (`sha256DocumentId`)
- **Localização:** [`Source.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/parser/Source.kt#L68-L103)
- **Regra:**
  1. Se `documentId` for fornecido explicitamente na criação do `Source`, ele é sanitizado e utilizado.
  2. Se a fonte estiver em memória (`bytes`), calcula-se o SHA-256 do array completo prefixado com `sha256-`.
  3. Se a fonte estiver em disco (`path`) e for maior que `131.072` bytes (128 KB), calcula-se o SHA-256 combinando: **tamanho total em bytes (8 bytes big-endian) + primeiros 64 KB + últimos 64 KB**.
- **Motivação de Negócio (Por quê?):** Permite identificar instantaneamente livros de centenas de megabytes (ex.: EPUBs ricos em vídeo/áudio) em $O(1)$ sem bloquear a UI lendo todo o arquivo antes de restaurar o `InitialBookState`.
- **Confiança:** 🟢 **CONFIRMADO** (commit `a92479f` e `93337de`).

### RN-02 — Proteção Anti-Zip Bomb e Limites de Memória (`ZipLimits`)
- **Localização:** [`ZipResourceProvider.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/provider/ZipResourceProvider.kt#L16-L32)
- **Regra:** Todo arquivo ZIP (EPUB/HPUB) é validado contra 4 limites estritos:
  - `maxEntries = 10_000` (máximo de arquivos no Central Directory)
  - `maxEntryUncompressedBytes = 50 * 1024 * 1024` (50 MB por arquivo individual descompactado via `readBytes`)
  - `maxTotalUncompressedBytes = 1024L * 1024L * 1024L` (1 GB de soma total declarada no Central Directory)
  - `maxCacheBytes = 16 * 1024 * 1024` (16 MB de teto no cache LRU em memória)
- **Motivação de Negócio:** Prevenir ataques de negação de serviço (DoS / OutOfMemoryError) ao abrir arquivos EPUB maliciosos ou corrompidos em dispositivos móveis com restrição de heap.
- **Confiança:** 🟢 **CONFIRMADO** (commits `a92479f` e `4ea2e95`).

### RN-03 — Cálculo Monotônico de Progresso Global (`ReadingProgress`)
- **Localização:** [`Models.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/model/Models.kt#L181-L199)
- **Regra:**
  - `safeTotal = max(1, totalPages)`
  - `safePage = currentPage.coerceIn(0, safeTotal - 1)`
  - Se `contentHeightPx > viewportHeightPx && viewportHeightPx > 0`, então `maxScroll = contentHeightPx - viewportHeightPx` e `scrollFraction = (scrollY.toDouble() / maxScroll.toDouble()).coerceIn(0.0, 1.0)`. Caso contrário, `scrollFraction = 0.0`.
  - Se `totalPages == 1 && scrollFraction == 0.0`, o percentual é `100.0`.
  - Caso geral: `percentage = (((safePage + scrollFraction) / safeTotal.toDouble()) * 100.0).coerceIn(0.0, 100.0)`.
- **Motivação de Negócio:** Garantir que o progresso de leitura nunca retroceda ao rolar dentro de um capítulo e avance suavemente de `0%` a `100%` mesmo em livros com capítulos longos.
- **Confiança:** 🟢 **CONFIRMADO** (commits `e884000` e `cd12514`).

### RN-04 — Throttling de Eventos de Rolagem (`UpdateScroll`)
- **Localização:** [`ReaderSession.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/session/ReaderSession.kt#L236-L254)
- **Regra:** O comando `ReaderCommand.UpdateScroll(scrollY, contentHeightPx, viewportHeightPx)` é descartado silenciosamente (early return) se:
  - `abs(clampedScrollY - current.scrollY) < 2` **E**
  - `clampedContentHeight == current.progress.contentHeightPx` **E**
  - `clampedViewportHeight == current.progress.viewportHeightPx`.
- **Motivação de Negócio:** O WebView dispara dezenas de eventos de `scroll` por segundo (mesmo com debounce de 80ms no JS). Ignorar variações sub-pixel/menores que 2px evita recomposições desnecessárias no Jetpack Compose/SwiftUI e gravações excessivas no armazenamento persistente.
- **Confiança:** 🟢 **CONFIRMADO**.

### RN-05 — Limites Tipográficos e Sanitização Anti-CSS Injection
- **Localização:** [`ReaderSession.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/session/ReaderSession.kt#L349) e [`ThemeTokens.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/theme/ThemeTokens.kt#L162-L183)
- **Regra:**
  - `fontSizeSp` é sempre limitado ao intervalo fechado `[12, 36]` via `.coerceIn(12, 36)`.
  - Cores de tema (`sanitizeCssColor`) só aceitam valores que casam com `^(#[0-9a-fA-F]{3,8}|(?:rgb|hsl)a?\([0-9.,%\s]+\)|[a-zA-Z]{3,20})$`; caso contrário, caem para fallback seguro (`#111111` ou `#FFFFFF`).
  - Famílias de fonte (`sanitizeCssFont`) removem caracteres `{} ; < > \` para impedir quebra de bloco `<style>`.
- **Motivação de Negócio:** Impedir que temas customizados (`DesignLanguage.CUSTOM`) quebrem o layout ou injetem seletores CSS/HTML arbitrários na WebView.
- **Confiança:** 🟢 **CONFIRMADO** (commit `813df95`).

### RN-06 — Desofuscação de Fontes EPUB (IDPF e Adobe) e Fallback DRM
- **Localização:** [`DrmProvider.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/drm/DrmProvider.kt#L95-L165)
- **Regra:**
  - Quando `META-INF/encryption.xml` declara um recurso com algoritmo `http://www.idpf.org/2008/embedding`:
    - Calcula-se o hash **SHA-1** de 20 bytes do identificador `opf:unique-identifier` (com whitespace colapsado e trimado).
    - Aplica-se XOR circular com a chave de 20 bytes nos primeiros **1.040 bytes** (`52 * 20`) do arquivo da fonte.
  - Quando o algoritmo é `http://ns.adobe.com/pdf/enc#RC`:
    - Extrai-se o UUID hexadecimal do `unique-identifier` (removendo prefixos `urn:uuid:` e hífens) para formar uma chave binária de **16 bytes**.
    - Aplica-se XOR circular com a chave de 16 bytes nos primeiros **1.024 bytes** (`64 * 16`) do arquivo.
  - Para qualquer outro algoritmo de criptografia, delega-se para `fallbackDecryptor.decrypt(href, rawBytes, encryptionInfo)`. Se `fallbackDecryptor` for `null`, retorna os bytes originais sem lançar exceção fatal durante a abertura.
- **Motivação de Negócio:** Garantir conformidade com a especificação EPUB 3 para fontes embarcadas comerciais e permitir integração corporativa com provedores de DRM externos.
- **Confiança:** 🟢 **CONFIRMADO** (commit `6189725`).

### RN-07 — Compressão Transparente de Payloads de Persistência (`PayloadCodec`)
- **Localização:** [`PayloadCodec.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/bridge/PayloadCodec.kt#L11-L47)
- **Regra:**
  - Strings serializadas com tamanho `< 200.000` caracteres (`DEFAULT_COMPRESSION_THRESHOLD_CHARS`) são retornadas em texto puro.
  - Strings `>= 200.000` caracteres são comprimidas via GZIP e codificadas em Base64 com o prefixo `gz:`.
  - Na decodificação, payloads iniciados por `gz:` são descomprimidos com limite rígido de **16 MB** (`MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024`), lançando `IllegalArgumentException("Compressed payload exceeds maximum allowed size")` se excederem.
- **Motivação de Negócio:** Evitar estouro de limite em `SharedPreferences` / `NSUserDefaults` ou lentidão de IPC quando o usuário acumula milhares de highlights ou formulários extensos em um livro didático.
- **Confiança:** 🟢 **CONFIRMADO**.

### RN-08 — Normalização Unicode 1-para-1 na Busca (`foldDiacritics`)
- **Localização:** [`BookSearchEngine.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/search/BookSearchEngine.kt#L102-L148)
- **Regra:** A busca textual remove tags `<script>`, `<style>` e `<[^>]+>`, decodifica entidades XML, colapsa espaços e aplica `foldDiacritics(char)` que mapeia **cada caractere acentuado da faixa `U+00C0..U+024F` para exatamente 1 caractere ASCII base** (mesmo para ligaduras como `Æ -> a`, `ß -> s`, `Œ -> o`).
- **Motivação de Negócio:** Manter **correspondência exata de índice 1-para-1** entre a string normalizada onde o `indexOf` é executado e a string original de onde o snippet (`-45` a `+55` caracteres ao redor do termo) é recortado.
- **Confiança:** 🟢 **CONFIRMADO** (commit `e884000`).

### RN-09 — Resolução Tolerante de Caminhos de Recursos (`resolveResourceHref`)
- **Localização:** [`ReaderSession.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/session/ReaderSession.kt#L163-L195)
- **Regra:** Ao requisitar um recurso interno (ex.: imagem, vídeo, CSS ou capítulo), o motor tenta resolver o `rawHref` em 3 passos sucessivos:
  1. Caminho normalizado direto (`normalizeHref(cleanHref)`).
  2. Caminho resolvido relativamente ao diretório do capítulo atual (`currentPageHref`).
  3. Match por sufixo case-insensitive (`/` + `normalized` no final de algum `resource.href` do manifesto).
- **Motivação de Negócio:** Ferramentas de autoria EPUB (InDesign, Calibre, Sigil) frequentemente geram referências relativas inconsistentes entre XHTML e OPF; a resolução em 3 níveis evita imagens/fontes quebradas.
- **Confiança:** 🟢 **CONFIRMADO** (commit `813df95`).

### RN-10 — Streaming Parcial HTTP 206 para Áudio/Vídeo Embarcado
- **Localização:** [`MainActivity.kt`](file:///Users/andrealves/Documents/dev/reader-engine/sample-android/src/main/java/com/readerengine/app/MainActivity.kt#L285-L345) e [`ZipResourceProvider.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/provider/ZipResourceProvider.kt#L105-L143)
- **Regra:**
  - Quando o `WebView` requisita `book://content/<href>` com cabeçalho `Range: bytes=start-end`, o `BookResourceWebViewClient` lê no máximo **1 MB (`1_048_576` bytes)** por fatia via `session.readResourceRange(href, start, length)` e responde com status HTTP `206 Partial Content`.
  - Se a entrada no ZIP estiver armazenada sem compressão (`compressionMethod == 0 / STORED`), `ZipResourceProvider.readRange` faz **seek direto no `FileHandle` em disco** sem descomprimir nem alocar o arquivo inteiro na memória.
- **Motivação de Negócio:** Permitir reprodução fluida e seek imediato em vídeos MP4/WebM e áudios MP3 embarcados dentro de EPUB 3 interativos sem causar `OutOfMemoryError`.
- **Confiança:** 🟢 **CONFIRMADO** (commit `91a7a27`).

---

## 3. Lacunas Identificadas (🔴 LACUNA / 🟡 INFERIDO)

| ID | Descrição da Lacuna / Inferência | Status | Impacto |
|----|----------------------------------|--------|---------|
| **LAC-01** | **Falta de módulo sample/host iOS no repositório:** O `reader-core` possui target `iosMain` completo (com C-interop `zlib` e `FlowSubscription`), workflows de build XCFramework e documentação de consumo Swift, mas não há um app `sample-ios` equivalente ao `sample-android` dentro do monorepo. | 🟡 **INFERIDO** | Testes de UI em WebView iOS (`WKWebView` / `WKURLSchemeHandler`) dependem de projetos consumidores externos. |
| **LAC-02** | **Provedor concreto de DRM comercial (`ContentDecryptor`):** O motor fornece a interface `ContentDecryptor` e a desofuscação padrão de fontes (IDPF/Adobe), mas não inclui uma implementação concreta de LCP (Readium Licensed Content Protection) ou Adobe ADEPT no `reader-core`. | 🟢 **CONFIRMADO** (intencional por design de biblioteca) | Apps hosts que exigirem DRM forte devem injetar seu próprio `fallbackDecryptor` em `ReaderEngine.open(...)`. |
| **LAC-03** | **Paginação por sub-páginas (`currentSubPage` / `totalSubPages`) não persiste o índice da sub-página no `InitialBookState`:** Embora `ReaderState` possua `currentSubPage`, `totalSubPages` e `readingMode`, `InitialBookState` restaura `currentPage`, `scrollY` e `readingMode`, iniciando em `subPage = 0` ao reabrir em modo paginado. | 🟡 **INFERIDO** (`Models.kt:255`, `ReaderSession.kt:92`) | Em capítulos longos no modo `PAGINATED`, reabrir o app restaura o capítulo (`currentPage`), mas posiciona na primeira coluna se `scrollY` não for convertido em `subPage`. |
