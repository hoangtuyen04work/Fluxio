package com.example.fluxio.transfer.download

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingRangeDownloaderTest {
    @Test
    fun streamsA206ResponseInBoundedBuffersAndSendsRange() = runBlocking {
        val captured = AtomicReference<Request>()
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured.set(chain.request())
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(206).message("Partial Content")
                .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }.build()
        val output = java.io.ByteArrayOutputStream()
        var progress = 0L

        val count = StreamingRangeDownloader(client, 8 * 1024).transfer(
            "https://storage.test/file", 50, payload.size.toLong(),
            { bytes, size -> output.write(bytes, 0, size) },
            { progress += it })

        assertEquals("bytes=50-", captured.get().header("Range"))
        assertEquals(payload.size.toLong(), count)
        assertEquals(payload.size.toLong(), progress)
        assertArrayEquals(payload, output.toByteArray())
    }
}
