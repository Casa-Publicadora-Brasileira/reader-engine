# reader-engine (Kotlin Multiplatform)

Motor de leitura multiplataforma (**Kotlin Multiplatform — KMP**) universal e *headless* para aplicativos Android, iOS e JVM.

O módulo `:reader-core` (`com.readerengine.core`) encapsula toda a engenharia de leitura — parsers de **EPUB 2/3**, **HPUB / Baker Framework** e **JSON**, resolução lazy de recursos ZIP em memória via `Okio` com proteção contra *Zip Bomb* (`ZipLimits`), busca textual insensível a acentos com cache por capítulo (`BookSearchEngine`), localizadores multiplataforma (`Locator`), marcadores de página (`Bookmark`), ponte JS segura para marcações no DOM e formulários HTML interativos (`ReaderJsBridge`), cálculo monotônico de progresso de leitura e compressão transparente `gz:` (`PayloadCodec`) — expondo fluxos reativos (`StateFlow<ReaderState>`, `SharedFlow<ReaderEvent>`, `SharedFlow<ReaderUiEffect>`) para que qualquer aplicativo host cuide apenas da sua **UI nativa** e da **persistência no seu próprio backend**.

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
│       │   ├── model/                 # Publication, Locator, ReadingProgress, Bookmark, SearchResult, ReaderStrings...
│       │   ├── parser/                # EpubParser, HpubParser, JsonParser, XmlEntities, ParserRegistry
│       │   ├── provider/              # ZipResourceProvider (lazy + ZipLimits), MemoryResourceProvider
│       │   ├── search/                # BookSearchEngine (busca insensível a acentos no spine)
│       │   ├── session/               # ReaderEngine, ReaderSession, RenderedDocument, ReaderState, ReaderEvent, ReaderCommand
│       │   └── theme/                 # ThemeTokens (Material 3, HIG, Fluent, Custom), DesignSystem
│       └── commonTest/kotlin/com/readerengine/core/
│           └── ReaderEngineTest.kt    # Suíte de testes unitários multiplataforma
└── sample-android/                    # App Android de demonstração (:sample-android) em Jetpack Compose
```

---

## Arquitetura Reativa e Agnóstica de Backend

O `:reader-core` **não possui acoplamento com nenhum backend específico** (funciona com SQLite/Room, CoreData, REST API, Firestore ou armazenamento local):

1. **Hidratação na abertura (`InitialBookState`)**:
   O app host passa opcionalmente o estado salvo (`initialPageIndex`, `initialScrollY`, `savedPageHighlights`, `savedPageForms`, `savedBookmarks`, `strings`) ao chamar `ReaderEngine.open(name, bytes, initialState)`. Caso o livro não possua `identifier` nos metadados, um ID determinístico `doc-<sha256>` é gerado automaticamente.
2. **Renderização com Base URL por capítulo (`currentDocument()`)**:
   `session.currentDocument()` retorna `RenderedDocument(html, href, baseUrl)` (ex.: `book://content/OEBPS/`), garantindo que imagens, fontes e folhas de estilo relativas dentro do pacote EPUB/HPUB sejam resolvidas tanto via `WebView.loadDataWithBaseURL` quanto via `session.readResource(href)`.
3. **Estado de UI (`StateFlow<ReaderState>`)**:
   A UI nativa (Compose / View / SwiftUI / UIKit) observa `session.state` para renderizar o capítulo atual, sumário hierárquico (`toc` com `level`), marcadores (`bookmarks`), destaques da página (`currentPageHighlights`), progresso monotônico (`0..100%`) e modo noturno.
4. **Eventos de Persistência (`SharedFlow<ReaderEvent>`)**:
   Sempre que o usuário navega, rola a página, adiciona/remove um marcador, cria um grifo ou preenche um campo interativo, a sessão emite:
   - `ReaderEvent.ProgressUpdated(progress)` (com `Locator` e deduplicação automática de scroll)
   - `ReaderEvent.PageHighlightsChanged(documentId, pageIndex, rawSerializedHighlights, compressedPayload, summaryItems)`
   - `ReaderEvent.PageFormChanged(documentId, pageIndex, rawJsonPayload, compressedPayload, formState)`
   - `ReaderEvent.BookmarksChanged(documentId, bookmarks, serializedJson)`
   - `ReaderEvent.PreferencesChanged(fontFamily, fontSizeClass, fontScale, isNightMode, theme)`
5. **Esquemas de URI Customizados Genéricos (`ReaderUiEffect.OpenCustomUri`)**:
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

### Gerar o `XCFramework` para iOS (em macOS)
```bash
./gradlew :reader-core:assembleReaderCoreXCFramework
```

---

## Licença

Distribuído sob a licença MIT. Veja [LICENSE](file:///home/antigravity/andreAlvesCarvalhojr/reader-engine/LICENSE) para mais detalhes.
