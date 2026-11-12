# User Stories — Fluxo de Abertura, Leitura, Paginação e Personalização

> Gerado pelo **Writer** (Reversa v1.3.6) | Escopo: Global | Nível: `detalhado`

---

## US-01: Abertura Instantânea de Publicações EPUB 2/3, HPUB e JSON com Restauração de Progresso

**Como** leitor de livros digitais,  
**Quero** abrir arquivos `.epub`, `.hpub` ou `.json` locais e retomar exatamente da página, posição de rolagem e aparência em que parei,  
**Para que** minha leitura seja contínua mesmo em livros extensos de centenas de megabytes. 🟢

### Critérios de Aceite (Gherkin)
```gherkin
Dado que o usuário possui um arquivo EPUB em disco já lido anteriormente até a página 3 com scrollY = 420
Quando o aplicativo invoca source.sha256DocumentId() e passa o InitialBookState salvo para ReaderEngine.open
Então a sessão é inicializada na página 3 com scrollY = 420
E o efeito ReaderUiEffect.ScrollToY(420) é emitido para posicionar a WebView
```

---

## US-02: Alternância entre Rolagem Contínua (`SCROLLED`) e Paginação por Colunas (`PAGINATED`)

**Como** leitor,  
**Quero** alternar entre o modo de rolagem vertical contínua e o modo paginado por colunas horizontais (ou visualizar capítulos `pre-paginated` em layout fixo),  
**Para que** eu possa escolher a experiência de leitura mais confortável para cada tipo de obra. 🟢

### Critérios de Aceite (Gherkin)
```gherkin
Dado que o usuário está lendo um capítulo reflowable no modo SCROLLED
Quando ele seleciona o modo PAGINATED (ReaderCommand.SetReadingMode(ReadingMode.PAGINATED))
Então o HTML injetado passa a aplicar CSS multi-column (column-width: 100vw; overflow: hidden)
E os comandos NextSubPage e PreviousSubPage navegam entre as colunas do capítulo antes de avançar ao próximo capítulo
```

---

## US-03: Personalização de Temas (`Material 3`, `iOS HIG`, `Fluent`, `Noturno`) e Escala Tipográfica

**Como** leitor em diferentes condições de iluminação,  
**Quero** trocar o tema visual (incluindo modo noturno) e ajustar o tamanho da fonte entre `12sp` e `36sp`,  
**Para que** o contraste da página, equações MathML e blocos de código se adaptem imediatamente sem recarregar o capítulo. 🟢

### Critérios de Aceite (Gherkin)
```gherkin
Dado que o capítulo atual está renderizado na WebView
Quando o usuário despacha ReaderCommand.SetTheme(ThemeTokens.material3(isNight = true)) e ReaderCommand.SetFontSize(20)
Então a sessão emite ApplyThemeJs e ApplyFontSizeJs(20)
E a WebView atualiza as variáveis CSS (:root) instantaneamente via window.readerJS
```
