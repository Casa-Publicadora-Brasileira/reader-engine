# ADR-003: Arquitetura Híbrida — Máquina de Estado em KMP e Renderização via WebView Bridge (`ReaderJsBridge`)

> Gerado pelo **Detective** (Reversa v1.3.6) | Status: **Aceito** | Confiança: 🟢 **CONFIRMADO** (`813df95`, `6189725`, `91a7a27`)

## Contexto

O padrão EPUB 2/3 é intrinsecamente baseado na plataforma Web (XHTML5, CSS3, SVG, MathML, `@font-face`, layouts fixos com viewport explícito e elementos `<audio>`/`<video>`). Tentar construir um motor de layout e tipografia HTML/CSS do zero em Canvas nativo (Compose Canvas / CoreText) exigiria anos de engenharia e nunca atingiria 100% de compatibilidade com livros complexos, equações MathML ou scripts de interatividade educacional. Por outro lado, delegar a lógica de negócio da sessão (progresso, busca, bookmarks, temas, DRM) para JavaScript dentro da WebView tornaria o estado frágil e difícil de testar fora de um navegador.

## Decisão

Adotar uma **Arquitetura Híbrida Estritamente Desacoplada**:
1. **Cérebro da Sessão no KMP (`ReaderSession`):** Toda a máquina de estados (`ReaderState`), cálculo monotônico de progresso, busca textual, resolução de hrefs e persistência de highlights/formulários rodam em Kotlin puro (`commonMain`), testáveis em milissegundos sem emulador ou WebView.
2. **Injeção Determinística de HTML/CSS/JS (`ReaderJsBridge`):** Antes de entregar o capítulo à WebView, `ReaderJsBridge.injectIntoHtml(...)` injeta no `<head>` as variáveis CSS do `ThemeTokens`, estilos de paginação por múltiplas colunas (`column-width: 100vw`), regras MathML e o script `FALLBACK_READER_JS` (`window.readerJS` + `window.ReaderEngine.postAction`).
3. **Comunicação Unidirecional Guiada por Efeitos (`ReaderUiEffect`):** Comandos despachados à `ReaderSession` emitem `ReaderUiEffect` (`ApplyThemeJs`, `ApplyFontSizeJs`, `ApplyHighlightsJs`, `RestoreFormsJs`, `ScrollToY`, `ExecuteJs`), que o host nativo executa na WebView via `evaluateJavascript`.

## Alternativas Consideradas

1. **Renderizador Nativo Puro (Compose Text / AttributedString sem WebView):**
   - *Prós:* Menor consumo de memória que uma instância de WebView.
   - *Contras:* Incapaz de renderizar fielmente CSS complexo, tabelas, SVG, MathML, EPUB 3 Fixed Layout (`pre-paginated`) e formulários HTML interativos.
2. **Motor 100% JavaScript rodando dentro da WebView (estilo ReadiumJS / Epub.js):**
   - *Prós:* Portável para navegadores web.
   - *Contras:* Estado preso dentro do contexto JS da WebView, serialização constante por ponte assíncrona para atualizar a UI nativa e impossibilidade de rodar testes de domínio rápidos em JVM pura.

## Consequências

- **Positivas:**
  - 76+ testes automatizados em `ReaderEngineTest.kt` validam navegação, busca, temas, paginação, DRM e ponte JS sem depender de WebView real.
  - Suporte completo a CSS multi-column pagination, EPUB 3 Fixed-Layout e MathML com poucas centenas de linhas em `ThemeTokens` e `ReaderJsBridge`.
- **Negativas / Trade-offs:**
  - O aplicativo host precisa instanciar uma `WebView` (Android) ou `WKWebView` (iOS) e conectar `uiEffects` a `evaluateJavascript` e `ReaderNativeBridge`/`AndroidScroll` de volta a `session.dispatch(...)`.
