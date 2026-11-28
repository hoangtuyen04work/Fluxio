package com.example.fluxio

import android.content.Context
import android.net.Uri
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okio.BufferedSink
import android.provider.OpenableColumns
import org.json.JSONArray
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class FluxioClient(private val context: Context, private val endpoint: HttpUrl) {
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun login(username: String, password: String): String {
        val body = JSONObject().put("username", username).put("password", password)
            .toString().toRequestBody("application/json".toMediaType())
        return json(Request.Builder().url(api("auth", "login")).post(body).build())
            .getString("accessToken")
    }

    fun upload(token: String, source: Uri): UUID {
        val ticket = json(
            authenticated(api("mvp", "files"), token)
                .post(ByteArray(0).toRequestBody()).build()
        )
        val id = UUID.fromString(ticket.getString("fileId"))
        val encrypted = File.createTempFile("fluxio-", ".enc", context.cacheDir)
        try {
            val input = checkNotNull(context.contentResolver.openInputStream(source)) {
                "Could not open the selected file."
            }
            input.use { content ->
                encrypted.outputStream().buffered().use { output ->
                    EncryptedFile.encrypt(id, content, output, DeviceKeys.wrappingKey(create = true))
                }
            }
            val body = encrypted.asRequestBody("application/octet-stream".toMediaType())
            execute(Request.Builder().url(ticket.getString("url")).put(body).build()) {}
            return id
        } finally {
            encrypted.delete()
        }
    }

    fun uploadSingle(token: String, source: Uri): UUID {
        val metadata = sourceMetadata(source)
        val requestJson = JSONObject()
            .put("fileName", metadata.name)
            .put("contentType", metadata.contentType)
            .put("sizeBytes", metadata.size)
        val ticket = json(authenticated(api("single-uploads"), token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post(requestJson.toString().toRequestBody("application/json".toMediaType())).build())
        val put = ticket.getJSONObject("put")
        execute(Request.Builder().url(put.getString("url"))
            .put(uriBody(source, 0, metadata.size, metadata.contentType)).build()) {}
        val uploadId = ticket.getString("uploadId")
        val completed = json(authenticated(api("single-uploads", uploadId, "complete"), token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post(ByteArray(0).toRequestBody()).build())
        return UUID.fromString(completed.getString("fileId"))
    }

    fun uploadMultipart(token: String, source: Uri): UUID {
        val metadata = sourceMetadata(source)
        val create = JSONObject().put("fileName", metadata.name)
            .put("contentType", metadata.contentType).put("sizeBytes", metadata.size)
        val session = json(authenticated(api("uploads"), token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post(create.toString().toRequestBody("application/json".toMediaType())).build())
        val uploadId = session.getString("uploadId")
        val partSize = session.getLong("partSizeBytes")
        val partCount = session.getInt("partCount")
        val completedParts = JSONArray()
        for (first in 1..partCount step 32) {
            val numbers = JSONArray()
            (first..minOf(partCount, first + 31)).forEach(numbers::put)
            val signed = json(authenticated(api("uploads", uploadId, "part-urls"), token)
                .post(JSONObject().put("partNumbers", numbers).toString()
                    .toRequestBody("application/json".toMediaType())).build()).getJSONArray("parts")
            for (index in 0 until signed.length()) {
                val part = signed.getJSONObject(index)
                val number = part.getInt("partNumber")
                val offset = (number - 1L) * partSize
                val length = minOf(partSize, metadata.size - offset)
                var etag = ""
                execute(Request.Builder().url(part.getString("url"))
                    .put(uriBody(source, offset, length, metadata.contentType)).build()) { response ->
                    etag = checkNotNull(response.header("ETag")).replace("\"", "")
                }
                completedParts.put(JSONObject().put("partNumber", number)
                    .put("etag", etag).put("sizeBytes", length))
            }
        }
        val result = json(authenticated(api("uploads", uploadId, "complete"), token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post(JSONObject().put("parts", completedParts).toString()
                .toRequestBody("application/json".toMediaType())).build())
        return UUID.fromString(result.getString("fileId"))
    }

    private data class SourceMetadata(val name: String, val contentType: String, val size: Long)

    private fun sourceMetadata(source: Uri): SourceMetadata {
        var name = "upload.bin"
        var size = -1L
        context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!cursor.isNull(it)) size = cursor.getLong(it) }
            }
        }
        require(size > 0) { "The selected file must report an exact positive size." }
        return SourceMetadata(name, context.contentResolver.getType(source) ?: "application/octet-stream", size)
    }

    private fun uriBody(source: Uri, offset: Long, length: Long, contentType: String) = object : RequestBody() {
        override fun contentType() = contentType.toMediaType()
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            val input = checkNotNull(context.contentResolver.openInputStream(source))
            input.use {
                var skipped = 0L
                while (skipped < offset) {
                    val count = it.skip(offset - skipped)
                    check(count > 0) { "Source cannot seek to upload offset" }
                    skipped += count
                }
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val count = it.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(count >= 0) { "Source ended before declared size" }
                    sink.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    }

    fun download(token: String, id: UUID, destination: Uri) {
        val key = DeviceKeys.wrappingKey(create = false)
        val ticket = json(authenticated(api("mvp", "files", id.toString()), token).build())
        val encrypted = File.createTempFile("fluxio-", ".enc", context.cacheDir)
        try {
            execute(Request.Builder().url(ticket.getString("url")).build()) { response ->
                val body = checkNotNull(response.body)
                require(body.contentLength() <= EncryptedFile.maxEncodedBytes) {
                    "Encrypted file exceeds the size limit."
                }
                body.byteStream().use { source ->
                    encrypted.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytes = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            bytes += count
                            require(bytes <= EncryptedFile.maxEncodedBytes) {
                                "Encrypted file exceeds the size limit."
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            // Verify the final frame before writing anything to the selected document.
            encrypted.inputStream().buffered().use { EncryptedFile.decrypt(id, it, null, key) }
            val output = checkNotNull(context.contentResolver.openOutputStream(destination, "wt")) {
                "Could not open the destination file."
            }
            output.use { target ->
                encrypted.inputStream().buffered().use { EncryptedFile.decrypt(id, it, target, key) }
            }
        } finally {
            encrypted.delete()
        }
    }

    private fun api(vararg segments: String): HttpUrl = endpoint.newBuilder()
        .addPathSegments("api/v1").apply { segments.forEach { addPathSegment(it) } }.build()

    private fun authenticated(url: HttpUrl, token: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    private fun json(request: Request): JSONObject = execute(request) {
        JSONObject(checkNotNull(it.body).string())
    }

    private fun <T> execute(request: Request, read: (Response) -> T): T =
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Request failed (HTTP ${response.code}).")
            read(response)
        }
}
