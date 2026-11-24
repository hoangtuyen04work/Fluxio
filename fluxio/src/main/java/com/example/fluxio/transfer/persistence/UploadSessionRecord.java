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
        long partSizeBytes,
        int partCount,
        UploadState state,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {}
