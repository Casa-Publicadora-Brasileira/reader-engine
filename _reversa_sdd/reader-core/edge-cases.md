# `reader-core` — Casos Extremos e Limites Operacionais (`edge-cases`)

> Gerado pelo **Writer** (Reversa v1.3.6) | Unit: `reader-core` | Nível: `detalhado`

---

## Catálogo de Casos de Borda Tratados no Código

### EC-01 — Ataque de Zip Bomb ou Arquivo EPUB Corrompido / Gigante
- **Gatilho:** Abrir um arquivo `.epub` ou `.hpub` que declara mais de `10.000` entradas no Central Directory, uma entrada individual `> 50 MB` ao chamar `readBytes`, ou soma total descompactada `> 1 GB`.
- **Comportamento no Código (`ZipResourceProvider.kt:16-32, 200-235`):** Lança imediatamente `ReaderException.InvalidContent` durante a leitura do Central Directory ou antes de alocar o buffer de descompressão em `inflateRaw`. 🟢

### EC-02 — Arquivo ZIP64 (> 4 GB ou > 65.535 entradas)
- **Gatilho:** Arquivo ZIP cujo registro EOCD contém `totalEntries == 0xFFFF` ou `centralDirSize == 0xFFFFFFFFL` ou `centralDirOffset == 0xFFFFFFFFL`.
- **Comportamento no Código (`ZipResourceProvider.kt:200-203`):** Lança `ReaderException.InvalidContent("ZIP64 archives are not supported")` antes de tentar indexar ponteiros truncados de 32 bits. 🟢

### EC-03 — Tentativa de Path Traversal em Links ou Manifesto (`../../etc/passwd`)
- **Gatilho:** Um arquivo `.opf` ou capítulo XHTML referencia `../../../secret.txt` ou `OEBPS/Text/../../META-INF/container.xml`.
- **Comportamento no Código (`ZipResourceProvider.kt:299-315`):** A função `normalizeHref` divide o caminho por `/`, remove segmentos `.` e, ao encontrar `..`, desempilha se houver diretório anterior ou **descarta silenciosamente o `..` se a pilha já estiver na raiz**, impedindo qualquer escape para fora da raiz lógica do container ZIP. 🟢

### EC-04 — EPUB onde Todos os Capítulos da Spine têm `linear="no"`
- **Gatilho:** Um arquivo `.opf` declara todos os `<itemref>` da `<spine>` com o atributo `linear="no"`.
- **Comportamento no Código (`EpubParser.kt:162-182`):** Se a lista `linearPages` ficar vazia após processar a spine, o `EpubParser` utiliza `nonLinearPages` como fallback para garantir que a publicação tenha páginas navegáveis em vez de falhar com livro vazio. 🟢

### EC-05 — EPUB Sem `<nav>` (EPUB 3) e Sem `.ncx` (EPUB 2)
- **Gatilho:** Publicação EPUB que não possui documento de navegação HTML5 nem arquivo NCX.
- **Comportamento no Código (`EpubParser.kt:200-218`):** Gera automaticamente um sumário sintético (`fallbackToc`) extraindo o título de cada capítulo a partir de `<h1>`/`<h2>`/`<title>` ou gerando `"Chapter N"`. 🟢

### EC-06 — Entidades XML Numéricas Fora do BMP (Emojis / Pares Substitutos UTF-16) e Codepoints Inválidos
- **Gatilho:** Texto XHTML contendo `&#x1F600;` (emoji acima de `0xFFFF`) ou codepoints inválidos (`> 0x10FFFF` ou faixa surrogate `0xD800..0xDFFF`).
- **Comportamento no Código (`XmlEntities.kt:38-64`):** `codePointToString` converte codepoints `> 0xFFFF` no par substituto UTF-16 exato (`highSurrogate` + `lowSurrogate`) sem usar `Character.toChars` (indisponível em `commonMain`), e retorna a entidade literal inalterada se o codepoint for inválido. 🟢

### EC-07 — Bomba de Descompressão em `PayloadCodec.decode`
- **Gatilho:** Um payload persistido com prefixo `gz:` tenta descomprimir para mais de `16 MB` (`MAX_DECOMPRESSED_BYTES`).
- **Comportamento no Código (`PayloadCodec.kt:36-46`, `PlatformCompression.*.kt`):** O loop de descompressão GZIP interrompe a leitura assim que `total > maxOutputBytes` e lança `IllegalArgumentException("Compressed payload exceeds maximum allowed size")`. 🟢

### EC-08 — Livro de Página Única sem Rolagem (`totalPages == 1` e `contentHeightPx <= viewportHeightPx`)
- **Gatilho:** Abrir um documento curto de apenas 1 capítulo que cabe inteiramente na tela sem barra de rolagem.
- **Comportamento no Código (`Models.kt:190-198`):** `ReadingProgress.from` detecta `safeTotal == 1 && scrollFraction == 0.0` e reporta `percentage = 100.0` (livro concluído na abertura), evitando travar o progresso em `0.0%`. 🟢
