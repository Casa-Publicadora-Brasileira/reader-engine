# `sample-android` — Casos Extremos e Limites Operacionais (`edge-cases`)

> Gerado pelo **Writer** (Reversa v1.3.6) | Unit: `sample-android` | Nível: `detalhado`

---

## Catálogo de Casos de Borda Tratados no Host Android

### EC-SA-01 — Requisição `Range: bytes=0-` Aberta para Vídeo Grande
- **Gatilho:** O player de vídeo nativo do Chromium Android WebView envia `Range: bytes=0-` (sem byte final explícito) para iniciar o streaming de um vídeo de vários megabytes.
- **Comportamento no Código (`MainActivity.kt:285-330`):** `BookResourceWebViewClient` calcula `val maxEnd = (start + MAX_RANGE_CHUNK_BYTES - 1).coerceAtMost(totalSize - 1L)`, limitando a fatia retornada a **1 MB (`1_048_576` bytes)** por requisição e forçando o WebView a buscar os próximos chunks incrementalmente sob demanda. 🟢

### EC-SA-02 — Cabeçalho `Range` Fora dos Limites do Recurso (`416 Range Not Satisfiable`)
- **Gatilho:** Requisição com `start < 0`, `start >= totalSize` ou `end < start`.
- **Comportamento no Código (`MainActivity.kt:305-316`):** Responde imediatamente com status HTTP `416` (`Range Not Satisfiable`) e cabeçalho `Content-Range: bytes */$totalSize`, cumprindo a RFC 7233. 🟢

### EC-SA-03 — Corrupção no Payload Persistido em `SharedPreferences`
- **Gatilho:** O valor salvo em `book_<bookId>` está truncado, com Base64 inválido ou JSON incompatível.
- **Comportamento no Código (`MainActivity.kt:112-160`):** `HostBookPersistenceStore.loadInitialState` envolve a decodificação e o parse JSON em `runCatching { ... }.getOrNull()`, retornando `null` em caso de falha e permitindo que o livro abra normalmente do início sem derrubar o aplicativo. 🟢

### EC-SA-04 — Chamadas Concorrentes de `@JavascriptInterface` Fora da Main Thread
- **Gatilho:** O Chromium Android invoca métodos anotados com `@JavascriptInterface` (`onScrollProgress`, `postMessage`) em uma thread privada de background (`JavaBridge`).
- **Comportamento no Código (`MainActivity.kt:85-99`):** Ambas as pontes utilizam `mainHandler.post { ... }` (`Handler(Looper.getMainLooper())`) para transferir a execução para a UI Thread antes de interagir com o estado do Compose ou `ReaderSession`. 🟢
