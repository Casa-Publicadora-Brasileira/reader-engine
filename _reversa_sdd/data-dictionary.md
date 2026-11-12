# Dicionário de Dados Completo (`data-dictionary.md`)

> **Gerado por:** Reversa Archaeologist (`escavacao` — Nível `detalhado`)
> **Escala de confiança:** 🟢 CONFIRMADO (extraído diretamente das data classes, enums e interfaces Kotlin)

---

## 1. Modelos de Publicação e Domínio (`com.readerengine.core.model`)

### 1.1. `Metadata` (`Models.kt:58-66`)
Metadados bibliográficos e de layout da publicação.

| Campo | Tipo | Obrigatório | Valor Padrão | Descrição |
|-------|------|-------------|--------------|-----------|
| `title` | `String` | Sim | `""` | Título principal da obra |
| `authors` | `List<String>` | Sim | `emptyList()` | Lista de autores/criadores (`dc:creator`) |
| `language` | `String?` | Não | `null` | Código de idioma BCP-47 / ISO (`dc:language`) |
| `identifier` | `String?` | Não | `null` | Identificador único (`dc:identifier` ou hash SHA-256 `doc-<16hex>`) |
| `layout` | `RenditionLayout` | Sim | `REFLOWABLE` | Modo de layout (`REFLOWABLE` ou `PRE_PAGINATED`) |
| `orientation` | `RenditionOrientation` | Sim | `AUTO` | Orientação preferencial (`AUTO`, `LANDSCAPE`, `PORTRAIT`) |
| `spread` | `RenditionSpread` | Sim | `AUTO` | Comportamento de página dupla (`AUTO`, `NONE`, `LANDSCAPE`, `BOTH`) |

### 1.2. `Resource` (`Models.kt:69-72`)
Item declarado no manifesto da publicação.

| Campo | Tipo | Obrigatório | Valor Padrão | Descrição |
|-------|------|-------------|--------------|-----------|
| `href` | `String` | Sim | — | Caminho interno normalizado dentro do pacote (ex: `OEBPS/ch1.xhtml`) |
| `mediaType` | `String` | Sim | — | MIME type do recurso (ex: `application/xhtml+xml`, `font/woff2`, `video/mp4`) |

### 1.3. `TocEntry` e `TocItem` (`Models.kt:75-90`)
Representações hierárquica (árvore) e achatada (resolvida para o spine) do sumário.

| Entidade | Campo | Tipo | Obrigatório | Valor Padrão | Descrição |
|----------|-------|------|-------------|--------------|-----------|
| `TocEntry` | `title` | `String` | Sim | — | Título do capítulo/seção |
| `TocEntry` | `subtitle` | `String?` | Não | `null` | Subtítulo opcional (comum em HPUB/JSON) |
| `TocEntry` | `href` | `String` | Sim | — | Caminho alvo com âncora opcional (`ch1.xhtml#s1`) |
| `TocEntry` | `children` | `List<TocEntry>` | Sim | `emptyList()` | Subitens aninhados do sumário |
| `TocItem` | `title` | `String` | Sim | — | Título exibido na UI |
| `TocItem` | `subtitle` | `String?` | Não | `null` | Subtítulo opcional |
| `TocItem` | `href` | `String` | Sim | — | Caminho alvo |
| `TocItem` | `spineIndex` | `Int` | Sim | — | Índice correspondente na lista `Publication.spine` (`0..N-1`) |
| `TocItem` | `level` | `Int` | Sim | `0` | Profundidade hierárquica no sumário (`0` = raiz) |

### 1.4. `Locator` (`Models.kt:98-104`)
Coordenada universal independente de tela para sincronização entre plataformas.

| Campo | Tipo | Obrigatório | Valor Padrão | Descrição |
|-------|------|-------------|--------------|-----------|
| `href` | `String` | Sim | — | Href canônico do capítulo no spine |
| `pageIndex` | `Int` | Sim | — | Índice base-0 no spine |
| `progression` | `Float` | Sim | `0f` | Fração de progresso dentro do capítulo (`0.0f..1.0f`) |
| `title` | `String?` | Não | `null` | Título do capítulo ou marcador |
| `textQuote` | `String?` | Não | `null` | Trecho textual ancorado (usado em grifos e resultados de busca) |

### 1.5. `ReadingProgress` (`Models.kt:168-179`)
Estado consolidado de progresso de leitura.

| Campo | Tipo | Obrigatório | Valor Padrão | Descrição |
|-------|------|-------------|--------------|-----------|
| `documentId` | `String` | Sim | — | Identificador único do documento |
| `pageIndex` | `Int` | Sim | `0` | Índice base-0 do capítulo atual no spine |
| `pageNumber` | `Int` | Sim | `1` | Número base-1 da página/capítulo atual |
| `totalPages` | `Int` | Sim | `1` | Total de itens no spine |
| `scrollY` | `Int` | Sim | `0` | Posição vertical de rolagem em pixels |
| `pageProgression` | `Float` | Sim | `0f` | Fração de rolagem dentro da página atual (`0f..1f`) |
| `totalPercentage` | `Float` | Sim | `0f` | Percentual global monotônico (`0f..100f`) |
| `chapterTitle` | `String` | Sim | `""` | Título do capítulo atual |
| `lastUpdateEpochMs` | `Long` | Sim | `0L` | Timestamp epoch em milissegundos da última atualização |
| `locator` | `Locator?` | Não | `null` | Localizador canônico correspondente |

### 1.6. `HighlightItem`, `Bookmark`, `SearchResult`, `FormEntry`, `PageFormState` (`Models.kt:259-312`)

| Entidade | Campo | Tipo | Obrigatório | Valor Padrão | Descrição |
|----------|-------|------|-------------|--------------|-----------|
| `HighlightItem` | `id` | `Int` | Sim | — | Identificador numérico do grifo na página |
| `HighlightItem` | `pageIndex` | `Int` | Sim | — | Índice da página onde o grifo reside |
| `HighlightItem` | `text` | `String` | Sim | — | Texto grifado pelo usuário |
| `HighlightItem` | `style` | `HighlightStyle` | Sim | — | Estilo/cor (`YELLOW`, `GREEN`, `BLUE`, `PINK`, `RED`, `ORANGE`, `PURPLE`, `DASHED`, `UNDERLINE`, `ANNOTATION`) |
| `HighlightItem` | `scrollPositionY` | `Double` | Sim | — | Coordenada Y do elemento `<span>` no DOM |
| `HighlightItem` | `locator` | `Locator?` | Não | `null` | Localizador com `textQuote` preenchido |
| `Bookmark` | `id` | `String` | Sim | — | ID único do marcador (`"bm-<pageIndex>-<seq>"`) |
| `Bookmark` | `pageIndex` | `Int` | Sim | — | Índice do capítulo no spine |
| `Bookmark` | `scrollY` | `Int` | Sim | `0` | Offset Y salvo no momento da criação |
| `Bookmark` | `label` | `String?` | Não | `null` | Rótulo amigável do marcador |
| `Bookmark` | `createdAtEpochMs` | `Long` | Sim | `0L` | Timestamp de criação |
| `Bookmark` | `locator` | `Locator?` | Não | `null` | Localizador associado |
| `SearchResult` | `pageIndex` | `Int` | Sim | — | Índice do capítulo onde ocorreu o match |
| `SearchResult` | `chapterTitle` | `String` | Sim | — | Título do capítulo |
| `SearchResult` | `snippet` | `String` | Sim | — | Trecho com contexto (`-45` a `+55` caracteres) e reticências |
| `SearchResult` | `matchIndex` | `Int` | Sim | — | Índice do caractere no texto plano do capítulo |
| `SearchResult` | `progression` | `Float` | Sim | — | Posição relativa (`matchIndex / plainText.length`) |
| `SearchResult` | `locator` | `Locator?` | Não | `null` | Localizador do resultado |
| `FormEntry` | `content` | `String` | Sim | — | Conteúdo textual do campo de formulário/anotação |
| `FormEntry` | `index` | `Int` | Sim | — | Índice posicional do campo no DOM |
| `PageFormState` | `annotations` | `List<FormEntry>` | Sim | `emptyList()` | Lista de anotações livres da página |
| `PageFormState` | `answers` | `List<FormEntry>` | Sim | `emptyList()` | Lista de respostas de exercícios da página |

---

## 2. Modelos de Sessão, DRM, ZipLimits e Temas

| Entidade | Pacote | Campos Principais | Descrição |
|----------|--------|-------------------|-----------|
| `ReaderState` | `session` | `documentId`, `metadata`, `pageIndex`, `spineLen`, `scrollY`, `currentSubPage`, `totalSubPages`, `readingMode`, `progress`, `toc`, `theme`, `fontScale`, `fontFamily`, `fontSizeClass`, `isNightMode`, `currentPageSerializedHighlights`, `currentPageHighlights`, `currentPageFormState`, `bookmarks`, `hasBookmarkAtCurrentPage` | Estado imutável completo emitido via `StateFlow` para a UI |
| `RenderedDocument` | `session` | `html: String`, `href: String`, `baseUrl: String` | HTML processado com CSS/JS injetados e URL base do diretório do capítulo |
| `EncryptedResourceInfo` | `drm` | `uri: String`, `algorithm: String`, `compressionMethod: String?`, `originalLength: Long?` | Metadados de `META-INF/encryption.xml` por recurso |
| `ZipLimits` | `provider` | `maxEntries` (`10_000`), `maxEntryUncompressedBytes` (`50 MB`), `maxTotalUncompressedBytes` (`1 GB`), `maxCacheBytes` (`16 MB`) | Parâmetros de segurança e limite de cache LRU do leitor ZIP |
| `ThemeTokens` | `theme` | `designSystem: DesignSystem`, `colors: Colors`, `typography: Typography`, `shape: Shape` | Tokens de design serializáveis para geração de CSS |
