# ADR-002: Leitor Customizado de Central Directory ZIP sobre Okio com Proteção Anti-Zip Bomb e Cache LRU

> Gerado pelo **Detective** (Reversa v1.3.6) | Status: **Aceito** | Confiança: 🟢 **CONFIRMADO** (`a92479f`, `93337de`, `4ea2e95`)

## Contexto

Arquivos EPUB e HPUB são containers ZIP que podem variar de poucos kilobytes (livros de texto puro) a centenas de megabytes (livros didáticos enriquecidos com áudio, vídeo e imagens de alta resolução). Carregar todo o arquivo ZIP na memória RAM (`ByteArray`) ou extrair todos os arquivos para o disco ao abrir o livro causava lentidão na abertura e risco de `OutOfMemoryError` ou ataques de **Zip Bomb**. Além disso, no Kotlin Multiplatform (`commonMain`), não há uma API unificada de acesso aleatório a entradas ZIP com suporte a leitura de intervalos de bytes (`readRange`).

## Decisão

Implementar `ZipResourceProvider` (`ZipResourceProvider.kt`) diretamente em `commonMain` operando sobre `okio.FileHandle` (para arquivos em disco) ou `ByteArray` (em memória):
1. **Descoberta Lazy via EOCD:** Ler apenas a janela final de até **65.557 bytes** do arquivo para localizar a assinatura `End of Central Directory (0x06054b50)` e mapear os metadados das entradas (`ZipEntryMeta`) sem ler o conteúdo dos arquivos.
2. **Reuso de `FileHandle` e Thread-Safety:** Manter um único `FileHandle` aberto para fontes em disco e proteger o acesso concorrente (ex.: múltiplas threads do WebView carregando imagens/fontes simultaneamente) com um `Mutex` corrotineiro (`cacheMutex`).
3. **Cache LRU Limitado em Bytes:** Armazenar entradas lidas recentemente em um `LinkedHashMap` LRU limitado a **16 MB** (`maxCacheBytes`), descartando entradas antigas quando o peso somado ultrapassa o teto, e nunca armazenando no cache entradas individuais maiores que 25% do teto (4 MB).
4. **Limites Anti-Zip Bomb (`ZipLimits`):** Impor limites estritos de `10.000` entradas, `50 MB` por entrada descompactada e `1 GB` de tamanho total não comprimido.

## Alternativas Consideradas

1. **Descompactar todo o EPUB para uma pasta temporária no disco ao abrir:**
   - *Prós:* Simplifica a leitura posterior via sistema de arquivos comum.
   - *Contras:* Tempo de abertura proibitivo em livros grandes (100+ MB), consumo duplicado de armazenamento do usuário e vulnerabilidade direta a esgotamento de disco (Disk Fill / Path Traversal).
2. **Usar `okio.FileSystem.openZip` nativo do Okio JVM:**
   - *Prós:* Menos código de baixo nível para parsing de cabeçalhos ZIP.
   - *Contras:* Não está disponível de forma idêntica em todos os targets KMP sem dependências extras e não expõe leitura zero-copy de sub-intervalos (`readRange`) em entradas `STORED` sem passar pelo fluxo completo.

## Consequências

- **Positivas:**
  - Abertura de livros gigantes em $O(1)$ (lê apenas o Central Directory no final do arquivo e os arquivos `.opf`/`.ncx`).
  - Identificação rápida de livros em disco via `sha256DocumentId` amostrado (primeiros 64 KB + últimos 64 KB + tamanho total).
  - Proteção determinística contra Zip Bombs e Path Traversal (`normalizeHref`).
- **Negativas / Trade-offs:**
  - Não suporta arquivos **ZIP64** (> 4 GB ou > 65.535 entradas), que retornam `ReaderException.InvalidContent` explicitamente.
