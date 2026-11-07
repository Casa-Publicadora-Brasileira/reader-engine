package com.readerengine.core

import com.readerengine.core.bridge.PayloadCodec
import com.readerengine.core.bridge.ReaderJsBridge
import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.HighlightStyle
import com.readerengine.core.model.InitialBookState
import com.readerengine.core.model.ReaderStrings
import com.readerengine.core.model.ReadingProgress
import com.readerengine.core.parser.EpubParser
import com.readerengine.core.parser.XmlEntities
import com.readerengine.core.provider.ZipLimits
import com.readerengine.core.provider.ZipResourceProvider
import com.readerengine.core.session.ReaderCommand
import com.readerengine.core.session.ReaderEngine
import com.readerengine.core.session.ReaderEvent
import com.readerengine.core.session.ReaderUiEffect
import com.readerengine.core.theme.ThemeTokens
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderEngineTest {

    private val engine = ReaderEngine()

    private fun readFixture(name: String): ByteArray {
        val candidates = listOf(
            "fixtures/$name".toPath(),
            "../fixtures/$name".toPath(),
            "sample-android/src/main/assets/samples/$name".toPath(),
            "../sample-android/src/main/assets/samples/$name".toPath(),
        )
        val path = candidates.firstOrNull { FileSystem.SYSTEM.exists(it) }
            ?: error("Fixture not found: $name")
        return FileSystem.SYSTEM.read(path) { readByteArray() }
    }

    @Test
    fun parsesJsonFixtureNavigatesSearchesAndBookmarks() = runTest {
        val bytes = readFixture("noite-clara.json")
        val session = engine.open(
            name = "noite-clara.json",
            bytes = bytes,
            initialState = InitialBookState(documentId = "noite-clara"),
        )

        val state0 = session.state.value
        assertEquals("noite-clara", state0.documentId)
        assertEquals("Noite Clara", state0.metadata.title)
        assertTrue(state0.spineLen >= 2)
        assertEquals(0, state0.pageIndex)
        assertTrue(session.currentHtml().contains("<!doctype html>", ignoreCase = true))

        val events = mutableListOf<ReaderEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.events.collect { events.add(it) }
        }

        // Navigation
        session.dispatch(ReaderCommand.NextPage)
        assertEquals(1, session.state.value.pageIndex)
        assertTrue(events.any { it is ReaderEvent.ProgressUpdated && it.progress.pageIndex == 1 })

        // Scroll update
        session.dispatch(
            ReaderCommand.UpdateScroll(
                scrollY = 450,
                contentHeightPx = 1200,
                viewportHeightPx = 300,
            ),
        )
        val progress = session.state.value.progress
        assertEquals(450, progress.scrollY)
        assertTrue(progress.totalPercentage in 0f..100f)
        assertNotNull(progress.locator)

        // Bookmarks
        assertFalse(session.state.value.hasBookmarkAtCurrentPage)
        session.dispatch(ReaderCommand.ToggleBookmark(label = "Meu marcador"))
        assertTrue(session.state.value.hasBookmarkAtCurrentPage)
        assertEquals(1, session.state.value.bookmarks.size)
        assertNotNull(session.state.value.bookmarks.first().locator)
        assertTrue(events.any { it is ReaderEvent.BookmarksChanged && it.bookmarks.size == 1 })

        val bookmarkId = session.state.value.bookmarks.first().id
        session.dispatch(ReaderCommand.GoToPage(0))
        assertEquals(0, session.state.value.pageIndex)
        session.dispatch(ReaderCommand.GoToBookmark(bookmarkId))
        assertEquals(1, session.state.value.pageIndex)

        // Full-text search (including diacritic-insensitive match & Locator navigation)
        val searchResults = session.search("nôite")
        assertTrue(searchResults.isNotEmpty())
        assertTrue(searchResults.first().snippet.contains("noite", ignoreCase = true))
        val loc = searchResults.first().locator
        assertNotNull(loc)
        session.dispatch(ReaderCommand.GoToLocator(loc))
        assertEquals(loc.pageIndex, session.state.value.pageIndex)

        job.cancel()
    }

    @Test
    fun parsesEpubFixtureRendersHtmlAndResolvesRelativeResources() = runTest {
        val bytes = readFixture("sample.epub")
        val session = engine.open(
            name = "sample.epub",
            bytes = bytes,
            initialState = InitialBookState(documentId = "epub-sample"),
        )

        val state = session.state.value
        assertEquals("EPUB Fixture", state.metadata.title)
        assertEquals(2, state.spineLen)
        assertEquals(2, state.toc.size)
        assertEquals("Um", state.toc[0].title)

        // Regression test for C1: currentHtml() and currentDocument() must not crash on EPUB HTML with </head>
        val doc = session.currentDocument()
        assertTrue(doc.html.contains("reader-engine-theme"))
        assertEquals("book://content/OEBPS/", doc.baseUrl)

        val firstHref = session.publication().spine.first()
        val ch1Bytes = session.readResource(firstHref)
        assertTrue(ch1Bytes.decodeToString().isNotBlank())
        assertEquals("application/xhtml+xml", session.resourceMediaType(firstHref))

        // Regression test for C2: relative resource resolution inside OEBPS/
        val withImageBytes = readFixture("sample-with-image.epub")
        val imgSession = engine.open("sample-with-image.epub", withImageBytes)
        assertEquals("book://content/OEBPS/", imgSession.currentBaseUrl())
        assertTrue(imgSession.currentHtml().contains("Chapter 1: With Image"))
        assertTrue(imgSession.readResource("styles/main.css").decodeToString().isNotBlank())
        assertTrue(imgSession.readResource("images/cover.png").isNotEmpty())
        assertEquals("text/css", imgSession.resourceMediaType("styles/main.css"))
        assertEquals("image/png", imgSession.resourceMediaType("images/cover.png"))
    }

    @Test
    fun parsesHpubFixtureGeneratesSha256DocIdAndRestoresInitialState() = runTest {
        val bytes = readFixture("sample.hpub")
        // Verify deterministic SHA-256 document ID when no identifier is provided
        val autoIdSession = engine.open(name = "sample.hpub", bytes = bytes)
        assertTrue(autoIdSession.state.value.documentId.startsWith("doc-"))

        val initialHighlights = mapOf(
            0 to "type:textContent|1\$highlight_yellow\$Texto grifado inicial",
        )
        val initialForms = mapOf(
            0 to """{"annotations":[{"content":"Minha nota","index":0}],"answers":[{"content":"Resposta 1","index":0}]}""",
        )
        val session = engine.open(
            name = "sample.hpub",
            bytes = bytes,
            initialState = InitialBookState(
                documentId = "licao-01",
                initialPageIndex = 1,
                initialScrollY = 240,
                savedPageHighlights = initialHighlights,
                savedPageForms = initialForms,
                strings = ReaderStrings.english(),
            ),
        )

        val state = session.state.value
        assertEquals("HPUB Fixture", state.metadata.title)
        assertEquals(1, state.pageIndex)
        assertEquals(240, state.progress.scrollY)
        assertTrue(session.currentHtml().contains("reader-engine-theme"))
        assertEquals("text/html", session.resourceMediaType("pages/one.html"))

        // Go back to page 0 and verify stored highlights, summaries, and forms are restored
        session.dispatch(ReaderCommand.GoToPage(0))
        assertEquals("type:textContent|1\$highlight_yellow\$Texto grifado inicial", session.state.value.currentPageSerializedHighlights)
        assertEquals(1, session.state.value.currentPageHighlights.size)
        assertEquals("Texto grifado inicial", session.state.value.currentPageHighlights[0].text)
        assertEquals(1, session.state.value.currentPageFormState.annotations.size)
        assertEquals("Minha nota", session.state.value.currentPageFormState.annotations[0].content)
        assertEquals("Resposta 1", session.state.value.currentPageFormState.answers[0].content)
    }

    @Test
    fun emitsHighlightFormAndGenericCustomUriEventsAndRestoresCloudData() = runTest {
        val bytes = readFixture("sample.hpub")
        val session = engine.open(
            name = "sample.hpub",
            bytes = bytes,
            initialState = InitialBookState(
                documentId = "licao-01",
                strings = ReaderStrings.english(),
            ),
        )

        val events = mutableListOf<ReaderEvent>()
        val effects = mutableListOf<ReaderUiEffect>()
        val eventJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.events.collect { events.add(it) }
        }
        val effectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.uiEffects.collect { effects.add(it) }
        }

        // Simulate WebView callback after applying a highlight
        session.onPageHighlightsReported(
            rawSerializedHighlights = "type:textContent|1\$green\$No princípio",
            rawSummaryJson = """[{"id":1,"text":"No princípio","cor":"green","pos":640.0}]""",
        )

        val highlightEvent = events.filterIsInstance<ReaderEvent.PageHighlightsChanged>().lastOrNull()
        assertNotNull(highlightEvent)
        assertEquals("licao-01", highlightEvent.documentId)
        assertEquals(0, highlightEvent.pageIndex)

        val summary = session.state.value.currentPageHighlights
        assertEquals(1, summary.size)
        assertEquals(HighlightStyle.GREEN, summary[0].style)
        assertEquals("No princípio", summary[0].text)
        assertNotNull(summary[0].locator)

        // Navigate to highlight -> should scroll to pos - 300 = 340
        session.dispatch(ReaderCommand.SelectHighlightItem(summary[0]))
        val scrollEffect = effects.filterIsInstance<ReaderUiEffect.ScrollToY>().lastOrNull()
        assertNotNull(scrollEffect)
        assertEquals(340, scrollEffect.scrollYPx)

        // Simulate WebView callback for interactive form inputs
        val rawFormJson = """{"annotations":[{"content":"Nota sabatina","index":0}],"answers":[{"content":"Sim","index":0}]}"""
        session.onPageFormReported(rawFormJson)

        val formEvent = events.filterIsInstance<ReaderEvent.PageFormChanged>().lastOrNull()
        assertNotNull(formEvent)
        assertEquals("licao-01", formEvent.documentId)
        assertEquals("Nota sabatina", formEvent.formState.annotations[0].content)

        // Cloud restore on active page should update state + emit EvaluateJs for highlights and forms
        effects.clear()
        session.restorePageCloudData(
            pageIndex = 0,
            serializedHighlights = "type:textContent|2\$highlight_blue\$Trecho nuvem",
            annotationsJson = """{"annotations":[{"content":"Nota nuvem","index":0}],"answers":[{"content":"Resp nuvem","index":0}]}""",
        )
        assertEquals(1, session.state.value.currentPageHighlights.size)
        assertEquals("Trecho nuvem", session.state.value.currentPageHighlights[0].text)
        val jsScripts = effects.filterIsInstance<ReaderUiEffect.EvaluateJs>().map { it.script }
        assertTrue(jsScripts.any { it.contains("setHighlights") })
        assertTrue(jsScripts.any { it.contains("setAnnotations") })
        assertTrue(jsScripts.any { it.contains("setAnswers") })

        // Intercept custom URI schemes generically (works for verse://, footnote://, audio://, etc.)
        assertTrue(session.handleUrlNavigation("verse://?q=Joao+3:16"))
        val verseEffect = effects.filterIsInstance<ReaderUiEffect.OpenCustomUri>().lastOrNull()
        assertNotNull(verseEffect)
        assertEquals("verse", verseEffect.scheme)
        assertEquals("Joao 3:16", verseEffect.title)

        // Invalid highlight on input should use localized English message
        assertTrue(session.handleUrlNavigation("highlight://invalid"))
        val msgEffect = effects.filterIsInstance<ReaderUiEffect.ShowMessage>().lastOrNull()
        assertNotNull(msgEffect)
        assertEquals("Cannot highlight inside an input field.", msgEffect.message)

        eventJob.cancel()
        effectJob.cancel()
    }

    @Test
    fun readingProgressIsStrictlyMonotonicAcrossPagesAndScrolls() {
        val p0Start = ReadingProgress.calculatePercentage(0, 3, scrollY = 0, contentHeightPx = 1000, viewportHeightPx = 100)
        val p0Mid = ReadingProgress.calculatePercentage(0, 3, scrollY = 450, contentHeightPx = 1000, viewportHeightPx = 100)
        val p0End = ReadingProgress.calculatePercentage(0, 3, scrollY = 900, contentHeightPx = 1000, viewportHeightPx = 100)
        val p1Start = ReadingProgress.calculatePercentage(1, 3, scrollY = 0, contentHeightPx = 1000, viewportHeightPx = 100)
        val p1End = ReadingProgress.calculatePercentage(1, 3, scrollY = 900, contentHeightPx = 1000, viewportHeightPx = 100)
        val p2Start = ReadingProgress.calculatePercentage(2, 3, scrollY = 0, contentHeightPx = 1000, viewportHeightPx = 100)
        val p2End = ReadingProgress.calculatePercentage(2, 3, scrollY = 900, contentHeightPx = 1000, viewportHeightPx = 100)

        assertEquals(0f, p0Start, 0.01f)
        assertTrue(p0Mid > p0Start && p0Mid < p0End)
        assertEquals(p0End, p1Start, 0.01f)
        assertTrue(p1End > p1Start)
        assertEquals(p1End, p2Start, 0.01f)
        assertEquals(100f, p2End, 0.01f)
    }

    @Test
    fun xmlEntitiesDecodesSinglePassAndSupportsNestedTocAndQuotes() {
        // Must not double-decode &amp;lt; into <
        assertEquals("&lt;tag&gt; é — ©", XmlEntities.decode("&amp;lt;tag&amp;gt; &#233; &#x2014; &copy;"))

        val parser = EpubParser()
        val attrWithApostrophe = """id="ch1" title="Author's &amp; Editor's Note""""
        assertEquals("Author's & Editor's Note", parser.extractAttribute(attrWithApostrophe, "title"))

        val nestedNav = """
            <nav epub:type="toc">
              <ol>
                <li>
                  <a href="c1.xhtml">Part 1</a>
                  <ol>
                    <li><a href="c1.xhtml#s1">Section 1.1</a></li>
                  </ol>
                </li>
                <li><a href="c2.xhtml">Part 2</a></li>
              </ol>
            </nav>
        """.trimIndent()
        val toc = parser.parseNavXhtml(nestedNav, "OEBPS/nav.xhtml")
        assertEquals(2, toc.size)
        assertEquals("Part 1", toc[0].title)
        assertEquals(1, toc[0].children.size)
        assertEquals("Section 1.1", toc[0].children[0].title)
        assertEquals("OEBPS/c1.xhtml#s1", toc[0].children[0].href)
    }

    @Test
    fun zipResourceProviderEnforcesSafetyLimits() {
        val epubBytes = readFixture("sample.epub")
        assertTrue(ZipResourceProvider.hasZipEntry(epubBytes, "META-INF/container.xml"))

        assertFailsWith<ReaderException.InvalidContent> {
            ZipResourceProvider.fromBytes(
                epubBytes,
                limits = ZipLimits(maxEntries = 1),
            )
        }

        assertFailsWith<ReaderException.InvalidContent> {
            val provider = ZipResourceProvider.fromBytes(
                epubBytes,
                limits = ZipLimits(maxEntryUncompressedBytes = 10),
            )
            provider.read("META-INF/container.xml")
        }
    }

    @Test
    fun payloadCodecCompressesLargePayloadsAndRoundTrips() {
        val small = "type:textContent|1\$10\$1\$yellow\$"
        val encodedSmall = PayloadCodec.encodeIfNeeded(small)
        assertFalse(PayloadCodec.isCompressed(encodedSmall))
        assertEquals(small, PayloadCodec.decodeIfNeeded(encodedSmall))

        val large = "type:textContent|100\$200\$1\$yellow\$|".repeat(8_000)
        assertTrue(large.encodeToByteArray().size > PayloadCodec.DEFAULT_COMPRESS_THRESHOLD_BYTES)
        val encodedLarge = PayloadCodec.encodeIfNeeded(large)
        assertTrue(PayloadCodec.isCompressed(encodedLarge))
        assertEquals(large, PayloadCodec.decodeIfNeeded(encodedLarge))
    }

    @Test
    fun readerJsBridgeGeneratesCompatibleScriptsAndPreventsInjection() {
        assertTrue(
            ReaderJsBridge.highlightSelectionCommand(HighlightStyle.YELLOW)
                .contains("readerJS.highlightSelection('yellow')"),
        )
        assertTrue(
            ReaderJsBridge.setNightModeCommand(true)
                .contains("nightMode(true)"),
        )

        // Regression test for S1: double-quote injection in setFontSizeClassCommand
        val maliciousSize = """ESFontSize2");alert(1);//"""
        val sizeCmd = ReaderJsBridge.setFontSizeClassCommand(maliciousSize)
        assertFalse(sizeCmd.contains("alert(1);"))
        assertTrue(sizeCmd.contains("""setFontSize("ESFontSize2alert1")"""))

        // Regression test for S2: CSS / script injection via fontFamily
        val maliciousFont = "serif;}</style><script>alert(1)</script><style>"
        val injectedHtml = ReaderJsBridge.injectThemeAndBridge(
            rawHtml = "<html><head><title>T</title></head><body><p>Hello</p></body></html>",
            theme = ThemeTokens.fluent(),
            fontScale = 1.0f,
            fontFamily = maliciousFont,
            fontSizeClass = """bad" onclick="alert(1)""",
            isNightMode = true,
        )
        assertFalse(injectedHtml.contains("<script>alert(1)</script>"))
        assertFalse(injectedHtml.contains("onclick="))
    }
}
