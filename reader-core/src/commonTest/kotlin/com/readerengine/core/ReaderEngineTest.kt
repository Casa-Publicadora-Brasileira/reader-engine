package com.readerengine.core

import com.readerengine.core.bridge.InterceptedUrl
import com.readerengine.core.bridge.PayloadCodec
import com.readerengine.core.bridge.ReaderJsBridge
import com.readerengine.core.drm.ContentDecryptor
import com.readerengine.core.drm.DecryptingResourceProvider
import com.readerengine.core.drm.EncryptedResourceInfo
import com.readerengine.core.drm.EpubEncryptionParser
import com.readerengine.core.drm.StandardEpubFontDeobfuscator
import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.Bookmark
import com.readerengine.core.model.BookStateSnapshot
import com.readerengine.core.model.HighlightStyle
import com.readerengine.core.model.InitialBookState
import com.readerengine.core.model.Metadata
import com.readerengine.core.model.PageProgressionDirection
import com.readerengine.core.model.Publication
import com.readerengine.core.model.ReaderStrings
import com.readerengine.core.model.ReadingMode
import com.readerengine.core.model.ReadingProgress
import com.readerengine.core.model.RenditionLayout
import com.readerengine.core.model.RenditionOrientation
import com.readerengine.core.model.RenditionSpread
import com.readerengine.core.model.Resource
import com.readerengine.core.model.ResourceProvider
import com.readerengine.core.model.TocEntry
import com.readerengine.core.parser.CbzParser
import com.readerengine.core.parser.EpubParser
import com.readerengine.core.parser.HpubParser
import com.readerengine.core.parser.ParserRegistry
import com.readerengine.core.parser.SmilParser
import com.readerengine.core.parser.Source
import com.readerengine.core.parser.XmlEntities
import com.readerengine.core.parser.XmlToken
import com.readerengine.core.parser.XmlTokenizer
import com.readerengine.core.provider.MemoryResourceProvider
import com.readerengine.core.provider.ZipLimits
import com.readerengine.core.provider.ZipResourceProvider
import com.readerengine.core.session.ReaderCommand
import com.readerengine.core.session.ReaderEngine
import com.readerengine.core.session.ReaderEvent
import com.readerengine.core.session.ReaderSession
import com.readerengine.core.session.ReaderState
import com.readerengine.core.session.ReaderUiEffect
import com.readerengine.core.theme.Shape
import com.readerengine.core.theme.ThemeTokens
import com.readerengine.core.theme.Typography
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderEngineTest {

    private val engine = ReaderEngine()

    private fun fixturePath(name: String): Path {
        val candidates = listOf(
            "fixtures/$name".toPath(),
            "../fixtures/$name".toPath(),
            "sample-android/src/main/assets/samples/$name".toPath(),
            "../sample-android/src/main/assets/samples/$name".toPath(),
        )
        return candidates.firstOrNull { FileSystem.SYSTEM.exists(it) }
            ?: error("Fixture not found: $name")
    }

    private fun readFixture(name: String): ByteArray {
        val path = fixturePath(name)
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
        val fullImage = imgSession.readResource("images/cover.png")
        assertTrue(fullImage.isNotEmpty())
        assertEquals("text/css", imgSession.resourceMediaType("styles/main.css"))
        assertEquals("image/png", imgSession.resourceMediaType("images/cover.png"))

        // Video / Range request streaming validation
        val totalSize = imgSession.resourceSize("images/cover.png")
        assertNotNull(totalSize)
        assertEquals(fullImage.size.toLong(), totalSize)
        val slice = imgSession.readResourceRange("images/cover.png", offset = 2L, byteCount = 8)
        assertEquals(8, slice.size)
        assertContentEquals(fullImage.copyOfRange(2, 10), slice)
        imgSession.close()
    }

    @Test
    fun streamsEpubAndHpubDirectlyFromDiskViaOkioFileHandle() = runTest {
        val fakeFs = FakeFileSystem()
        val virtualEpubPath = "/books/large-book.epub".toPath()
        val virtualHpubPath = "/books/lesson.hpub".toPath()
        fakeFs.createDirectories("/books".toPath())
        fakeFs.write(virtualEpubPath) { write(readFixture("sample-with-image.epub")) }
        fakeFs.write(virtualHpubPath) { write(readFixture("sample.hpub")) }

        assertTrue(ZipResourceProvider.hasZipEntry(virtualEpubPath, "META-INF/container.xml", fakeFs))
        assertFalse(ZipResourceProvider.hasZipEntry(virtualEpubPath, "non-existent.xml", fakeFs))

        val epubSession = engine.open(path = virtualEpubPath, fileSystem = fakeFs)
        assertEquals("book://content/OEBPS/", epubSession.currentBaseUrl())
        assertTrue(epubSession.currentHtml().contains("Chapter 1: With Image"))
        assertTrue(epubSession.readResource("images/cover.png").isNotEmpty())
        epubSession.close()

        val hpubSession = engine.open(path = virtualHpubPath, fileSystem = fakeFs)
        assertEquals("HPUB Fixture", hpubSession.state.value.metadata.title)
        assertTrue(hpubSession.state.value.documentId.startsWith("doc-"))
        hpubSession.close()

        // Verify zero open FileHandles leaked after session.close()
        fakeFs.checkNoOpenFiles()

        // Also test opening directly from real FileSystem.SYSTEM via concise overload
        val systemSession = engine.open(fixturePath("sample.epub"))
        assertEquals("EPUB Fixture", systemSession.state.value.metadata.title)
        systemSession.close()
    }

    @Test
    fun iosFlowSubscriptionsDeliverStateEventsAndEffectsAndCancelCleanly() = runTest {
        val session = engine.open("sample.epub", readFixture("sample.epub"))
        val observedStates = mutableListOf<ReaderState>()
        val observedEvents = mutableListOf<ReaderEvent>()
        val observedEffects = mutableListOf<ReaderUiEffect>()

        val stateSub = session.watchState { observedStates.add(it) }
        val eventSub = session.watchEvents { observedEvents.add(it) }
        val effectSub = session.watchUiEffects { observedEffects.add(it) }

        // Initial state is emitted immediately
        assertTrue(observedStates.isNotEmpty())
        assertEquals(0, observedStates.last().pageIndex)

        session.dispatch(ReaderCommand.NextPage)
        assertEquals(1, observedStates.last().pageIndex)
        assertTrue(observedEvents.any { it is ReaderEvent.ProgressUpdated })

        session.dispatch(ReaderCommand.SetNightMode(true))
        assertTrue(observedEffects.any { it is ReaderUiEffect.EvaluateJs })

        val countBeforeCancel = observedStates.size
        stateSub.cancel()
        eventSub.cancel()
        effectSub.cancel()

        session.dispatch(ReaderCommand.PrevPage)
        assertEquals(countBeforeCancel, observedStates.size)
        session.close()
    }

    @Test
    fun supportsPaginatedModeFixedLayoutMathMlAndDrmFontDeobfuscation() = runTest {
        val parser = EpubParser()
        val fixedOpf = """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Fixed Layout Album</dc:title>
                <dc:identifier>urn:uuid:12345678-1234-1234-1234-1234567890ab</dc:identifier>
                <meta property="rendition:layout">pre-paginated</meta>
                <meta property="rendition:orientation">landscape</meta>
                <meta property="rendition:spread">none</meta>
              </metadata>
            </package>
        """.trimIndent()
        val meta = parser.parseMetadata(fixedOpf)
        assertEquals(RenditionLayout.PRE_PAGINATED, meta.layout)
        assertEquals(RenditionOrientation.LANDSCAPE, meta.orientation)
        assertEquals(RenditionSpread.NONE, meta.spread)

        // Paginated CSS Multi-Column mode & sub-page navigation
        val session = engine.open("sample.epub", readFixture("sample.epub"))
        session.dispatch(ReaderCommand.SetReadingMode(ReadingMode.PAGINATED))
        assertEquals(ReadingMode.PAGINATED, session.state.value.readingMode)
        val paginatedHtml = session.currentHtml()
        assertTrue(paginatedHtml.contains("reader-paginated"))
        assertTrue(paginatedHtml.contains("column-width:calc(100vw - 48px)"))
        assertTrue(paginatedHtml.contains("math{font-family:"))
        assertTrue(ReaderJsBridge.setReadingModeCommand(ReadingMode.SCROLL).contains("resetSubPage"))
        assertEquals(1 to 4, ReaderJsBridge.parseSubPageState("\"{\\\"subPage\\\":1,\\\"totalSubPages\\\":4}\""))

        session.onSubPageReported(currentSubPage = 0, totalSubPages = 3)
        assertEquals(0, session.state.value.currentSubPage)
        assertEquals(3, session.state.value.totalSubPages)
        session.dispatch(ReaderCommand.NextPage)
        assertEquals(0, session.state.value.pageIndex)
        assertEquals(1, session.state.value.currentSubPage)
        session.dispatch(ReaderCommand.PrevPage)
        assertEquals(0, session.state.value.currentSubPage)

        // Encryption XML parser + IDPF & Adobe font deobfuscation round-trip
        val encryptionXml = """
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                        xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
              <enc:EncryptedData>
                <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                <enc:CipherData>
                  <enc:CipherReference URI="OEBPS/fonts/obfuscated.otf"/>
                </enc:CipherData>
              </enc:EncryptedData>
            </encryption>
        """.trimIndent()
        val encryptedMap = EpubEncryptionParser.parse(encryptionXml)
        val fontInfo = encryptedMap["OEBPS/fonts/obfuscated.otf"]
        assertNotNull(fontInfo)
        assertEquals(StandardEpubFontDeobfuscator.ALGORITHM_IDPF_FONT, fontInfo.algorithm)

        val originalFontBytes = ByteArray(1500) { (it % 251).toByte() }
        val pubId = "urn:uuid:12345678-1234-1234-1234-1234567890ab"
        val deobfuscator = StandardEpubFontDeobfuscator()

        // XOR obfuscation is symmetric: applying twice restores original bytes
        val obfuscatedIdpf = deobfuscator.decrypt("OEBPS/fonts/obfuscated.otf", originalFontBytes, fontInfo, pubId)
        assertFalse(obfuscatedIdpf.contentEquals(originalFontBytes))
        val restoredIdpf = deobfuscator.decrypt("OEBPS/fonts/obfuscated.otf", obfuscatedIdpf, fontInfo, pubId)
        assertContentEquals(originalFontBytes, restoredIdpf)

        val adobeInfo = EncryptedResourceInfo(
            uri = "OEBPS/fonts/adobe.otf",
            algorithm = StandardEpubFontDeobfuscator.ALGORITHM_ADOBE_FONT,
        )
        val obfuscatedAdobe = deobfuscator.decrypt("OEBPS/fonts/adobe.otf", originalFontBytes, adobeInfo, pubId)
        assertFalse(obfuscatedAdobe.contentEquals(originalFontBytes))
        val restoredAdobe = deobfuscator.decrypt("OEBPS/fonts/adobe.otf", obfuscatedAdobe, adobeInfo, pubId)
        assertContentEquals(originalFontBytes, restoredAdobe)

        // Custom LCP/DRM decryptor hook
        val customHook = ContentDecryptor { _, raw, info, _ ->
            if (info?.algorithm == "custom:xor42") {
                ByteArray(raw.size) { i -> (raw[i].toInt() xor 42).toByte() }
            } else {
                raw
            }
        }
        val chained = StandardEpubFontDeobfuscator(fallbackDecryptor = customHook)
        val customEncrypted = ByteArray(16) { (it xor 42).toByte() }
        val customDecrypted = chained.decrypt(
            href = "OEBPS/ch1.xhtml",
            rawBytes = customEncrypted,
            encryptionInfo = EncryptedResourceInfo("OEBPS/ch1.xhtml", "custom:xor42"),
            publicationIdentifier = pubId,
        )
        assertContentEquals(ByteArray(16) { it.toByte() }, customDecrypted)
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

        // Bounded LRU cache must evict older entries instead of throwing when reading across all entries
        val tinyCacheProvider = ZipResourceProvider.fromBytes(
            epubBytes,
            limits = ZipLimits(maxCacheBytes = 64),
        )
        for (entry in tinyCacheProvider.entryNames()) {
            assertTrue(tinyCacheProvider.read(entry).isNotEmpty())
        }
        tinyCacheProvider.close()
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

    @Test
    fun readerJsBridgeParsesActionUrlsAndSessionDispatchesEventsAndEffects() = runTest {
        // 1. Intercepting action URLs via ReaderJsBridge.parseSpecialUrl
        val actionWithPayload = ReaderJsBridge.parseSpecialUrl("action://submit_quiz?q=%7B%22score%22%3A10%7D")
        assertTrue(actionWithPayload is InterceptedUrl.Action)
        assertEquals("submit_quiz", actionWithPayload.action)
        assertEquals("""{"score":10}""", actionWithPayload.payload)

        val simpleAction = ReaderJsBridge.parseSpecialUrl("action://toggle_sidebar")
        assertTrue(simpleAction is InterceptedUrl.Action)
        assertEquals("toggle_sidebar", simpleAction.action)
        assertNull(simpleAction.payload)

        // 2. ReaderSession handling action navigation and TriggerAction command
        val session = engine.open("sample.epub", readFixture("sample.epub"))
        val receivedEvents = mutableListOf<ReaderEvent>()
        val receivedEffects = mutableListOf<ReaderUiEffect>()
        val eventSub = session.watchEvents { receivedEvents.add(it) }
        val effectSub = session.watchUiEffects { receivedEffects.add(it) }

        // Trigger via handleUrlNavigation
        val handled = session.handleUrlNavigation("action://open_video?q=intro.mp4")
        assertTrue(handled)
        val navEvent = receivedEvents.filterIsInstance<ReaderEvent.ActionTriggered>().lastOrNull()
        assertNotNull(navEvent)
        assertEquals("open_video", navEvent.action)
        assertEquals("intro.mp4", navEvent.payload)

        val navEffect = receivedEffects.filterIsInstance<ReaderUiEffect.CustomAction>().lastOrNull()
        assertNotNull(navEffect)
        assertEquals("open_video", navEffect.action)
        assertEquals("intro.mp4", navEffect.payload)

        // Trigger via ReaderCommand.TriggerAction
        session.dispatch(ReaderCommand.TriggerAction("complete_module", """{"module":1}"""))
        val cmdEvent = receivedEvents.filterIsInstance<ReaderEvent.ActionTriggered>().lastOrNull()
        assertNotNull(cmdEvent)
        assertEquals("complete_module", cmdEvent.action)
        assertEquals("""{"module":1}""", cmdEvent.payload)

        val cmdEffect = receivedEffects.filterIsInstance<ReaderUiEffect.CustomAction>().lastOrNull()
        assertNotNull(cmdEffect)
        assertEquals("complete_module", cmdEffect.action)
        assertEquals("""{"module":1}""", cmdEffect.payload)

        eventSub.cancel()
        effectSub.cancel()
        session.close()
    }

    @Test
    fun hpubParserInfersAudioAndVideoMediaTypes() {
        val parser = HpubParser()
        assertEquals("video/mp4", parser.inferMediaType("video.mp4"))
        assertEquals("video/mp4", parser.inferMediaType("movie.m4v"))
        assertEquals("video/webm", parser.inferMediaType("movie.webm"))
        assertEquals("video/ogg", parser.inferMediaType("clip.ogv"))
        assertEquals("video/quicktime", parser.inferMediaType("clip.mov"))
        assertEquals("audio/mpeg", parser.inferMediaType("sound.mp3"))
        assertEquals("audio/mp4", parser.inferMediaType("audio.m4a"))
        assertEquals("audio/aac", parser.inferMediaType("stream.aac"))
        assertEquals("audio/wav", parser.inferMediaType("track.wav"))
        assertEquals("audio/ogg", parser.inferMediaType("voice.ogg"))
        assertEquals("audio/ogg", parser.inferMediaType("voice.oga"))
    }

    @Test
    fun memoryResourceProviderSupportsSizeAndRangeReading() {
        val data = "Hello, Multimedia World!".encodeToByteArray()
        val provider = MemoryResourceProvider(
            files = mapOf("media/test.mp4" to data),
        )
        assertEquals(data.size.toLong(), provider.size("media/test.mp4"))
        val range = provider.readRange("media/test.mp4", offset = 7L, byteCount = 10)
        assertEquals("Multimedia", range.decodeToString())
    }

    @Test
    fun paginatedModeRestoresSubPageAndEmitsProgress() = runTest {
        val epubBytes = readFixture("sample.epub")
        val session = engine.open(
            name = "sample.epub",
            bytes = epubBytes,
            initialState = InitialBookState(
                readingMode = ReadingMode.PAGINATED,
                initialPageIndex = 0,
                initialSubPage = 2,
            ),
        )

        assertEquals(ReadingMode.PAGINATED, session.state.value.readingMode)
        assertEquals(2, session.state.value.currentSubPage)
        assertEquals(2, session.state.value.progress.subPage)

        val progressEvents = mutableListOf<ReadingProgress>()
        val sub = session.watchEvents { event ->
            if (event is ReaderEvent.ProgressUpdated) {
                progressEvents.add(event.progress)
            }
        }

        // On reported subpage from webview
        session.onSubPageReported(currentSubPage = 3, totalSubPages = 5)
        assertEquals(3, session.state.value.currentSubPage)
        assertEquals(5, session.state.value.totalSubPages)
        assertEquals(3, session.state.value.progress.subPage)

        // PrevPage in paginated mode decreases subpage
        session.dispatch(ReaderCommand.PrevPage)
        assertEquals(2, session.state.value.currentSubPage)
        assertEquals(2, session.state.value.progress.subPage)

        // NextPage in paginated mode increases subpage
        session.dispatch(ReaderCommand.NextPage)
        assertEquals(3, session.state.value.currentSubPage)
        assertEquals(3, session.state.value.progress.subPage)

        sub.cancel()
        session.close()
    }

    @Test
    fun coverDiscoveryAndExtractionForEpubAndHpub() {
        val coverBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val pub = Publication(
            metadata = Metadata(title = "Cover Test", coverHref = "images/my-cover.png"),
            spine = listOf("chap1.xhtml"),
            resources = listOf(Resource(href = "images/my-cover.png", mediaType = "image/png")),
            toc = emptyList(),
            provider = MemoryResourceProvider(
                files = mapOf(
                    "images/my-cover.png" to coverBytes,
                    "chap1.xhtml" to "<html><body>Chapter 1</body></html>".encodeToByteArray(),
                ),
            ),
        )

        assertEquals("images/my-cover.png", pub.metadata.coverHref)

        val session = ReaderSession(pub)
        val readCover = session.readCoverBytes()
        assertNotNull(readCover)
        assertEquals(coverBytes.size, readCover.size)
        session.close()

        // 2. HPUB metadata check from real fixture
        val hpubBytes = readFixture("sample.hpub")
        val hpubSession = engine.open(name = "sample.hpub", bytes = hpubBytes)
        assertNotNull(hpubSession.state.value.metadata)
        hpubSession.close()
    }

    @Test
    fun highlightNotesUpdateAndPersistence() = runTest {
        val epubBytes = readFixture("sample.epub")
        val session = engine.open(
            name = "sample.epub",
            bytes = epubBytes,
            initialState = InitialBookState(
                savedPageHighlights = mapOf(0 to "type:textContent|42\$yellow\$Interesting Text|"),
                savedHighlightNotes = mapOf(42 to "Initial observation"),
            ),
        )

        val initialHighlights = session.state.value.currentPageHighlights
        assertEquals(1, initialHighlights.size)
        assertEquals(42, initialHighlights.first().id)
        assertEquals("Initial observation", initialHighlights.first().note)

        val highlightEvents = mutableListOf<ReaderEvent.PageHighlightsChanged>()
        val sub = session.watchEvents { event ->
            if (event is ReaderEvent.PageHighlightsChanged) {
                highlightEvents.add(event)
            }
        }

        // Update note
        session.dispatch(ReaderCommand.UpdateHighlightNote(highlightId = 42, note = "Updated observation"))
        assertEquals("Updated observation", session.state.value.currentPageHighlights.first().note)
        assertEquals("Updated observation", highlightEvents.last().summaryItems.first().note)

        // Clear note
        session.dispatch(ReaderCommand.UpdateHighlightNote(highlightId = 42, note = null))
        assertNull(session.state.value.currentPageHighlights.first().note)

        sub.cancel()
        session.close()
    }

    @Test
    fun rtlProgressionDirectionAndCssInjection() {
        val opfRtl = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata><title>Arabic Book</title><language>ar</language></metadata>
                <manifest>
                    <item id="c1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine page-progression-direction="rtl">
                    <itemref idref="c1"/>
                </spine>
            </package>
        """.trimIndent()

        val parsedMeta = EpubParser().parseMetadata(opfRtl)
        assertEquals(PageProgressionDirection.RTL, parsedMeta.pageProgressionDirection)

        val injectedHtml = ReaderJsBridge.injectThemeAndBridge(
            rawHtml = "<html><head><title>RTL</title></head><body><p>مرحبا</p></body></html>",
            theme = ThemeTokens.material3(),
            fontScale = 1.0f,
            direction = PageProgressionDirection.RTL,
        )
        assertTrue(injectedHtml.contains("dir=\"rtl\""))

        val goToSubPageScript = ReaderJsBridge.goToSubPageCommand(2)
        assertTrue(goToSubPageScript.contains("readerJS.goToSubPage(2)"))
    }

    @Test
    fun bookStateSnapshotExportAndImport() = runTest {
        val epubBytes = readFixture("sample.epub")
        val session = engine.open(
            name = "sample.epub",
            bytes = epubBytes,
            initialState = InitialBookState(
                documentId = "book-snap-1",
                savedBookmarks = listOf(
                    Bookmark(id = "bm-1", pageIndex = 0, scrollY = 120, label = "Mark 1"),
                ),
            ),
        )

        // Mutate state
        session.dispatch(ReaderCommand.SetNightMode(true))
        session.dispatch(ReaderCommand.SetReadingMode(ReadingMode.PAGINATED))
        session.onSubPageReported(currentSubPage = 2, totalSubPages = 4)
        session.dispatch(ReaderCommand.UpdateHighlightNote(highlightId = 99, note = "Saved Note"))

        val snapshotJson = session.exportBookStateSnapshot()
        assertTrue(snapshotJson.contains("book-snap-1"))
        assertTrue(snapshotJson.contains("Saved Note"))
        assertTrue(snapshotJson.contains("bm-1"))

        // Create clean second session and import snapshot
        val cleanSession = engine.open("sample.epub", epubBytes)
        assertFalse(cleanSession.state.value.isNightMode)
        assertEquals(ReadingMode.SCROLL, cleanSession.state.value.readingMode)

        val restoredState = cleanSession.importBookStateSnapshot(snapshotJson)
        assertTrue(restoredState.isNightMode)
        assertEquals(ReadingMode.PAGINATED, restoredState.readingMode)
        assertEquals(2, restoredState.currentSubPage)
        assertEquals(1, restoredState.bookmarks.size)
        assertEquals("bm-1", restoredState.bookmarks.first().id)

        cleanSession.close()
        session.close()
    }

    @Test
    fun darkThemeForcesColorsPreservesHighlightsAndSupportsTypographyLayout() {
        val baseTheme = ThemeTokens.material3()
        assertEquals("justify", baseTheme.typography.textAlign)
        assertEquals(24, baseTheme.shape.horizontalPaddingPx)

        val defaultCss = baseTheme.toContentCss(
            fontScale = 1.0f,
            customFontFamily = null,
            isNightMode = false,
            readingMode = ReadingMode.SCROLL,
            layout = RenditionLayout.REFLOWABLE,
        )
        assertTrue(defaultCss.contains("body{padding-left:24px !important;padding-right:24px !important;text-align:justify !important;box-sizing:border-box;}"))

        val customTheme = baseTheme.copy(
            typography = Typography(
                fontFamily = "Georgia, serif",
                baseSizePx = 20f,
                lineHeight = 1.8f,
                textAlign = "left",
            ),
            shape = Shape(
                cornerRadiusPx = 8f,
                spacingUnitPx = 8f,
                horizontalPaddingPx = 32,
            ),
        )

        val css = customTheme.toContentCss(
            fontScale = 1.2f,
            customFontFamily = "Georgia, serif",
            isNightMode = true,
            readingMode = ReadingMode.SCROLL,
            layout = RenditionLayout.REFLOWABLE,
        )

        assertTrue(
            css.contains("html,body{background-color:#131313 !important;color:#EAEAEA !important;font-family:Georgia, serif !important;font-size:24.0px;line-height:1.8 !important;}") ||
                css.contains("html,body{background-color:#131313 !important;color:#EAEAEA !important;font-family:Georgia, serif !important;font-size:24px;line-height:1.8 !important;}"),
        )
        assertTrue(css.contains("""body *:not([class*="highlight_"]){color:#EAEAEA !important;background-color:transparent !important;line-height:inherit;}"""))
        assertTrue(css.contains("""a,a *:not([class*="highlight_"]){color:#6AB7FF !important;}"""))
        assertTrue(css.contains(".highlight_yellow,.highlight_yellow *{background-color:#FFF59D !important;color:#111 !important;}"))
        assertTrue(css.contains("body{padding-left:32px !important;padding-right:32px !important;text-align:left !important;box-sizing:border-box;}"))
        assertTrue(css.contains("body,body p,body div,body li,body blockquote{text-align:left !important;}"))

        // Paginated spread CSS
        val spreadCss = customTheme.toContentCss(
            fontScale = 1.0f,
            customFontFamily = null,
            isNightMode = false,
            readingMode = ReadingMode.PAGINATED,
            layout = RenditionLayout.REFLOWABLE,
            spread = RenditionSpread.LANDSCAPE,
        )
        assertTrue(spreadCss.contains("html.reader-paginated{height:var(--reader-page-height,560px) !important;min-height:var(--reader-page-height,560px) !important;max-height:var(--reader-page-height,560px) !important;width:100% !important;overflow:hidden !important;"))
        assertTrue(spreadCss.contains("html.reader-paginated body{height:var(--reader-page-height,560px) !important;min-height:var(--reader-page-height,560px) !important;max-height:var(--reader-page-height,560px) !important;width:100% !important;overflow:visible !important;"))
        assertTrue(spreadCss.contains("html.reader-paginated body{column-width:calc(100vw - 64px);column-gap:64px;column-fill:auto;padding:16px 32px !important;}"))
        assertTrue(spreadCss.contains("@media (orientation: landscape){html.reader-paginated body{column-count:2;column-width:auto;column-gap:64px;}}"))

        // Inject into <html> tag without attributes/spaces after <html
        val injectedBareHtml = ReaderJsBridge.injectThemeAndBridge(
            rawHtml = "<html><head></head><body><p style=\"color:#000\">Texto</p></body></html>",
            theme = customTheme,
            fontScale = 1.0f,
            isNightMode = true,
        )
        assertTrue(injectedBareHtml.contains("<html class=\"nightMode\">"))
        assertTrue(injectedBareHtml.contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"))

        // Inject into <html lang="pt"> tag preserving existing attributes
        val injectedWithAttr = ReaderJsBridge.injectThemeAndBridge(
            rawHtml = "<html lang=\"pt\"><head></head><body><p>Texto</p></body></html>",
            theme = customTheme,
            fontScale = 1.0f,
            isNightMode = true,
        )
        assertTrue(injectedWithAttr.contains("<html class=\"nightMode\" lang=\"pt\">"))
    }

    @Test
    fun positionsPageListLandmarksFootnotePopupAndReadingStats() = runTest {
        val parser = EpubParser()
        val navHtml = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc"><ol><li><a href="chap1.xhtml">Cap 1</a></li></ol></nav>
                <nav epub:type="page-list"><ol><li><a href="chap1.xhtml#p45">45</a></li><li><a href="chap2.xhtml#p46">46</a></li></ol></nav>
                <nav epub:type="landmarks"><ol><li><a href="chap1.xhtml" epub:type="bodymatter">Início do Livro</a></li></ol></nav>
              </body>
            </html>
        """.trimIndent()

        val pageListEntries = parser.parseNavByType(navHtml, "OEBPS/nav.xhtml", "page-list")
        assertEquals(2, pageListEntries.size)
        assertEquals("45", pageListEntries[0].title)
        assertEquals("OEBPS/chap1.xhtml#p45", pageListEntries[0].href)

        val landmarkEntries = parser.parseNavByType(navHtml, "OEBPS/nav.xhtml", "landmarks")
        assertEquals(1, landmarkEntries.size)
        assertEquals("Início do Livro", landmarkEntries[0].title)
        assertEquals("OEBPS/chap1.xhtml", landmarkEntries[0].href)

        val ncxXml = """
            <ncx>
              <pageList>
                <pageTarget id="pt1" value="10"><navLabel><text>10</text></navLabel><content src="chap1.xhtml#pg10"/></pageTarget>
              </pageList>
            </ncx>
        """.trimIndent()
        val ncxPages = parser.parseNcxPageList(ncxXml, "OEBPS/toc.ncx")
        assertEquals(1, ncxPages.size)
        assertEquals("10", ncxPages[0].title)
        assertEquals("OEBPS/chap1.xhtml#pg10", ncxPages[0].href)

        val chap1Words = (1..250).joinToString(" ") { "palavra$it" }
        val chap2Words = (1..200).joinToString(" ") { "termo$it" }
        val chap1Html = """
            <html><head><title>3MensAngelicas</title></head><body>
              <p>$chap1Words <a href="#fn1" epub:type="noteref">1</a></p>
              <aside id="fn1" epub:type="footnote"><p>Nota explicativa sobre Zaqueu.</p></aside>
            </body></html>
        """.trimIndent()
        val chap2Html = "<html><body><p>$chap2Words</p></body></html>"

        val pub = Publication(
            metadata = Metadata(title = "Estudo", spread = RenditionSpread.LANDSCAPE),
            spine = listOf("OEBPS/chap1.xhtml", "OEBPS/chap2.xhtml"),
            resources = listOf(
                Resource("OEBPS/chap1.xhtml", "application/xhtml+xml"),
                Resource("OEBPS/chap2.xhtml", "application/xhtml+xml"),
            ),
            toc = parser.parseNavXhtml(navHtml, "OEBPS/nav.xhtml"),
            provider = MemoryResourceProvider(
                mapOf(
                    "OEBPS/chap1.xhtml" to chap1Html.encodeToByteArray(),
                    "OEBPS/chap2.xhtml" to chap2Html.encodeToByteArray(),
                ),
            ),
            pageList = pageListEntries,
            landmarks = landmarkEntries,
        )

        val positions = pub.positions()
        assertTrue(positions.size >= 3)
        assertEquals("OEBPS/chap1.xhtml", positions.first().href)

        val session = ReaderSession(pub)
        val initialState = session.state.value
        assertEquals(2, initialState.pageList.size)
        assertEquals("45", initialState.pageList[0].title)
        assertEquals(1, initialState.landmarks.size)
        assertEquals(positions.size, initialState.positions.size)
        assertTrue(initialState.stats.chapterWordCount >= 250)
        assertTrue(initialState.stats.totalWordCount >= 450)
        assertTrue(initialState.stats.estimatedChapterMinutesRemaining >= 2)

        val effects = mutableListOf<ReaderUiEffect>()
        val effectSub = session.watchUiEffects { effects.add(it) }

        // Footnote popup via handleUrlNavigation without changing current page
        val handledFootnote = session.handleUrlNavigation("book://content/OEBPS/chap1.xhtml#fn1")
        assertTrue(handledFootnote)
        val footnoteEffect = effects.filterIsInstance<ReaderUiEffect.OpenCustomUri>().lastOrNull()
        assertNotNull(footnoteEffect)
        assertEquals("footnote", footnoteEffect.scheme)
        assertEquals("fn1", footnoteEffect.title)
        assertEquals("Nota explicativa sobre Zaqueu.", footnoteEffect.content)
        assertEquals(0, session.state.value.pageIndex)

        // Chapter preview helper (getPagePreview & pagePreview)
        val preview0 = session.getPagePreview(0)
        assertNotNull(preview0)
        assertEquals("Cap 1", preview0.title)
        assertFalse(preview0.excerpt.contains("3MensAngelicas"))
        assertTrue(preview0.excerpt.startsWith("palavra1"))
        val preview1 = session.pagePreview(1)
        assertNotNull(preview1)
        assertTrue(preview1.excerpt.contains("termo1"))
        assertNull(session.getPagePreview(-1))
        assertNull(session.getPagePreview(99))

        effectSub.cancel()
        session.close()
    }

    @Test
    fun gestureDetectionScriptAndSessionGestureNavigationActions() = runTest {
        val pub = Publication(
            metadata = Metadata(title = "Gestos"),
            spine = listOf("ch1.xhtml", "ch2.xhtml", "ch3.xhtml"),
            resources = listOf(
                Resource("ch1.xhtml", "application/xhtml+xml"),
                Resource("ch2.xhtml", "application/xhtml+xml"),
                Resource("ch3.xhtml", "application/xhtml+xml"),
            ),
            toc = emptyList(),
            provider = MemoryResourceProvider(
                mapOf(
                    "ch1.xhtml" to "<html><body><p>Capítulo 1</p></body></html>".encodeToByteArray(),
                    "ch2.xhtml" to "<html><body><p>Capítulo 2</p></body></html>".encodeToByteArray(),
                    "ch3.xhtml" to "<html><body><p>Capítulo 3</p></body></html>".encodeToByteArray(),
                ),
            ),
        )

        val injectedPaginated = ReaderJsBridge.injectThemeAndBridge(
            rawHtml = "<html><head></head><body><p>Olá</p></body></html>",
            theme = ThemeTokens.material3(),
            fontScale = 1.0f,
            fontFamily = "Default",
            fontSizeClass = "",
            isNightMode = false,
            readingMode = ReadingMode.PAGINATED,
            layout = RenditionLayout.REFLOWABLE,
            direction = PageProgressionDirection.DEFAULT,
            spread = RenditionSpread.AUTO,
            initialSubPage = 2,
        )
        assertTrue(injectedPaginated.contains("data-reader-subpage=\"2\""))
        assertTrue(injectedPaginated.contains("handlePageTurnGesture"))
        assertTrue(injectedPaginated.contains("__readerGestureHooked"))

        val session = ReaderSession(
            publication = pub,
            initialBookState = InitialBookState(
                readingMode = ReadingMode.PAGINATED,
                initialPageIndex = 0,
                initialSubPage = 1,
            ),
        )

        val events = mutableListOf<ReaderEvent>()
        val effects = mutableListOf<ReaderUiEffect>()
        val eventSub = session.watchEvents { events.add(it) }
        val effectSub = session.watchUiEffects { effects.add(it) }

        assertTrue(session.currentHtml().contains("data-reader-subpage=\"1\""))

        // Sync sub-page via action:// URL fallback
        val subHandled = session.handleUrlNavigation(
            "action://${ReaderJsBridge.ACTION_SUB_PAGE_CHANGED}?q=%7B%22subPage%22%3A2%2C%22totalSubPages%22%3A4%7D",
        )
        assertTrue(subHandled)
        assertEquals(2, session.state.value.currentSubPage)
        assertEquals(4, session.state.value.totalSubPages)

        // Advance chapter via swipe gesture (URL action://)
        assertTrue(session.handleUrlNavigation("action://${ReaderJsBridge.ACTION_GESTURE_NEXT_PAGE}"))
        assertEquals(1, session.state.value.pageIndex)
        assertEquals(0, session.state.value.currentSubPage)
        assertEquals(ReaderJsBridge.ACTION_GESTURE_NEXT_PAGE, events.filterIsInstance<ReaderEvent.ActionTriggered>().last().action)

        // Advance again via JS bridge TriggerAction command
        session.dispatch(ReaderCommand.TriggerAction(ReaderJsBridge.ACTION_GESTURE_NEXT_PAGE, """{"source":"tap"}"""))
        assertEquals(2, session.state.value.pageIndex)
        assertEquals(0, session.state.value.currentSubPage)

        // Go back chapter via prev gesture
        assertTrue(session.handleUrlNavigation("action://${ReaderJsBridge.ACTION_GESTURE_PREV_PAGE}"))
        assertEquals(1, session.state.value.pageIndex)
        session.dispatch(ReaderCommand.TriggerAction(ReaderJsBridge.ACTION_GESTURE_PREV_PAGE, """{"source":"swipe"}"""))
        assertEquals(0, session.state.value.pageIndex)

        // Boundary check: prev at page 0 stays at page 0 without crashing
        assertTrue(session.handleUrlNavigation("action://${ReaderJsBridge.ACTION_GESTURE_PREV_PAGE}"))
        assertEquals(0, session.state.value.pageIndex)

        // Center tap emits CustomAction effect for host toolbar toggle
        val customEffectsBefore = effects.filterIsInstance<ReaderUiEffect.CustomAction>().size
        assertTrue(session.handleUrlNavigation("action://${ReaderJsBridge.ACTION_GESTURE_TAP_CENTER}?q=%7B%22x%22%3A200%2C%22y%22%3A300%7D"))
        val customEffects = effects.filterIsInstance<ReaderUiEffect.CustomAction>()
        assertEquals(customEffectsBefore + 1, customEffects.size)
        assertEquals(ReaderJsBridge.ACTION_GESTURE_TAP_CENTER, customEffects.last().action)

        eventSub.cancel()
        effectSub.cancel()
        session.close()
    }

    @Test
    fun ttsSentencesMediaOverlaysDrmRangeStreamingAndPersistentSearchIndex() = runTest {
        // 1. SMIL 3.0 Media Overlays parser + clock value parsing
        assertEquals(83.45, SmilParser.parseClockValue("0:01:23.450"))
        assertEquals(83.5, SmilParser.parseClockValue("01:23.5"))
        assertEquals(12.5, SmilParser.parseClockValue("12.5s"))
        assertEquals(1.25, SmilParser.parseClockValue("1250ms"))
        assertEquals(120.0, SmilParser.parseClockValue("2min"))
        assertEquals(3600.0, SmilParser.parseClockValue("1h"))

        val smilXml = """
            <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0">
              <body>
                <seq>
                  <par id="par1">
                    <text src="../text/ch1.xhtml#p1"/>
                    <audio src="../audio/ch1.mp3" clipBegin="0:00:00.000" clipEnd="0:00:04.500"/>
                  </par>
                  <par id="par2">
                    <text src="../text/ch1.xhtml#p2"/>
                    <audio src="../audio/ch1.mp3" clipBegin="4.5s" clipEnd="9.25s"/>
                  </par>
                </seq>
              </body>
            </smil>
        """.trimIndent()
        val overlay = SmilParser.parse(smilXml, "OEBPS/smil/ch1.smil")
        assertEquals("OEBPS/smil/ch1.smil", overlay.href)
        assertEquals(2, overlay.clips.size)
        assertEquals("OEBPS/text/ch1.xhtml", overlay.clips[0].textHref)
        assertEquals("p1", overlay.clips[0].textFragmentId)
        assertEquals("OEBPS/audio/ch1.mp3", overlay.clips[0].audioHref)
        assertEquals(0.0, overlay.clips[0].clipBeginSeconds)
        assertEquals(4.5, overlay.clips[0].clipEndSeconds)

        // 2. TTS sentences + HighlightTtsSentence / ClearTtsHighlight + Media Overlays lookup in ReaderSession
        val ch1Html = """
            <html><head><title>Cap 1</title></head><body>
              <p id="p1">No princípio era o Verbo. A luz brilha nas trevas!</p>
              <p id="p2">Você compreende esta mensagem?</p>
            </body></html>
        """.trimIndent()
        val ch2Html = "<html><body><p>Capítulo dois com palavra especial sabedoria.</p></body></html>"

        val pub = Publication(
            metadata = Metadata(title = "Áudio e TTS"),
            spine = listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"),
            resources = listOf(
                Resource("OEBPS/text/ch1.xhtml", "application/xhtml+xml"),
                Resource("OEBPS/text/ch2.xhtml", "application/xhtml+xml"),
                Resource("OEBPS/smil/ch1.smil", "application/smil+xml"),
            ),
            toc = emptyList(),
            provider = MemoryResourceProvider(
                mapOf(
                    "OEBPS/text/ch1.xhtml" to ch1Html.encodeToByteArray(),
                    "OEBPS/text/ch2.xhtml" to ch2Html.encodeToByteArray(),
                ),
            ),
            mediaOverlays = listOf(overlay),
        )

        val session = ReaderSession(pub)
        val renderedHtml = session.currentHtml()
        assertTrue(renderedHtml.contains(".reader-tts-active"))
        assertTrue(renderedHtml.contains("highlightTtsSentence"))
        assertTrue(renderedHtml.contains("clearTtsHighlight"))

        val sentences = session.currentChapterSentences()
        assertEquals(3, sentences.size)
        assertEquals("No princípio era o Verbo.", sentences[0].text)
        assertEquals("A luz brilha nas trevas!", sentences[1].text)
        assertEquals("Você compreende esta mensagem?", sentences[2].text)

        val chapterClips = session.currentChapterMediaOverlayClips()
        assertEquals(2, chapterClips.size)
        val activeClip = session.findMediaOverlayClipAt("OEBPS/audio/ch1.mp3", 5.2)
        assertNotNull(activeClip)
        assertEquals("p2", activeClip.textFragmentId)

        val effects = mutableListOf<ReaderUiEffect>()
        val effectSub = session.watchUiEffects { effects.add(it) }
        session.dispatch(ReaderCommand.HighlightTtsSentence(sentenceIndex = 1))
        val highlightEffect = effects.filterIsInstance<ReaderUiEffect.EvaluateJs>().last()
        assertTrue(highlightEffect.script.contains("highlightTtsSentence(1,"))
        assertTrue(highlightEffect.script.contains("A luz brilha nas trevas!"))

        session.dispatch(ReaderCommand.HighlightTtsSentence(sentenceIndex = 0, elementId = "p2"))
        val byIdEffect = effects.filterIsInstance<ReaderUiEffect.EvaluateJs>().last()
        assertTrue(byIdEffect.script.contains("\"p2\""))

        session.dispatch(ReaderCommand.ClearTtsHighlight)
        val clearEffect = effects.filterIsInstance<ReaderUiEffect.EvaluateJs>().last()
        assertTrue(clearEffect.script.contains("clearTtsHighlight"))

        // 3. Persistent search index on disk (Okio FakeFileSystem)
        val fakeFs = FakeFileSystem()
        val indexPath = "/cache/search-index.json".toPath()
        session.saveSearchIndex(indexPath, fakeFs)
        assertTrue(fakeFs.exists(indexPath))

        // Restore into a fresh Publication whose provider fails if read() is called (proves search uses disk index!)
        val noReadProvider = object : ResourceProvider {
            override fun read(href: String): ByteArray = error("Should not read raw HTML when search index is cached")
        }
        val restoredPub = Publication(
            metadata = Metadata(title = "Áudio e TTS"),
            spine = listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"),
            resources = emptyList(),
            toc = emptyList(),
            provider = noReadProvider,
        )
        val restoredSession = ReaderSession(restoredPub)
        assertTrue(restoredSession.loadSearchIndex(indexPath, fakeFs))
        val searchHits = restoredSession.search("sabedoria")
        assertEquals(1, searchHits.size)
        assertEquals(1, searchHits[0].pageIndex)

        // 4. Block/range decryption in DecryptingResourceProvider (GAP-02)
        val pubId = "urn:uuid:12345678-1234-1234-1234-1234567890ab"
        val originalBytes = ByteArray(2048) { (it % 251).toByte() }
        val deobfuscator = StandardEpubFontDeobfuscator()
        val idpfInfo = EncryptedResourceInfo(
            uri = "OEBPS/fonts/sample.otf",
            algorithm = StandardEpubFontDeobfuscator.ALGORITHM_IDPF_FONT,
        )
        val obfuscated = deobfuscator.decrypt("OEBPS/fonts/sample.otf", originalBytes, idpfInfo, pubId)

        var fullReadCalls = 0
        var rangeReadCalls = 0
        val trackingProvider = object : ResourceProvider {
            override fun read(href: String): ByteArray {
                fullReadCalls++
                return obfuscated
            }

            override fun size(href: String): Long = obfuscated.size.toLong()

            override fun readRange(href: String, offset: Long, byteCount: Int): ByteArray {
                rangeReadCalls++
                val start = offset.toInt()
                val end = (start + byteCount).coerceAtMost(obfuscated.size)
                return obfuscated.copyOfRange(start, end)
            }
        }
        val decryptingProvider = DecryptingResourceProvider(
            delegate = trackingProvider,
            encryptedResources = mapOf("OEBPS/fonts/sample.otf" to idpfInfo),
            publicationIdentifier = pubId,
            decryptor = deobfuscator,
        )

        // Slice crossing the 1040-byte IDPF boundary without full read()
        val sliceCrossing = decryptingProvider.readRange("OEBPS/fonts/sample.otf", offset = 1000L, byteCount = 100)
        assertContentEquals(originalBytes.copyOfRange(1000, 1100), sliceCrossing)
        assertEquals(0, fullReadCalls)
        assertEquals(1, rangeReadCalls)
        assertEquals(2048L, decryptingProvider.size("OEBPS/fonts/sample.otf"))
        assertEquals(0, fullReadCalls)

        // Custom streaming DRM decryptRange (e.g. XOR-42 block stream)
        val customEncryptedVideo = ByteArray(512) { ((it % 200) xor 42).toByte() }
        val customInfo = EncryptedResourceInfo("OEBPS/video/clip.mp4", "custom:stream-xor42", originalLength = 512L)
        var customFullReads = 0
        val customRawProvider = object : ResourceProvider {
            override fun read(href: String): ByteArray {
                customFullReads++
                return customEncryptedVideo
            }

            override fun readRange(href: String, offset: Long, byteCount: Int): ByteArray {
                val start = offset.toInt()
                val end = (start + byteCount).coerceAtMost(customEncryptedVideo.size)
                return customEncryptedVideo.copyOfRange(start, end)
            }
        }
        val streamingDecryptor = object : ContentDecryptor {
            override fun decrypt(
                href: String,
                rawBytes: ByteArray,
                encryptionInfo: EncryptedResourceInfo?,
                publicationIdentifier: String?,
            ): ByteArray = ByteArray(rawBytes.size) { i -> (rawBytes[i].toInt() xor 42).toByte() }

            override fun decryptRange(
                href: String,
                offset: Long,
                byteCount: Int,
                rawRangeProvider: (rangeOffset: Long, rangeLength: Int) -> ByteArray,
                encryptionInfo: EncryptedResourceInfo?,
                publicationIdentifier: String?,
            ): ByteArray {
                val rawSlice = rawRangeProvider(offset, byteCount)
                return ByteArray(rawSlice.size) { i -> (rawSlice[i].toInt() xor 42).toByte() }
            }
        }
        val customDecryptingProvider = DecryptingResourceProvider(
            delegate = customRawProvider,
            encryptedResources = mapOf("OEBPS/video/clip.mp4" to customInfo),
            publicationIdentifier = pubId,
            decryptor = StandardEpubFontDeobfuscator(fallbackDecryptor = streamingDecryptor),
        )
        val videoChunk = customDecryptingProvider.readRange("OEBPS/video/clip.mp4", offset = 128L, byteCount = 64)
        assertContentEquals(ByteArray(64) { ((128 + it) % 200).toByte() }, videoChunk)
        assertEquals(0, customFullReads)

        effectSub.cancel()
        restoredSession.close()
        session.close()
    }

    @Test
    fun xmlTokenizerHandlesCommentsCdataMultilineAttributesAndReplacesRegexFragility() {
        val complexOpf = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE package [
              <!ENTITY nbsp "&#160;">
            ]>
            <!-- Comentário enganoso com tag falsa:
              <dc:title>Título Falso</dc:title>
              <meta property="rendition:layout">pre-paginated</meta>
              <spine page-progression-direction="rtl">
            -->
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title><![CDATA[Livro <Ciên­cia> & Fé]]></dc:title>
                <dc:creator
                  id="c1"
                  data-note="A > B">João Silva</dc:creator>
                <dc:language>pt-BR</dc:language>
                <dc:identifier id="uid">urn:isbn:9780000000001</dc:identifier>
                <meta property="rendition:layout">reflowable</meta>
                <meta property="rendition:spread">landscape</meta>
              </metadata>
              <spine page-progression-direction="ltr"/>
            </package>
        """.trimIndent()

        val tokens = XmlTokenizer.tokenize(complexOpf)
        assertTrue(tokens.any { it is XmlToken.CData && it.content == "Livro <Ciên­cia> & Fé" })
        assertTrue(tokens.any { it is XmlToken.StartTag && it.localName == "creator" && it.attr("data-note") == "A > B" })

        val parser = EpubParser()
        val meta = parser.parseMetadata(complexOpf)
        assertEquals("Livro <Ciên­cia> & Fé", meta.title)
        assertEquals(listOf("João Silva"), meta.authors)
        assertEquals("pt-BR", meta.language)
        assertEquals("urn:isbn:9780000000001", meta.identifier)
        assertEquals(RenditionLayout.REFLOWABLE, meta.layout)
        assertEquals(RenditionSpread.LANDSCAPE, meta.spread)
        assertEquals(PageProgressionDirection.LTR, meta.pageProgressionDirection)

        val encryptionXml = """
            <!-- <EncryptedData><EncryptionMethod Algorithm="fake"/><CipherReference URI="fake.bin"/></EncryptedData> -->
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                        xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
              <enc:EncryptedData>
                <enc:EncryptionMethod
                  Algorithm="http://www.idpf.org/2008/embedding"/>
                <enc:CipherData>
                  <enc:CipherReference URI="OEBPS/fonts/RealFont.otf"/>
                </enc:CipherData>
              </enc:EncryptedData>
            </encryption>
        """.trimIndent()
        val encrypted = EpubEncryptionParser.parse(encryptionXml)
        assertEquals(1, encrypted.size)
        assertNotNull(encrypted["OEBPS/fonts/RealFont.otf"])

        val smilXml = """
            <!-- <par><text src="ignored.xhtml#x"/><audio src="ignored.mp3" clipBegin="0s"/></par> -->
            <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0">
              <body><seq>
                <par id="p1"><text src="ch1.xhtml#s1"/><audio src="ch1.mp3" clipBegin="0s" clipEnd="3.2s"/></par>
              </seq></body>
            </smil>
        """.trimIndent()
        val overlay = SmilParser.parse(smilXml, "OEBPS/ch1.smil")
        assertEquals(1, overlay.clips.size)
        assertEquals("OEBPS/ch1.xhtml", overlay.clips[0].textHref)
    }

    @Test
    fun cbzParserOrdersPagesNaturallyReadsComicInfoAndServesImages() = runTest {
        val page1Bytes = byteArrayOf(1, 2, 3, 4)
        val page2Bytes = byteArrayOf(5, 6, 7, 8)
        val page10Bytes = byteArrayOf(9, 10, 11, 12)
        val comicInfoXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <ComicInfo>
              <Title>Aventura Espacial</Title>
              <Writer>Ana Costa, Pedro Lima</Writer>
              <LanguageISO>pt</LanguageISO>
              <Manga>YesAndRightToLeft</Manga>
            </ComicInfo>
        """.trimIndent().encodeToByteArray()

        val cbzBytes = buildStoredZip(
            entries = linkedMapOf(
                "__MACOSX/._page 1.jpg" to byteArrayOf(0, 0),
                ".DS_Store" to byteArrayOf(0),
                "Cap 1/page10.png" to page10Bytes,
                "Cap 1/page2.jpg" to page2Bytes,
                "Cap 1/page 1.jpg" to page1Bytes,
                "ComicInfo.xml" to comicInfoXml,
            ),
            asZip64 = false,
        )

        assertTrue(CbzParser().canParse(Source.fromBytes("quadrinho.cbz", byteArrayOf())))
        val session = engine.open(name = "quadrinho.cbz", bytes = cbzBytes)
        val state = session.state.value
        assertEquals("Aventura Espacial", state.metadata.title)
        assertEquals(listOf("Ana Costa", "Pedro Lima"), state.metadata.authors)
        assertEquals("pt", state.metadata.language)
        assertEquals(RenditionLayout.PRE_PAGINATED, state.metadata.layout)
        assertEquals(PageProgressionDirection.RTL, state.metadata.pageProgressionDirection)
        assertEquals("Cap 1/page 1.jpg", state.metadata.coverHref)
        assertEquals(3, state.spineLen)
        assertContentEquals(page1Bytes, session.readCoverBytes())

        // Page 0 -> "Cap 1/page 1.jpg" (natural sort places 1 < 2 < 10)
        val page0Html = session.currentHtml()
        assertTrue(page0Html.contains("Cap%201/page%201.jpg"))
        assertContentEquals(page1Bytes, session.readResource("Cap%201/page%201.jpg"))

        // Page 1 -> "Cap 1/page2.jpg"
        session.dispatch(ReaderCommand.NextPage)
        assertTrue(session.currentHtml().contains("Cap%201/page2.jpg"))
        assertContentEquals(page2Bytes, session.readResource("Cap 1/page2.jpg"))

        // Page 2 -> "Cap 1/page10.png"
        session.dispatch(ReaderCommand.NextPage)
        assertTrue(session.currentHtml().contains("Cap%201/page10.png"))
        assertContentEquals(page10Bytes, session.readResource("Cap 1/page10.png"))

        session.close()
    }

    @Test
    fun zipResourceProviderSupportsZip64HeadersAndExtraFields() {
        val entry1 = "OEBPS/hello.txt" to "Conteúdo em ZIP64 com sucesso!".encodeToByteArray()
        val entry2 = "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>".encodeToByteArray()
        val zip64Bytes = buildStoredZip(
            entries = linkedMapOf(entry1, entry2),
            asZip64 = true,
        )

        assertTrue(ZipResourceProvider.hasZipEntry(zip64Bytes, "OEBPS/hello.txt"))
        val provider = ZipResourceProvider.fromBytes(zip64Bytes)
        assertEquals(entry1.second.size.toLong(), provider.size("OEBPS/hello.txt"))
        assertContentEquals(entry1.second, provider.read("OEBPS/hello.txt"))
        assertContentEquals(entry2.second, provider.read("META-INF/container.xml"))
        assertEquals(
            "ZIP64",
            provider.readRange("OEBPS/hello.txt", offset = 13L, byteCount = 5).decodeToString(),
        )
        provider.close()

        // Truncated/invalid ZIP64 EOCD sentinel without locator must fail deterministically
        val truncatedZip = Buffer().apply {
            write(ByteArray(32))
            writeIntLe(0x06054b50)
            writeShortLe(0)
            writeShortLe(0)
            writeShortLe(0xFFFF)
            writeShortLe(0xFFFF)
            writeIntLe(-1)
            writeIntLe(-1)
            writeShortLe(0)
        }.readByteArray()
        assertFailsWith<ReaderException.InvalidContent> {
            ZipResourceProvider.fromBytes(truncatedZip)
        }
    }

    private fun buildStoredZip(
        entries: Map<String, ByteArray>,
        asZip64: Boolean,
    ): ByteArray {
        val out = Buffer()
        data class WrittenEntry(
            val nameBytes: ByteArray,
            val data: ByteArray,
            val localOffset: Long,
        )
        val written = mutableListOf<WrittenEntry>()

        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val localOffset = out.size
            out.writeIntLe(0x04034b50) // Local File Header signature
            out.writeShortLe(if (asZip64) 45 else 20) // version needed
            out.writeShortLe(0x0800) // UTF-8 filename flag
            out.writeShortLe(0) // STORED
            out.writeShortLe(0) // mod time
            out.writeShortLe(0) // mod date
            out.writeIntLe(0) // crc32 (unused by reader)
            out.writeIntLe(data.size)
            out.writeIntLe(data.size)
            out.writeShortLe(nameBytes.size)
            out.writeShortLe(0) // extra length
            out.write(nameBytes)
            out.write(data)
            written.add(WrittenEntry(nameBytes, data, localOffset))
        }

        val cdOffset = out.size
        for (item in written) {
            val extra = if (asZip64) {
                Buffer().apply {
                    writeShortLe(0x0001) // ZIP64 Extended Information Extra Field
                    writeShortLe(24) // 3 * 8 bytes
                    writeLongLe(item.data.size.toLong())
                    writeLongLe(item.data.size.toLong())
                    writeLongLe(item.localOffset)
                }.readByteArray()
            } else {
                ByteArray(0)
            }

            out.writeIntLe(0x02014b50) // Central Directory signature
            out.writeShortLe(if (asZip64) 45 else 20)
            out.writeShortLe(if (asZip64) 45 else 20)
            out.writeShortLe(0x0800) // UTF-8
            out.writeShortLe(0) // STORED
            out.writeShortLe(0)
            out.writeShortLe(0)
            out.writeIntLe(0) // crc32
            out.writeIntLe(if (asZip64) -1 else item.data.size) // 0xFFFFFFFF sentinel for ZIP64
            out.writeIntLe(if (asZip64) -1 else item.data.size)
            out.writeShortLe(item.nameBytes.size)
            out.writeShortLe(extra.size)
            out.writeShortLe(0) // comment len
            out.writeShortLe(0) // disk start
            out.writeShortLe(0) // internal attrs
            out.writeIntLe(0) // external attrs
            out.writeIntLe(if (asZip64) -1 else item.localOffset.toInt())
            out.write(item.nameBytes)
            if (extra.isNotEmpty()) out.write(extra)
        }

        val cdSize = out.size - cdOffset
        if (asZip64) {
            val zip64EocdOffset = out.size
            out.writeIntLe(0x06064b50) // ZIP64 EOCD signature
            out.writeLongLe(44L) // remaining bytes after offset +12
            out.writeShortLe(45) // version made by
            out.writeShortLe(45) // version needed
            out.writeIntLe(0) // disk number
            out.writeIntLe(0) // disk start
            out.writeLongLe(written.size.toLong())
            out.writeLongLe(written.size.toLong())
            out.writeLongLe(cdSize)
            out.writeLongLe(cdOffset)

            // ZIP64 EOCD Locator (20 bytes)
            out.writeIntLe(0x07064b50)
            out.writeIntLe(0) // disk number
            out.writeLongLe(zip64EocdOffset)
            out.writeIntLe(1) // total disks

            // Standard EOCD with sentinel fields
            out.writeIntLe(0x06054b50)
            out.writeShortLe(0)
            out.writeShortLe(0)
            out.writeShortLe(0xFFFF)
            out.writeShortLe(0xFFFF)
            out.writeIntLe(-1)
            out.writeIntLe(-1)
            out.writeShortLe(0)
        } else {
            out.writeIntLe(0x06054b50)
            out.writeShortLe(0)
            out.writeShortLe(0)
            out.writeShortLe(written.size)
            out.writeShortLe(written.size)
            out.writeIntLe(cdSize.toInt())
            out.writeIntLe(cdOffset.toInt())
            out.writeShortLe(0)
        }

        return out.readByteArray()
    }

    // --- FASE 5 (v0.8.0) ---

    @Test
    fun paginatedPrevChapterOpensAtLastSubPageAndNavigationHistoryWorks() {
        val pub = Publication(
            metadata = Metadata(title = "Livro Histórico", language = "pt-BR"),
            spine = listOf("ch1.xhtml", "ch2.xhtml", "ch3.xhtml"),
            resources = listOf(
                Resource("ch1.xhtml", "application/xhtml+xml"),
                Resource("ch2.xhtml", "application/xhtml+xml"),
                Resource("ch3.xhtml", "application/xhtml+xml"),
            ),
            toc = listOf(
                TocEntry("Capítulo 1", href = "ch1.xhtml"),
                TocEntry("Capítulo 2", href = "ch2.xhtml"),
                TocEntry("Capítulo 3", href = "ch3.xhtml"),
            ),
            provider = MemoryResourceProvider(
                mapOf(
                    "ch1.xhtml" to "<html><body><h1>Cap 1</h1><p>Texto um.</p></body></html>".encodeToByteArray(),
                    "ch2.xhtml" to "<html><body><h1>Cap 2</h1><p>Texto dois.</p></body></html>".encodeToByteArray(),
                    "ch3.xhtml" to "<html><body><h1>Cap 3</h1><p>Texto três.</p></body></html>".encodeToByteArray(),
                ),
            ),
        )
        val session = ReaderSession(
            publication = pub,
            initialBookState = InitialBookState(
                initialPageIndex = 1,
                readingMode = ReadingMode.PAGINATED,
            ),
        )

        // Retroceder capítulo no modo PAGINATED deve marcar data-reader-subpage="last"
        session.dispatch(ReaderCommand.PrevPage)
        assertEquals(0, session.state.value.pageIndex)
        val prevHtml = session.currentHtml()
        assertTrue(prevHtml.contains("data-reader-subpage=\"last\""))
        assertTrue(prevHtml.contains("lang=\"pt-BR\""))
        assertTrue(prevHtml.contains("goToLastSubPage"))

        // Próxima leitura do mesmo capítulo já não deve reabrir na última sub-página
        val secondReadHtml = session.currentHtml()
        assertFalse(secondReadHtml.contains("data-reader-subpage=\"last\""))

        // Histórico de navegação em saltos não-lineares (GoToPage)
        assertFalse(session.state.value.canNavigateBack)
        session.dispatch(ReaderCommand.GoToPage(pageIndex = 2, scrollY = 120))
        assertEquals(2, session.state.value.pageIndex)
        assertTrue(session.state.value.canNavigateBack)
        assertEquals(0, session.state.value.previousLocation?.pageIndex)
        assertEquals("Capítulo 1", session.state.value.previousLocation?.title)

        // Voltar no histórico restaura página 0 e habilita NavigateForward
        session.dispatch(ReaderCommand.NavigateBack)
        assertEquals(0, session.state.value.pageIndex)
        assertFalse(session.state.value.canNavigateBack)
        assertTrue(session.state.value.canNavigateForward)

        // Avançar no histórico retorna para página 2 e scroll 120
        session.dispatch(ReaderCommand.NavigateForward)
        assertEquals(2, session.state.value.pageIndex)
        assertEquals(120, session.state.value.scrollY)
        assertTrue(session.state.value.canNavigateBack)
        assertFalse(session.state.value.canNavigateForward)
    }

    @Test
    fun searchOccurrenceInPageAndNavigateToSearchResultEmitScrollAndHighlightJs() {
        val pub = Publication(
            metadata = Metadata(title = "Busca Destacada"),
            spine = listOf("ch1.xhtml", "ch2.xhtml"),
            resources = listOf(
                Resource("ch1.xhtml", "application/xhtml+xml"),
                Resource("ch2.xhtml", "application/xhtml+xml"),
            ),
            toc = listOf(
                TocEntry("Intro", href = "ch1.xhtml"),
                TocEntry("Esperança", href = "ch2.xhtml"),
            ),
            provider = MemoryResourceProvider(
                mapOf(
                    "ch1.xhtml" to "<html><body><p>Primeira página simples.</p></body></html>".encodeToByteArray(),
                    "ch2.xhtml" to "<html><body><p>A esperança renova. Nossa Esperança permanece viva.</p></body></html>".encodeToByteArray(),
                ),
            ),
        )
        val session = ReaderSession(pub)
        val matches = session.search("esperanca")
        assertEquals(2, matches.size)
        assertEquals(0, matches[0].occurrenceInPage)
        assertEquals(1, matches[1].occurrenceInPage)

        val effects = mutableListOf<ReaderUiEffect>()
        val sub = session.watchUiEffects { effects.add(it) }
        try {
            session.dispatch(
                ReaderCommand.NavigateToSearchResult(
                    query = "esperanca",
                    pageIndex = matches[1].pageIndex,
                    occurrenceInPage = matches[1].occurrenceInPage,
                ),
            )
            assertEquals(1, session.state.value.pageIndex)
            assertTrue(session.state.value.canNavigateBack)
            val eval = effects.filterIsInstance<ReaderUiEffect.EvaluateJs>().lastOrNull()
            assertNotNull(eval)
            assertTrue(eval.script.contains("scrollToSearchMatch(\"esperanca\",1)"))

            session.dispatch(ReaderCommand.ClearSearchHighlight)
            val clearEval = effects.filterIsInstance<ReaderUiEffect.EvaluateJs>().lastOrNull()
            assertNotNull(clearEval)
            assertTrue(clearEval.script.contains("clearSearchHighlight"))
        } finally {
            sub.cancel()
        }
    }

    @Test
    fun imageTapActionResolvesRelativeHrefAndEmitsImageActivatedEffect() {
        val imgBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val pub = Publication(
            metadata = Metadata(title = "Atlas"),
            spine = listOf("OEBPS/text/ch1.xhtml"),
            resources = listOf(
                Resource("OEBPS/text/ch1.xhtml", "application/xhtml+xml"),
                Resource("OEBPS/images/mapa.png", "image/png"),
            ),
            toc = listOf(TocEntry("Mapa", href = "OEBPS/text/ch1.xhtml")),
            provider = MemoryResourceProvider(
                mapOf(
                    "OEBPS/text/ch1.xhtml" to "<html><body><figure><img src=\"../images/mapa.png\" alt=\"Mapa antigo\"/><figcaption>Figura 1</figcaption></figure></body></html>".encodeToByteArray(),
                    "OEBPS/images/mapa.png" to imgBytes,
                ),
            ),
        )
        val session = ReaderSession(pub)
        val effects = mutableListOf<ReaderUiEffect>()
        val sub = session.watchUiEffects { effects.add(it) }
        try {
            session.dispatch(
                ReaderCommand.TriggerAction(
                    action = ReaderJsBridge.ACTION_IMAGE_TAP,
                    payload = """{"src":"../images/mapa.png","alt":"Mapa antigo","caption":"Figura 1"}""",
                ),
            )
            val activated = effects.filterIsInstance<ReaderUiEffect.ImageActivated>().singleOrNull()
            assertNotNull(activated)
            assertEquals("../images/mapa.png", activated.image.src)
            assertEquals("OEBPS/images/mapa.png", activated.image.resolvedHref)
            assertEquals("Mapa antigo", activated.image.alt)
            assertEquals("Figura 1", activated.image.caption)
            assertContentEquals(imgBytes, session.readResource(activated.image.resolvedHref))
        } finally {
            sub.cancel()
        }
    }

    @Test
    fun studyNotebookExporterGeneratesMarkdownHtmlAndBibliographicCitation() {
        val pub = Publication(
            metadata = Metadata(
                title = "Guia de Estudos",
                authors = listOf("Maria Silva"),
            ),
            spine = listOf("ch1.xhtml"),
            resources = listOf(Resource("ch1.xhtml", "application/xhtml+xml")),
            toc = listOf(TocEntry("Lição 1", href = "ch1.xhtml")),
            pageList = listOf(TocEntry("42", href = "ch1.xhtml")),
            provider = MemoryResourceProvider(
                mapOf("ch1.xhtml" to "<html><body><p>A prática constante traz clareza.</p></body></html>".encodeToByteArray()),
            ),
        )
        val session = ReaderSession(pub)
        session.onPageHighlightsReported(
            rawSerializedHighlights = "type:textContent|1\$highlight_yellow\$A prática constante traz clareza.",
            pageIndex = 0,
        )
        session.dispatch(ReaderCommand.UpdateHighlightNote(highlightId = 1, note = "Aplicar no estudo diário"))
        session.onPageFormReported(
            rawModificationsJson = """{"annotations":[{"content":"Revisar sexta","index":0}],"answers":[{"content":"Sim, diariamente","index":0}]}""",
            pageIndex = 0,
        )
        session.dispatch(ReaderCommand.ToggleBookmark(label = "Ponto importante"))

        val highlight = session.state.value.currentPageHighlights.single()
        val citation = session.formatQuoteCitation(highlight)
        assertTrue(citation.contains("«A prática constante traz clareza.»"))
        assertTrue(citation.contains("Guia de Estudos"))
        assertTrue(citation.contains("Maria Silva"))
        assertTrue(citation.contains("Lição 1"))
        assertTrue(citation.contains("p. 42"))
        assertTrue(citation.contains("Aplicar no estudo diário"))

        val markdown = session.exportStudyNotebookMarkdown()
        assertTrue(markdown.contains("# Guia de Estudos"))
        assertTrue(markdown.contains("## Lição 1 (p. 42)"))
        assertTrue(markdown.contains("> A prática constante traz clareza."))
        assertTrue(markdown.contains("**Nota:** Aplicar no estudo diário"))
        assertTrue(markdown.contains("**Q1:** Sim, diariamente"))
        assertTrue(markdown.contains("Revisar sexta"))
        assertTrue(markdown.contains("Ponto importante"))

        val html = session.exportStudyNotebookHtml()
        assertTrue(html.contains("<!doctype html>"))
        assertTrue(html.contains("@media print"))
        assertTrue(html.contains("<blockquote>A prática constante traz clareza.</blockquote>"))
        assertTrue(html.contains("Sim, diariamente"))
    }

    @Test
    fun typographyHyphenationWidowsOrphansAndSearchMatchCssGenerated() {
        val base = ThemeTokens.material3()
        val cssDefault = base.toContentCss()
        assertTrue(cssDefault.contains("hyphens:auto;-webkit-hyphens:auto;"))
        assertTrue(cssDefault.contains("orphans:2;widows:2;"))
        assertTrue(cssDefault.contains(".reader-search-match"))

        val noHyphens = base.copy(
            typography = base.typography.copy(hyphens = false, orphans = 3, widows = 3),
        )
        val cssDisabled = noHyphens.toContentCss()
        assertTrue(cssDisabled.contains("hyphens:none;-webkit-hyphens:none;"))
        assertTrue(cssDisabled.contains("orphans:3;widows:3;"))
    }

    @Test
    fun fb2ParserParsesMetadataCoverChaptersAndFootnotesInPlainAndZippedFormats() {
        // 1x1 PNG mínimo em Base64
        val pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val fb2Xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
              <description>
                <title-info>
                  <author><first-name>Machado</first-name><last-name>de Assis</last-name></author>
                  <book-title>Dom Casmurro</book-title>
                  <lang>pt-BR</lang>
                  <coverpage><image l:href="#cover.png"/></coverpage>
                </title-info>
                <document-info>
                  <id>fb2-dom-casmurro-1</id>
                </document-info>
              </description>
              <body>
                <section>
                  <title><p>Capítulo I</p></title>
                  <p>Uma noite destas, vindo da <emphasis>cidade</emphasis> para o Engenho Novo<a l:href="#n1" type="note">[1]</a>.</p>
                </section>
                <section>
                  <title><p>Capítulo II</p></title>
                  <p>Agora que expliquei o <strong>título</strong>, passo a escrever o livro.</p>
                </section>
              </body>
              <body name="notes">
                <section id="n1">
                  <p>Bairro do Rio de Janeiro.</p>
                </section>
              </body>
              <binary id="cover.png" content-type="image/png">$pngBase64</binary>
            </FictionBook>
        """.trimIndent()

        val registry = ParserRegistry.default()
        val pub = registry.open(Source.fromBytes("dom-casmurro.fb2", fb2Xml.encodeToByteArray()))
        assertEquals("Dom Casmurro", pub.metadata.title)
        assertEquals(listOf("Machado de Assis"), pub.metadata.authors)
        assertEquals("pt-BR", pub.metadata.language)
        assertEquals("fb2-dom-casmurro-1", pub.metadata.identifier)
        assertEquals("images/cover.png", pub.metadata.coverHref)
        assertEquals(2, pub.spine.size)
        assertEquals("Capítulo I", pub.toc[0].title)
        assertEquals("Capítulo II", pub.toc[1].title)

        val session = ReaderSession(pub)
        val coverBytes = session.readCoverBytes()
        assertNotNull(coverBytes)
        assertTrue(coverBytes.size > 20)

        val ch1Html = session.currentHtml()
        assertTrue(ch1Html.contains("<em>cidade</em>"))
        assertTrue(ch1Html.contains("epub:type=\"footnote\""))
        assertTrue(ch1Html.contains("Bairro do Rio de Janeiro."))

        // Footnote popup resolution funciona nativamente no FB2
        val effects = mutableListOf<ReaderUiEffect>()
        val sub = session.watchUiEffects { effects.add(it) }
        try {
            assertTrue(session.handleUrlNavigation("#n1"))
            val noteEffect = effects.filterIsInstance<ReaderUiEffect.OpenCustomUri>().singleOrNull()
            assertNotNull(noteEffect)
            assertEquals("footnote", noteEffect.scheme)
            assertTrue(noteEffect.content.orEmpty().contains("Bairro do Rio de Janeiro."))
        } finally {
            sub.cancel()
        }

        // Também suporta .fb2.zip
        val zippedFb2 = buildStoredZip(
            entries = mapOf("book.fb2" to fb2Xml.encodeToByteArray()),
            asZip64 = false,
        )
        val pubFromZip = registry.open(Source.fromBytes("dom-casmurro.fb2.zip", zippedFb2))
        assertEquals("Dom Casmurro", pubFromZip.metadata.title)
        assertEquals(2, pubFromZip.spine.size)
    }
}


