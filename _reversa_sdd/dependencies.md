# Dependências e Stack Tecnológica — `reader-engine`

> **Gerado por:** Reversa Scout (`reconhecimento`)
> **Fonte:** [`gradle/libs.versions.toml`](file:///Users/andrealves/Documents/dev/reader-engine/gradle/libs.versions.toml), [`reader-core/build.gradle.kts`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/build.gradle.kts), [`sample-android/build.gradle.kts`](file:///Users/andrealves/Documents/dev/reader-engine/sample-android/build.gradle.kts)
> **Escala de confiança:** 🟢 CONFIRMADO

---

## 1. Ferramentas de Build e Compiladores

| Componente | Versão | Fonte | Papel |
|------------|--------|-------|-------|
| **Gradle Wrapper** | `9.4.1` | `gradle-wrapper.properties` | Sistema de build multi-projeto |
| **Kotlin (`multiplatform`, `android`, `serialization`, `compose`)** | `2.4.20` | `gradle/libs.versions.toml` | Linguagem principal e plugins KMP |
| **Android Gradle Plugin (AGP)** | `9.4.1` | `gradle/libs.versions.toml` | Build de biblioteca Android AAR e app de amostra |
| **Foojay Toolchain Resolver** | `1.0.0` | `settings.gradle.kts` | Resolução automática de JDK |
| **JVM Target (Compilação)** | `17` (`JvmTarget.JVM_17`) | `reader-core/build.gradle.kts` | Compatibilidade de bytecode Android e JVM |
| **Android SDK (`:reader-core`)** | `compileSdk = 35`, `minSdk = 24` | `reader-core/build.gradle.kts` | Suporte Android 7.0+ |
| **Android SDK (`:sample-android`)** | `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35` | `sample-android/build.gradle.kts` | App demonstrativo Android 8.0+ |

---

## 2. Dependências do Módulo `:reader-core`

### Produção (`commonMain`)

| Biblioteca | Coordenada Maven | Versão | Escopo | Finalidade |
|------------|------------------|--------|--------|------------|
| **Kotlinx Coroutines Core** | `org.jetbrains.kotlinx:kotlinx-coroutines-core` | `1.9.0` | `api` | Fluxos reativos (`StateFlow`, `SharedFlow`, `CoroutineScope`) |
| **Kotlinx Serialization JSON** | `org.jetbrains.kotlinx:kotlinx-serialization-json` | `1.7.3` | `api` | Parsing e serialização JSON (temas, livros JSON, HPUB `book.json`, bookmarks, payloads JS) |
| **Square Okio** | `com.squareup.okio:okio` | `3.9.1` | `api` | I/O multiplataforma, `FileSystem`, `FileHandle` (streaming em disco), descompressão Zlib/Deflate/Gzip e SHA-1/SHA-256 |

> **Destaque Arquitetural:** `:reader-core` possui apenas **3 dependências externas** em `commonMain`, todas 100% multiplataforma (compatíveis com Android, iOS e JVM), sem depender de bibliotecas nativas específicas de plataforma para parsing de ZIP ou XML.

### Testes (`commonTest`)

| Biblioteca | Coordenada Maven | Versão | Escopo | Finalidade |
|------------|------------------|--------|--------|------------|
| **Kotlin Test** | `org.jetbrains.kotlin:kotlin-test` | `2.4.20` | `implementation` | Asserções e anotações `@Test` multiplataforma |
| **Kotlinx Coroutines Test** | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `1.9.0` | `implementation` | Execução determinística de coroutines (`runTest`) |
| **Okio FakeFileSystem** | `com.squareup.okio:okio-fakefilesystem` | `3.9.1` | `implementation` | Simulação em memória de sistema de arquivos para testes de streaming `FileHandle` |

---

## 3. Dependências do Módulo `:sample-android`

| Biblioteca | Coordenada Maven | Versão | Escopo | Finalidade |
|------------|------------------|--------|--------|------------|
| **Reader Core** | `project(":reader-core")` | `0.3.1` | `implementation` | Motor de leitura KMP |
| **AndroidX Compose BOM** | `androidx.compose:compose-bom` | `2024.10.01` | `implementation(platform)` | Gerenciamento de versões do Jetpack Compose (`ui`, `ui-tooling-preview`, `material3`, `ui-tooling`) |
| **AndroidX Activity Compose** | `androidx.activity:activity-compose` | `1.9.3` | `implementation` | Integração `ComponentActivity` + `setContent` + `BackHandler` |
| **AndroidX Webkit** | `androidx.webkit:webkit` | `1.12.1` | `implementation` | Utilitários avançados para `android.webkit.WebView` |

---

## 4. Repositórios e Integrações de Publicação

- **Consumo de Dependências (`settings.gradle.kts`):**
  - `google()`
  - `mavenCentral()`
  - `gradlePluginPortal()`
- **Publicação de Artefatos (`reader-core/build.gradle.kts`):**
  - `PublicMaven` (`./public-maven` local -> publicado via `scripts/deploy-public-maven-vercel.sh` para `https://reader-engine-maven.vercel.app/`)
  - `SonatypeCentral` (`https://central.sonatype.com/api/v1/publisher/upload` e snapshots)
  - `GitHubPackages` (`https://maven.pkg.github.com/<owner>/<repo>` ou GitHub Enterprise Server `https://maven.<host>/<owner>/<repo>`)
