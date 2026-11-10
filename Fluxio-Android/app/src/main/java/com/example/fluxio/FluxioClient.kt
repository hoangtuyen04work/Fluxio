package com.example.fluxio

import android.content.Context
import android.net.Uri
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
