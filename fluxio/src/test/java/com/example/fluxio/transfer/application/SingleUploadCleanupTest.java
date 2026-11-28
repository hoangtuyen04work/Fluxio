package com.example.fluxio.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.fluxio.transfer.domain.UploadState;
import com.example.fluxio.transfer.persistence.CreateUploadRecord;
import com.example.fluxio.transfer.persistence.StoredFileRecord;
import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.persistence.UploadSessionRecord;
import com.example.fluxio.transfer.persistence.UploadStrategy;
import com.example.fluxio.transfer.storage.MultipartObjectStore;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SingleUploadCleanupTest {
    private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");

    @Test
    void promotesExactSizeObjectInsteadOfDeletingUploadedData() {
        var repository = new CleanupRepository();
        var storage = new CleanupStorage(100);
        var cleanup = new UploadCleanupJob(repository, storage, Clock.fixed(NOW, ZoneOffset.UTC), 10);

        assertThat(cleanup.runBatch()).isEqualTo(1);
        assertThat(repository.state).isEqualTo(UploadState.COMPLETED);
        assertThat(storage.deleted).isFalse();
    }

    @Test
    void expiresAndDeletesMismatchedObject() {
        var repository = new CleanupRepository();
        var storage = new CleanupStorage(99);
        var cleanup = new UploadCleanupJob(repository, storage, Clock.fixed(NOW, ZoneOffset.UTC), 10);

        assertThat(cleanup.runBatch()).isEqualTo(1);
        assertThat(repository.state).isEqualTo(UploadState.EXPIRED);
        assertThat(storage.deleted).isTrue();
    }

    @Test
    void leavesSessionReservedWhenStorageLookupFails() {
        var repository = new CleanupRepository();
        var storage = new CleanupStorage(100);
        storage.lookupFailure = new IllegalStateException("storage unavailable");
        var cleanup = new UploadCleanupJob(repository, storage, Clock.fixed(NOW, ZoneOffset.UTC), 10);

        assertThatThrownBy(cleanup::runBatch).isInstanceOf(IllegalStateException.class);
        assertThat(repository.state).isEqualTo(UploadState.ACTIVE);
    }

    private static final class CleanupRepository implements UploadRepository {
        private final UploadSessionRecord session = new UploadSessionRecord(UUID.randomUUID(), UUID.randomUUID(),
                "alice", "object", null, "hash", "x.bin", "application/octet-stream", 100,
                null, null, UploadState.ACTIVE, NOW.minusSeconds(1), NOW.minusSeconds(10), NOW.minusSeconds(10),
                UploadStrategy.SINGLE_PUT);
        private UploadState state = UploadState.ACTIVE;
        @Override public List<UploadSessionRecord> findExpired(Instant now, int limit) { return List.of(session); }
        @Override public boolean claimCompletion(UUID id, String owner) { state = UploadState.COMPLETING; return true; }
        @Override public StoredFileRecord finalizeCompletion(UUID id, String etag, String version) {
            state = UploadState.COMPLETED;
            return new StoredFileRecord(session.fileId(), session.id(), session.ownerId(), session.objectKey(),
                    session.fileName(), session.contentType(), 100, version, etag, NOW);
        }
        @Override public boolean claimExpiry(UUID id) { state = UploadState.EXPIRED; return true; }
        @Override public UploadSessionRecord reserveAndCreateInitializing(CreateUploadRecord command) { throw new UnsupportedOperationException(); }
        @Override public boolean activate(UUID id, String storageId, Instant expiresAt) { return false; }
        @Override public boolean convertReservationToActive(UUID id) { return false; }
        @Override public boolean releaseReservation(UUID id) { return false; }
        @Override public Optional<UploadSessionRecord> findByOwner(UUID id, String owner) { return Optional.of(session); }
    }

    private static final class CleanupStorage implements MultipartObjectStore {
        private final long size;
        private boolean deleted;
        private RuntimeException lookupFailure;
        CleanupStorage(long size) { this.size = size; }
        @Override public ObjectMetadata headObject(String key) { return new ObjectMetadata(size, "etag", "version"); }
        @Override public Optional<ObjectMetadata> findObject(String key) {
            if (lookupFailure != null) throw lookupFailure;
            return Optional.of(headObject(key));
        }
        @Override public void deleteObject(String key) { deleted = true; }
        @Override public String createMultipart(String key, String type) { throw new UnsupportedOperationException(); }
        @Override public URI presignUploadPart(String key, String id, int part) { throw new UnsupportedOperationException(); }
        @Override public List<UploadedPart> listParts(String key, String id) { return List.of(); }
        @Override public CompletedObject complete(String key, String id, List<CompletedPart> parts) { throw new UnsupportedOperationException(); }
        @Override public void abort(String key, String id) { throw new UnsupportedOperationException(); }
        @Override public boolean multipartExists(String key, String id) { return false; }
    }
}
