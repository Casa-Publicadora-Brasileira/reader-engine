# Fluxograma por Função — `ZipResourceProvider.indexCentralDirectory` & `read`

> **Gerado por:** Reversa Archaeologist (`escavacao` — Nível `detalhado`)
> **Localização:** `reader-core/src/commonMain/kotlin/com/readerengine/core/provider/ResourceProviders.kt:308-483`

```mermaid
flowchart TD
    A["indexCentralDirectory(access, limits)"] --> B{"access.size < 22 bytes?"}
    B -->|"Sim"| C["Throw InvalidContent (ZIP muito pequeno)"]
    B -->|"Não"| D["Lê janela final tailBytes (máx 65.557 bytes)"]
    D --> E["Busca assinatura EOCD (0x06054b50) de trás para frente"]
    E --> F{"EOCD encontrado?"}
    F -->|"Não"| G["Throw InvalidContent (EOCD ausente)"]
    F -->|"Sim"| H["Lê totalEntries, centralDirSize, centralDirOffset"]
    H --> I{"É ZIP64 ou totalEntries > maxEntries (10.000)?"}
    I -->|"Sim"| J["Throw InvalidContent (ZIP64 / limite excedido)"]
    I -->|"Não"| K["Lê fatia centralDirBytes"]
    K --> L["Loop i = 0 until totalEntries"]
    L --> M["Valida assinatura CEN (0x02014b50) e comprimentos"]
    M --> N{"uncompressedSize > 50MB ou soma > 1GB?"}
    N -->|"Sim"| O["Throw InvalidContent (Proteção anti-Zip Bomb)"]
    N -->|"Não"| P["Normaliza fileName via normalizeHref e salva ZipEntryMeta"]
    P --> Q["Retorna Map<String, ZipEntryMeta>"]
```
