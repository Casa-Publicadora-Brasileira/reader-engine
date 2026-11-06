package com.cpb.reader.engine

import com.cpb.reader.engine.bridge.FirestorePayloadCodec
import com.cpb.reader.engine.bridge.ReaderJsBridge
import com.cpb.reader.engine.model.HighlightStyle
import com.cpb.reader.engine.model.InitialBookState
import com.cpb.reader.engine.session.ReaderCommand
import com.cpb.reader.engine.session.ReaderEngine
import com.cpb.reader.engine.session.ReaderEvent
import com.cpb.reader.engine.session.ReaderUiEffect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
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
        )
        val path = candidates.firstOrNull { FileSystem.SYSTEM.exists(it) }
            ?: error("Fixture not found: $name")
        return FileSystem.SYSTEM.read(path) { readByteArray() }
    }

    @Test
    fun parsesJsonFixtureAndNavigates() = runTest {
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

        session.dispatch(ReaderCommand.NextPage)
        assertEquals(1, session.state.value.pageIndex)
        assertTrue(events.any { it is ReaderEvent.ProgressUpdated && it.progress.pageIndex == 1 })

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

        job.cancel()
    }

    @Test
    fun parsesEpubFixtureAndReadsResources() = runTest {
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

        val firstHref = session.publication().spine.first()
        val ch1Bytes = session.readResource(firstHref)
        assertTrue(ch1Bytes.decodeToString().isNotBlank())
        assertEquals("application/xhtml+xml", session.resourceMediaType(firstHref))
    }

    @Test
    fun parsesHpubFixtureAndRestoresInitialState() = runTest {
        val bytes = readFixture("sample.hpub")
        val initialHighlights = mapOf(
            0 to "type:textContent|10\$25\$1\$yellow\$",
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
            ),
        )

        val state = session.state.value
        assertEquals("HPUB Fixture", state.metadata.title)
        assertEquals(1, state.pageIndex)
        assertEquals(240, state.progress.scrollY)

        // Go back to page 0 and verify stored highlights and forms are restored
        session.dispatch(ReaderCommand.GoToPage(0))
        assertEquals("type:textContent|10\$25\$1\$yellow\$", session.state.value.currentPageSerializedHighlights)
        assertEquals(1, session.state.value.currentPageFormState.annotations.size)
        assertEquals("Minha nota", session.state.value.currentPageFormState.annotations[0].content)
        assertEquals("Resposta 1", session.state.value.currentPageFormState.answers[0].content)
    }

    @Test
    fun emitsHighlightAndFormEventsForHostPersistence() = runTest {
        val bytes = readFixture("sample.hpub")
        val session = engine.open(
            name = "sample.hpub",
            bytes = bytes,
            initialState = InitialBookState(documentId = "licao-01"),
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
            rawSerializedHighlights = "type:textContent|5\$15\$1\$green\$",
            rawSummaryJson = """[{"id":1,"text":"No princípio","cor":"green","pos":640.0}]""",
        )

        val highlightEvent = events.filterIsInstance<ReaderEvent.PageHighlightsChanged>().lastOrNull()
        assertNotNull(highlightEvent)
        assertEquals("licao-01", highlightEvent.documentId)
        assertEquals(0, highlightEvent.pageIndex)
        assertEquals("type:textContent|5\$15\$1\$green\$", highlightEvent.rawSerializedHighlights)

        val summary = session.state.value.currentPageHighlights
        assertEquals(1, summary.size)
        assertEquals(HighlightStyle.GREEN, summary[0].style)
        assertEquals("No princípio", summary[0].text)

        // Navigate to highlight -> should scroll to pos - 300 = 340
        session.dispatch(ReaderCommand.SelectHighlightItem(summary[0]))
        val scrollEffect = effects.filterIsInstance<ReaderUiEffect.ScrollToY>().lastOrNull()
        assertNotNull(scrollEffect)
        assertEquals(340, scrollEffect.scrollYPx)

        // Simulate WebView callback for Escola Sabatina form inputs
        val rawFormJson = """{"annotations":[{"content":"Nota sabatina","index":0}],"answers":[{"content":"Sim","index":0}]}"""
        session.onPageFormReported(rawFormJson)

        val formEvent = events.filterIsInstance<ReaderEvent.PageFormChanged>().lastOrNull()
        assertNotNull(formEvent)
        assertEquals("licao-01", formEvent.documentId)
        assertEquals(0, formEvent.pageIndex)
        assertEquals(rawFormJson, formEvent.rawJsonPayload)
        assertEquals("Nota sabatina", formEvent.formState.annotations[0].content)

        // Intercept verse:// URL
        val intercepted = session.handleUrlNavigation("verse://?q=Joao+3:16")
        assertTrue(intercepted)
        val verseEffect = effects.filterIsInstance<ReaderUiEffect.OpenVerseModal>().lastOrNull()
        assertNotNull(verseEffect)
        assertEquals("Joao 3:16", verseEffect.referenceTitle)

        eventJob.cancel()
        effectJob.cancel()
    }

    @Test
    fun firestorePayloadCodecCompressesLargePayloadsAndRoundTrips() {
        val small = "type:textContent|1\$10\$1\$yellow\$"
        val encodedSmall = FirestorePayloadCodec.encodeIfNeeded(small)
        assertFalse(encodedSmall.startsWith(FirestorePayloadCodec.GZIP_PREFIX))
        assertEquals(small, FirestorePayloadCodec.decodeIfNeeded(encodedSmall))

        val large = "type:textContent|100\$200\$1\$yellow\$|".repeat(8_000)
        assertTrue(large.encodeToByteArray().size > FirestorePayloadCodec.DEFAULT_COMPRESS_THRESHOLD_BYTES)
        val encodedLarge = FirestorePayloadCodec.encodeIfNeeded(large)
        assertTrue(encodedLarge.startsWith(FirestorePayloadCodec.GZIP_PREFIX))
        assertEquals(large, FirestorePayloadCodec.decodeIfNeeded(encodedLarge))
    }

    @Test
    fun readerJsBridgeGeneratesCompatibleScripts() {
        assertTrue(
            ReaderJsBridge.highlightSelectionCommand(HighlightStyle.YELLOW)
                .contains("readerJS.highlightSelection('yellow')"),
        )
        assertTrue(
            ReaderJsBridge.setNightModeCommand(true)
                .contains("nightMode(true)"),
        )
    }
}
