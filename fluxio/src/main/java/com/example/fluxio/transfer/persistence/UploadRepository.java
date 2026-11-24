package com.example.fluxio.transfer.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface UploadRepository {
    UploadSessionRecord reserveAndCreateInitializing(CreateUploadRecord command);

    boolean activate(UUID uploadId, String storageUploadId, Instant expiresAt);

    boolean convertReservationToActive(UUID uploadId);

    boolean releaseReservation(UUID uploadId);

    Optional<UploadSessionRecord> findByOwner(UUID uploadId, String ownerId);

    default boolean claimCompletion(UUID uploadId, String ownerId) { return false; }

    default StoredFileRecord finalizeCompletion(UUID uploadId, String objectEtag, String objectVersion) {
        throw new UnsupportedOperationException();
    }

    default Optional<StoredFileRecord> findFileByUpload(UUID uploadId, String ownerId) {
        return Optional.empty();
    }

    default boolean claimAbort(UUID uploadId, String ownerId) { return false; }

    default List<UploadSessionRecord> findExpired(Instant now, int limit) { return List.of(); }

    default boolean claimExpiry(UUID uploadId) { return false; }

    default Optional<StoredFileRecord> findFileById(UUID fileId, String ownerId) { return Optional.empty(); }
}
