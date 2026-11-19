package com.readerengine.core.export

import com.readerengine.core.model.Bookmark
import com.readerengine.core.model.HighlightItem
import com.readerengine.core.model.PageFormState
import com.readerengine.core.model.Publication
import com.readerengine.core.model.ReaderStrings
import com.readerengine.core.model.TocItem

/**
 * Exportador multiplataforma de Caderno de Estudos (Markdown e HTML para impressão/PDF)
 * e formatador de citações com referência bibliográfica automática.
 */
public object StudyNotebookExporter {

    /**
     * Formata uma citação a partir de um [HighlightItem], incluindo título do livro,
     * autores, capítulo e página impressa (`pageList`), quando disponíveis.
     */
    public fun formatQuoteCitation(
        publication: Publication,
        toc: List<TocItem>,
        pageList: List<TocItem>,
        highlight: HighlightItem,
        strings: ReaderStrings = ReaderStrings.portuguese(),
    ): String {
        val quote = highlight.text.trim()
        val refParts = mutableListOf<String>()
        val title = publication.metadata.title.trim()
        if (title.isNotEmpty()) refParts.add(title)
        val authors = publication.metadata.authors.map { it.trim() }.filter { it.isNotEmpty() }
        if (authors.isNotEmpty()) refParts.add(authors.joinToString(", "))
        val chapterTitle = toc.firstOrNull { it.spineIndex == highlight.pageIndex }?.title?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: strings.formatChapterLabel(highlight.pageIndex + 1)
        refParts.add(chapterTitle)
        val printedPage = pageList.firstOrNull { it.spineIndex == highlight.pageIndex }?.title?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (printedPage != null) {
            refParts.add("p. $printedPage")
        }
        val reference = refParts.joinToString(", ")
        return buildString {
            append('«').append(quote).append('»')
            if (reference.isNotEmpty()) {
                append(" — ").append(reference)
            }
            val note = highlight.note?.trim()?.takeIf { it.isNotEmpty() }
            if (note != null) {
                append('\n').append(note)
            }
        }
    }

    /**
     * Exporta todo o caderno de estudos (grifos, notas, marcadores e respostas de formulários)
     * agrupado por capítulo em formato Markdown limpo.
     */
    public fun exportMarkdown(
        publication: Publication,
        toc: List<TocItem>,
        pageList: List<TocItem>,
        highlightsByPage: Map<Int, List<HighlightItem>>,
        bookmarks: List<Bookmark>,
        formsByPage: Map<Int, PageFormState>,
        strings: ReaderStrings = ReaderStrings.portuguese(),
    ): String {
        val title = publication.metadata.title.trim().ifEmpty { "Caderno de Estudos" }
        val authors = publication.metadata.authors.map { it.trim() }.filter { it.isNotEmpty() }
        val activePages = collectActivePages(highlightsByPage, bookmarks, formsByPage)

        return buildString {
            append("# ").append(title).append('\n')
            if (authors.isNotEmpty()) {
                append('*').append(authors.joinToString(", ")).append("*\n")
            }
            append('\n')

            if (activePages.isEmpty()) {
                append("_Nenhuma anotação, destaque ou resposta registrada._\n")
                return@buildString
            }

            for (pageIndex in activePages) {
                val chapterTitle = resolveChapterTitle(toc, pageIndex, strings)
                val printedPage = pageList.firstOrNull { it.spineIndex == pageIndex }?.title?.trim()
                    ?.takeIf { it.isNotEmpty() }
                append("## ").append(chapterTitle)
                if (printedPage != null) {
                    append(" (p. ").append(printedPage).append(')')
                }
                append("\n\n")

                val pageHighlights = highlightsByPage[pageIndex].orEmpty()
                if (pageHighlights.isNotEmpty()) {
                    append("### Destaques\n")
                    for (item in pageHighlights) {
                        append("> ").append(item.text.trim().replace("\n", "\n> ")).append('\n')
                        val note = item.note?.trim()?.takeIf { it.isNotEmpty() }
                        if (note != null) {
                            append("- **Nota:** ").append(note).append('\n')
                        }
                        append('\n')
                    }
                }

                val form = formsByPage[pageIndex]
                if (form != null && form.hasNonBlankContent) {
                    val answers = form.answers.filter { it.content.isNotBlank() }.sortedBy { it.index }
                    val annotations = form.annotations.filter { it.content.isNotBlank() }.sortedBy { it.index }
                    if (answers.isNotEmpty()) {
                        append("### Respostas\n")
                        for (ans in answers) {
                            append("- **Q").append(ans.index + 1).append(":** ").append(ans.content.trim()).append('\n')
                        }
                        append('\n')
                    }
                    if (annotations.isNotEmpty()) {
                        append("### Anotações\n")
                        for (ann in annotations) {
                            append("- ").append(ann.content.trim()).append('\n')
                        }
                        append('\n')
                    }
                }

                val pageBookmarks = bookmarks.filter { it.pageIndex == pageIndex }
                if (pageBookmarks.isNotEmpty()) {
                    append("### Marcadores\n")
                    for (bm in pageBookmarks) {
                        val label = bm.label?.trim()?.takeIf { it.isNotEmpty() }
                            ?: strings.formatPageLabel(pageIndex + 1)
                        append("- ").append(label).append('\n')
                    }
                    append('\n')
                }
            }
        }
    }

    /**
     * Exporta todo o caderno de estudos em documento HTML autocontido com CSS amigável
     * para impressão/PDF (`@media print`).
     */
    public fun exportHtml(
        publication: Publication,
        toc: List<TocItem>,
        pageList: List<TocItem>,
        highlightsByPage: Map<Int, List<HighlightItem>>,
        bookmarks: List<Bookmark>,
        formsByPage: Map<Int, PageFormState>,
        strings: ReaderStrings = ReaderStrings.portuguese(),
    ): String {
        val title = publication.metadata.title.trim().ifEmpty { "Caderno de Estudos" }
        val authors = publication.metadata.authors.map { it.trim() }.filter { it.isNotEmpty() }
        val activePages = collectActivePages(highlightsByPage, bookmarks, formsByPage)

        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<title>").append(escapeHtml(title)).append("</title>")
            append("<style>")
            append("body{font-family:system-ui,-apple-system,sans-serif;max-width:760px;margin:24px auto;padding:0 20px;color:#1c1b1f;line-height:1.6;}")
            append("h1{margin-bottom:4px;} .authors{color:#555;font-style:italic;margin-top:0;}")
            append("section{margin-top:28px;border-top:1px solid #e0e0e0;padding-top:16px;break-inside:avoid;}")
            append("blockquote{margin:10px 0;padding:8px 14px;border-left:4px solid #ffca28;background:#fffde7;}")
            append(".note{margin:4px 0 12px 16px;color:#333;}")
            append("ul{padding-left:20px;} @media print{body{margin:0;max-width:none;}}")
            append("</style></head><body>")
            append("<h1>").append(escapeHtml(title)).append("</h1>")
            if (authors.isNotEmpty()) {
                append("<p class=\"authors\">").append(escapeHtml(authors.joinToString(", "))).append("</p>")
            }

            if (activePages.isEmpty()) {
                append("<p><em>Nenhuma anotação, destaque ou resposta registrada.</em></p>")
            } else {
                for (pageIndex in activePages) {
                    val chapterTitle = resolveChapterTitle(toc, pageIndex, strings)
                    val printedPage = pageList.firstOrNull { it.spineIndex == pageIndex }?.title?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    append("<section><h2>").append(escapeHtml(chapterTitle))
                    if (printedPage != null) {
                        append(" (p. ").append(escapeHtml(printedPage)).append(')')
                    }
                    append("</h2>")

                    val pageHighlights = highlightsByPage[pageIndex].orEmpty()
                    if (pageHighlights.isNotEmpty()) {
                        append("<h3>Destaques</h3>")
                        for (item in pageHighlights) {
                            append("<blockquote>").append(escapeHtml(item.text.trim())).append("</blockquote>")
                            val note = item.note?.trim()?.takeIf { it.isNotEmpty() }
                            if (note != null) {
                                append("<p class=\"note\"><strong>Nota:</strong> ").append(escapeHtml(note)).append("</p>")
                            }
                        }
                    }

                    val form = formsByPage[pageIndex]
                    if (form != null && form.hasNonBlankContent) {
                        val answers = form.answers.filter { it.content.isNotBlank() }.sortedBy { it.index }
                        val annotations = form.annotations.filter { it.content.isNotBlank() }.sortedBy { it.index }
                        if (answers.isNotEmpty()) {
                            append("<h3>Respostas</h3><ul>")
                            for (ans in answers) {
                                append("<li><strong>Q").append(ans.index + 1).append(":</strong> ")
                                    .append(escapeHtml(ans.content.trim())).append("</li>")
                            }
                            append("</ul>")
                        }
                        if (annotations.isNotEmpty()) {
                            append("<h3>Anotações</h3><ul>")
                            for (ann in annotations) {
                                append("<li>").append(escapeHtml(ann.content.trim())).append("</li>")
                            }
                            append("</ul>")
                        }
                    }

                    val pageBookmarks = bookmarks.filter { it.pageIndex == pageIndex }
                    if (pageBookmarks.isNotEmpty()) {
                        append("<h3>Marcadores</h3><ul>")
                        for (bm in pageBookmarks) {
                            val label = bm.label?.trim()?.takeIf { it.isNotEmpty() }
                                ?: strings.formatPageLabel(pageIndex + 1)
                            append("<li>").append(escapeHtml(label)).append("</li>")
                        }
                        append("</ul>")
                    }

                    append("</section>")
                }
            }
            append("</body></html>")
        }
    }

    private fun collectActivePages(
        highlightsByPage: Map<Int, List<HighlightItem>>,
        bookmarks: List<Bookmark>,
        formsByPage: Map<Int, PageFormState>,
    ): List<Int> {
        val pages = LinkedHashSet<Int>()
        highlightsByPage.forEach { (page, list) -> if (list.isNotEmpty()) pages.add(page) }
        bookmarks.forEach { pages.add(it.pageIndex) }
        formsByPage.forEach { (page, form) -> if (form.hasNonBlankContent) pages.add(page) }
        return pages.sorted()
    }

    private fun resolveChapterTitle(
        toc: List<TocItem>,
        pageIndex: Int,
        strings: ReaderStrings,
    ): String = toc.firstOrNull { it.spineIndex == pageIndex }?.title?.trim()?.takeIf { it.isNotEmpty() }
        ?: strings.formatChapterLabel(pageIndex + 1)

    private fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
