# Inventário do Projeto — `reader-engine`

> **Gerado por:** Reversa Scout (`reconhecimento`)
> **Escala de confiança:** 🟢 CONFIRMADO (extraído diretamente da árvore do repositório)

---

## 1. Visão Geral da Superfície

- **Nome do Projeto:** `reader-engine`
- **Arquitetura de Build:** Gradle Multi-Project (`Kotlin Multiplatform` + `Android Application`)
- **Linguagem Principal:** Kotlin (`2.4.20`, Explicit API mode habilitado no core)
- **Targets Suportados (`:reader-core`):**
  - Android Library (`compileSdk = 35`, `minSdk = 24`, JVM Target 17)
  - JVM (`JVM_17`)
  - iOS (`iosX64`, `iosArm64`, `iosSimulatorArm64` -> `XCFramework("ReaderCore")`)
- **Aplicação de Demonstração (`:sample-android`):** Android App (`com.readerengine.app`, `minSdk = 26`, `targetSdk = 35`, Jetpack Compose + WebView)

---

## 2. Árvore de Diretórios e Arquivos

```text
reader-engine/
├── .github/
│   └── workflows/
│       ├── ci.yml                                  # Pipeline de CI (testes JVM + build Release/Debug)
│       └── publish-mobile.yml                      # Publicação Maven (PublicMaven, Vercel, Sonatype, GitHub Packages)
├── fixtures/                                       # Arquivos de amostra para testes e validação
│   ├── noite-clara.json
│   ├── sample.epub
│   └── sample.hpub
├── gradle/
│   ├── gradle-daemon-jvm.properties                # Configuração de toolchain JVM (JDK 25 daemon)
│   ├── libs.versions.toml                          # Catálogo centralizado de versões e dependências
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties               # Gradle 9.4.1
├── reader-core/                                    # Módulo principal: Motor de leitura KMP Headless
│   ├── build.gradle.kts                            # Configuração KMP, XCFramework, Android e Maven Publish
│   └── src/
│       ├── commonMain/kotlin/com/readerengine/core/
│       │   ├── bridge/
│       │   │   ├── PayloadCodec.kt                 # Compressão/descompressão transparente GZIP Base64 (gz:)
│       │   │   └── ReaderJsBridge.kt               # Injeção JS/CSS (Rangy, colunas CSS, MathML, formulários, ações)
│       │   ├── drm/
│       │   │   └── ContentProtection.kt            # Desofuscação de fontes IDPF/Adobe e interface ContentDecryptor
│       │   ├── error/
│       │   │   └── ReaderException.kt              # Hierarquia selada de exceções do motor
│       │   ├── model/
│       │   │   ├── Models.kt                       # Modelos de domínio (Publication, Locator, ReadingProgress, etc.)
│       │   │   └── ReaderStrings.kt                # Internacionalização de mensagens (PT/EN/ES)
│       │   ├── parser/
│       │   │   ├── EpubParser.kt                   # Parser EPUB 2/3 (OPF, NCX, Navigation Document, Fixed-Layout)
│       │   │   ├── FormatParser.kt                 # Contratos FormatParser, PublicationSource e ParserRegistry
│       │   │   ├── HpubParser.kt                   # Parser HPUB / Baker Framework (book.json)
│       │   │   ├── JsonParser.kt                   # Parser JSON estruturado nativo
│       │   │   └── XmlEntities.kt                  # Decodificador single-pass de entidades XML/HTML
│       │   ├── platform/
│       │   │   └── PlatformFileSystem.kt           # Expect declaration para FileSystem nativo
│       │   ├── provider/
│       │   │   └── ResourceProviders.kt            # ZipResourceProvider (RAM e Okio FileHandle streaming + ZipLimits)
│       │   ├── search/
│       │   │   └── BookSearchEngine.kt             # Busca textual insensível a acentos com cache por capítulo
│       │   ├── session/
│       │   │   ├── FlowSubscription.kt             # Wrapper Closeable/Cancellable para interop Swift/iOS
│       │   │   ├── ReaderEngine.kt                 # Ponto de entrada principal da biblioteca
│       │   │   └── ReaderSession.kt                # Máquina de estados reativa (StateFlow, SharedFlow, comandos)
│       │   └── theme/
│       │       └── ThemeTokens.kt                  # Tokens de tema (Material 3, Apple HIG, Fluent e Custom)
│       ├── androidMain/kotlin/com/readerengine/core/platform/
│       │   └── PlatformFileSystem.android.kt       # Actual Android para FileSystem.SYSTEM
│       ├── iosMain/kotlin/com/readerengine/core/platform/
│       │   └── PlatformFileSystem.ios.kt           # Actual iOS para FileSystem.SYSTEM
│       ├── jvmMain/kotlin/com/readerengine/core/platform/
│       │   └── PlatformFileSystem.jvm.kt           # Actual JVM para FileSystem.SYSTEM
│       └── commonTest/kotlin/com/readerengine/core/
│           └── ReaderEngineTest.kt                 # Suíte de testes multiplataforma (15 testes)
├── sample-android/                                 # App Android demonstrativo (Host App)
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── samples/                            # Livros embarcados (.epub, .hpub, .json)
│       │   └── themes/                             # Temas JSON embarcados
│       ├── java/com/readerengine/app/
│       │   └── MainActivity.kt                     # UI Compose, WebViewClient HTTP 206, WebChromeClient, Bridges JS
│       └── res/values/
│           ├── strings.xml
│           └── themes.xml
├── scripts/
│   └── deploy-public-maven-vercel.sh               # Script de deploy estático de repositório Maven na Vercel
├── themes/                                         # Definições JSON de temas na raiz
│   ├── fluent.json
│   ├── hig.json
│   └── material3.json
├── build.gradle.kts                                # Configuração raiz de plugins Gradle
├── gradle.properties                               # Propriedades JVM, AndroidX, KMP e GitHub Packages
├── settings.gradle.kts                             # Definição dos módulos :reader-core e :sample-android
├── CHANGELOG.md
├── LICENSE
└── README.md
```

---

## 3. Contagem de Arquivos por Linguagem / Tipo

| Linguagem / Formato | Extensões | Quantidade | Papel no Sistema |
|---------------------|-----------|------------|------------------|
| **Kotlin** | `.kt` | 19 | Código-fonte principal (`:reader-core`, `:sample-android`) e testes (`commonTest`) |
| **Kotlin Script (Gradle)** | `.kts` | 4 | Scripts de build (`settings.gradle.kts`, `build.gradle.kts`) |
| **JSON** | `.json` | 8 | Fixtures de livros (`noite-clara.json`) e tokens de temas (`material3`, `hig`, `fluent`) |
| **XML** | `.xml` | 3 | `AndroidManifest.xml`, `strings.xml`, `themes.xml` |
| **YAML** | `.yml` | 2 | Workflows do GitHub Actions (`ci.yml`, `publish-mobile.yml`) |
| **TOML / Properties** | `.toml`, `.properties` | 4 | Catálogo de versões (`libs.versions.toml`) e propriedades do Gradle |
| **Shell / Batch** | `.sh`, `.bat`, `gradlew` | 3 | Gradle wrappers e script de publicação Vercel |
| **Markdown** | `.md` | 2 | `README.md` e `CHANGELOG.md` |
| **Pacotes de E-book (Binários)** | `.epub`, `.hpub` | 5 | Fixtures para testes e demonstração no app Android |

---

## 4. Pontos de Entrada (Entry Points)

1. **API Pública da Biblioteca (`:reader-core`):**
   - [`ReaderEngine.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/session/ReaderEngine.kt) — Fachada principal (`open`, `openSource`, `parseBytes`, `parsePath`, `parseSource`).
   - [`ReaderSession.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonMain/kotlin/com/readerengine/core/session/ReaderSession.kt) — Sessão interativa de leitura (`state`, `events`, `uiEffects`, `dispatch`, `handleUrlNavigation`, `readResource`, `readResourceRange`).
2. **Aplicação Host Android (`:sample-android`):**
   - [`MainActivity.kt`](file:///Users/andrealves/Documents/dev/reader-engine/sample-android/src/main/java/com/readerengine/app/MainActivity.kt) — Activity principal em Jetpack Compose integrando `ReaderEngine` com `android.webkit.WebView`.
3. **Automação e CI/CD:**
   - [`.github/workflows/ci.yml`](file:///Users/andrealves/Documents/dev/reader-engine/.github/workflows/ci.yml) — Executa `:reader-core:jvmTest`, `:reader-core:assembleRelease` e `:sample-android:assembleDebug`.
   - [`.github/workflows/publish-mobile.yml`](file:///Users/andrealves/Documents/dev/reader-engine/.github/workflows/publish-mobile.yml) — Publica artefatos KMP/Android/JVM no GitHub Releases, Vercel Maven, Sonatype Central e GitHub Packages.

---

## 5. Banco de Dados e Persistência

- **`reader-core`:** 🟢 **CONFIRMADO** — Totalmente *stateless* em relação a banco de dados (não possui SQLite/Room/ORM). Exporta estados, destaques (`gz:` ou JSON) e marcadores (`exportBookmarksJson` / `importBookmarksJson`) para que o aplicativo host persista onde preferir.
- **`sample-android`:** 🟢 **CONFIRMADO** — Utiliza `SharedPreferences` (`ReaderHostStore` em `MainActivity.kt`) para persistir localmente progresso por `documentId`, destaques, formulários, marcadores e preferências visuais.

---

## 6. Cobertura de Testes

- **Framework:** `kotlin.test` + `kotlinx-coroutines-test` + `okio-fakefilesystem`.
- **Arquivo de Teste:** [`ReaderEngineTest.kt`](file:///Users/andrealves/Documents/dev/reader-engine/reader-core/src/commonTest/kotlin/com/readerengine/core/ReaderEngineTest.kt) (667 linhas, **15 testes** cobrindo EPUB 2/3, HPUB, JSON, streaming `FileHandle`, limites anti-*Zip Bomb*, paginação por colunas, Fixed-Layout, MathML, desofuscação IDPF/Adobe, `ContentDecryptor`, busca insensível a acentos, progresso monotônico, entidades XML, higienização XSS, HTTP Range requests e Action Bridge).
