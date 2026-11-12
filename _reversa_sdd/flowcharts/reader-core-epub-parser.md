# Fluxograma por Função — `EpubParser.parse(source)`

> **Gerado por:** Reversa Archaeologist (`escavacao` — Nível `detalhado`)
> **Localização:** `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/EpubParser.kt:30-115`

```mermaid
flowchart TD
    A["EpubParser.parse(source)"] --> B["source.openZip()"]
    B --> C["Lê META-INF/container.xml e localiza <rootfile full-path>"]
    C --> D["Lê arquivo .opf e extrai baseDir"]
    D --> E["parseMetadata(opfXml): Dublin Core + rendition:layout/orientation/spread"]
    E --> F["Itera <item>: monta resources, idToHref, navHref e ncxHref"]
    F --> G["Itera <itemref>: filtra linear != no (fallback: todos)"]
    G --> H{"Existe navHref (EPUB 3)?"}
    H -->|"Sim"| I["parseNavXhtml (pilha ArrayDeque<LiBuilder>)"]
    H -->|"Não"| J{"Existe ncxHref (EPUB 2)?"}
    J -->|"Sim"| K["parseNcx (pilha ArrayDeque<NavPointBuilder>)"]
    J -->|"Não"| L["toc = emptyList()"]
    I --> M{"Existe META-INF/encryption.xml?"}
    K --> M
    L --> M
    M -->|"Sim"| N["EpubEncryptionParser.parse + DecryptingResourceProvider"]
    M -->|"Não"| O["Mantém ZipResourceProvider direto"]
    N --> P["Retorna Publication"]
    O --> P
```
