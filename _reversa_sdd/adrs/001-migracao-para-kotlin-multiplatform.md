# ADR-001: Migração da Engine para Kotlin Multiplatform (KMP) com `explicitApi()`

> Gerado pelo **Detective** (Reversa v1.3.6) | Status: **Aceito** | Confiança: 🟢 **CONFIRMADO** (`ab566f4`, `e884000`)

## Contexto

Nas versões iniciais (`v0.1.0`, commits `2cb3adc` e `8096d2a`), o projeto explorava uma abordagem baseada em FFI (`reader-ffi`) empacotada separadamente para Android AAR, iOS Swift Package e snippets WASM/Web. Manter camadas de binding nativo (JNI/C-FFI) aumentava a complexidade de serialização de estado, dificultava o compartilhamento de fluxos reativos (`StateFlow`/`SharedFlow`) com Jetpack Compose e SwiftUI, e exigia toolchains heterogêneas no CI.

## Decisão

Migrar todo o núcleo de leitura para **Kotlin Multiplatform (`reader-core`)** compilando a partir de um único código-fonte em `commonMain` para:
- **Android** (`androidTarget` / AAR publicado em Maven)
- **iOS** (`iosX64`, `iosArm64`, `iosSimulatorArm64` empacotados via `XCFramework` `ReaderCore`)
- **JVM** (`jvm()` para testes rápidos de regressão e servidores/desktop)

Adicionalmente, ativar `explicitApi()` no Gradle (`reader-core/build.gradle.kts:42`) para exigir visibilidade e tipos de retorno explícitos em toda a superfície pública da biblioteca, e fornecer wrappers `FlowSubscription` (`watchState`, `watchEvents`, `watchUiEffects`) para consumo ergonômico de `Flow` no Swift/iOS sem exigir conhecimento de corrotinas pelo consumidor iOS.

## Alternativas Consideradas

1. **Manter núcleo em Rust + UniFFI / JNI (`reader-ffi`):**
   - *Prós:* Performance bruta de CPU e binário único para WebAssembly.
   - *Contras:* Fricção elevada para expor estados reativos complexos (`ReaderState`, `ReaderUiEffect`) no Android/Compose, necessidade de manter NDK/Cargo no pipeline mobile e overhead de cópia de memória na fronteira JNI.
2. **Implementações Nativas Separadas (Kotlin para Android + Swift para iOS):**
   - *Prós:* Uso direto de APIs nativas de cada plataforma.
   - *Contras:* Duplicação total das regras críticas de parsing EPUB 2/3, desofuscação de fontes, resolução de caminhos relativos, busca sem diacríticos e cálculo monotônico de progresso.

## Consequências

- **Positivas:**
  - Mais de 95% do código do motor reside em `commonMain`, restando apenas ~60-90 linhas por plataforma em `PlatformCompression.*.kt` (`java.util.zip` na JVM/Android e `platform.zlib` no iOS).
  - Integração zero-friction com Jetpack Compose no Android e consumo simplificado no iOS via `FlowSubscription`.
  - Contratos públicos blindados contra mudanças acidentais graças ao `explicitApi()`.
- **Negativas / Trade-offs:**
  - Como `commonMain` não possui API de ZIP nativa na biblioteca padrão Kotlin, foi necessário implementar um leitor próprio do Central Directory ZIP (`ZipResourceProvider`) sobre `Okio` + `inflateRaw` multiplataforma.
