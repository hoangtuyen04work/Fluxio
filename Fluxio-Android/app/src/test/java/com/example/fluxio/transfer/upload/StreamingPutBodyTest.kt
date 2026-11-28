package com.example.fluxio.transfer.upload

import java.io.ByteArrayInputStream
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingPutBodyTest {
    @Test
    fun streamsExactBytesFromOffsetZeroWithBoundedReads() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        var opens = 0
        val body = StreamingPutBody(
            length = payload.size.toLong(),
            mediaType = "application/octet-stream".toMediaType(),
            open = { opens++; ByteArrayInputStream(payload) }
        )
        val sink = Buffer()

        body.writeTo(sink)

        assertEquals(payload.size.toLong(), body.contentLength())
        assertEquals(1, opens)
        assertArrayEquals(payload, sink.readByteArray())
    }
}
