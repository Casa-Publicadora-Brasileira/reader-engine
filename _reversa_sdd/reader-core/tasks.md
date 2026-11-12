# `reader-core`, Tarefas de Implementação

> Gerado pelo **Writer** (Reversa v1.3.6) | Unit: `reader-core` | Nível: `detalhado`

---

## Pré-requisitos

- [x] Configurar projeto Gradle Kotlin Multiplatform (`kotlin("multiplatform")`, `kotlin("plugin.serialization")`, `com.android.library`) com `explicitApi()` e targets `androidTarget()`, `jvm()`, `iosX64()`, `iosArm64()`, `iosSimulatorArm64()`.
- [x] Declarar dependências de `commonMain`: `kotlinx-coroutines-core:1.9.0`, `kotlinx-serialization-json:1.7.3` e `okio:3.9.1`.
- [x] Configurar `XCFramework("ReaderCore")` para empacotamento iOS.

---

## Tarefas

- [x] **T-01**, Implementar hierarquia selada de exceções `ReaderException` (`UnsupportedFormat`, `InvalidContent`, `ResourceNotFound`)
  - Origem no legado: `reader-core/src/commonMain/kotlin/com/readerengine/core/error/ReaderException.kt:1-29`
  - Critério de pronto: Exceções compilam com `explicitApi()` e preservam `message` e `cause`.
  - Confiança: 🟢

- [x] **T-02**, Implementar modelos imutáveis de domínio (`Metadata`, `Resource`, `TocEntry`, `TocItem`, `Locator`, `Publication`, `ReadingProgress`, `HighlightStyle`, `HighlightItem`, `Bookmark`, `SearchResult`, `FormEntry`, `PageFormState`, `InitialBookState`, `ReaderStrings`)
  - Origem no legado: `reader-core/src/commonMain/kotlin/com/readerengine/core/model/Models.kt:1-275`
  - Critério de pronto: `ReadingProgress.from(...)` calcula progresso monotônico exato (`0.0..100.0`) e `TocEntry.flatten()` achata recursivamente a árvore de sumário.
  - Confiança: 🟢

- [x] **T-03**, Implementar `PlatformCompression` (`expect`/`actual` para `inflateRaw`, `gzipCompress`, `gzipDecompress`) em JVM, Android e iOS
  - Origem no legado: `reader-core/src/commonMain/kotlin/com/readerengine/core/provider/PlatformCompression.kt:1-16`, `PlatformCompression.jvm.kt:1-61`, `PlatformCompression.android.kt:1-61`, `PlatformCompression.ios.kt:1-91`
  - Critério de pronto: Compressão e descompressão DEFLATE raw e GZIP funcionam identicamente em JVM/Android (`java.util.zip`) e iOS (`platform.zlib`), respeitando `maxOutputBytes`.
  - Confiança: 🟢

- [x] **T-04**, Implementar `ZipResourceProvider`, `ZipLimits` e sanitizador `normalizeHref`
  - Origem no legado: `reader-core/src/commonMain/kotlin/com/readerengine/core/provider/ZipResourceProvider.kt:1-316`
  - Critério de pronto: Lê EOCD e Central Directory nos últimos 65.557 bytes, valida limites anti-Zip Bomb, mantém cache LRU de 16 MB com `Mutex` e executa `readRange` zero-copy para entradas `STORED` em disco.
  - Confiança: 🟢

- [x] **T-05**, Implementar `Source`, `ResourceProvider`, `ParserRegistry` e `sha256DocumentId` amostrado
  - Origem no legado: `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/Source.kt:1-150`
  - Critério de pronto: Calcula SHA-256 em memória ou por amostragem de 64 KB iniciais/finais em arquivos de disco `> 128 KB`.
  - Confiança: 🟢

- [x] **T-06**, Implementar decodificador single-pass de entidades XML/HTML (`XmlEntities`)
  - Origem no legado: `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/XmlEntities.kt:1-68`
  - Critério de pronto: Decodifica entidades nominais (`&amp;`, `&nbsp;`, `&mdash;`, etc.), decimais (`&#123;`) e hexadecimais (`&#x1F600;`) com suporte a codepoints suplementares UTF-16.
  - Confiança: 🟢

- [x] **T-07**, Implementar pipeline de DRM e desofuscação de fontes (`EpubEncryptionParser`, `StandardEpubFontDeobfuscator`, `DecryptingResourceProvider`, `ContentDecryptor`)
  - Origem no legado: `reader-core/src/commonMain/kotlin/com/readerengine/core/drm/DrmProvider.kt:1-200`
  - Critério de pronto: Faz parse de `META-INF/encryption.xml`, aplica XOR IDPF (1040B SHA-1) e Adobe (1024B UUID) e delega algoritmos externos para `fallbackDecryptor`.
  - Confiança: 🟢

- [x] **T-08**, Implementar parsers de publicações (`EpubParser`, `HpubParser`, `JsonParser`)
  - Origem no legado: `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/EpubParser.kt:1-645`, `HpubParser.kt:1-140`, `JsonParser.kt:1-91`
  - Critério de pronto: `EpubParser` extrai metadados OPF (incluindo `rendition:*` e `coverHref`), spine linear/não-linear e TOC aninhado de `<nav>` e `.ncx`.
  - Confiança: 🟢

- [x] **T-09**, Implementar `ThemeTokens` e gerador/sanitizador de CSS (`DesignLanguage`, `toCssVariables`, `sanitizeCssColor`, `sanitizeCssFont`)
  - Origem no legado: `reader-core/src/commonMain/kotlin/com/readerengine/core/theme/ThemeTokens.kt:1-184`
  - Critério de pronto: Gera CSS para `MATERIAL3`, `HIG`, `FLUENT` e `CUSTOM` com suporte a modo noturno, paginação por colunas CSS, Fixed-Layout e MathML, bloqueando CSS Injection.
  - Confiança: 🟢

- [x] **T-10**, Implementar `BookSearchEngine` com normalização `foldDiacritics` 1-para-1 e cache de texto plano
  - Origem no legado: `reader-core/src/commonMain/kotlin/com/readerengine/core/search/BookSearchEngine.kt:1-150`
  - Critério de pronto: Busca textual ignora acentos e caixa preservando offsets exatos no texto original e gerando snippets de contexto (`-45..+55` chars).
  - Confiança: 🟢

- [x] **T-11**, Implementar `PayloadCodec` e `ReaderJsBridge` (`injectIntoHtml`, `parseSpecialUrl`, `FALLBACK_READER_JS`)
  - Origem no legado: `reader-core/src/commonMain/kotlin/com/readerengine/core/bridge/PayloadCodec.kt:1-48`, `ReaderJsBridge.kt:1-538`
  - Critério de pronto: Comprime payloads `>= 200KB` com `gz:<base64>`, injeta HTML/CSS/JS na página atual e classifica rotas especiais (`parseSpecialUrl`).
  - Confiança: 🟢

- [x] **T-12**, Implementar `ReaderEngine`, `ReaderSession` e `FlowSubscription`
  - Origem no legado: `reader-core/src/commonMain/kotlin/com/readerengine/core/session/ReaderSession.kt:1-599`, `FlowSubscription.kt:1-30`
  - Critério de pronto: Processa todos os 18 `ReaderCommand`, resolve hrefs em 3 etapas, aplica throttling de 2px no scroll e expõe `watchState`/`watchEvents`/`watchUiEffects`.
  - Confiança: 🟢

---

## Tarefas de Teste

- [x] **TT-01**, Executar suíte completa `ReaderEngineTest` (`76` testes cobrindo EPUB 2/3, HPUB, JSON, ZipLimits, LRU cache, range streaming, DRM IDPF/Adobe, busca sem diacríticos, paginação e temas)
  - Origem no legado: `reader-core/src/commonTest/kotlin/com/readerengine/core/ReaderEngineTest.kt:1-1623`

---

## Ordem Sugerida

1. **Fundação e Modelos (`T-01`, `T-02`, `T-03`)** — Sem dependências internas; base para todos os demais pacotes.
2. **Provedores de I/O, XML e DRM (`T-04`, `T-05`, `T-06`, `T-07`)** — Habilitam leitura segura de containers ZIP e desofuscação.
3. **Parsers, Busca, Temas e Bridge (`T-08`, `T-09`, `T-10`, `T-11`)** — Constroem a `Publication` e os transformadores de conteúdo.
4. **Orquestração de Sessão e Testes (`T-12`, `TT-01`)** — Integram todos os subsistemas em `ReaderSession` e validam regressões.

---

## Lacunas Pendentes (🔴)

- Nenhuma lacuna bloqueante (🔴) identificada no módulo `reader-core`.
