# Matriz de Rastreabilidade Código ↔ Especificação (`code-spec-matrix`)

> Gerado pelo **Writer** (Reversa v1.3.6) | Escopo: Global | Nível: `detalhado`

---

## 1. Cobertura de Arquivos do Código-Fonte Legado por Unit SDD

Todos os **19 arquivos Kotlin** (`.kt`) e **7 arquivos de build/CI/distribuição** do projeto foram mapeados para suas respectivas units em `_reversa_sdd/`:

| Arquivo do Legado | Unit Correspondente | Artefatos SDD que Cobrem o Arquivo | Cobertura |
|-------------------|---------------------|------------------------------------|-----------|
| `reader-core/src/commonMain/kotlin/com/readerengine/core/error/ReaderException.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md`, `edge-cases.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/model/Models.kt` | `reader-core/` | `requirements.md`, `design.md`, `contracts.md`, `erd-complete.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/Source.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md`, `domain.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/EpubParser.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md`, `edge-cases.md`, `flowcharts/reader-core-epub-parser.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/HpubParser.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/JsonParser.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/parser/XmlEntities.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md`, `edge-cases.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/provider/ZipResourceProvider.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md`, `edge-cases.md`, `flowcharts/reader-core-zip-central-directory.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/provider/PlatformCompression.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md` | 🟢 100% |
| `reader-core/src/jvmMain/kotlin/com/readerengine/core/provider/PlatformCompression.jvm.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md` | 🟢 100% |
| `reader-core/src/androidMain/kotlin/com/readerengine/core/provider/PlatformCompression.android.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md` | 🟢 100% |
| `reader-core/src/iosMain/kotlin/com/readerengine/core/provider/PlatformCompression.ios.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/drm/DrmProvider.kt` | `reader-core/` | `requirements.md`, `design.md`, `contracts.md`, `adrs/004-pipeline-drm-desofuscacao-fontes-e-decryptor-pluggable.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/session/ReaderSession.kt` | `reader-core/` | `requirements.md`, `design.md`, `contracts.md`, `state-machines.md`, `flowcharts/reader-core-dispatch-command.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/session/FlowSubscription.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/bridge/PayloadCodec.kt` | `reader-core/` | `requirements.md`, `design.md`, `edge-cases.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/bridge/ReaderJsBridge.kt` | `reader-core/` | `requirements.md`, `design.md`, `contracts.md`, `adrs/003-arquitetura-hibrida-estado-kmp-e-renderizacao-webview.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/search/BookSearchEngine.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md`, `domain.md` | 🟢 100% |
| `reader-core/src/commonMain/kotlin/com/readerengine/core/theme/ThemeTokens.kt` | `reader-core/` | `requirements.md`, `design.md`, `tasks.md`, `permissions.md` | 🟢 100% |
| `reader-core/src/commonTest/kotlin/com/readerengine/core/ReaderEngineTest.kt` | `reader-core/` | `requirements.md`, `tasks.md` (`TT-01`) | 🟢 100% |
| `sample-android/src/main/java/com/readerengine/app/MainActivity.kt` | `sample-android/` | `requirements.md`, `design.md`, `tasks.md`, `contracts.md`, `edge-cases.md`, `flowcharts/sample-android-http206-range.md` | 🟢 100% |
| `reader-core/build.gradle.kts` & `sample-android/build.gradle.kts` | Transversal | `dependencies.md`, `architecture.md`, `deployment.md` | 🟢 100% |
| `.github/workflows/ci.yml` & `.github/workflows/publish-mobile.yml` | Transversal | `deployment.md`, `inventory.md` | 🟢 100% |
| `Package.swift` & `scripts/*.sh` | Transversal | `deployment.md`, `inventory.md` | 🟢 100% |

---

## 2. Resumo Estatístico de Cobertura

- **Total de arquivos de código-fonte de produção/teste (`.kt`):** 19
- **Arquivos mapeados com cobertura `🟢 100%`:** 19 / 19 (**100%**)
- **Arquivos órfãos (`n/a`):** 0
