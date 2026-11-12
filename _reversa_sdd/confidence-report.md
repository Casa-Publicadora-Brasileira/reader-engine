# Relatório de Confiança — `reader-engine`

> Gerado pelo **Reviewer** (Reversa v1.3.6) em 2026-11-12

---

## Resumo Geral

| Nível | Quantidade de Afirmações / Regras / Contratos | Percentual |
|-------|-----------------------------------------------|------------|
| 🟢 CONFIRMADO | 116 | 96,7% |
| 🟡 INFERIDO   | 4 | 3,3% |
| 🔴 LACUNA     | 0 | 0,0% |
| **Total**     | **120** | **100%** |

**Confiança geral:** **98,3%** (soma de 🟢 `116` + metade dos 🟡 `2.0` = `118 / 120`)

---

## Por Spec e Artefato SDD

| Spec / Artefato | 🟢 | 🟡 | 🔴 | Confiança |
|-----------------|----|----|-----|-----------|
| [`reader-core/requirements.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/reader-core/requirements.md) | 24 | 1 | 0 | 98,0% |
| [`reader-core/design.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/reader-core/design.md) | 22 | 2 | 0 | 95,8% |
| [`reader-core/tasks.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/reader-core/tasks.md) | 13 | 0 | 0 | 100,0% |
| [`reader-core/contracts.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/reader-core/contracts.md) | 26 | 0 | 0 | 100,0% |
| [`reader-core/edge-cases.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/reader-core/edge-cases.md) | 8 | 0 | 0 | 100,0% |
| [`sample-android/requirements.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/sample-android/requirements.md) | 9 | 0 | 0 | 100,0% |
| [`sample-android/design.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/sample-android/design.md) | 11 | 1 | 0 | 95,8% |
| [`sample-android/tasks.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/sample-android/tasks.md) | 6 | 0 | 0 | 100,0% |
| [`sample-android/contracts.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/sample-android/contracts.md) | 8 | 0 | 0 | 100,0% |
| [`sample-android/edge-cases.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/sample-android/edge-cases.md) | 4 | 0 | 0 | 100,0% |
| [`user-stories/leitura-e-navegacao.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/user-stories/leitura-e-navegacao.md) | 3 | 0 | 0 | 100,0% |
| [`user-stories/anotacoes-busca-e-midia.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/user-stories/anotacoes-busca-e-midia.md) | 3 | 0 | 0 | 100,0% |
| [`traceability/code-spec-matrix.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/traceability/code-spec-matrix.md) | 24 | 0 | 0 | 100,0% |

---

## Validação de Matrizes de Rastreabilidade

1. **[`traceability/code-spec-matrix.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/traceability/code-spec-matrix.md):** Validada com **100% de cobertura** (19/19 arquivos `.kt` e todos os scripts/workflows mapeados sem nenhum arquivo órfão).
2. **[`traceability/spec-impact-matrix.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/traceability/spec-impact-matrix.md):** Validada contra os imports reais dos pacotes `model`, `error`, `provider`, `drm`, `parser`, `search`, `theme`, `bridge`, `session` e `sample-android`.

---

## Lacunas Pendentes 🔴

- **0 lacunas 🔴 pendentes.**

---

## Recomendações

- [x] Especificações SDD de `reader-core` e `sample-android` prontas para uso por agentes de IA (`/reversa-forward`, `/reversa-debugger`, `/reversa-refactor` ou `/reversa-docs`).
- [ ] Avaliar futuramente as 2 perguntas opcionais de evolução em [`_reversa_sdd/questions.md`](file:///Users/andrealves/Documents/dev/reader-engine/_reversa_sdd/questions.md) (`currentSubPage` em `InitialBookState` e eventual app `sample-ios`).

---

## Histórico de Reclassificações

| De | Para | Afirmação | Evidência |
|----|------|-----------|-----------|
| 🟡 | 🟢 | Cálculo monotônico de `scrollFraction` quando `contentHeightPx > viewportHeightPx && viewportHeightPx > 0` | Confirmado em `Models.kt:190-198` (corrigido no commit `cd12514`) |
| 🟡 | 🟢 | Limite de chunk de 1 MB (`1_048_576` bytes) no streaming HTTP 206 da WebView | Confirmado na constante `MAX_RANGE_CHUNK_BYTES` em `MainActivity.kt:285` |
