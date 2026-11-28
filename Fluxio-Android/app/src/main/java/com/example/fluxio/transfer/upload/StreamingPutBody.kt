package com.example.fluxio.transfer.upload

import java.io.InputStream
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

class StreamingPutBody(
    private val length: Long,
    private val mediaType: MediaType,
    private val open: () -> InputStream,
    private val progress: (Long) -> Unit = {}
) : RequestBody() {
    init { require(length >= 0) }
    override fun contentType() = mediaType
    override fun contentLength() = length
    override fun writeTo(sink: BufferedSink) {
        open().use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            var sent = 0L
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count >= 0) { "Source ended before declared size" }
                sink.write(buffer, 0, count)
                remaining -= count
                sent += count
                progress(sent)
            }
        }
    }
}
