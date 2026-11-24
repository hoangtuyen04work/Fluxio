package com.example.fluxio.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fluxio.transfer.domain.UploadState;
import com.example.fluxio.transfer.persistence.CreateUploadRecord;
import com.example.fluxio.transfer.persistence.StoredFileRecord;
import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.persistence.UploadSessionRecord;
import com.example.fluxio.transfer.storage.MultipartObjectStore;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-24T09:00:00Z");

    @Test
    void cleanupExpiresOnlyClaimedSessionsAndAbortsStorage() {
        var repository = new RecoveryRepository(UploadState.ACTIVE);
        var storage = new RecoveryStore();
        var job = new UploadCleanupJob(repository, storage, Clock.fixed(NOW, ZoneOffset.UTC), 100);

        assertThat(job.runBatch()).isEqualTo(1);
        assertThat(repository.session.state()).isEqualTo(UploadState.EXPIRED);
        assertThat(storage.abortCalls).isEqualTo(1);
    }

    @Test
    void reconciliationFinalizesCompletingSessionWhenObjectExists() {
        var repository = new RecoveryRepository(UploadState.COMPLETING);
        var storage = new RecoveryStore();
        var service = new UploadReconciliationService(repository, storage);

        assertThat(service.reconcile(repository.session.id(), "alice").fileName()).isEqualTo("report.zip");
        assertThat(repository.session.state()).isEqualTo(UploadState.COMPLETED);
    }

    private static final class RecoveryRepository implements UploadRepository {
        private UploadSessionRecord session;
        RecoveryRepository(UploadState state) {
            session = new UploadSessionRecord(UUID.randomUUID(), UUID.randomUUID(), "alice", "object-key",
                    "storage-id", "hash", "report.zip", "application/zip", 10, 8, 2, state,
                    NOW.minusSeconds(1), NOW.minusSeconds(3600), NOW.minusSeconds(180));
        }
        @Override public List<UploadSessionRecord> findExpired(Instant now, int limit) { return List.of(session); }
        @Override public boolean claimExpiry(UUID id) { session = state(UploadState.EXPIRED); return true; }
        @Override public StoredFileRecord finalizeCompletion(UUID id, String etag, String version) {
            session = state(UploadState.COMPLETED);
            return new StoredFileRecord(session.fileId(), id, "alice", "object-key", "report.zip",
                    "application/zip", 10, version, etag, NOW);
        }
        private UploadSessionRecord state(UploadState state) {
            return new UploadSessionRecord(session.id(), session.fileId(), session.ownerId(), session.objectKey(),
                    session.storageUploadId(), session.requestHash(), session.fileName(), session.contentType(),
                    session.declaredSizeBytes(), session.partSizeBytes(), session.partCount(), state,
                    session.expiresAt(), session.createdAt(), NOW);
        }
        @Override public Optional<UploadSessionRecord> findByOwner(UUID id, String owner) { return Optional.of(session); }
        @Override public UploadSessionRecord reserveAndCreateInitializing(CreateUploadRecord command) { throw new UnsupportedOperationException(); }
        @Override public boolean activate(UUID id, String storageId, Instant expiry) { return false; }
        @Override public boolean convertReservationToActive(UUID id) { return false; }
        @Override public boolean releaseReservation(UUID id) { return false; }
    }

    private static final class RecoveryStore implements MultipartObjectStore {
        int abortCalls;
        @Override public ObjectMetadata headObject(String key) { return new ObjectMetadata(10, "etag", "version"); }
        @Override public void abort(String key, String id) { abortCalls++; }
        @Override public String createMultipart(String key, String type) { throw new UnsupportedOperationException(); }
        @Override public URI presignUploadPart(String key, String id, int part) { throw new UnsupportedOperationException(); }
        @Override public List<UploadedPart> listParts(String key, String id) { return List.of(); }
        @Override public CompletedObject complete(String key, String id, List<CompletedPart> parts) { throw new UnsupportedOperationException(); }
        @Override public boolean multipartExists(String key, String id) { return false; }
    }
}
