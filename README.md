# reader-engine (Kotlin Multiplatform)

Motor de leitura multiplataforma (**Kotlin Multiplatform — KMP**) unificado para os aplicativos Android e iOS (**CPB Books** e **Escola Sabatina**).

O `:reader-core` encapsula toda a engenharia do leitor (parsers de **EPUB**, **HPUB / Baker Framework** e **JSON**, resolução de recursos ZIP em memória via Okio, ponte JS compatível com `readerJS` / Rangy, cálculo de progresso de leitura e codec `gz:` para payloads do Firestore), expondo fluxos reativos (`StateFlow<ReaderState>`, `SharedFlow<ReaderEvent>`, `SharedFlow<ReaderUiEffect>`) para que cada aplicativo host cuide apenas da sua **UI nativa** e da **persistência no seu próprio backend**.

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
│       ├── commonMain/kotlin/com/cpb/reader/engine/
│       │   ├── bridge/                # ReaderJsBridge (readerJS.*) & FirestorePayloadCodec (gz:)
│       │   ├── error/                 # ReaderException
│       │   ├── model/                 # Publication, ReadingProgress, HighlightItem, PageFormState...
│       │   ├── parser/                # EpubParser, HpubParser, JsonParser, ParserRegistry
│       │   ├── provider/              # ZipResourceProvider (Okio), MemoryResourceProvider
│       │   ├── session/               # ReaderEngine, ReaderSession, ReaderState, ReaderEvent, ReaderCommand
│       │   └── theme/                 # ThemeTokens, DesignSystem
│       └── commonTest/kotlin/com/cpb/reader/engine/
│           └── ReaderEngineTest.kt    # Testes unitários multiplataforma
└── sample-android/                    # App Android de demonstração (:sample-android) em Jetpack Compose
```

---

## Arquitetura Reativa Desacoplada

Cada aplicativo host (CPB Books Android/iOS, Escola Sabatina Android/iOS) possui seu próprio backend e regras de sincronização. O `:reader-core` **não faz chamadas de rede**:

1. **Hidratação na abertura (`InitialBookState`)**:
   O app host busca do seu armazenamento local/Firestore o progresso salvo, marcações (`Map<Int, String>`) e anotações/respostas de lição (`Map<Int, String>`) e passa para `ReaderEngine.open(fileName, bytes, initialState)`.
2. **Estado de UI (`StateFlow<ReaderState>`)**:
   A UI nativa (Compose / View / SwiftUI / UIKit) observa `session.state` para renderizar o capítulo atual, sumário (`toc`), lista de marcações da página (`HighlightItem`), progresso (`0..100%`) e modo noturno.
3. **Eventos de Persistência (`SharedFlow<ReaderEvent>`)**:
   Sempre que o usuário avança/rola a página, cria/remove um grifo ou preenche uma pergunta/anotação da lição, a sessão emite:
   - `ReaderEvent.ProgressChanged(progress)`
   - `ReaderEvent.PageHighlightsChanged(bookId, pageIndex, serializedRangy, items)`
   - `ReaderEvent.PageFormChanged(bookId, pageIndex, rawFormJson, parsed)`
   - `ReaderEvent.ChapterCompleted(bookId, pageIndex)`

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
