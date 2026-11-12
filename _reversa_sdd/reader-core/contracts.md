# `reader-core` — Contratos de Interface e Protocolos (`contracts`)

> Gerado pelo **Writer** (Reversa v1.3.6) | Unit: `reader-core` | Nível: `detalhado`

---

## 1. Contrato de Comandos da Sessão (`ReaderCommand`)

Todos os comandos aceitos por `ReaderSession.dispatch(command: ReaderCommand)`:

| Comando | Parâmetros | Efeito no `ReaderState` | Evento Emitido (`ReaderEvent`) | Efeito de UI (`ReaderUiEffect`) | Confiança |
|---------|------------|-------------------------|--------------------------------|---------------------------------|-----------|
| `NextPage` | — | `currentPage + 1`, `scrollY = 0`, `currentSubPage = 0` | `PageChanged`, `ProgressSaved` | `ScrollToY(0)`, `ApplyHighlightsJs`, `RestoreFormsJs` | 🟢 |
| `PreviousPage` | — | `currentPage - 1`, `scrollY = 0`, `currentSubPage = 0` | `PageChanged`, `ProgressSaved` | `ScrollToY(0)`, `ApplyHighlightsJs`, `RestoreFormsJs` | 🟢 |
| `GoToPage` | `page: Int` | `currentPage = page`, `scrollY = 0`, `currentSubPage = 0` | `PageChanged`, `ProgressSaved` | `ScrollToY(0)`, `ApplyHighlightsJs`, `RestoreFormsJs` | 🟢 |
| `UpdateScroll` | `scrollY: Int, contentHeightPx: Int, viewportHeightPx: Int` | Atualiza `scrollY` e `progress` (se delta `>= 2px` ou dimensões mudaram) | `ProgressSaved(progress)` | — | 🟢 |
| `SetReadingMode` | `mode: ReadingMode` (`SCROLLED` \| `PAGINATED`) | Atualiza `readingMode`, `currentSubPage = 0` | `ReadingModeUpdated(mode)` | — | 🟢 |
| `NextSubPage` | — | `currentSubPage + 1` (ou `NextPage` se na última sub-página) | `ProgressSaved` | `ExecuteJs("window.readerJS.nextSubPage()")` | 🟢 |
| `PreviousSubPage` | — | `currentSubPage - 1` (ou `PreviousPage` se na sub-página 0) | `ProgressSaved` | `ExecuteJs("window.readerJS.prevSubPage()")` | 🟢 |
| `ResetSubPage` | — | `currentSubPage = 0`, `totalSubPages = 1` | — | — | 🟢 |
| `SyncSubPageState` | `currentSubPage: Int, totalSubPages: Int` | Atualiza `currentSubPage`, `totalSubPages` e `progress` | `ProgressSaved` | — | 🟢 |
| `AddBookmark` | `label: String? = null` | Insere/substitui `Bookmark` na `currentPage` | `BookmarksUpdated` | `ShowNotice(bookmarkAdded)` | 🟢 |
| `RemoveBookmark` | `pageIndex: Int` | Remove `Bookmark` da `pageIndex` | `BookmarksUpdated` | `ShowNotice(bookmarkRemoved)` | 🟢 |
| `OpenBookmark` | `bookmark: Bookmark` | Navega para `bookmark.pageIndex` e `bookmark.scrollY` | `PageChanged`, `ProgressSaved` | `ScrollToY(bookmark.scrollY)`, `ApplyHighlightsJs`, `RestoreFormsJs` | 🟢 |
| `AddHighlight` | `text: String, style: HighlightStyle, note: String?, id: String?, pageIndex: Int?, prefix: String?, suffix: String?` | Insere/atualiza `HighlightItem` | `HighlightsUpdated` | `ApplyHighlightsJs`, `ShowNotice(highlightSaved)` | 🟢 |
| `RemoveHighlight` | `id: String` | Remove `HighlightItem` por `id` | `HighlightsUpdated` | `ApplyHighlightsJs`, `ShowNotice(highlightRemoved)` | 🟢 |
| `SetFormValue` | `key: String, value: String` | Insere/atualiza `FormEntry(currentPage, key, value)` | `FormsUpdated` | — | 🟢 |
| `ClearCurrentPageForms` | — | Remove `FormEntry` de `currentPage` | `FormsUpdated` | `TriggerClearFormsJs`, `ShowNotice(formsCleared)` | 🟢 |
| `Search` | `query: String, maxResults: Int = 100` | Atualiza `searchResults` | `SearchCompleted(query, results)` | — | 🟢 |
| `OpenSearchResult` | `result: SearchResult` | Navega para `result.pageIndex` | `PageChanged`, `ProgressSaved` | `ScrollToY(0)`, `ApplyHighlightsJs`, `RestoreFormsJs` | 🟢 |
| `OpenTocItem` | `item: TocItem` | Navega para `item.pageIndex` | `PageChanged`, `ProgressSaved` | `ScrollToY(0)` (ou `ExecuteJs` para âncora `#fragment`), `ApplyHighlightsJs`, `RestoreFormsJs` | 🟢 |
| `SetTheme` | `theme: ThemeTokens` | Atualiza `theme` | `ThemeUpdated(theme)` | `ApplyThemeJs` | 🟢 |
| `SetFontSize` | `sizeSp: Int` | Atualiza `fontSizeSp` (`12..36`) | `FontSizeUpdated(fontSizeSp)` | `ApplyFontSizeJs` | 🟢 |

---

## 2. Contrato JavaScript Injetado (`window.readerJS` & `window.ReaderEngine`)

O objeto global `window.readerJS` injetado por `ReaderJsBridge.injectIntoHtml` (`ReaderJsBridge.kt:290-510`) expõe:

```javascript
window.readerJS = {
  applyTheme(themeCssVarsJson),      // Aplica variáveis CSS (--reader-bg, --reader-fg, etc.) no :root
  setFontSize(sizeSp),               // Atualiza --reader-font-size e recalcula sub-páginas se paginado
  applyHighlights(highlightsJson),   // Renderiza <mark data-highlight-id="..."> no DOM e retorna contagem
  restoreForms(formsJson),           // Preenche <input>, <textarea>, <select> por name/id/data-form-key
  clearForms(),                      // Limpa campos interativos da página atual
  nextSubPage(),                     // Avança 1 coluna no modo paginado e sincroniza via postAction
  prevSubPage(),                     // Retrocede 1 coluna no modo paginado e sincroniza via postAction
  goToSubPage(index),                // Posiciona o container horizontal na coluna index * viewportWidth
  recalculateSubPages()              // Mede scrollWidth / clientWidth e envia type="subpage" ao nativo
};

window.ReaderEngine = {
  postAction(actionObjectOrJson)     // Encaminha para ReaderNativeBridge.postMessage ou webkit.messageHandlers
};
```

### Payloads JSON de `window.ReaderEngine.postAction` (WebView -> Host Nativo)

| `type` | Formato do Payload JSON | Comando `ReaderSession` Equivalente | Confiança |
|--------|-------------------------|-------------------------------------|-----------|
| `"highlight"` | `{"type":"highlight","text":"...","style":"YELLOW","note":"...","id":"..."}` | `ReaderCommand.AddHighlight(...)` | 🟢 |
| `"form"` | `{"type":"form","key":"q1","value":"resposta"}` | `ReaderCommand.SetFormValue(key, value)` | 🟢 |
| `"subpage"` | `{"type":"subpage","currentSubPage":1,"totalSubPages":4}` | `ReaderCommand.SyncSubPageState(1, 4)` | 🟢 |
| `"navigate"` | `{"type":"navigate","target":"next" \| "prev" \| "page","page":2}` | `NextPage` / `PreviousPage` / `GoToPage(2)` | 🟢 |

---

## 3. Contrato de Provedor de Descriptografia (`ContentDecryptor`)

```kotlin
public fun interface ContentDecryptor {
    public fun decrypt(
        href: String,
        encryptedBytes: ByteArray,
        encryptionInfo: EncryptedResourceInfo,
    ): ByteArray
}
```
- Invocado por `StandardEpubFontDeobfuscator` quando `encryptionInfo.algorithm` em `META-INF/encryption.xml` não é `http://www.idpf.org/2008/embedding` nem `http://ns.adobe.com/pdf/enc#RC`. 🟢
