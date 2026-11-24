package com.example.fluxio.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface TransferApi {
    @POST("uploads")
    suspend fun createUpload(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateUploadRequest
    ): UploadSession

    @POST("uploads/{uploadId}/part-urls")
    suspend fun signParts(
        @Path("uploadId") uploadId: String,
        @Body request: SignPartsRequest
    ): SignedParts

    @GET("uploads/{uploadId}/parts")
    suspend fun listParts(@Path("uploadId") uploadId: String): UploadedParts

    @POST("uploads/{uploadId}/complete")
    suspend fun completeUpload(
        @Path("uploadId") uploadId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CompleteUploadRequest
    ): FileMetadata

    @DELETE("uploads/{uploadId}")
    suspend fun abortUpload(@Path("uploadId") uploadId: String)

    @POST("files/{fileId}/download-ticket")
    suspend fun createDownloadTicket(@Path("fileId") fileId: String): DownloadTicket
}
