package com.readerengine.core.error

sealed class ReaderException(override val message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnsupportedFormat(name: String) : ReaderException("Formato não suportado para '$name'")
    class InvalidContent(details: String, cause: Throwable? = null) : ReaderException("Conteúdo inválido: $details", cause)
    class ResourceNotFound(val href: String) : ReaderException("Recurso não encontrado: $href")
}
