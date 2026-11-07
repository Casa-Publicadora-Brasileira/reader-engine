package com.readerengine.core.model

import kotlinx.serialization.Serializable

/**
 * Textos localizados utilizados pela engine para rótulos padrão de marcadores, capítulos e avisos.
 */
@Serializable
public data class ReaderStrings(
    val pageLabelPrefix: String = "Página",
    val chapterLabelPrefix: String = "Capítulo",
    val invalidHighlightOnInputMessage: String = "Marcação inválida neste campo.",
) {
    public fun formatPageLabel(pageNumber: Int): String = "$pageLabelPrefix $pageNumber"

    public fun formatChapterLabel(chapterNumber: Int): String = "$chapterLabelPrefix $chapterNumber"

    public companion object {
        public fun portuguese(): ReaderStrings = ReaderStrings()

        public fun english(): ReaderStrings = ReaderStrings(
            pageLabelPrefix = "Page",
            chapterLabelPrefix = "Chapter",
            invalidHighlightOnInputMessage = "Cannot highlight inside an input field.",
        )

        public fun spanish(): ReaderStrings = ReaderStrings(
            pageLabelPrefix = "Página",
            chapterLabelPrefix = "Capítulo",
            invalidHighlightOnInputMessage = "Marcado no válido en este campo.",
        )
    }
}
