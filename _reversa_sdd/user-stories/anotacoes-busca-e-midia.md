# User Stories — Fluxo de Anotações, Busca Sem Diacríticos, Formulários e Mídia Embarcada

> Gerado pelo **Writer** (Reversa v1.3.6) | Escopo: Global | Nível: `detalhado`

---

## US-04: Busca Textual Insensível a Acentos e Maiúsculas/Minúsculas

**Como** estudante ou pesquisador,  
**Quero** pesquisar termos em todo o livro sem me preocupar com acentuação ou letras maiúsculas, visualizando trechos de contexto (`snippets`) e saltando direto para a página encontrada,  
**Para que** eu localize rapidamente conceitos em qualquer capítulo da obra. 🟢

### Critérios de Aceite (Gherkin)
```gherkin
Dado um livro contendo "Equação Termodinâmica" no capítulo 4
Quando o usuário pesquisa por "equacao termodinamica" via ReaderCommand.Search
Então BookSearchEngine normaliza os diacríticos 1-para-1 e retorna SearchResult apontando para pageIndex = 4
Quando o usuário clica no resultado (ReaderCommand.OpenSearchResult)
Então a sessão navega imediatamente para o capítulo 4
```

---

## US-05: Criação e Persistência de Destaques (`Highlights`), Favoritos (`Bookmarks`) e Exercícios (`Forms`)

**Como** leitor de livros técnicos ou didáticos,  
**Quero** destacar trechos em 5 estilos (`YELLOW`, `GREEN`, `BLUE`, `PINK`, `UNDERLINE`), marcar páginas favoritas e preencher campos de exercícios HTML (`<input>`, `<textarea>`, `<select>`),  
**Para que** minhas anotações e respostas fiquem salvas e sejam restauradas sempre que eu voltar à página. 🟢

### Critérios de Aceite (Gherkin)
```gherkin
Dado que o usuário preencheu o campo "resposta_1" com "42" e destacou um parágrafo em amarelo na página 1
Quando ele navega para a página 2 e depois retorna para a página 1
Então a sessão emite ApplyHighlightsJs e RestoreFormsJs para a página 1
E o destaque amarelo e o valor "42" são reaplicados no DOM da WebView
```

---

## US-06: Reprodução e Seek em Vídeos e Áudios HTML5 Embarcados no EPUB 3

**Como** leitor de livros multimídia interativos,  
**Quero** reproduzir vídeos e áudios embutidos no arquivo EPUB, avançar para qualquer ponto da linha do tempo (seek) e expandir o vídeo em tela cheia,  
**Para que** eu consuma conteúdo audiovisual offline diretamente de dentro do pacote EPUB sem travamentos de memória. 🟢

### Critérios de Aceite (Gherkin)
```gherkin
Dado um EPUB contendo um vídeo MP4 armazenado como STORED no pacote ZIP
Quando o player <video> da WebView solicita um intervalo de bytes via cabeçalho Range
Então BookResourceWebViewClient lê apenas a fatia de até 1 MB via session.readResourceRange e responde HTTP 206 Partial Content
E quando o usuário aciona o botão de tela cheia no player de vídeo
Então WebChromeClient.onShowCustomView exibe o vídeo em modo fullscreen nativo
```
