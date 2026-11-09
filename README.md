# reader-engine (Kotlin Multiplatform)

Motor de leitura multiplataforma (**Kotlin Multiplatform — KMP**) universal e *headless* para aplicativos Android, iOS e JVM.

O módulo `:reader-core` (`com.readerengine.core`) encapsula toda a engenharia de leitura — parsers de **EPUB 2/3** (reflowable e **Fixed-Layout**), **HPUB / Baker Framework** e **JSON**, streaming de arquivos grandes em disco via `Okio FileHandle` com descompressão *lazy* e proteção contra *Zip Bomb* (`ZipLimits`), pipeline de decriptografia/DRM (`ContentDecryptor` + desofuscação oficial de fontes **IDPF** e **Adobe** via `META-INF/encryption.xml`), paginação horizontal por colunas CSS (`ReadingMode.PAGINATED`) e rolagem contínua (`ReadingMode.SCROLL`), suporte a **MathML**, busca textual insensível a acentos com cache por capítulo (`BookSearchEngine`), localizadores multiplataforma (`Locator`), marcadores de página (`Bookmark`), ponte JS segura para marcações no DOM e formulários HTML interativos (`ReaderJsBridge`), cálculo monotônico de progresso de leitura e compressão transparente `gz:` (`PayloadCodec`) — expondo tanto fluxos Kotlin (`StateFlow<ReaderState>`, `SharedFlow<ReaderEvent>`, `SharedFlow<ReaderUiEffect>`) quanto observadores ergonômicos para **Swift / iOS** (`watchState`, `watchEvents`, `watchUiEffects` -> `FlowSubscription`).

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
│       │   ├── bridge/                # ReaderJsBridge (Scroll + CSS Columns + MathML) & PayloadCodec (gz:)
│       │   ├── drm/                   # ContentDecryptor, EpubEncryptionParser, StandardEpubFontDeobfuscator
│       │   ├── error/                 # ReaderException
│       │   ├── model/                 # Publication, Locator, ReadingMode, RenditionLayout, ReadingProgress...
│       │   ├── parser/                # EpubParser, HpubParser, JsonParser, XmlEntities, ParserRegistry
│       │   ├── provider/              # ZipResourceProvider (RAM ou streaming FileHandle + ZipLimits)
│       │   ├── search/                # BookSearchEngine (busca insensível a acentos no spine)
│       │   ├── session/               # ReaderEngine, ReaderSession, FlowSubscription, RenderedDocument...
│       │   └── theme/                 # ThemeTokens (Material 3, HIG, Fluent, Custom), DesignSystem
│       └── commonTest/kotlin/com/readerengine/core/
│           └── ReaderEngineTest.kt    # Suíte de testes unitários multiplataforma
└── sample-android/                    # App Android de demonstração (:sample-android) em Jetpack Compose
```

---

## Principais Capacidades (v0.3.1)

1. **Streaming de Arquivos Grandes em Disco (`Okio FileHandle`)**:
   Abra arquivos `.epub` / `.hpub` de centenas de MB diretamente do sistema de arquivos sem carregar o pacote inteiro na memória RAM:
   ```kotlin
   val session = engine.open(path = "/storage/books/encyclopedia.epub".toPath())
   ```
2. **Ergonomia Nativa para iOS / Swift (`FlowSubscription`)**:
   No Swift (`ReaderCore.xcframework`), observe estados, eventos e efeitos de UI sem precisar instanciar coletores de Coroutines manualmente:
   ```swift
   let session = engine.open(name: "book.epub", bytes: epubData)
   let stateSub = session.watchState { state in
       print("Página \(state.pageIndex + 1)/\(state.spineLen)")
   }
   // Quando encerrar a tela:
   stateSub.cancel()
   session.close()
   ```
3. **Paginação Horizontal (`ReadingMode.PAGINATED`), Fixed-Layout e MathML**:
   Alterne em tempo real entre rolagem contínua (`ReadingMode.SCROLL`) e paginação horizontal por colunas CSS (`ReadingMode.PAGINATED`) com `session.dispatch(ReaderCommand.SetReadingMode(ReadingMode.PAGINATED))`. Livros EPUB 3 `rendition:layout="pre-paginated"` têm seus metadados (`layout`, `orientation`, `spread`) extraídos automaticamente.
4. **Pipeline de DRM e Desofuscação de Fontes (`ContentDecryptor`)**:
   O `EpubParser` processa automaticamente `META-INF/encryption.xml`, desofuscando fontes **IDPF** e **Adobe** nativamente e permitindo injetar um `ContentDecryptor` customizado (ex.: Readium LCP) no construtor do `ReaderEngine`.
5. **Streaming de Vídeo e Áudio HTML5 (`Range Requests` / HTTP 206)**:
   Vídeos `<video>` e áudios `<audio>` empacotados em livros EPUB/HPUB contam com suporte completo a seek e buffer contínuo através de requisições parciais HTTP 206 (`readResourceRange`), sem carregar vídeos inteiros na memória RAM. No Android, `WebChromeClient` gerencia a alternância para modo tela cheia integrada com o Compose e o botão Voltar do sistema.
6. **Mecanismo de Ações Interativas (Bridge E-book <-> Host App)**:
   Links `action://<nome>?q=<payload>` e chamadas `window.ReaderEngine.postAction(nome, payload)` disparam `ReaderEvent.ActionTriggered` e `ReaderUiEffect.CustomAction`, permitindo que o aplicativo host responda a quizzes, botões interativos e rotinas customizadas do livro.

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
