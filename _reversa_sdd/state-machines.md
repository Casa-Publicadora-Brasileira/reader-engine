# Máquinas de Estado — `reader-engine`

> Gerado pelo **Detective** (Reversa v1.3.6) | Nível: `detalhado`

---

## 1. Máquina de Estados da Sessão de Leitura (`ReaderSession`)

Embora `ReaderState` seja uma `data class` imutável exposta via `StateFlow<ReaderState>`, o ciclo de vida completo de uma sessão de leitura desde a abertura do `Source` até o fechamento/troca de livro segue estados bem definidos governados por `ReaderEngine.open(...)` e `ReaderSession.dispatch(ReaderCommand)`.

### 1.1 Diagrama de Estados do Ciclo de Vida da Sessão

```mermaid
stateDiagram-v2
    [*] --> Uninitialized : App iniciado
    Uninitialized --> ParsingSource : ReaderEngine.open(source, initialState)
    ParsingSource --> ParseFailed : ReaderException (UnsupportedFormat / InvalidContent)
    ParseFailed --> [*] : Propaga excecao ao Host

    ParsingSource --> HydratingState : Publication criada + sha256DocumentId resolvido
    HydratingState --> ReadyScrolled : readingMode == SCROLLED (default)
    HydratingState --> ReadyPaginated : readingMode == PAGINATED ou rendition:layout == pre-paginated

    state ActiveSession {
        ReadyScrolled --> ReadyPaginated : SetReadingMode(PAGINATED)
        ReadyPaginated --> ReadyScrolled : SetReadingMode(SCROLLED)

        ReadyScrolled --> NavigatingChapter : NextPage / PreviousPage / GoToPage / OpenTocItem / OpenBookmark / OpenSearchResult
        ReadyPaginated --> NavigatingChapter : NextPage (na ultima subpagina) / PreviousPage (na subpagina 0) / GoToPage

        NavigatingChapter --> ReadyScrolled : Emite PageChanged + ScrollToY(0) + ApplyHighlightsJs + RestoreFormsJs
        NavigatingChapter --> ReadyPaginated : Reset subPage=0 + Emite PageChanged + ApplyHighlightsJs + RestoreFormsJs

        ReadyScrolled --> UpdatingScroll : UpdateScroll (delta >= 2px)
        UpdatingScroll --> ReadyScrolled : Atualiza ReadingProgress + Emite ProgressSaved

        ReadyPaginated --> NavigatingSubPage : NextSubPage / PreviousSubPage / SyncSubPageState
        NavigatingSubPage --> ReadyPaginated : Atualiza currentSubPage / totalSubPages + ExecuteJs(goToSubPage)

        ReadyScrolled --> MutatingAnnotations : AddBookmark / RemoveBookmark / AddHighlight / RemoveHighlight / SetFormValue / ClearCurrentPageForms
        ReadyPaginated --> MutatingAnnotations : AddBookmark / RemoveBookmark / AddHighlight / RemoveHighlight / SetFormValue / ClearCurrentPageForms
        MutatingAnnotations --> ReadyScrolled : Atualiza StateFlow + Emite Updated Event + UI Effect JS
        MutatingAnnotations --> ReadyPaginated : Atualiza StateFlow + Emite Updated Event + UI Effect JS

        ReadyScrolled --> Searching : Search(query)
        ReadyPaginated --> Searching : Search(query)
        Searching --> ReadyScrolled : Atualiza searchResults + Emite SearchCompleted
        Searching --> ReadyPaginated : Atualiza searchResults + Emite SearchCompleted
    }

    ActiveSession --> Closed : Host cancela CoroutineScope / Fecha livro
    Closed --> [*]
```

### 1.2 Tabela de Transições de `ReaderSession.dispatch(ReaderCommand)`

| Estado Origem | Comando (`ReaderCommand`) | Condição de Guarda | Estado Destino / Mutação em `ReaderState` | Eventos Emitidos (`ReaderEvent`) | Efeitos de UI (`ReaderUiEffect`) | Confiança |
|---------------|---------------------------|--------------------|-------------------------------------------|----------------------------------|----------------------------------|-----------|
| `Ready*` | `NextPage` | `currentPage < totalPages - 1` | `currentPage + 1`, `scrollY = 0`, `currentSubPage = 0` | `PageChanged`, `ProgressSaved` | `ScrollToY(0)`, `ApplyHighlightsJs`, `RestoreFormsJs` | 🟢 CONFIRMADO |
| `Ready*` | `NextPage` | `currentPage >= totalPages - 1` | Sem mutação de página | `Error(PAGE_OUT_OF_RANGE)` | `ShowNotice(alreadyAtLastPage)` | 🟢 CONFIRMADO |
| `Ready*` | `PreviousPage` | `currentPage > 0` | `currentPage - 1`, `scrollY = 0`, `currentSubPage = 0` | `PageChanged`, `ProgressSaved` | `ScrollToY(0)`, `ApplyHighlightsJs`, `RestoreFormsJs` | 🟢 CONFIRMADO |
| `Ready*` | `PreviousPage` | `currentPage == 0` | Sem mutação de página | `Error(PAGE_OUT_OF_RANGE)` | `ShowNotice(alreadyAtFirstPage)` | 🟢 CONFIRMADO |
| `Ready*` | `GoToPage(page)` | `page in 0 until totalPages` | `currentPage = page`, `scrollY = 0`, `currentSubPage = 0` | `PageChanged`, `ProgressSaved` | `ScrollToY(0)`, `ApplyHighlightsJs`, `RestoreFormsJs` | 🟢 CONFIRMADO |
| `Ready*` | `GoToPage(page)` | `page !in 0 until totalPages` | Sem mutação | `Error(INVALID_PAGE_TARGET)` | `ShowNotice(invalidTargetPage)` | 🟢 CONFIRMADO |
| `ReadyScrolled` | `UpdateScroll(y, ch, vh)` | `abs(y - scrollY) >= 2` ou dimensões mudaram | Atualiza `scrollY` e `progress` (`ReadingProgress`) | `ProgressSaved(progress)` | Nenhum | 🟢 CONFIRMADO |
| `ReadyPaginated` | `NextSubPage` | `currentSubPage < totalSubPages - 1` | `currentSubPage + 1` | `ProgressSaved` | `ExecuteJs("window.readerJS.nextSubPage()")` | 🟢 CONFIRMADO |
| `ReadyPaginated` | `NextSubPage` | `currentSubPage >= totalSubPages - 1` | Delega internamente para `NextPage` | `PageChanged`, `ProgressSaved` | Transição para próximo capítulo | 🟢 CONFIRMADO |
| `ReadyPaginated` | `PreviousSubPage` | `currentSubPage > 0` | `currentSubPage - 1` | `ProgressSaved` | `ExecuteJs("window.readerJS.prevSubPage()")` | 🟢 CONFIRMADO |
| `ReadyPaginated` | `PreviousSubPage` | `currentSubPage == 0` | Delega internamente para `PreviousPage` | `PageChanged`, `ProgressSaved` | Transição para capítulo anterior | 🟢 CONFIRMADO |
| `Ready*` | `SetReadingMode(mode)` | `mode != readingMode` | Atualiza `readingMode`, reseta `currentSubPage = 0` | `ReadingModeUpdated(mode)` | Re-renderização HTML da página no host | 🟢 CONFIRMADO |
| `Ready*` | `AddBookmark(label)` | Sempre permitido | Adiciona/atualiza `Bookmark` para `currentPage` e `scrollY` | `BookmarksUpdated` | `ShowNotice(bookmarkAdded)` | 🟢 CONFIRMADO |
| `Ready*` | `RemoveBookmark(page)` | Sempre permitido | Remove `Bookmark` com `pageIndex == page` | `BookmarksUpdated` | `ShowNotice(bookmarkRemoved)` | 🟢 CONFIRMADO |
| `Ready*` | `AddHighlight(...)` | `text.isNotBlank()` | Insere/atualiza `HighlightItem` | `HighlightsUpdated` | `ApplyHighlightsJs`, `ShowNotice(highlightSaved)` | 🟢 CONFIRMADO |
| `Ready*` | `AddHighlight(...)` | `text.isBlank()` | Sem mutação | `Error(EMPTY_HIGHLIGHT_TEXT)` | `ShowNotice(emptyHighlightText)` | 🟢 CONFIRMADO |
| `Ready*` | `SetFormValue(key, val)` | `key.isNotBlank()` | Insere/atualiza `FormEntry(currentPage, key, val)` | `FormsUpdated` | Nenhum | 🟢 CONFIRMADO |
| `Ready*` | `ClearCurrentPageForms` | Sempre permitido | Remove todos os `FormEntry` de `currentPage` | `FormsUpdated` | `TriggerClearFormsJs`, `ShowNotice(formsCleared)` | 🟢 CONFIRMADO |
| `Ready*` | `SetTheme(tokens)` | Sempre permitido | Atualiza `theme = tokens` | `ThemeUpdated` | `ApplyThemeJs` | 🟢 CONFIRMADO |
| `Ready*` | `SetFontSize(sp)` | Coerced para `12..36` | Atualiza `fontSizeSp` | `FontSizeUpdated` | `ApplyFontSizeJs` | 🟢 CONFIRMADO |

---

## 2. Máquina de Estados de Modo de Renderização (`ReadingMode` & `RenditionLayout`)

O motor combina a preferência do usuário (`ReadingMode`: `SCROLLED` vs `PAGINATED`) com os metadados estruturais do EPUB 3 (`Metadata.renditionLayout`: `"reflowable"` vs `"pre-paginated"`).

```mermaid
stateDiagram-v2
    [*] --> InspectMetadata : Carrega Publication.metadata
    InspectMetadata --> FixedLayoutMode : renditionLayout == "pre-paginated"
    InspectMetadata --> ReflowableScrolled : renditionLayout != "pre-paginated" & readingMode == SCROLLED
    InspectMetadata --> ReflowablePaginated : renditionLayout != "pre-paginated" & readingMode == PAGINATED

    ReflowableScrolled --> ReflowablePaginated : ReaderCommand.SetReadingMode(PAGINATED)
    ReflowablePaginated --> ReflowableScrolled : ReaderCommand.SetReadingMode(SCROLLED)

    state FixedLayoutMode {
        [*] --> ViewportScaled : Injeta meta viewport e CSS fixed-layout (sem multi-column)
    }

    state ReflowablePaginated {
        [*] --> MultiColumnCss : Injeta CSS column-width: 100vw, overflow: hidden
        MultiColumnCss --> SubPageSync : JS reporta syncSubPages(current, total)
    }

    state ReflowableScrolled {
        [*] --> VerticalScroll : Overflow vertical padrao + listener scroll 80ms
    }
```

---

## 3. Máquina de Estados do Ciclo de Desofuscação / Descriptografia (`DecryptingResourceProvider`)

Para cada leitura de recurso (`readBytes` ou `readRange`) em um arquivo EPUB:

```mermaid
stateDiagram-v2
    [*] --> LookupResource : readBytes(href) / readRange(href)
    LookupResource --> CheckEncryptionMap : Normaliza href e consulta encryptedResources[href]

    CheckEncryptionMap --> Unencrypted : href nao consta em META-INF/encryption.xml
    Unencrypted --> ReturnRawOrRange : Delega direto ao ZipResourceProvider (suporta zero-copy range)

    CheckEncryptionMap --> Encrypted : href encontrado em encryptedResources
    Encrypted --> CheckAlgorithm : Inspeciona encryptionInfo.algorithm

    CheckAlgorithm --> IdpfDeobfuscation : algorithm == http://www.idpf.org/2008/embedding
    IdpfDeobfuscation --> ReturnTransformed : XOR primeiros 1040 bytes com SHA-1(unique-identifier)

    CheckAlgorithm --> AdobeDeobfuscation : algorithm == http://ns.adobe.com/pdf/enc#RC
    AdobeDeobfuscation --> ReturnTransformed : XOR primeiros 1024 bytes com UUID(16 bytes)

    CheckAlgorithm --> CustomDrm : Outro algoritmo (ex. LCP / ADEPT)
    CustomDrm --> DelegatedDecrypt : fallbackDecryptor != null
    CustomDrm --> PassthroughFallback : fallbackDecryptor == null (retorna rawBytes)

    DelegatedDecrypt --> ReturnTransformed : fallbackDecryptor.decrypt(...)
```
