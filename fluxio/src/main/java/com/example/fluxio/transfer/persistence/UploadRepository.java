package com.example.fluxio.transfer.persistence;

import java.time.Instant;
import java.util.UUID;

public interface UploadRepository {
    UploadSessionRecord reserveAndCreateInitializing(CreateUploadRecord command);

    boolean activate(UUID uploadId, String storageUploadId, Instant expiresAt);

    boolean convertReservationToActive(UUID uploadId);

    boolean releaseReservation(UUID uploadId);
}
