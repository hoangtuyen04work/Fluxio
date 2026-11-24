package com.example.fluxio.data.remote

data class CreateUploadRequest(val fileName: String, val contentType: String?, val sizeBytes: Long)
data class UploadSession(
    val uploadId: String,
    val fileId: String,
    val state: String,
    val sizeBytes: Long,
    val partSizeBytes: Long,
    val partCount: Int,
    val expiresAt: String
)
data class SignPartsRequest(val partNumbers: List<Int>)
data class SignedPart(val partNumber: Int, val url: String, val requiredHeaders: Map<String, String> = emptyMap())
data class SignedParts(val uploadId: String, val expiresAt: String, val parts: List<SignedPart>)
data class CompletedPart(
    val partNumber: Int,
    val etag: String,
    val sizeBytes: Long,
    val checksumSha256: String? = null
)
data class UploadedParts(val uploadId: String, val parts: List<CompletedPart>)
data class CompleteUploadRequest(val parts: List<CompletedPart>)
data class FileMetadata(
    val fileId: String,
    val fileName: String,
    val contentType: String?,
    val sizeBytes: Long,
    val objectVersion: String,
    val createdAt: String
)
data class DownloadTicket(
    val file: FileMetadata,
    val url: String,
    val expiresAt: String,
    val rangeSupported: Boolean
)
data class TransferProblem(val status: Int, val code: String, val title: String, val detail: String? = null)
