package com.example.fluxio.transfer.persistence;

import java.time.Instant;
import java.util.UUID;

public record CreateSingleUploadRecord(
        UUID id,
        UUID fileId,
        String ownerId,
        String objectKey,
        String requestHash,
        String fileName,
        String contentType,
        long declaredSizeBytes,
        Instant expiresAt) {}
