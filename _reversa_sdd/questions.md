# Perguntas para Validação — `reader-engine`

> Gerado pelo **Reviewer** (Reversa v1.3.6) em 2026-11-12
> Nível: `detalhado`

---

## Status de Lacunas Críticas (🔴)

Após a auditoria completa dos **19 arquivos Kotlin** de produção/teste e dos **13 artefatos de especificação SDD**, **nenhuma lacuna bloqueante (🔴 LACUNA)** foi encontrada no comportamento atual do código. Todas as regras de negócio, algoritmos de desofuscação, limites de segurança e contratos de ponte foram extraídos diretamente do código-fonte com evidência linha a linha (🟢 **CONFIRMADO**).

Abaixo estão registradas **2 perguntas de evolução de produto (🟡 INFERIDO)** opcionais para confirmação futura pelo mantenedor:

---

## Pergunta 1 (Opcional — Evolução de Persistência Paginada)

**Contexto:** Módulo `reader-core` — classe `InitialBookState` em `reader-core/src/commonMain/kotlin/com/readerengine/core/model/Models.kt:255` e hidratação em `ReaderSession.kt:92`
**Spec afetada:** [`_reversa_sdd/reader-core/requirements.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/reader-core/requirements.md), [`_reversa_sdd/reader-core/design.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/reader-core/design.md)
**Pergunta:** Ao fechar um livro no modo `ReadingMode.PAGINATED`, deseja-se que `InitialBookState` também persista o índice da sub-página (`currentSubPage`) além do capítulo (`currentPage`) e `scrollY`, ou o comportamento atual de iniciar na sub-página `0` do capítulo salvo é intencional?
**Impacto:** Se desejar persistir a coluna exata em modo paginado, `InitialBookState` receberá o campo `currentSubPage: Int = 0` e `ReaderJsBridge` chamará `window.readerJS.goToSubPage(initialSubPage)` após o carregamento do capítulo.

**Resposta:** <!-- preencha aqui -->

---

## Pergunta 2 (Opcional — App Sample iOS no Monorepo)

**Contexto:** Raiz do monorepo — `settings.gradle.kts` e `Package.swift`
**Spec afetada:** [`_reversa_sdd/architecture.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/architecture.md)
**Pergunta:** Há intenção de incluir um projeto `sample-ios` (SwiftUI + `WKWebView` / `WKURLSchemeHandler`) dentro deste mesmo repositório para espelhar o `sample-android`, ou os apps iOS consumidores permanecem em repositórios externos consumindo o `ReaderCore.xcframework`?
**Impacto:** Define se o backlog futuro deve prever uma unit `sample-ios/` ou manter apenas `reader-core/` e `sample-android/`.

**Resposta:** <!-- preencha aqui -->
