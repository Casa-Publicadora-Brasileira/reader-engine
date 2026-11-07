package com.readerengine.core.drm

import com.readerengine.core.model.ResourceProvider
import com.readerengine.core.parser.XmlEntities
import com.readerengine.core.provider.normalizeHref
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8

/**
 * Metadados extraídos de `META-INF/encryption.xml` para um recurso protegido ou ofuscado dentro do EPUB.
 */
@Serializable
public data class EncryptedResourceInfo(
    val uri: String,
    val algorithm: String,
    val compressionMethod: String? = null,
    val originalLength: Long? = null,
)

/**
 * Gancho extensível de decriptografia/desofuscação para recursos de publicações (LCP, Adobe DRM,
 * AES customizado ou ofuscação de fontes IDPF/Adobe).
 */
public fun interface ContentDecryptor {
    public fun decrypt(
        href: String,
        rawBytes: ByteArray,
        encryptionInfo: EncryptedResourceInfo?,
        publicationIdentifier: String?,
    ): ByteArray
}

/**
 * Implementação padrão que realiza a desofuscação oficial de fontes EPUB:
 * - **IDPF** (`http://www.idpf.org/2008/embedding`): XOR nos primeiros 1040 bytes com a chave SHA-1 (20 bytes) do `identifier`.
 * - **Adobe** (`http://ns.adobe.com/pdf/enc#RC`): XOR nos primeiros 1024 bytes com a chave de 16 bytes derivada do UUID/identifier.
 *
 * Também permite delegar algoritmos de DRM adicionais (ex.: Readium LCP `http://www.w3.org/2001/04/xmlenc#aes256-cbc`)
 * para um [fallbackDecryptor] fornecido pelo app host.
 */
public class StandardEpubFontDeobfuscator(
    private val fallbackDecryptor: ContentDecryptor? = null,
) : ContentDecryptor {

    override fun decrypt(
        href: String,
        rawBytes: ByteArray,
        encryptionInfo: EncryptedResourceInfo?,
        publicationIdentifier: String?,
    ): ByteArray {
        val algorithm = encryptionInfo?.algorithm?.trim().orEmpty()
        val cleanId = publicationIdentifier?.replace(Regex("\\s+"), "").orEmpty()

        return when {
            algorithm == ALGORITHM_IDPF_FONT && cleanId.isNotEmpty() -> {
                val key = cleanId.encodeUtf8().sha1().toByteArray()
                xorPrefix(rawBytes, key, maxBytes = IDPF_OBFUSCATION_LENGTH)
            }
            algorithm == ALGORITHM_ADOBE_FONT && cleanId.isNotEmpty() -> {
                val key = deriveAdobeKey(cleanId)
                if (key.isNotEmpty()) {
                    xorPrefix(rawBytes, key, maxBytes = ADOBE_OBFUSCATION_LENGTH)
                } else {
                    rawBytes
                }
            }
            fallbackDecryptor != null -> {
                fallbackDecryptor.decrypt(href, rawBytes, encryptionInfo, publicationIdentifier)
            }
            else -> rawBytes
        }
    }

    private fun xorPrefix(source: ByteArray, key: ByteArray, maxBytes: Int): ByteArray {
        if (key.isEmpty() || source.isEmpty()) return source
        val out = source.copyOf()
        val limit = minOf(out.size, maxBytes)
        for (i in 0 until limit) {
            out[i] = (out[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return out
    }

    private fun deriveAdobeKey(identifier: String): ByteArray {
        val hexOnly = identifier
            .removePrefix("urn:uuid:")
            .replace("-", "")
            .filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (hexOnly.length >= 32) {
            return ByteArray(16) { idx ->
                hexOnly.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        }
        return identifier.encodeUtf8().sha1().toByteArray().copyOfRange(0, 16)
    }

    public companion object {
        public const val ALGORITHM_IDPF_FONT: String = "http://www.idpf.org/2008/embedding"
        public const val ALGORITHM_ADOBE_FONT: String = "http://ns.adobe.com/pdf/enc#RC"
        public const val IDPF_OBFUSCATION_LENGTH: Int = 1040
        public const val ADOBE_OBFUSCATION_LENGTH: Int = 1024
    }
}

/**
 * Parser multiplataforma para `META-INF/encryption.xml` de pacotes EPUB.
 */
public object EpubEncryptionParser {

    private val encryptedDataRegex = Regex(
        """<(?:[a-zA-Z0-9_-]+:)?EncryptedData\b[^>]*>([\s\S]*?)</(?:[a-zA-Z0-9_-]+:)?EncryptedData>""",
        RegexOption.IGNORE_CASE,
    )
    private val methodRegex = Regex(
        """<(?:[a-zA-Z0-9_-]+:)?EncryptionMethod\b([^>]*)/?>""",
        RegexOption.IGNORE_CASE,
    )
    private val cipherRefRegex = Regex(
        """<(?:[a-zA-Z0-9_-]+:)?CipherReference\b([^>]*)/?>""",
        RegexOption.IGNORE_CASE,
    )
    private val compressionRegex = Regex(
        """<(?:[a-zA-Z0-9_-]+:)?Compression\b([^>]*)/?>""",
        RegexOption.IGNORE_CASE,
    )

    public fun parse(encryptionXml: String): Map<String, EncryptedResourceInfo> {
        val map = LinkedHashMap<String, EncryptedResourceInfo>()
        for (block in encryptedDataRegex.findAll(encryptionXml)) {
            val inner = block.groupValues[1]
            val methodAttrs = methodRegex.find(inner)?.groupValues?.get(1) ?: continue
            val cipherAttrs = cipherRefRegex.find(inner)?.groupValues?.get(1) ?: continue

            val algorithm = extractAttr(methodAttrs, "Algorithm") ?: continue
            val rawUri = extractAttr(cipherAttrs, "URI") ?: continue
            val normalizedUri = normalizeHref(rawUri)
            if (normalizedUri.isEmpty()) continue

            val compressionAttrs = compressionRegex.find(inner)?.groupValues?.get(1)
            val compMethod = compressionAttrs?.let { extractAttr(it, "Method") }
            val origLen = compressionAttrs?.let { extractAttr(it, "OriginalLength")?.toLongOrNull() }

            map[normalizedUri] = EncryptedResourceInfo(
                uri = normalizedUri,
                algorithm = algorithm,
                compressionMethod = compMethod,
                originalLength = origLen,
            )
        }
        return map
    }

    private fun extractAttr(attrs: String, name: String): String? {
        val regex = Regex("""(?:^|\s)$name\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        val m = regex.find(attrs) ?: return null
        val raw = if (m.groups[1] != null) m.groupValues[1] else m.groupValues[2]
        return XmlEntities.decode(raw)
    }
}

/**
 * Decorator de [ResourceProvider] que aplica automaticamente desofuscação de fontes e o [ContentDecryptor]
 * quando o recurso solicitado consta em `META-INF/encryption.xml` ou quando um decriptador customizado está ativo.
 */
public class DecryptingResourceProvider(
    private val delegate: ResourceProvider,
    private val encryptedResources: Map<String, EncryptedResourceInfo>,
    private val publicationIdentifier: String?,
    private val decryptor: ContentDecryptor,
) : ResourceProvider {

    override fun read(href: String): ByteArray {
        val clean = normalizeHref(href)
        val rawBytes = delegate.read(clean)
        val info = encryptedResources[clean]
        return decryptor.decrypt(
            href = clean,
            rawBytes = rawBytes,
            encryptionInfo = info,
            publicationIdentifier = publicationIdentifier,
        )
    }
}
