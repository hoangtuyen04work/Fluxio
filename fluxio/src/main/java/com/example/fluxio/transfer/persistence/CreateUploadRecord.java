package com.example.fluxio.transfer.persistence;

import java.time.Instant;
import java.util.UUID;

public record CreateUploadRecord(
        UUID id,
        UUID fileId,
        String ownerId,
        String objectKey,
        String requestHash,
        long declaredSizeBytes,
        long partSizeBytes,
        int partCount,
        Instant expiresAt) {}
