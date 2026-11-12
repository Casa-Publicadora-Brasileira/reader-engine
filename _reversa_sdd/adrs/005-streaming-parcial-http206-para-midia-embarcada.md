# ADR-005: Streaming Parcial HTTP 206 (`Range`) para Mídia HTML5 Embarcada no EPUB

> Gerado pelo **Detective** (Reversa v1.3.6) | Status: **Aceito** | Confiança: 🟢 **CONFIRMADO** (`91a7a27`)

## Contexto

Livros interativos EPUB 3 (especialmente materiais didáticos, infantis e técnicos) frequentemente embutem arquivos de áudio (`<audio>`) e vídeo (`<video>`) de dezenas de megabytes dentro do container ZIP. O motor de mídia do Chromium/Android `WebView` exige suporte a requisições **HTTP Range (`Range: bytes=start-end`)** com resposta **`206 Partial Content`** para realizar buffering progressivo e permitir que o usuário avance/retroceda (seek) na barra de tempo do vídeo. Retornar o arquivo inteiro com HTTP `200 OK` em `shouldInterceptRequest` causava alto consumo de RAM, travamento no início da reprodução e falha no seek de vídeos.

## Decisão

Implementar suporte fim-a-fim a **Byte-Range Streaming**:
1. No `reader-core` (`ZipResourceProvider.readRange` e `ReaderSession.readResourceRange`):
   - Para entradas ZIP armazenadas com `compressionMethod == 0 (STORED)` (padrão recomendado pela especificação EPUB para arquivos de áudio/vídeo já comprimidos como MP4/MP3), realizar **leitura parcial direta do `FileHandle` em disco** (`fileHandle.read(dataStartOffset + offset, buffer, 0, sliceLen)`), sem carregar o arquivo completo na memória e sem poluir o cache LRU.
   - Expor `session.getResourceSize(href)` para retornar o `uncompressedSize` declarado no Central Directory em $O(1)$.
2. No `sample-android` (`BookResourceWebViewClient.shouldInterceptRequest`):
   - Detectar o cabeçalho HTTP `Range: bytes=start-end`.
   - Limitar cada fatia de resposta a no máximo **1 MB (`1_048_576` bytes)** (`MAX_RANGE_CHUNK_BYTES`).
   - Responder com status HTTP **`206 Partial Content`** e cabeçalhos `Accept-Ranges: bytes`, `Content-Range: bytes start-end/totalSize`, `Content-Length` e `Access-Control-Allow-Origin: *`.

## Alternativas Consideradas

1. **Extrair arquivos de áudio/vídeo para arquivos temporários no cache do Android e redirecionar para `file://`:**
   - *Prós:* O WebView lida nativamente com seek em `file://` se permitido.
   - *Contras:* Exige habilitar `allowFileAccess = true` (enfraquecendo a segurança sandbox da WebView) e causa latência e gasto de disco na extração prévia.
2. **Subir um servidor HTTP local (ex.: NanoHTTPD / Ktor local socket) em uma porta TCP loopback:**
   - *Prós:* Pilha HTTP completa.
   - *Contras:* Gerenciamento complexo de portas livres, permissões de rede, consumo de bateria em background e risco de outros processos locais na mesma máquina acessarem o conteúdo do livro em texto claro.

## Consequências

- **Positivas:**
  - Streaming instantâneo e seek fluido em vídeos MP4/WebM e áudios MP3 dentro da WebView mantendo `allowFileAccess = false` e sem abrir sockets de rede.
  - Consumo máximo de memória por chunk de streaming limitado a 1 MB.
- **Negativas / Trade-offs:**
  - Se o autor do EPUB tiver comprimido o vídeo com `DEFLATE` (`compressionMethod == 8`) em vez de `STORED` (`0`), `ZipResourceProvider.readRange` precisa inflar a entrada antes de fatiar o intervalo (embora o cache LRU de 16 MB mitigue leituras repetidas para arquivos médios).
