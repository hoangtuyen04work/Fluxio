package com.example.fluxio.transfer.upload

import com.example.fluxio.data.remote.CompletedPart
import com.example.fluxio.data.remote.SignedPart
import java.io.InputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

fun interface RangeSource {
    fun open(offsetBytes: Long, lengthBytes: Long): InputStream
}

class StreamingPartUploader(
    private val client: OkHttpClient,
    private val source: RangeSource
) : PartUploader {
    override suspend fun upload(signed: SignedPart, range: PartRange): CompletedPart {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = range.sizeBytes
            override fun writeTo(sink: BufferedSink) {
                source.open(range.offsetBytes, range.sizeBytes).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = range.sizeBytes
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(count >= 0) { "Source ended before the planned part range" }
                        sink.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
        }
        val request = Request.Builder().url(signed.url).put(body).apply {
            signed.requiredHeaders.forEach { (name, value) -> header(name, value) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1_000)
                throw PartUploadException(response.code, retryAfter)
            }
            val etag = checkNotNull(response.header("ETag")) { "Part response did not include ETag" }
                .replace("\"", "")
            return CompletedPart(range.partNumber, etag, range.sizeBytes, response.header("x-amz-checksum-sha256"))
        }
    }
}
