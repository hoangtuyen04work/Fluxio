package com.example.fluxio.transfer.persistence;

import com.example.fluxio.transfer.domain.UploadState;
import java.time.Instant;
import java.util.UUID;

public record UploadSessionRecord(
        UUID id,
        UUID fileId,
        String ownerId,
        String objectKey,
        String storageUploadId,
        String requestHash,
        String fileName,
        String contentType,
        long declaredSizeBytes,
        Long partSizeBytes,
        Integer partCount,
        UploadState state,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        UploadStrategy strategy) {

    public UploadSessionRecord(
            UUID id, UUID fileId, String ownerId, String objectKey, String storageUploadId,
            String requestHash, String fileName, String contentType, long declaredSizeBytes,
            long partSizeBytes, int partCount, UploadState state, Instant expiresAt,
            Instant createdAt, Instant updatedAt) {
        this(id, fileId, ownerId, objectKey, storageUploadId, requestHash, fileName, contentType,
                declaredSizeBytes, partSizeBytes, partCount, state, expiresAt, createdAt, updatedAt,
                UploadStrategy.MULTIPART);
    }
}
