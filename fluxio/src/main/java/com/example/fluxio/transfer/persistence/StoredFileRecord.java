package com.example.fluxio.transfer.persistence;

import java.time.Instant;
import java.util.UUID;

public record StoredFileRecord(
        UUID fileId,
        UUID sourceUploadId,
        String ownerId,
        String objectKey,
        String fileName,
        String contentType,
        long sizeBytes,
        String objectVersion,
        String objectEtag,
        Instant createdAt) {}
