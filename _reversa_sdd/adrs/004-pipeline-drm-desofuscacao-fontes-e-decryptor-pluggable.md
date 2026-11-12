# ADR-004: Pipeline de Desofuscação de Fontes EPUB (IDPF/Adobe) e `ContentDecryptor` Plugável

> Gerado pelo **Detective** (Reversa v1.3.6) | Status: **Aceito** | Confiança: 🟢 **CONFIRMADO** (`6189725`)

## Contexto

Uma grande parcela dos arquivos EPUB 2 e EPUB 3 comerciais — mesmo aqueles distribuídos **sem DRM de conteúdo** — utiliza **ofuscação de fontes embarcadas** declarada em `META-INF/encryption.xml` (algoritmos `http://www.idpf.org/2008/embedding` e `http://ns.adobe.com/pdf/enc#RC`) para cumprir licenças de fundições tipográficas. Sem desofuscar os primeiros 1040 ou 1024 bytes das fontes, a WebView falha ao carregar os arquivos `.ttf`/`.otf`/`.woff` e exibe fontes genéricas de fallback. Adicionalmente, distribuidores corporativos ou educacionais podem utilizar DRM proprietário (ex.: Readium LCP) nos capítulos XHTML e mídias.

## Decisão

Criar o pacote `com.readerengine.core.drm` (`DrmProvider.kt`) estruturado em três componentes:
1. **`EpubEncryptionParser`:** Faz o parse de `META-INF/encryption.xml` durante a abertura do EPUB, extraindo o mapa `href -> EncryptedResourceInfo(uri, algorithm, originalLength)`.
2. **`StandardEpubFontDeobfuscator`:** Implementa nativamente em `commonMain` (sem dependências criptográficas externas, incluindo implementação própria de SHA-1 em Kotlin puro) os dois padrões oficiais de ofuscação de fontes:
   - **IDPF (`http://www.idpf.org/2008/embedding`):** Hash SHA-1 de 20 bytes sobre os identificadores únicos do OPF, aplicando XOR circular nos primeiros **1.040 bytes**.
   - **Adobe (`http://ns.adobe.com/pdf/enc#RC`):** Chave binária de 16 bytes extraída do UUID hexadecimal do livro, aplicando XOR circular nos primeiros **1.024 bytes**.
3. **`DecryptingResourceProvider` + `ContentDecryptor` (`fallbackDecryptor`):** Envolve o `ResourceProvider` base; desofusca fontes automaticamente e delega recursos com outros algoritmos de criptografia para o `fallbackDecryptor: ContentDecryptor?` opcional fornecido pelo aplicativo host em `ReaderEngine.open(...)`.

## Alternativas Consideradas

1. **Ignorar `META-INF/encryption.xml` no core e exigir que o host processe tudo:**
   - *Prós:* Menos código no `reader-core`.
   - *Contras:* Todo app consumidor teria que reimplementar o parser de `encryption.xml`, a extração do `unique-identifier` do `.opf` e os algoritmos XOR IDPF/Adobe apenas para exibir fontes padrão de EPUBs sem DRM.
2. **Embutir SDKs pesados de DRM comercial (ex. LCP/ADEPT) diretamente no `reader-core`:**
   - *Prós:* Suporte out-of-the-box a DRM comercial.
   - *Contras:* Acoplamento com bibliotecas proprietárias/licenciadas, aumento drástico do binário e violação da filosofia de zero dependências pesadas do `reader-core`.

## Consequências

- **Positivas:**
  - Fontes ofuscadas pelo InDesign e distribuidores EPUB funcionam automaticamente em Android, iOS e JVM sem configuração pelo desenvolvedor host.
  - Arquitetura aberta/fechada (OCP): qualquer esquema de DRM de terceiros pode ser conectado via `fallbackDecryptor` em `ReaderEngine.open`.
- **Negativas / Trade-offs:**
  - Recursos protegidos por criptografia (inclusive fontes ofuscadas) não podem usar o atalho zero-copy em disco de `readRange` sem antes passar pela transformação em `DecryptingResourceProvider.readRange` (que lê e descriptografa o recurso completo antes de fatiar o range).
