package com.example.fluxio.transfer.download

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class StreamingRangeDownloader(
    private val client: OkHttpClient,
    private val bufferSize: Int = 64 * 1024
) : RangeDownloader {
    init { require(bufferSize > 0) }

    override suspend fun transfer(
        url: String,
        startOffset: Long,
        expectedBytes: Long,
        write: suspend (ByteArray, Int) -> Unit,
        progress: suspend (Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            if (startOffset > 0) header("Range", "bytes=$startOffset-")
        }.build()
        client.newCall(request).execute().use { response ->
            val expectedStatus = if (startOffset > 0) 206 else 200
            if (response.code != expectedStatus && !(startOffset == 0L && response.code == 206)) {
                throw DownloadHttpException(response.code)
            }
            val body = response.body ?: throw IOException("Download response has no body")
            val buffer = ByteArray(bufferSize)
            var received = 0L
            body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    write(buffer, count)
                    received += count
                    progress(count.toLong())
                }
            }
            if (received != expectedBytes) {
                throw IOException("Expected $expectedBytes bytes but received $received")
            }
            received
        }
    }
}

class DownloadHttpException(val status: Int) : IOException("Download failed with HTTP $status")
