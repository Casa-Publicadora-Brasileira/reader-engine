# Matriz de Impacto de Especificação (`spec-impact-matrix`)

> Gerado pelo **Architect** (Reversa v1.3.6) | Nível: `detalhado`

---

## 1. Matriz de Dependência e Impacto Cruzado entre Componentes

A tabela abaixo mapeia como alterações em um componente (linhas) impactam diretamente (`🔴 Alto`, `🟡 Médio`, `🟢 Baixo`, `— Nenhum`) os demais componentes do sistema (colunas).

| Componente Origem (Se alterado...) ⬇️ \ Impacta ➡️ | `model` & `error` | `provider` (`Zip` / `Compression`) | `drm` (`DrmProvider`) | `parser` (`Epub` / `Hpub` / `Json`) | `search` (`BookSearchEngine`) | `theme` (`ThemeTokens`) | `bridge` (`ReaderJsBridge` / `PayloadCodec`) | `session` (`ReaderSession`) | `sample-android` (`MainActivity`) |
|-----------------------------------------------------|-------------------|------------------------------------|-----------------------|-------------------------------------|-------------------------------|-------------------------|----------------------------------------------|-----------------------------|-----------------------------------|
| **`model` & `error` (`Models.kt`, `ReaderException.kt`)** | — | 🔴 Alto (usa `ReaderException`) | 🟡 Médio (usa `Resource`) | 🔴 Alto (constrói `Publication`, `Metadata`, `TocEntry`) | 🔴 Alto (usa `Publication`, `SearchResult`, `Locator`) | — | 🔴 Alto (serializa `HighlightItem`, `FormEntry`) | 🔴 Alto (expõe todas as entidades em `ReaderState`) | 🔴 Alto (consome `ReaderState`, `InitialBookState`) |
| **`provider` (`ZipResourceProvider`, `PlatformCompression`)** | — | — | 🔴 Alto (`DecryptingResourceProvider` delega para `ResourceProvider`) | 🔴 Alto (`EpubParser` e `HpubParser` abrem ZIP) | — | — | 🔴 Alto (`PayloadCodec` usa `gzipCompress`/`gzipDecompress`) | 🟡 Médio (`readResourceBytes` / `readResourceRange`) | 🟡 Médio (`BookResourceWebViewClient` depende de `readRange`) |
| **`drm` (`DrmProvider.kt`)** | — | — | — | 🔴 Alto (`EpubParser` injeta `DecryptingResourceProvider`) | — | — | — | 🟡 Médio (`ReaderEngine.open` aceita `fallbackDecryptor`) | 🟢 Baixo |
| **`parser` (`Source`, `EpubParser`, `HpubParser`, `JsonParser`)** | — | — | — | — | 🟡 Médio (depende da qualidade do HTML em `pages`) | — | 🟡 Médio (depende da estrutura HTML/OPF) | 🔴 Alto (`ReaderEngine.open` invoca `ParserRegistry`) | 🟡 Médio (`Source.sha256DocumentId` usado na persistência) |
| **`search` (`BookSearchEngine.kt`)** | — | — | — | — | — | — | — | 🔴 Alto (`ReaderCommand.Search` invoca `BookSearchEngine`) | 🟢 Baixo (exibe `searchResults` na UI) |
| **`theme` (`ThemeTokens.kt`)** | — | — | — | — | — | — | 🔴 Alto (`ReaderJsBridge` injeta variáveis CSS do tema) | 🔴 Alto (`ReaderState.theme` e `SetTheme`) | 🟡 Médio (seletor de temas na barra superior) |
| **`bridge` (`ReaderJsBridge.kt`, `PayloadCodec.kt`)** | — | — | — | — | — | — | — | 🔴 Alto (`renderCurrentPageHtml` e `ReaderUiEffect`) | 🔴 Alto (`WebView` depende de `window.readerJS` e `HostBookPersistenceStore` usa `PayloadCodec`) |
| **`session` (`ReaderSession.kt`, `FlowSubscription.kt`)** | — | — | — | — | — | — | — | — | 🔴 Alto (toda a UI Android observa `state`, `events` e `uiEffects`) |
| **`sample-android` (`MainActivity.kt`)** | — | — | — | — | — | — | — | — | — |

---

## 2. Análise de Blast Radius (Raio de Explosão) por Unidade de Spec

| Unidade (`granularity = module`) | Nível de Criticidade | Consumidores Diretos | Risco em Caso de Quebra de Contrato |
|----------------------------------|----------------------|----------------------|-------------------------------------|
| **`reader-core`** | **Crítico (Core SDK)** | `sample-android`, apps iOS consumidores (`ReaderCore.xcframework`), consumidores Maven/GitHub Packages | Quebra de compilação binária/fonte (`explicitApi()`) ou regressão funcional em Android, iOS e JVM simultaneamente. |
| **`sample-android`** | **Médio (Reference Host)** | Desenvolvedores que usam o app como referência e testes manuais de dispositivos Android | Impacto restrito ao aplicativo demonstrativo Android; não afeta artefatos publicados da biblioteca `reader-core`. |
