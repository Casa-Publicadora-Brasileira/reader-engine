# Fluxograma por Função — `BookResourceWebViewClient.shouldInterceptRequest` (HTTP 206)

> **Gerado por:** Reversa Archaeologist (`escavacao` — Nível `detalhado`)
> **Localização:** `sample-android/src/main/java/com/readerengine/app/MainActivity.kt:354-421`

```mermaid
flowchart TD
    A["shouldInterceptRequest(view, request)"] --> B{"URL começa com book://content/?"}
    B -->|"Não"| C["Delega para super.shouldInterceptRequest"]
    B -->|"Sim"| D["Extrai href e consulta mimeType + totalSize na ReaderSession"]
    D --> E{"Existe header Range: bytes=start-end e totalSize > 0?"}
    E -->|"Sim"| F["Calcula start e effectiveEnd (máx 1 MB por chunk)"]
    F --> G["session.readResourceRange(href, start, length)"]
    G --> H["Retorna WebResourceResponse HTTP 206 Partial Content + Content-Range"]
    E -->|"Não"| I["session.readResource(href)"]
    I --> J["Retorna WebResourceResponse HTTP 200 OK + Accept-Ranges: bytes"]
```
