# Matriz de Permissões, Fronteiras de Segurança e Isolamento — `reader-engine`

> Gerado pelo **Detective** (Reversa v1.3.6) | Nível: `detalhado`

---

## 1. Modelo de Controle de Acesso e Atores

Por ser uma **biblioteca SDK multiplataforma embarcada (`reader-core`)** acompanhada de um **aplicativo host Android (`sample-android`)**, o `reader-engine` não possui autenticação multiusuário nem RBAC (Role-Based Access Control) tradicional de backend. Em vez disso, o modelo de permissões opera como uma **Matriz de Capacidades e Fronteiras de Isolamento (Capability & Sandbox Matrix)** entre 4 atores de execução:

1. **Usuário Final (Leitor):** Interage com a UI nativa (Compose/SwiftUI) e com o conteúdo HTML dentro da WebView.
2. **Conteúdo da Publicação (Untrusted EPUB/HPUB Content + JS):** XHTML, CSS, mídia e scripts originados do arquivo aberto pelo usuário. Considerado **não confiável (untrusted)**.
3. **Ponte de Sessão (`ReaderJsBridge` / `BookResourceWebViewClient`):** Camada mediadora que filtra URLs, chamadas JS e acesso a recursos.
4. **Aplicação Host (`sample-android` / Host App):** Detém acesso ao sistema de arquivos do SO (via SAF `OpenDocument`), `SharedPreferences` e criação de `ReaderSession`.

---

## 2. Matriz de Capacidades e Permissões por Ator

| Capacidade / Operação | Usuário Final (UI) | Conteúdo EPUB / WebView (Untrusted) | Ponte (`ReaderJsBridge` / `WebViewClient`) | App Host (`sample-android`) | Mecanismo de Aplicação no Código | Confiança |
|-----------------------|--------------------|-------------------------------------|--------------------------------------------|-----------------------------|----------------------------------|-----------|
| Abrir arquivo local do sistema operacional | ✅ Via seletor SAF (`OpenDocument`) | ❌ Bloqueado (`allowFileAccess = false`) | ❌ Sem acesso direto | ✅ Apenas URIs concedidas pelo SAF | `MainActivity.kt:692` (`allowFileAccess = false`, `allowContentAccess = false`) | 🟢 CONFIRMADO |
| Ler recursos internos do pacote ZIP (`book://content/*`) | ✅ Visualização | ✅ Apenas dentro do próprio pacote e com path sanitizado | ✅ Resolve via `resolveResourceHref` | ✅ Acesso total via `ReaderSession` | `normalizeHref` remove `..` (`ZipResourceProvider.kt:299`) | 🟢 CONFIRMADO |
| Executar Path Traversal (`../../etc/passwd`) | ❌ | ❌ Bloqueado | ❌ Segmentos `..` são descartados na pilha | ❌ | `normalizeHref` (`ZipResourceProvider.kt:299-315`) | 🟢 CONFIRMADO |
| Alocar memória ilimitada via ZIP malicioso (Zip Bomb) | ❌ | ❌ Bloqueado | ❌ Rejeitado com `InvalidContent` | ⚠️ Pode customizar `ZipLimits` | `ZipLimits`: máx 10.000 entradas, 50MB/entrada, 1GB total (`ZipResourceProvider.kt:16`) | 🟢 CONFIRMADO |
| Injetar CSS arbitrário fora de escopo via `ThemeTokens` | ❌ | ❌ Bloqueado | ✅ Sanitiza via Regex | ⚠️ Pode passar `customCss` explícito | `sanitizeCssColor` e `sanitizeCssFont` (`ThemeTokens.kt:162-183`) | 🟢 CONFIRMADO |
| Disparar ações nativas via `window.ReaderEngine.postAction` | ✅ Indiretamente | ⚠️ Restrito aos tipos suportados (`highlight`, `form`, `subpage`, `navigate`) | ✅ Faz parse JSON seguro e valida campos | ✅ Processa em `handleBridgeActionJson` | `MainActivity.kt:467-530` | 🟢 CONFIRMADO |
| Navegar para URLs externas (`http://`, `https://`, `mailto:`, `tel:`) | ✅ Abre em app externo (`Intent.ACTION_VIEW`) | ❌ Não carrega dentro da WebView do livro | ✅ Intercepta via `parseSpecialUrl` | ✅ Delega ao SO via `Intent` | `ReaderJsBridge.kt:45-100`, `MainActivity.kt:355-397` | 🟢 CONFIRMADO |
| Descriptografar recursos com DRM comercial (ex. LCP) | ❌ | ❌ Transparente | ✅ Delega ao `ContentDecryptor` | ✅ Injeta `fallbackDecryptor` em `ReaderEngine.open` | `DrmProvider.kt:82-199` | 🟢 CONFIRMADO |

---

## 3. Controles de Segurança do `WebView` Host (`sample-android`)

No arquivo [`MainActivity.kt`](file:///Users/andrealves/Documents/dev/reader-engine/sample-android/src/main/java/com/readerengine/app/MainActivity.kt#L689-L702), o `WebView` é configurado com endurecimento explícito:

- `settings.javaScriptEnabled = true` — necessário para `window.readerJS` (paginação por colunas, restauração de highlights/formulários e medição de scroll).
- `settings.allowFileAccess = false` — impede que scripts dentro do EPUB leiam `file:///data/data/...` do aplicativo Android.
- `settings.allowContentAccess = false` — impede acesso a `content://` providers do dispositivo.
- `settings.mediaPlaybackRequiresUserGesture = false` — permite reprodução de mídia HTML5 embarcada controlada pelo leitor.
- **Isolamento de Origem Virtual (`https://book.local/` / `book://content/`):** Os capítulos são carregados com base URL controlada (`book://content/`) e todas as requisições de sub-recursos passam obrigatoriamente por `BookResourceWebViewClient.shouldInterceptRequest`.
