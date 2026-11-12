# `sample-android`, Design Técnico

> Gerado pelo **Writer** (Reversa v1.3.6) | Unit: `sample-android` | Nível: `detalhado`

---

## Interface

### Classes e Funções Internas do Host Android (`MainActivity.kt`)

| Símbolo | Assinatura | Retorno | Observação |
|---------|------------|---------|------------|
| `ScrollProgressInterface.onScrollProgress` | `@JavascriptInterface (scrollY: Int, contentHeight: Int, viewportHeight: Int)` | `Unit` | Registrado na WebView como `"AndroidScroll"`; posta no `mainHandler` o callback que despacha `ReaderCommand.UpdateScroll` 🟢 |
| `ActionBridge.postMessage` | `@JavascriptInterface (payloadJson: String)` | `Unit` | Registrado na WebView como `"ReaderNativeBridge"`; posta no `mainHandler` o JSON enviado por `window.ReaderEngine.postAction` 🟢 |
| `HostBookPersistenceStore.loadInitialState` | `(bookId: String)` | `InitialBookState?` | Lê `SharedPreferences`, descomprime via `PayloadCodec.decode` e reconstrói `InitialBookState` 🟢 |
| `HostBookPersistenceStore.saveFromState` | `(state: ReaderState)` | `Unit` | Serializa `ReaderState` em JSON, comprime via `PayloadCodec.encode` e grava na chave `"book_${state.bookId}"` 🟢 |
| `BookResourceWebViewClient.shouldInterceptRequest` | `(view: WebView?, request: WebResourceRequest?)` | `WebResourceResponse?` | Intercepta `book://content/<path>`, resolve MIME type e serve HTTP `200 OK` ou `206 Partial Content` 🟢 |
| `BookResourceWebViewClient.shouldOverrideUrlLoading` | `(view: WebView?, request: WebResourceRequest?)` | `Boolean` | Intercepta navegação clicada pelo usuário e roteia via `ReaderJsBridge.parseSpecialUrl` 🟢 |
| `ReaderScreen` | `@Composable ()` | `Unit` | Tela principal contendo TopBar, controles de leitura, WebView, vídeo fullscreen e painéis de TOC/Busca/Anotações 🟢 |

---

## Fluxo Principal

1. **Abertura de Livro e Hidratação (`MainActivity.kt:535-610`):**
   - O usuário escolhe um livro demo ou seleciona um arquivo via SAF (`OpenDocument`).
   - O arquivo é referenciado por um `Source` em disco (`okio.Path`).
   - Calcula-se `val docId = source.sha256DocumentId()` e recupera-se `val savedState = persistenceStore.loadInitialState(docId)`.
   - Invoca-se `ReaderEngine.open(source = source, scope = coroutineScope, initialState = savedState, strings = ReaderStrings.forLanguage(selectedLanguage))`. 🟢
2. **Renderização do Capítulo na WebView (`MainActivity.kt:680-750`):**
   - `AndroidView` instancia a `WebView` configurada com `BookResourceWebViewClient`, `ScrollProgressInterface` (`AndroidScroll`) e `ActionBridge` (`ReaderNativeBridge`).
   - Sempre que `state.currentPage`, `state.readingMode` ou `activeSession` mudam, chama-se `activeSession.renderCurrentPageHtml(...)` e carrega-se na `WebView` via `loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)` onde `baseUrl` é `book://content/<currentPageHref>`. 🟢
3. **Atendimento de Sub-recursos e Streaming HTTP 206 (`MainActivity.kt:251-354`):**
   - A `WebView` solicita imagens, folhas de estilo, fontes e vídeos relativos a `book://content/`.
   - `BookResourceWebViewClient.shouldInterceptRequest` verifica se há cabeçalho `Range: bytes=start-end`.
   - Se houver `Range`, consulta `session.getResourceSize(href)` e lê até 1 MB com `session.readResourceRange(href, start, chunkLength)`, respondendo com status `206 Partial Content`.
   - Se não houver `Range`, lê o recurso completo com `session.readResourceBytes(href)` e responde com `200 OK`. 🟢
4. **Execução de Efeitos e Persistência Contínua (`MainActivity.kt:612-675`):**
   - Dois blocos `LaunchedEffect(activeSession)` coletam `session.events` (salvando em `HostBookPersistenceStore`) e `session.uiEffects` (executando scripts em `webView.evaluateJavascript` ou exibindo `Toast`). 🟢

---

## Fluxos Alternativos

- **Intervalo HTTP Range Inválido (`416 Range Not Satisfiable`, `MainActivity.kt:305-316`):**
  - Se o cabeçalho `Range` solicitar um `start >= totalSize` ou `start < 0`, `BookResourceWebViewClient` retorna `WebResourceResponse` com status `416` (`Range Not Satisfiable`) e cabeçalho `Content-Range: bytes */totalSize`. 🟢
- **Vídeo HTML5 em Modo Fullscreen (`MainActivity.kt:705-725`):**
  - Quando o usuário toca em tela cheia num elemento `<video>`, `WebChromeClient.onShowCustomView(view, callback)` armazena a `customView` em estado Compose e sobrepõe um container de tela cheia até que `onHideCustomView()` seja chamado. 🟢

---

## Dependências

- **`project(":reader-core")`** — Motor KMP de leitura. 🟢
- **Jetpack Compose Material 3 & Activity Compose (`androidx.activity:activity-compose:1.9.2`)** — Construção da UI reativa e seletor SAF `rememberLauncherForActivityResult`. 🟢
- **`org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`** — Serialização de estado em `HostBookPersistenceStore` e parsing de ações em `handleBridgeActionJson`. 🟢
- **`com.squareup.okio:okio:3.9.1`** — Conversão de `java.io.File.toOkioPath()` e `FileSystem.SYSTEM`. 🟢

---

## Decisões de Design Identificadas

| Decisão | Evidência no código | Confiança |
|---------|---------------------|-----------|
| Uso do esquema customizado `book://content/` interceptado em memória/disco em vez de servidor HTTP em socket local | `sample-android/src/main/java/com/readerengine/app/MainActivity.kt:251-354` | 🟢 |
| Despacho na thread principal via `Handler(Looper.getMainLooper())` em todas as chamadas oriundas de `@JavascriptInterface` | `sample-android/src/main/java/com/readerengine/app/MainActivity.kt:85-99` | 🟢 |
| Geração programática de arquivos EPUB/HPUB de demonstração com vídeo/áudio embutido no cache local | `sample-android/src/main/java/com/readerengine/app/MainActivity.kt:950-1127` | 🟢 |

---

## Estado Interno

- **`ReaderScreen` Compose State:**
  - `activeSession: ReaderSession?`, `selectedLanguage: ReaderLanguage`, `activeDrawer: String?`, `fullscreenCustomView: View?`, `statusBanner: String?`. 🟢
- **`SharedPreferences` (`reader_engine_host_book_state`):**
  - Armazena um registro codificado via `PayloadCodec` por chave `book_<bookId>` mais a chave `last_opened_book_id`. 🟢

---

## Observabilidade

- Feedback visual imediato ao usuário por meio de `Toast.makeText(...)` acionado por `ReaderUiEffect.ShowNotice` e banner de status na tela principal. 🟢

---

## Riscos e Lacunas

- 🟡 **Concentração em Arquivo Único (`DT-01`):** Toda a camada de UI, persistência, WebViewClient e gerador de fixtures de demonstração do app Android está concentrada em `MainActivity.kt` (1.127 linhas).
