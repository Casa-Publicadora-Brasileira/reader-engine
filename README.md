# reader-engine (Kotlin Multiplatform)

Motor de leitura multiplataforma (**Kotlin Multiplatform — KMP**) universal e *headless* para aplicativos Android e iOS.

O módulo `:reader-core` (`com.readerengine.core`) encapsula toda a engenharia de leitura — parsers de **EPUB**, **HPUB / Baker Framework** e **JSON**, resolução de recursos ZIP em memória via `Okio`, busca textual completa (`BookSearchEngine`), marcadores de página (`Bookmark`), ponte JS para marcações de texto e formulários HTML interativos (`ReaderJsBridge`), cálculo de progresso de leitura e compressão transparente `gz:` (`PayloadCodec`) — expondo fluxos reativos (`StateFlow<ReaderState>`, `SharedFlow<ReaderEvent>`, `SharedFlow<ReaderUiEffect>`) para que qualquer aplicativo host cuide apenas da sua **UI nativa** e da **persistência no seu próprio backend**.

---

## Estrutura do Projeto

```text
reader-engine/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── fixtures/                          # Arquivos de teste (.epub, .hpub, .json)
├── reader-core/                       # Biblioteca KMP (:reader-core -> Android AAR / iOS XCFramework / JVM)
│   └── src/
│       ├── commonMain/kotlin/com/readerengine/core/
│       │   ├── bridge/                # ReaderJsBridge & PayloadCodec (gz:)
│       │   ├── error/                 # ReaderException
│       │   ├── model/                 # Publication, ReadingProgress, Bookmark, SearchResult, HighlightItem, PageFormState...
│       │   ├── parser/                # EpubParser, HpubParser, JsonParser, ParserRegistry
│       │   ├── provider/              # ZipResourceProvider (Okio), MemoryResourceProvider
│       │   ├── search/                # BookSearchEngine (busca textual em todo o spine)
│       │   ├── session/               # ReaderEngine, ReaderSession, ReaderState, ReaderEvent, ReaderCommand
│       │   └── theme/                 # ThemeTokens, DesignSystem
│       └── commonTest/kotlin/com/readerengine/core/
│           └── ReaderEngineTest.kt    # Testes unitários multiplataforma
└── sample-android/                    # App Android de demonstração (:sample-android) em Jetpack Compose
```

---

## Arquitetura Reativa e Agnóstica de Backend

O `:reader-core` **não possui acoplamento com nenhum backend específico** (funciona com SQLite/Room, CoreData, REST API, Firestore ou armazenamento local):

1. **Hidratação na abertura (`InitialBookState`)**:
   O app host passa opcionalmente o estado salvo (`initialPageIndex`, `initialScrollY`, `savedPageHighlights`, `savedPageForms`, `savedBookmarks`) ao chamar `ReaderEngine.open(name, bytes, initialState)`.
2. **Estado de UI (`StateFlow<ReaderState>`)**:
   A UI nativa (Compose / View / SwiftUI / UIKit) observa `session.state` para renderizar o capítulo atual, sumário (`toc`), marcadores (`bookmarks`), destaques da página (`currentPageHighlights`), progresso (`0..100%`) e modo noturno.
3. **Eventos de Persistência (`SharedFlow<ReaderEvent>`)**:
   Sempre que o usuário navega, rola a página, adiciona/remove um marcador, cria um grifo ou preenche um campo interativo, a sessão emite:
   - `ReaderEvent.ProgressUpdated(progress)`
   - `ReaderEvent.PageHighlightsChanged(documentId, pageIndex, rawSerializedHighlights, compressedPayload, summaryItems)`
   - `ReaderEvent.PageFormChanged(documentId, pageIndex, rawJsonPayload, compressedPayload, formState)`
   - `ReaderEvent.BookmarksChanged(documentId, bookmarks, serializedJson)`
   - `ReaderEvent.PreferencesChanged(fontFamily, fontSizeClass, fontScale, isNightMode, theme)`
4. **Esquemas de URI Customizados Genéricos (`ReaderUiEffect.OpenCustomUri`)**:
   Qualquer link com esquema customizado clicado no livro (`verse://?q=...`, `footnote://...`, `audio://...`, `glossary://...`) é interceptado e emitido como `ReaderUiEffect.OpenCustomUri(scheme, rawUrl, title, content)`.

---

## Comandos Principais

### Executar testes unitários do `:reader-core`
```bash
./gradlew :reader-core:jvmTest
```

### Compilar o aplicativo de exemplo Android (`:sample-android`)
```bash
./gradlew :sample-android:assembleDebug
```

### Gerar o `XCFramework` para iOS
```bash
./gradlew :reader-core:assembleReaderCoreXCFramework
```
