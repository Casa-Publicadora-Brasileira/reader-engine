package com.readerengine.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReaderResourceWebViewClientTest {

    @Test
    fun parseByteRangeHeaderHandlesOpenEndedAndCapsAtOneMegabyte() {
        val result = parseByteRangeHeader(
            rangeHeader = "bytes=0-",
            totalSize = 5L * 1024L * 1024L,
        )
        val valid = assertIs<ByteRangeParseResult.Valid>(result)
        assertEquals(0L, valid.slice.start)
        assertEquals(1024L * 1024L - 1L, valid.slice.endInclusive)
        assertEquals(1024 * 1024, valid.slice.length)
        assertEquals("bytes 0-1048575/5242880", valid.slice.contentRangeHeader)
    }

    @Test
    fun parseByteRangeHeaderHandlesBoundedAndClampsToTotalSize() {
        val bounded = parseByteRangeHeader(rangeHeader = "bytes=100-499", totalSize = 1000L)
        val boundedSlice = assertIs<ByteRangeParseResult.Valid>(bounded).slice
        assertEquals(100L, boundedSlice.start)
        assertEquals(499L, boundedSlice.endInclusive)
        assertEquals(400, boundedSlice.length)

        val clamped = parseByteRangeHeader(rangeHeader = "BYTES=800-5000", totalSize = 1000L)
        val clampedSlice = assertIs<ByteRangeParseResult.Valid>(clamped).slice
        assertEquals(800L, clampedSlice.start)
        assertEquals(999L, clampedSlice.endInclusive)
        assertEquals(200, clampedSlice.length)
    }

    @Test
    fun parseByteRangeHeaderDetectsUnsatisfiableAndInvalidRanges() {
        val pastEnd = parseByteRangeHeader(rangeHeader = "bytes=1000-", totalSize = 1000L)
        val unsatisfiablePast = assertIs<ByteRangeParseResult.Unsatisfiable>(pastEnd)
        assertEquals(1000L, unsatisfiablePast.totalSize)

        val reversed = parseByteRangeHeader(rangeHeader = "bytes=500-100", totalSize = 1000L)
        assertIs<ByteRangeParseResult.Unsatisfiable>(reversed)

        assertEquals(ByteRangeParseResult.Invalid, parseByteRangeHeader(null, 1000L))
        assertEquals(ByteRangeParseResult.Invalid, parseByteRangeHeader("items=0-10", 1000L))
        assertEquals(ByteRangeParseResult.Invalid, parseByteRangeHeader("bytes=-50", 1000L))
        assertEquals(ByteRangeParseResult.Invalid, parseByteRangeHeader("bytes=0-", 0L))
    }

    @Test
    fun guessReaderMimeTypeMapsExtensionsAndStripsFragmentsOrQueries() {
        assertEquals("application/xhtml+xml", guessReaderMimeType("OEBPS/ch1.xhtml#sec2"))
        assertEquals("video/mp4", guessReaderMimeType("media/clip.mp4?t=10"))
        assertEquals("font/woff2", guessReaderMimeType("fonts/Inter.WOFF2"))
        assertEquals("audio/mpeg", guessReaderMimeType("audio/track01.mp3"))
        assertEquals("application/xml", guessReaderMimeType("overlay/ch1.smil"))
        assertEquals("application/octet-stream", guessReaderMimeType("data/unknown.bin"))
    }
}
