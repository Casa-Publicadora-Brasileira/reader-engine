# Relatório de Lacunas e Dívidas Técnicas Categorizadas (`gaps`)

> Gerado pelo **Reviewer** (Reversa v1.3.6) em 2026-11-12
> Nível: `detalhado` (Categorizado por Severidade: Crítico / Moderado / Cosmético)

---

## 1. Severidade Crítica (🔴 Bloqueante para Reimplementação)

*Nenhum item crítico identificado.* Todos os fluxos de execução de `reader-core` e `sample-android` estão 100% especificados e rastreados ao código-fonte.

---

## 2. Severidade Moderada (🟡 Inferências e Limitações Arquiteturais Conhecidas)

| ID | Unit Afetada | Descrição | Evidência / Localização | Recomendação |
|----|--------------|-----------|-------------------------|--------------|
| **GAP-01** | `reader-core` | **`InitialBookState` não armazena `currentSubPage`:** No modo `PAGINATED`, a reabertura do livro restaura o capítulo (`currentPage`) e o modo `PAGINATED`, mas reinicia `currentSubPage = 0`. | `Models.kt:255`, `ReaderSession.kt:92` | Adicionar `currentSubPage: Int = 0` em `InitialBookState` caso seja necessário restaurar a coluna exata em capítulos longos. |
| **GAP-02** | `reader-core` | **Descriptografia em `DecryptingResourceProvider.readRange`:** Para recursos criptografados/ofuscados em `META-INF/encryption.xml`, `readRange` precisa ler e descriptografar o recurso completo antes de fatiar o intervalo `[offset, offset + length)`. | `DrmProvider.kt:182-196` | Comportamento esperado para fontes ofuscadas (pequenas), mas se um vídeo grande usar DRM customizado (`ContentDecryptor`), recomenda-se suportar descriptografia em blocos (CTR/CBC chunked) no futuro. |
| **GAP-03** | `reader-core` | **Parsing XML/OPF baseado em Regex:** `EpubParser` e `EpubEncryptionParser` usam expressões regulares em vez de parser XML StAX/DOM. | `EpubParser.kt:360-640`, `DrmProvider.kt:40-78` | Adequado para manter zero dependências pesadas em `commonMain`; manter cobertura de testes ao adicionar novos atributos OPF. |

---

## 3. Severidade Cosmética / Manutenibilidade (🟢 Dívidas Técnicas Leves)

| ID | Unit Afetada | Descrição | Evidência / Localização | Recomendação |
|----|--------------|-----------|-------------------------|--------------|
| **GAP-04** | `reader-core` | **Código idêntico entre `PlatformCompression.jvm.kt` e `PlatformCompression.android.kt`:** Os dois arquivos compartilham exatamente as mesmas 61 linhas baseadas em `java.util.zip`. | `reader-core/src/jvmMain/...`, `reader-core/src/androidMain/...` | Opcionalmente unificar em um sourceSet intermediário `jvmAndAndroidMain` no `build.gradle.kts`. |
| **GAP-05** | `sample-android` | **Concentração de classes em `MainActivity.kt`:** O app demonstrativo Android reúne UI Compose, `WebViewClient`, bridges JS, persistência e gerador de fixtures em um único arquivo de 1.127 linhas. | `sample-android/src/main/java/com/readerengine/app/MainActivity.kt` | Caso o `sample-android` cresça, separar `BookResourceWebViewClient` e `HostBookPersistenceStore` em arquivos próprios. |
