# Fluxograma por Função — `ReaderSession.dispatch(command)`

> **Gerado por:** Reversa Archaeologist (`escavacao` — Nível `detalhado`)
> **Localização:** `reader-core/src/commonMain/kotlin/com/readerengine/core/session/ReaderSession.kt:364-563`

```mermaid
flowchart TD
    A["dispatch(command: ReaderCommand)"] --> B{"Tipo de Comando"}
    B -->|"NextPage / PrevPage"| C{"readingMode == PAGINATED e há sub-página?"}
    C -->|"Sim"| D["Atualiza currentSubPage + emite EvaluateJs(nextSubPage/prevSubPage)"]
    C -->|"Não"| E["navigateToPageInternal(novoIndice, scrollY = 0)"]

    B -->|"GoToPage / GoToLocator / GoToBookmark"| E
    B -->|"GoToHref"| F["handleUrlNavigation(href)"]

    B -->|"UpdateScroll"| G["Calcula ReadingProgress.calculatePercentage"]
    G --> H{"Mudou página ou |deltaScrollY| >= 2px?"}
    H -->|"Sim"| I["Emite ReaderEvent.ProgressUpdated"]
    H -->|"Não"| J["Apenas atualiza StateFlow"]

    B -->|"SetTheme / SetFontScale / SetFontFamily / SetNightMode / SetReadingMode"| K["Atualiza ReaderState + emite EvaluateJs + PreferencesChanged"]
    B -->|"ApplyHighlightStyle / RemoveSelectedHighlight / RemoveHighlightItem"| L["Emite EvaluateJs para window.readerJS"]
    B -->|"ToggleBookmark / RemoveBookmark"| M["Atualiza lista de Bookmarks + emite BookmarksChanged"]
    B -->|"TriggerAction"| N["Emite ReaderEvent.ActionTriggered + ReaderUiEffect.CustomAction"]
```
