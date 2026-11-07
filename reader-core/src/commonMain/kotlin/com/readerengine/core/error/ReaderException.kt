package com.readerengine.core.error

public sealed class ReaderException(override val message: String, cause: Throwable? = null) : Exception(message, cause) {
    public class UnsupportedFormat(name: String) : ReaderException("Formato não suportado para '$name'")
    public class InvalidContent(details: String, cause: Throwable? = null) : ReaderException("Conteúdo inválido: $details", cause)
    public class ResourceNotFound(public val href: String) : ReaderException("Recurso não encontrado: $href")
}
