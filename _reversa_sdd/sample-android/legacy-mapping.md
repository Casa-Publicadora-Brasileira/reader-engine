# Mapeamento Legado — `sample-android`

> Gerado pelo **Archaeologist** (Reversa v1.3.6) | Granularidade: `module`

## 1. Visão Geral da Unidade

O módulo `sample-android` (`com.readerengine.app`) é a aplicação Android de referência e host de integração que conecta a biblioteca multiplataforma `reader-core` a um `WebView` Android real, fornecendo persistência local em `SharedPreferences`, servidor virtual `book://content/` com suporte a HTTP 206 Partial Content (streaming de mídia) e interface Jetpack Compose Material 3.

## 2. Mapeamento de Arquivos e Classes Internas

| Arquivo Fonte | Componente / Classe | Linhas | Responsabilidade Legada | Confiança |
|---------------|---------------------|--------|-------------------------|-----------|
| `sample-android/src/main/java/com/readerengine/app/MainActivity.kt` | `ScrollProgressInterface` | L85-L90 | `@JavascriptInterface` exposto como `AndroidScroll` para receber `onScrollProgress(scrollY, contentHeight, viewportHeight)` do WebView | 🟢 CONFIRMADO |
| `sample-android/src/main/java/com/readerengine/app/MainActivity.kt` | `ActionBridge` | L92-L99 | `@JavascriptInterface` exposto como `ReaderNativeBridge` para receber `postMessage(payloadJson)` do `window.ReaderEngine.postAction` | 🟢 CONFIRMADO |
| `sample-android/src/main/java/com/readerengine/app/MainActivity.kt` | `HostBookPersistenceStore` | L101-L249 | Persistência por `bookId` em `SharedPreferences` (`reader_engine_host_book_state`) usando `PayloadCodec` para comprimir estados grandes (`gz:<base64>`) | 🟢 CONFIRMADO |
| `sample-android/src/main/java/com/readerengine/app/MainActivity.kt` | `BookResourceWebViewClient` | L251-L398 | Interceptação de `book://content/<path>`, resolução de MIME type, suporte a `Range: bytes=start-end` com resposta HTTP `206 Partial Content` em fatias de até 1 MB (`readResourceRange`) e roteamento de URLs especiais (`parseSpecialUrl`) | 🟢 CONFIRMADO |
| `sample-android/src/main/java/com/readerengine/app/MainActivity.kt` | `MainActivity` | L400-L426 | Activity principal Android que inicializa o tema Material 3 e monta `ReaderScreen()` | 🟢 CONFIRMADO |
| `sample-android/src/main/java/com/readerengine/app/MainActivity.kt` | `ReaderScreen` | L429-L1127 | Composable principal: seletor de livros de demonstração/SAF (`OpenDocument`), barra de controle de tema/fonte/idioma, gavetas de TOC/Busca/Destaques/Bookmarks/Formulários, `AndroidView(WebView)` com suporte a vídeo fullscreen (`WebChromeClient`) e coleta de `uiEffects` via `evaluateJavascript` | 🟢 CONFIRMADO |

## 3. Fluxos Críticos do Host Android

1. **Hidratação e Persistência (`HostBookPersistenceStore`):**
   - Ao abrir um livro (`Source`), calcula `source.sha256DocumentId()` antes do parse para carregar `InitialBookState` de `SharedPreferences`.
   - Escuta `session.events` (`PageChanged`, `ProgressSaved`, `BookmarksUpdated`, `HighlightsUpdated`, `FormsUpdated`, `ThemeUpdated`, `FontSizeUpdated`) e persiste imediatamente no `HostBookPersistenceStore`.
2. **Streaming de Mídia Zero-Copy (`BookResourceWebViewClient`):**
   - Intercepta `shouldInterceptRequest` para o host `book://content/`.
   - Quando o `WebView` envia cabeçalho `Range: bytes=start-end` (ex.: tags `<audio>` ou `<video>`), invoca `session.readResourceRange(href, offset, length)` limitado a chunks de 1 MB (`1_048_576` bytes) e retorna `WebResourceResponse` com status `206 Partial Content`, `Accept-Ranges: bytes` e `Content-Range: bytes start-end/total`.
3. **Execução de Efeitos de UI (`ReaderUiEffect`):**
   - Coleta `session.uiEffects` em `LaunchedEffect(activeSession)` e traduz cada efeito (`ScrollToY`, `ExecuteJs`, `ApplyThemeJs`, `ApplyFontSizeJs`, `ApplyHighlightsJs`, `RestoreFormsJs`, `TriggerClearFormsJs`, `ShowNotice`) em chamadas `webView.evaluateJavascript(...)` ou `Toast`.
