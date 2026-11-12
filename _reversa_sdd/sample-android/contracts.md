# `sample-android` — Contratos de Interface e Protocolos (`contracts`)

> Gerado pelo **Writer** (Reversa v1.3.6) | Unit: `sample-android` | Nível: `detalhado`

---

## 1. Contrato HTTP Virtual (`book://content/<href>`)

Interceptado por `BookResourceWebViewClient.shouldInterceptRequest`:

| Método | URL / Caminho | Cabeçalhos de Entrada | Status de Saída | Cabeçalhos de Resposta | Corpo | Confiança |
|--------|---------------|-----------------------|-----------------|------------------------|-------|-----------|
| `GET` | `book://content/<href>` | Sem `Range` | `200 OK` | `Accept-Ranges: bytes`, `Content-Length: <size>`, `Access-Control-Allow-Origin: *` | Recurso completo (`readResourceBytes`) | 🟢 |
| `GET` | `book://content/<href>` | `Range: bytes=<start>-<end>` | `206 Partial Content` | `Accept-Ranges: bytes`, `Content-Range: bytes <start>-<actualEnd>/<total>`, `Content-Length: <chunkLen>`, `Access-Control-Allow-Origin: *` | Fatia de até `1.048.576` bytes (`readResourceRange`) | 🟢 |
| `GET` | `book://content/<href>` | `Range: bytes=<invalid>` (`start >= total`) | `416 Range Not Satisfiable` | `Content-Range: bytes */<total>` | Vazio | 🟢 |
| `GET` | `book://content/<inexistente>` | Qualquer | `404 Not Found` | `Access-Control-Allow-Origin: *` | Vazio | 🟢 |

---

## 2. Contrato `@JavascriptInterface` Exposto na WebView

### 2.1 `window.AndroidScroll` (`ScrollProgressInterface`)
```kotlin
@JavascriptInterface
public fun onScrollProgress(scrollY: Int, contentHeight: Int, viewportHeight: Int)
```
- Invocado pelo listener de scroll (`80ms` debounce) do `FALLBACK_READER_JS`. 🟢

### 2.2 `window.ReaderNativeBridge` (`ActionBridge`)
```kotlin
@JavascriptInterface
public fun postMessage(payloadJson: String)
```
- Invocado por `window.ReaderEngine.postAction(action)` para reportar seleção de highlight, edição de formulário, sincronização de sub-página ou comando de navegação. 🟢

---

## 3. Esquema de Persistência em `SharedPreferences` (`reader_engine_host_book_state`)

Arquivo XML de preferências: `reader_engine_host_book_state` (`Context.MODE_PRIVATE`)

| Chave | Tipo | Formato do Valor | Confiança |
|-------|------|------------------|-----------|
| `last_opened_book_id` | `String` | `<bookId>` do último livro aberto | 🟢 |
| `book_<bookId>` | `String` | JSON serializado (ou comprimido com prefixo `gz:<base64>` via `PayloadCodec.encode`) contendo `currentPage`, `scrollY`, `readingMode`, `fontSizeSp`, `theme`, `bookmarks`, `highlights` e `forms` | 🟢 |
