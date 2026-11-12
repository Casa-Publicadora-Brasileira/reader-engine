# `sample-android` — Requisitos de Especificação

> Gerado pelo **Writer** (Reversa v1.3.6) | Unit: `sample-android` | Nível: `detalhado`

---

## Visão Geral

O módulo **`sample-android`** (`com.readerengine.app`) é o aplicativo Android nativo (Jetpack Compose + Material 3) de referência que demonstra e valida a integração ponta a ponta do SDK `reader-core`. Ele fornece seleção de arquivos locais via Storage Access Framework (SAF), persistência automática por livro em `SharedPreferences` com compressão `PayloadCodec`, servidor virtual `book://content/` com streaming parcial HTTP `206 Partial Content` para áudio e vídeo embarcados, suporte a vídeo fullscreen e controles interativos de leitura. 🟢

---

## Responsabilidades

- Prover interface Jetpack Compose (`ReaderScreen`) para abrir livros de demonstração embutidos (EPUB Reflowable, EPUB Media/Video, HPUB, JSON) ou arquivos arbitrários do dispositivo via `ActivityResultContracts.OpenDocument`. 🟢
- Copiar URIs do SAF para o diretório de cache privado do aplicativo (`context.cacheDir/imported_books/`) e abri-los via `Source(path = okioPath, fileSystem = FileSystem.SYSTEM)` sem carregar o arquivo inteiro na RAM. 🟢
- Persistir e restaurar automaticamente o estado de cada livro (`InitialBookState`) em `SharedPreferences` (`reader_engine_host_book_state`) utilizando `PayloadCodec` (`HostBookPersistenceStore`). 🟢
- Interceptar requisições `book://content/<href>` na `WebView` (`BookResourceWebViewClient`), atendendo tanto leituras completas (`200 OK`) quanto requisições de intervalo `Range: bytes=start-end` (`206 Partial Content`) em fatias de até 1 MB (`1.048.576` bytes). 🟢
- Expor pontes `@JavascriptInterface` (`AndroidScroll` e `ReaderNativeBridge`) e executar `ReaderUiEffect` via `webView.evaluateJavascript(...)`. 🟢
- Suportar reprodução de vídeo HTML5 em tela cheia através de `WebChromeClient.onShowCustomView` e `onHideCustomView`. 🟢

---

## Regras de Negócio

- **RN-SA-01 (Streaming HTTP 206 em Chunks de 1 MB):** Requisições para `book://content/<href>` contendo o cabeçalho `Range: bytes=start-end` retornam status `206 Partial Content` com no máximo `MAX_RANGE_CHUNK_BYTES = 1_048_576` bytes por resposta, preenchendo `Content-Range: bytes start-actualEnd/totalSize` e `Accept-Ranges: bytes`. 🟢
- **RN-SA-02 (Hardening do WebView Sandbox):** O `WebView` de leitura é inicializado obrigatoriamente com `allowFileAccess = false` e `allowContentAccess = false`, impedindo que scripts do livro leiam arquivos locais do aplicativo. 🟢
- **RN-SA-03 (Persistência Reativa Guiada por Eventos):** `HostBookPersistenceStore.saveFromState(session.state.value)` é acionado automaticamente sempre que `session.events` emite `PageChanged`, `ProgressSaved`, `ReadingModeUpdated`, `BookmarksUpdated`, `HighlightsUpdated`, `FormsUpdated`, `ThemeUpdated` ou `FontSizeUpdated`. 🟢
- **RN-SA-04 (Roteamento Seguro de Links):** Cliques em links na `WebView` passam por `ReaderJsBridge.parseSpecialUrl(url)`: links internos navegam para o capítulo correspondente; links `http://`, `https://`, `mailto:` e `tel:` abrem aplicativos externos do Android via `Intent(Intent.ACTION_VIEW)`. 🟢

---

## Requisitos Funcionais

| ID | Requisito | Prioridade | Critério de Aceite |
|----|-----------|------------|--------------------|
| **RF-SA-01** | Carregar livros de demonstração gerados em cache e arquivos selecionados pelo usuário via SAF | Must | Ao selecionar um arquivo `.epub`, `.hpub` ou `.json`, o livro abre na `ReaderScreen` exibindo título, capítulo atual e progresso |
| **RF-SA-02** | Restaurar e salvar estado de leitura por `bookId` (`HostBookPersistenceStore`) | Must | Ao fechar e reabrir o mesmo livro, `currentPage`, `scrollY`, `readingMode`, `theme`, `fontSizeSp`, `bookmarks`, `highlights` e `forms` são restaurados |
| **RF-SA-03** | Servir recursos internos do livro via `book://content/` com suporte a HTTP 200 e HTTP 206 (`Range`) | Must | Imagens, CSS, fontes desofuscadas e vídeos MP4/WebM com seek funcionam dentro da `WebView` |
| **RF-SA-04** | Sincronizar rolagem (`AndroidScroll`) e ações interativas (`ReaderNativeBridge`) com `ReaderSession` | Must | Rolar a página atualiza a barra de progresso; selecionar texto/preencher formulário dispara `AddHighlight`/`SetFormValue` |
| **RF-SA-05** | Oferecer controles de UI para modo de leitura (`SCROLLED` / `PAGINATED`), temas (`Material 3`, `iOS HIG`, `Fluent`, `Noturno`), tamanho de fonte (`A-` / `A+`) e idioma (`PT`, `EN`, `ES`) | Must | Alterações refletem imediatamente na `WebView` sem recarregar o aplicativo |
| **RF-SA-06** | Exibir painéis modais/gavetas para Sumário (TOC), Busca Textual, Destaques, Favoritos (Bookmarks) e Formulários | Must | Clicar em um item de TOC, resultado de busca ou bookmark navega imediatamente para a página e posição correspondentes |
| **RF-SA-07** | Suportar vídeo HTML5 em tela cheia (`WebChromeClient`) | Should | Clicar no botão de tela cheia de um `<video>` embarcado expande o player sobre a tela e restaura ao sair |

---

## Requisitos Não Funcionais

| Tipo | Requisito inferido | Evidência no código | Confiança |
|------|--------------------|---------------------|-----------|
| **Performance (Streaming)** | Limite de `1 MB` (`1_048_576` bytes) por fatia HTTP 206 para evitar picos de alocação no processo Android | `sample-android/src/main/java/com/readerengine/app/MainActivity.kt:285` | 🟢 |
| **Performance (Startup I/O)** | Cópia por stream de URIs SAF para arquivo em `cacheDir` e abertura via `Okio Path` (random-access em disco) | `sample-android/src/main/java/com/readerengine/app/MainActivity.kt:555-575` | 🟢 |
| **Segurança (WebView Isolation)** | Desativação explícita de `allowFileAccess` e `allowContentAccess` na configuração da `WebView` | `sample-android/src/main/java/com/readerengine/app/MainActivity.kt:691-693` | 🟢 |

---

## Critérios de Aceitação

```gherkin
# Cenário 1: Streaming parcial HTTP 206 de vídeo embarcado (Happy Path)
Dado um livro EPUB aberto contendo o recurso "OEBPS/media/intro.mp4" de 5.000.000 bytes
Quando a WebView requisita "book://content/OEBPS/media/intro.mp4" com cabeçalho "Range: bytes=0-"
Então BookResourceWebViewClient deve responder com statusCode = 206 e reasonPhrase = "Partial Content"
E o cabeçalho "Content-Range" deve ser "bytes 0-1048575/5000000"
E o corpo da resposta deve conter exatamente 1.048.576 bytes lidos via session.readResourceRange

# Cenário 2: Persistência e restauração automática do estado do livro (Happy Path)
Dado que o usuário abriu um livro com bookId "sha256-abc", avançou para a página 2, adicionou 1 bookmark e alterou a fonte para 22sp
Quando o livro é fechado e reaberto posteriormente
Então HostBookPersistenceStore.loadInitialState("sha256-abc") deve retornar InitialBookState com currentPage = 2, fontSizeSp = 22 e 1 bookmark

# Cenário 3: Recurso inexistente em book://content/ (Falha 404)
Dado uma sessão ativa na WebView
Quando a WebView requisita "book://content/OEBPS/nao-existe.png"
Então BookResourceWebViewClient deve retornar WebResourceResponse com statusCode = 404 e reasonPhrase = "Not Found"
```

---

## Prioridade (MoSCoW)

| Requisito | MoSCoW | Justificativa |
|-----------|--------|---------------|
| Interceptador `BookResourceWebViewClient` (HTTP 200 e HTTP 206 Range) | **Must** | Essencial para carregar qualquer sub-recurso (CSS, fontes, imagens, vídeos) do EPUB na WebView |
| Integração reativa `ReaderScreen` <-> `ReaderSession` (`state`, `events`, `uiEffects`) | **Must** | Caminho crítico de apresentação e controle do motor |
| Persistência local `HostBookPersistenceStore` com `PayloadCodec` | **Must** | Garante retenção de progresso, notas e exercícios entre sessões |
| Pontes `@JavascriptInterface` (`AndroidScroll` e `ReaderNativeBridge`) | **Must** | Necessárias para sincronizar scroll, sub-páginas, highlights e formulários |
| Vídeo fullscreen via `WebChromeClient` e gerador de livros demo em runtime | **Should** | Facilitam demonstração rica de recursos multimídia EPUB 3 |

---

## Rastreabilidade de Código

| Arquivo | Função / Classe | Cobertura |
|---------|-----------------|-----------|
| `sample-android/src/main/java/com/readerengine/app/MainActivity.kt` | `ScrollProgressInterface`, `ActionBridge`, `HostBookPersistenceStore`, `BookResourceWebViewClient`, `MainActivity`, `ReaderScreen` | 🟢 |
