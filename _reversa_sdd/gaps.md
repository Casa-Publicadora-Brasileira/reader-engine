# Relatório de Lacunas e Dívidas Técnicas Categorizadas (`gaps`)

> Gerado pelo **Reviewer** (Reversa v1.3.6) em 2026-11-12
> Nível: `detalhado` (Categorizado por Severidade: Crítico / Moderado / Cosmético)

---

## 1. Severidade Crítica (🔴 Bloqueante para Reimplementação)

*Nenhum item crítico identificado.* Todos os fluxos de execução de `reader-core` e `sample-android` estão 100% especificados e rastreados ao código-fonte.

---

## 2. Severidade Moderada (🟡 Inferências e Limitações Arquiteturais Conhecidas)

| ID | Unit Afetada | Descrição | Evidência / Localização | Status / Resolução |
|----|--------------|-----------|-------------------------|--------------------|
| **GAP-01** | `reader-core` | **`InitialBookState` não armazena `currentSubPage`:** No modo `PAGINATED`, a reabertura do livro restaura o capítulo (`currentPage`) e o modo `PAGINATED`, mas reiniciava `currentSubPage = 0`. | `Models.kt`, `ReaderSession.kt`, `ReaderJsBridge.kt` | ✅ **RESOLVIDO (v0.4.0)**: Adicionado `initialSubPage: Int = 0` em `InitialBookState`, `subPage` em `ReadingProgress`, comando `goToSubPageCommand(index)` e persistência reativa. |
| **GAP-02** | `reader-core` | **Descriptografia em `DecryptingResourceProvider.readRange`:** Para recursos criptografados/ofuscados em `META-INF/encryption.xml`, `readRange` precisa ler e descriptografar o recurso completo antes de fatiar o intervalo `[offset, offset + length)`. | `ContentProtection.kt` | ✅ **RESOLVIDO (v0.6.0)**: Adicionado `ContentDecryptor.decryptRange(...)` com implementação streaming em `StandardEpubFontDeobfuscator` (IDPF/Adobe) e suporte plugável para DRM por blocos em `DecryptingResourceProvider.readRange`. |
| **GAP-03** | `reader-core` | **Parsing XML/OPF baseado em Regex:** `EpubParser` e `EpubEncryptionParser` usam expressões regulares em vez de parser XML StAX/DOM. | `EpubParser.kt:360-640`, `DrmProvider.kt:40-78` | ⏳ *Planejado para Fase 4 (v0.7.0)*: Tokenizer XML determinístico em Kotlin puro. |

---

## 3. Severidade Cosmética / Manutenibilidade (🟢 Dívidas Técnicas Leves)

| ID | Unit Afetada | Descrição | Evidência / Localização | Status / Resolução |
|----|--------------|-----------|-------------------------|--------------------|
| **GAP-04** | `reader-core` | **Código idêntico entre `PlatformCompression.jvm.kt` e `PlatformCompression.android.kt`:** Os dois arquivos compartilhavam código `java.util.zip`. | `reader-core/src/commonMain/.../PayloadCodec.kt` | ✅ **RESOLVIDO (v0.4.0)**: Substituído por `PayloadCodec` multiplataforma com Okio `GzipSink`/`GzipSource` em `commonMain`. |
| **GAP-05** | `sample-android` | **Concentração de classes em `MainActivity.kt`:** O app demonstrativo Android reunia persistência, bridges JS, dialogs e telas em um único arquivo. | `sample-android/src/main/java/com/readerengine/app/` | ✅ **RESOLVIDO (v0.4.0)**: Modularizado em `HostBookPersistenceStore.kt`, `ReaderBridges.kt` e `OpenBookDialog.kt`. |
