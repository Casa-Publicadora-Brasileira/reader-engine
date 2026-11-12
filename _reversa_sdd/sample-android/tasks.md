# `sample-android`, Tarefas de Implementação

> Gerado pelo **Writer** (Reversa v1.3.6) | Unit: `sample-android` | Nível: `detalhado`

---

## Pré-requisitos

- [x] Módulo `:reader-core` compilado e vinculado em `sample-android/build.gradle.kts`.
- [x] Android SDK `compileSdk = 35`, `minSdk = 24` e Compose habilitado (`buildFeatures { compose = true }`).

---

## Tarefas

- [x] **T-SA-01**, Implementar pontes `@JavascriptInterface` (`ScrollProgressInterface` e `ActionBridge`) com postagem segura no `Looper.getMainLooper()`
  - Origem no legado: `sample-android/src/main/java/com/readerengine/app/MainActivity.kt:85-99`
  - Critério de pronto: Eventos JS de scroll e `postAction` chegam na thread principal sem `CalledFromWrongThreadException`.
  - Confiança: 🟢

- [x] **T-SA-02**, Implementar `HostBookPersistenceStore` sobre `SharedPreferences` + `PayloadCodec`
  - Origem no legado: `sample-android/src/main/java/com/readerengine/app/MainActivity.kt:101-249`
  - Critério de pronto: Salva e restaura `InitialBookState` (página, scrollY, modo de leitura, tema, fonte, bookmarks, highlights e forms) por `bookId`.
  - Confiança: 🟢

- [x] **T-SA-03**, Implementar `BookResourceWebViewClient` com suporte a `book://content/`, HTTP 200, HTTP 206 (`Range`) e HTTP 416/404
  - Origem no legado: `sample-android/src/main/java/com/readerengine/app/MainActivity.kt:251-398`
  - Critério de pronto: Intercepta `shouldInterceptRequest`, fatia respostas `Range` em até 1 MB (`1_048_576` bytes) e roteia cliques em links via `ReaderJsBridge.parseSpecialUrl`.
  - Confiança: 🟢

- [x] **T-SA-04**, Implementar `ReaderScreen` em Jetpack Compose com integração SAF (`OpenDocument`), barras de controle, gavetas de navegação/anotações e `WebView` + `WebChromeClient` (fullscreen video)
  - Origem no legado: `sample-android/src/main/java/com/readerengine/app/MainActivity.kt:429-948`
  - Critério de pronto: Coleta `state`, `events` e `uiEffects` da `ReaderSession`, executa scripts via `evaluateJavascript` e exibe vídeo em tela cheia.
  - Confiança: 🟢

- [x] **T-SA-05**, Implementar gerador de publicações de demonstração em runtime (EPUB Reflowable, EPUB Media Video/Audio, HPUB)
  - Origem no legado: `sample-android/src/main/java/com/readerengine/app/MainActivity.kt:950-1127`
  - Critério de pronto: Gera arquivos `.epub` e `.hpub` válidos no diretório de cache para teste imediato sem arquivos externos.
  - Confiança: 🟢

---

## Tarefas de Teste

- [x] **TT-SA-01**, Compilação e verificação do APK Debug (`./gradlew :sample-android:assembleDebug`) no pipeline CI
  - Origem no legado: `.github/workflows/ci.yml:32`

---

## Ordem Sugerida

1. **Adaptadores de Persistência e Bridges JS (`T-SA-01`, `T-SA-02`)**
2. **Servidor Virtual WebViewClient HTTP 200/206 (`T-SA-03`)**
3. **Tela Principal Compose e Geradores Demo (`T-SA-04`, `T-SA-05`)**

---

## Lacunas Pendentes (🔴)

- Nenhuma lacuna bloqueante (🔴) identificada no módulo `sample-android`.
