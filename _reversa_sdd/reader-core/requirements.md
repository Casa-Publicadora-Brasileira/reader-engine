# `reader-core` — Requisitos de Especificação

> Gerado pelo **Writer** (Reversa v1.3.6) | Unit: `reader-core` | Nível: `detalhado`

---

## Visão Geral

O módulo **`reader-core`** (`com.readerengine.core`) é a biblioteca Kotlin Multiplatform (`commonMain`, `androidMain`, `iosMain`, `jvmMain`) que implementa o motor central de leitura de publicações digitais nos formatos **EPUB 2**, **EPUB 3** (reflowable e fixed-layout), **HPUB** e **`reader-json`**. Ele resolve o problema de parsing zero-copy em disco, desofuscação de fontes embarcadas e DRM plugável, busca textual insensível a diacríticos, máquina de estado reativa (`ReaderSession`) e injeção determinística de HTML/CSS/JS para WebViews nativas sem acoplamento de plataforma. 🟢

---

## Responsabilidades

- Abrir fontes de documentos em memória (`ByteArray`) ou em disco (`okio.Path`) e calcular o identificador determinístico `sha256DocumentId` em $O(1)$ por amostragem de 64 KB iniciais e finais. 🟢
- Ler a tabela Central Directory de arquivos ZIP (`.epub`, `.hpub`) diretamente dos 65.557 bytes finais do arquivo, aplicando proteções anti-Zip Bomb (`ZipLimits`), cache LRU em memória de 16 MB e leitura parcial zero-copy (`readRange`) para entradas `STORED`. 🟢
- Realizar o parsing completo de metadados Dublin Core/OPF (`rendition:layout`, `rendition:orientation`, `rendition:spread`), sumários hierárquicos (`<nav epub:type="toc">` e `.ncx`) e spine de capítulos lineares e não-lineares. 🟢
- Interpretar `META-INF/encryption.xml`, desofuscar fontes nativamente pelos algoritmos IDPF (SHA-1 1.040 bytes) e Adobe (UUID 1.024 bytes) e delegar recursos protegidos por DRM de terceiros para `ContentDecryptor` (`fallbackDecryptor`). 🟢
- Gerenciar a máquina de estados reativa (`ReaderSession`) via `StateFlow<ReaderState>`, `SharedFlow<ReaderEvent>` e `SharedFlow<ReaderUiEffect>` (com wrappers canceláveis `FlowSubscription` para iOS/Swift). 🟢
- Calcular o progresso global monotônico (`ReadingProgress`) combinando o índice da página atual com a fração de scroll vertical. 🟢
- Executar buscas textuais full-text insensíveis a maiúsculas/minúsculas e diacríticos (`foldDiacritics` 1-para-1) com cache de texto plano por capítulo e geração de snippets de contexto. 🟢
- Gerar CSS de temas (`ThemeTokens`: `MATERIAL3`, `HIG`, `FLUENT`, `CUSTOM`), paginação multi-colunas CSS3, suporte a MathML e injetar o runtime JavaScript `window.readerJS` / `window.ReaderEngine` via `ReaderJsBridge`. 🟢
- Comprimir e descomprimir automaticamente payloads de estado JSON maiores ou iguais a 200.000 caracteres usando GZIP + Base64 (`gz:<base64>`) com trava de segurança em 16 MB (`PayloadCodec`). 🟢

---

## Regras de Negócio

- **RN-01 (Hash Determinístico O(1)):** Para arquivos em disco maiores que 128 KB (`131.072` bytes), `Source.sha256DocumentId()` calcula o SHA-256 sobre os 8 bytes do tamanho total + primeiros 64 KB + últimos 64 KB, evitando leitura integral do arquivo na abertura. 🟢
- **RN-02 (Proteção Anti-Zip Bomb):** `ZipResourceProvider` rejeita arquivos ZIP que excedam `maxEntries = 10.000`, `maxEntryUncompressedBytes = 50 MB` ou `maxTotalUncompressedBytes = 1 GB`, lançando `ReaderException.InvalidContent`. 🟢
- **RN-03 (Cache LRU de Recursos ZIP):** `ZipResourceProvider` mantém cache LRU thread-safe protegido por `Mutex` limitado a `maxCacheBytes = 16 MB`; entradas individuais maiores que 25% do teto (4 MB) não entram no cache. 🟢
- **RN-04 (Progresso Monotônico):** `ReadingProgress.from(...)` calcula `percentage = (((safePage + scrollFraction) / safeTotal) * 100.0).coerceIn(0.0, 100.0)`, onde `scrollFraction = (scrollY / (contentHeightPx - viewportHeightPx)).coerceIn(0.0, 1.0)` quando `contentHeightPx > viewportHeightPx && viewportHeightPx > 0`. Se `totalPages == 1 && scrollFraction == 0.0`, retorna `100.0%`. 🟢
- **RN-05 (Throttling de Scroll):** `ReaderSession` ignora comandos `UpdateScroll` quando `abs(clampedScrollY - current.scrollY) < 2` e as dimensões `contentHeightPx`/`viewportHeightPx` não mudaram. 🟢
- **RN-06 (Limites Tipográficos e Sanitização CSS):** `fontSizeSp` é restrito a `12..36`; cores e famílias de fontes em `ThemeTokens` são sanitizadas por regex antes de compor o bloco `<style>`. 🟢
- **RN-07 (Desofuscação de Fontes IDPF e Adobe):** Recursos declarados em `META-INF/encryption.xml` com `http://www.idpf.org/2008/embedding` sofrem XOR circular nos primeiros 1.040 bytes com o SHA-1 de 20 bytes do `unique-identifier`; recursos com `http://ns.adobe.com/pdf/enc#RC` sofrem XOR circular nos primeiros 1.024 bytes com os 16 bytes do UUID hexadecimal. 🟢
- **RN-08 (Resolução de Href em 3 Etapas):** `ReaderSession.resolveResourceHref(rawHref)` tenta resolver (1) caminho normalizado exato, (2) caminho relativo ao diretório de `currentPageHref`, e (3) sufixo case-insensitive `/<normalized>` na lista de recursos da publicação. 🟢
- **RN-09 (Busca Index-Preserving):** `BookSearchEngine.foldDiacritics` mapeia cada caractere acentuado ou ligadura (`U+00C0..U+024F`, `ß`, `æ`, `œ`) para exatamente 1 caractere ASCII, preservando os offsets de `matchOffset` e `matchLength` no texto original. 🟢
- **RN-10 (Restauração de Sub-página em Modo Paginado):** `InitialBookState` persiste `currentPage`, `scrollY` e `readingMode`, mas inicializa `currentSubPage = 0` ao hidratar a sessão. 🟡

---

## Requisitos Funcionais

| ID | Requisito | Prioridade | Critério de Aceite |
|----|-----------|------------|--------------------|
| **RF-01** | Abrir e identificar publicações a partir de `Source` em memória (`ByteArray`) ou em disco (`okio.Path`) | Must | `ReaderEngine.open(source)` retorna uma `ReaderSession` válida com `bookId` determinístico (`sha256-...` quando não informado) |
| **RF-02** | Realizar parsing de arquivos EPUB 2 e EPUB 3 (`container.xml`, `.opf`, `<nav>` HTML, `.ncx`) | Must | Extrai `Metadata` completo (incluindo `renditionLayout`, `coverHref`), `resources`, `pages`, `pageHrefs` e árvore `toc` aninhada |
| **RF-03** | Realizar parsing de pacotes HPUB (`book.json` + capítulos HTML) e documentos `reader-json` | Should | Publicações HPUB e JSON são abertas pela mesma chamada `ReaderEngine.open` via `ParserRegistry` |
| **RF-04** | Desofuscar fontes EPUB (IDPF e Adobe) e suportar `fallbackDecryptor` customizado | Must | Fontes ofuscadas têm os primeiros 1040/1024 bytes restaurados via XOR; recursos com algoritmo desconhecido chamam `fallbackDecryptor.decrypt` |
| **RF-05** | Fornecer leitura de recursos inteiros (`readResourceBytes`) e por intervalo (`readResourceRange`) | Must | Entradas `STORED` não criptografadas são lidas em fatias diretamente do `FileHandle` em disco sem inflar o arquivo inteiro |
| **RF-06** | Processar os 18 comandos de `ReaderCommand` via `ReaderSession.dispatch` | Must | Atualiza `state: StateFlow<ReaderState>` de forma síncrona/reativa e emite `ReaderEvent` e `ReaderUiEffect` correspondentes |
| **RF-07** | Suportar navegação por capítulos (`NextPage`, `PreviousPage`, `GoToPage`, `OpenTocItem`, `OpenBookmark`, `OpenSearchResult`) e por sub-páginas (`NextSubPage`, `PreviousSubPage`, `SyncSubPageState`) | Must | Navegação fora dos limites emite `ReaderEvent.Error` e `ReaderUiEffect.ShowNotice` localizado (`ReaderStrings`) sem corromper o estado |
| **RF-08** | Gerenciar anotações do leitor: Bookmarks, Highlights (`YELLOW`, `GREEN`, `BLUE`, `PINK`, `UNDERLINE`) e Formulários (`FormEntry`) | Must | Cada mutação atualiza `ReaderState`, emite evento de persistência e dispara efeito JS (`ApplyHighlightsJs`, `RestoreFormsJs`, `TriggerClearFormsJs`) |
| **RF-09** | Executar busca full-text insensível a caixa e acentos em todos os capítulos da publicação | Must | `ReaderCommand.Search(query)` retorna até `maxResults` ocorrências com `pageIndex`, `snippet`, `matchOffset`, `matchLength` e `locator` |
| **RF-10** | Transformar o HTML do capítulo atual injetando variáveis CSS de tema, modo noturno, colunas de paginação, MathML e script `window.readerJS` | Must | `session.renderCurrentPageHtml()` retorna documento HTML válido sem duplicar tags `<head>` ou quebrar atributos existentes |
| **RF-11** | Comprimir e descomprimir estados grandes via `PayloadCodec` | Should | Textos `>= 200.000` chars são codificados como `gz:<base64>` e restaurados identicamente no `decode`, bloqueando payloads `> 16 MB` |
| **RF-12** | Expor wrappers `FlowSubscription` (`watchState`, `watchEvents`, `watchUiEffects`) para clientes iOS/Swift | Must | Consumidores podem observar mudanças via closures e cancelar a assinatura invocando `subscription.close()` |

---

## Requisitos Não Funcionais

| Tipo | Requisito inferido | Evidência no código | Confiança |
|------|--------------------|---------------------|-----------|
| **Performance (Memória e I/O)** | Amostragem de hash SHA-256 em $O(1)$ (64 KB iniciais + 64 KB finais) para arquivos `> 128 KB` em disco | `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/Source.kt:79-102` | 🟢 |
| **Performance (Cache)** | Cache LRU em memória limitado a 16 MB (`maxCacheBytes`) e entradas individuais `<= 4 MB`, protegido por `kotlinx.coroutines.sync.Mutex` | `reader-core/src/commonMain/kotlin/com/readerengine/core/provider/ZipResourceProvider.kt:20-89` | 🟢 |
| **Performance (UI Throttling)** | Descarte de atualizações de scroll com variação `< 2px` no Kotlin e debounce de `80ms` no listener de scroll JS | `reader-core/src/commonMain/kotlin/com/readerengine/core/session/ReaderSession.kt:238`, `ReaderJsBridge.kt:381` | 🟢 |
| **Segurança (Anti-DoS / Zip Bomb)** | Limite de 10.000 entradas ZIP, 50 MB por entrada descompactada, 1 GB total no ZIP e 16 MB na descompressão de `PayloadCodec` | `ZipResourceProvider.kt:16-32`, `PayloadCodec.kt:12` | 🟢 |
| **Segurança (Anti-Injection & Path Traversal)** | Sanitização de caminhos (`normalizeHref` descarta `..`) e validação por regex de cores e fontes CSS (`sanitizeCssColor`, `sanitizeCssFont`) | `ZipResourceProvider.kt:299-315`, `ThemeTokens.kt:162-183` | 🟢 |
| **Manutenibilidade (Contrato de API)** | Obrigatoriedade de `explicitApi()` em todo o módulo KMP | `reader-core/build.gradle.kts:42` | 🟢 |

---

## Critérios de Aceitação

```gherkin
# Cenário 1: Abertura de EPUB 3 e cálculo monotônico de progresso (Happy Path)
Dado um arquivo EPUB 3 válido com 2 capítulos na spine aberto via ReaderEngine.open(source)
Quando a sessão é iniciada na página 0 com scrollY = 0
Então state.value.currentPage deve ser 0, totalPages deve ser 2 e progress.percentage deve ser 0.0
Quando é despachado ReaderCommand.UpdateScroll(scrollY = 500, contentHeightPx = 1500, viewportHeightPx = 500)
Então progress.percentage deve ser 25.0 (50% do primeiro capítulo de um total de 2 capítulos)
E o evento ReaderEvent.ProgressSaved deve ser emitido

# Cenário 2: Rejeição de arquivo ZIP acima dos limites de segurança (Falha / Zip Bomb)
Dado um arquivo EPUB/ZIP cuja entrada declara uncompressedSize maior que maxEntryUncompressedBytes (50 MB)
Quando ZipResourceProvider.readBytes(href) é invocado
Então deve lançar ReaderException.InvalidContent com mensagem indicando que a entrada excede o tamanho máximo permitido

# Cenário 3: Desofuscação de fonte IDPF (Happy Path)
Dado um EPUB contendo META-INF/encryption.xml com algoritmo "http://www.idpf.org/2008/embedding" para "OEBPS/fonts/Body.ttf" e unique-identifier "urn:uuid:12345"
Quando session.readResourceBytes("OEBPS/fonts/Body.ttf") é chamado
Então os primeiros 1040 bytes retornados devem corresponder ao XOR circular com o hash SHA-1 de 20 bytes de "urn:uuid:12345"

# Cenário 4: Busca textual insensível a acentos e caixa (Happy Path)
Dado uma publicação contendo o texto "Ação pedagógica e coração" no capítulo 0
Quando é despachado ReaderCommand.Search("acao pedagogica")
Então state.value.searchResults deve conter 1 resultado apontando para pageIndex = 0 com snippet destacando o trecho original acentuado

# Cenário 5: Tentativa de adicionar highlight vazio (Falha de Validação)
Dado uma ReaderSession ativa
Quando é despachado ReaderCommand.AddHighlight(text = "   ")
Então state.value.highlights não deve ser alterado
E deve ser emitido ReaderEvent.Error(ReaderErrorCode.EMPTY_HIGHLIGHT_TEXT) e ReaderUiEffect.ShowNotice
```

---

## Prioridade (MoSCoW)

| Requisito | MoSCoW | Justificativa |
|-----------|--------|---------------|
| Parsing de EPUB 2/3 (`EpubParser`) e leitura ZIP (`ZipResourceProvider`) | **Must** | Caminho crítico de abertura de livros; toda a sessão depende de `Publication` e `ResourceProvider` |
| Máquina de estados `ReaderSession` e comandos de navegação/progresso | **Must** | Núcleo de controle consumido por todas as telas e plataformas |
| Ponte HTML/CSS/JS (`ReaderJsBridge`) e `ThemeTokens` | **Must** | Indispensável para renderização visual na WebView |
| Desofuscação de fontes (`StandardEpubFontDeobfuscator`) e `DecryptingResourceProvider` | **Must** | Necessário para compatibilidade com EPUBs comerciais e DRM |
| Busca textual (`BookSearchEngine`) e anotações (`Bookmark`, `HighlightItem`, `FormEntry`) | **Must** | Funcionalidades centrais do leitor amplamente cobertas pela suíte de testes |
| Interoperabilidade iOS (`FlowSubscription` e `PlatformCompression.ios.kt`) | **Must** | Contrato público para o artefato `ReaderCore.xcframework` |
| Compressão de persistência (`PayloadCodec`) e parsers `HpubParser` / `JsonParser` | **Should** | Importantes para formatos complementares e otimização de armazenamento de grandes estados |

---

## Rastreabilidade de Código

| Arquivo | Função / Classe | Cobertura |
|---------|-----------------|-----------|
| `reader-core/src/commonMain/kotlin/com/readerengine/core/error/ReaderException.kt` | `ReaderException`, `UnsupportedFormat`, `InvalidContent`, `ResourceNotFound` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/model/Models.kt` | `Publication`, `Metadata`, `Resource`, `TocEntry`, `TocItem`, `Locator`, `ReadingProgress`, `HighlightItem`, `Bookmark`, `FormEntry`, `InitialBookState`, `ReaderStrings` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/Source.kt` | `Source`, `DocumentParser`, `ParserRegistry`, `ResourceProvider`, `MemoryResourceProvider` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/EpubParser.kt` | `EpubParser` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/HpubParser.kt` | `HpubParser` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/JsonParser.kt` | `JsonParser` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/XmlEntities.kt` | `XmlEntities` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/provider/ZipResourceProvider.kt` | `ZipLimits`, `ZipResourceProvider`, `normalizeHref` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/provider/PlatformCompression.kt` | `inflateRaw`, `gzipCompress`, `gzipDecompress` (`expect`/`actual` em `jvmMain`, `androidMain`, `iosMain`) | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/drm/DrmProvider.kt` | `EncryptedResourceInfo`, `EpubEncryptionParser`, `ContentDecryptor`, `StandardEpubFontDeobfuscator`, `DecryptingResourceProvider` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/session/ReaderSession.kt` | `ReaderEngine`, `ReaderSession`, `ReaderState`, `ReaderCommand`, `ReaderEvent`, `ReaderUiEffect` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/session/FlowSubscription.kt` | `FlowSubscription` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/bridge/PayloadCodec.kt` | `PayloadCodec` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/bridge/ReaderJsBridge.kt` | `ReaderJsBridge`, `SpecialUrlRoute` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/search/BookSearchEngine.kt` | `BookSearchEngine` | 🟢 |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/theme/ThemeTokens.kt` | `DesignLanguage`, `ThemeTokens` | 🟢 |
